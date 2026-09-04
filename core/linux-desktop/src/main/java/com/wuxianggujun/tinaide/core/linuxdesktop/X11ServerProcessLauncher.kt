package com.wuxianggujun.tinaide.core.linuxdesktop

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * [X11ServerLauncher] 的进程外实现：把 X server 拉到 `:x11` 进程的
 * [X11ServerService] 里启动，主进程只通过 [IX11ServerController] 控制。
 *
 * 进程边界的理由见 [X11ServerLauncher]——X server 崩溃会 `_exit()` 掉所在进程。
 */
class X11ServerProcessLauncher(
    context: Context,
    private val readyTimeoutMs: Long = DEFAULT_READY_TIMEOUT_MS,
) : X11ServerLauncher {

    private val appContext = context.applicationContext

    private val lock = Any()
    private var connection: ServiceConnection? = null
    private var controller: IX11ServerController? = null

    override suspend fun launch(
        args: X11ServerArgs,
        layout: X11SocketLayout,
    ): Result<String> = runCatching {
        val service = bindController().getOrThrow()

        val failure = service.startServer(
            args.displayNumber,
            args.toArgv().toTypedArray(),
            layout.hostTmpDir.absolutePath,
            layout.hostXkbConfigRoot.absolutePath,
        )
        if (failure != null) {
            error(failure)
        }

        // startServer() 返回只代表 native start() 已被调用并 fork 出 X server 线程；
        // 监听 socket 是 dix_main 起来之后才 bind 的。等 socket 文件出现才算真的可用，
        // 否则 guest 立刻连过去会拿到 ENOENT。
        awaitSocket(layout, args.displayNumber)

        layout.guestDisplay(args.displayNumber)
    }

    override suspend fun terminate() {
        // X server 无法在进程内停止：dix_main 的退出路径是 _exit()。停止手段只有让
        // :x11 进程结束——解绑并 stopService，进程失去所有客户端后被系统回收。
        val active = synchronized(lock) {
            val current = connection
            connection = null
            controller = null
            current
        }

        withContext(Dispatchers.Main.immediate) {
            active?.let { established ->
                runCatching { appContext.unbindService(established) }
                    .onFailure { error -> Timber.tag(TAG).w(error, "unbindService failed") }
            }
            runCatching { appContext.stopService(serviceIntent()) }
                .onFailure { error -> Timber.tag(TAG).w(error, "stopService failed") }
        }
    }

    override fun isAlive(): Boolean = synchronized(lock) {
        controller?.asBinder()?.isBinderAlive == true
    }

    private suspend fun bindController(): Result<IX11ServerController> {
        synchronized(lock) {
            controller?.takeIf { it.asBinder().isBinderAlive }?.let { return Result.success(it) }
        }

        val binderReady = CompletableDeferred<IX11ServerController?>()
        val established = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val stub = binder?.let(IX11ServerController.Stub::asInterface)
                synchronized(lock) { controller = stub }
                binderReady.complete(stub)
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                Timber.tag(TAG).w("X11 server process disconnected")
                synchronized(lock) { controller = null }
                // 绑定期内断开：让等待方失败而不是永久挂住。
                binderReady.complete(null)
            }

            override fun onBindingDied(name: ComponentName?) {
                Timber.tag(TAG).w("X11 server binding died")
                synchronized(lock) {
                    controller = null
                    connection = null
                }
                binderReady.complete(null)
            }
        }

        val intent = serviceIntent()
        return withContext(Dispatchers.Main.immediate) {
            // 前台服务必须显式 start，否则 bindService 只会让进程处于 bound 状态，
            // X server 得不到前台服务的存活保障。
            appContext.startForegroundService(intent)
            if (!appContext.bindService(intent, established, Context.BIND_AUTO_CREATE)) {
                runCatching { appContext.unbindService(established) }
                return@withContext Result.failure(
                    IllegalStateException("Failed to bind X11 server service")
                )
            }
            synchronized(lock) { connection = established }

            val stub = withTimeoutOrNull(readyTimeoutMs) { binderReady.await() }
            when (stub) {
                null -> {
                    runCatching { appContext.unbindService(established) }
                    synchronized(lock) { connection = null }
                    Result.failure(IllegalStateException("X11 server service did not connect"))
                }
                else -> Result.success(stub)
            }
        }
    }

    private suspend fun awaitSocket(layout: X11SocketLayout, displayNumber: Int) {
        val socket = layout.hostSocketFile(displayNumber)
        val appeared = withTimeoutOrNull(readyTimeoutMs) {
            while (!socket.exists()) {
                // binder 还活着才有意义继续等；X server 致命失败会带走整个 :x11 进程。
                check(isAlive()) { "X11 server process died during startup" }
                delay(SOCKET_POLL_INTERVAL_MS)
            }
            true
        }
        check(appeared == true) {
            "X11 server did not create ${socket.absolutePath} within ${readyTimeoutMs}ms"
        }
    }

    private fun serviceIntent() = Intent(appContext, X11ServerService::class.java)

    private companion object {
        private const val TAG = "X11ServerLauncher"
        private const val DEFAULT_READY_TIMEOUT_MS = 15_000L
        private const val SOCKET_POLL_INTERVAL_MS = 50L
    }
}
