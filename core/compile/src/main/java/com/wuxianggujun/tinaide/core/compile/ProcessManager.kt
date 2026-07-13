package com.wuxianggujun.tinaide.core.compile

import com.wuxianggujun.tinaide.terminal.process.PtyProcess
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * 进程管理器单例
 *
 * 负责管理所有运行中的进程，提供统一的启动、停止、状态查询接口。
 * 使用 StateFlow 提供响应式的状态更新。
 *
 * 这是通过本类注册的构建/标准进程状态来源。独立 TerminalActivity 的 PTY 生命周期
 * 由 ITerminalSessionManager 管理，不能用本类状态推断或终止终端会话。
 */
class ProcessManager {
    private companion object {
        private const val TAG = "ProcessManager"
    }

    /**
     * 进程状态
     */
    enum class ProcessState {
        IDLE, // 空闲，没有进程运行
        STARTING, // 正在启动
        RUNNING, // 运行中
        STOPPING, // 正在停止
        STOPPED // 已停止
    }

    /**
     * 进程信息
     */
    data class ProcessInfo(
        val id: Long,
        val state: ProcessState,
        val startTime: Long = 0,
        val exitCode: Int? = null,
        val error: String? = null,
        val wasTerminated: Boolean = false
    ) {
        /**
         * 是否处于活跃状态（正在启动、运行中或正在停止）
         */
        val isActive: Boolean
            get() = state == ProcessState.STARTING ||
                state == ProcessState.RUNNING ||
                state == ProcessState.STOPPING
    }

    /**
     * 运行中的进程句柄
     */
    sealed class ProcessHandle {
        abstract val id: Long
        abstract fun isRunning(): Boolean
        abstract fun sendSignal(signal: Int)
        abstract fun destroy()

        class PtyHandle(
            override val id: Long,
            private val pty: PtyProcess
        ) : ProcessHandle() {
            override fun isRunning(): Boolean = pty.isRunning()

            override fun sendSignal(signal: Int) {
                when (signal) {
                    SIGINT -> pty.write(byteArrayOf(3)) // Ctrl+C
                    SIGQUIT -> pty.write(byteArrayOf(28)) // Ctrl+\
                    else -> Timber.tag(TAG).w("Unsupported signal: $signal")
                }
            }

            override fun destroy() {
                pty.destroy()
            }

            fun read(buffer: ByteArray, offset: Int, length: Int): Int = pty.read(buffer, offset, length)

            fun write(data: ByteArray) {
                pty.write(data)
            }

            fun waitFor(): Int = pty.waitFor()
        }

        class StandardHandle(
            override val id: Long,
            private val process: Process
        ) : ProcessHandle() {
            override fun isRunning(): Boolean = process.isAlive

            override fun sendSignal(signal: Int) {
                // 标准 Process 不支持发送信号，只能销毁
                Timber.tag(TAG).w("Standard process does not support signals, will destroy")
            }

            override fun destroy() {
                process.destroy()
                process.destroyForcibly()
            }

            fun getInputStream() = process.inputStream
            fun getErrorStream() = process.errorStream
            fun getOutputStream() = process.outputStream
            fun waitFor(): Int = process.waitFor()
        }

        companion object {
            const val SIGINT = 2
            const val SIGQUIT = 3
            const val SIGTERM = 15
            const val SIGKILL = 9
        }
    }

    // 进程 ID 生成器
    private val idGenerator = AtomicLong(0)

    // 当前进程（使用锁保护）
    private val processLock = ReentrantLock()

    @Volatile
    private var currentHandle: ProcessHandle? = null

    // 进程状态流
    private val _processState = MutableStateFlow(ProcessInfo(0, ProcessState.IDLE))
    val processState: StateFlow<ProcessInfo> = _processState.asStateFlow()

    // 协程作用域
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // 监控任务
    private var monitorJob: Job? = null

    // 停止任务（保证 stop 幂等，避免重复启动多个 stop 协程）
    private var stopJob: Job? = null

    // 当前运行任务（用于取消）
    @Volatile
    private var currentRunJob: Job? = null

    // 是否正在重置状态
    private val isResetting = AtomicBoolean(false)

    /**
     * 重置状态，准备新的运行
     *
     * 在开始新的编译/运行之前调用，确保状态干净。
     * 如果有正在运行的进程，会先停止它。
     */
    fun reset() {
        if (!isResetting.compareAndSet(false, true)) {
            Timber.tag(TAG).w("Already resetting, skip")
            return
        }

        try {
            Timber.tag(TAG).i("Resetting ProcessManager state")

            processLock.withLock {
                // 取消当前运行任务
                currentRunJob?.cancel()
                currentRunJob = null

                // 停止当前进程
                val handle = currentHandle
                if (handle != null) {
                    Timber.tag(TAG).i("Stopping existing process: id=${handle.id}")
                    monitorJob?.cancel()
                    monitorJob = null
                    runCatching { handle.destroy() }
                    currentHandle = null
                }

                // 重置状态
                wasManuallyTerminated = false
                _processState.value = ProcessInfo(0, ProcessState.IDLE)
            }
        } finally {
            isResetting.set(false)
        }
    }

    /**
     * 设置当前运行任务的 Job（用于取消）
     */
    fun setCurrentRunJob(job: Job) {
        currentRunJob = job
    }

    /** 仅清除调用方自己登记的任务，避免旧任务完成时覆盖后启动的新任务。 */
    fun clearCurrentRunJob(job: Job) {
        if (currentRunJob === job) {
            currentRunJob = null
        }
    }

    /**
     * 注册 PTY 进程
     */
    fun registerPtyProcess(pty: PtyProcess): ProcessHandle.PtyHandle {
        val id = idGenerator.incrementAndGet()
        val handle = ProcessHandle.PtyHandle(id, pty)

        processLock.withLock {
            // 停止之前的进程（静默停止，不标记为手动终止）
            stopCurrentProcessSilentlyInternal()

            // 重置手动终止标志
            wasManuallyTerminated = false

            currentHandle = handle
            _processState.value = ProcessInfo(id, ProcessState.RUNNING, System.currentTimeMillis())

            // 启动监控
            startMonitoring(handle)
        }

        Timber.tag(TAG).i("Registered PTY process: id=$id")
        return handle
    }

    /**
     * 注册标准进程
     */
    fun registerStandardProcess(process: Process): ProcessHandle.StandardHandle {
        val id = idGenerator.incrementAndGet()
        val handle = ProcessHandle.StandardHandle(id, process)

        processLock.withLock {
            // 停止之前的进程（静默停止，不标记为手动终止）
            stopCurrentProcessSilentlyInternal()

            // 重置手动终止标志
            wasManuallyTerminated = false

            currentHandle = handle
            _processState.value = ProcessInfo(id, ProcessState.RUNNING, System.currentTimeMillis())

            // 启动监控
            startMonitoring(handle)
        }

        Timber.tag(TAG).i("Registered standard process: id=$id")
        return handle
    }

    // 标记是否被手动终止
    @Volatile
    private var wasManuallyTerminated = false

    /**
     * 停止当前进程
     *
     * 注意：这个方法发送停止信号，并启动一个协程来等待进程结束。
     * 进程结束后会自动更新状态为 STOPPED。
     */
    fun stopCurrentProcess(): Boolean {
        processLock.withLock {
            val handle = currentHandle ?: return false

            // 幂等：正在停止则不重复触发。
            if (_processState.value.state == ProcessState.STOPPING) return true

            Timber.tag(TAG).i("Stopping process: id=${handle.id}")
            val processId = handle.id

            wasManuallyTerminated = true
            _processState.value = _processState.value.copy(state = ProcessState.STOPPING, wasTerminated = true)

            // 取消运行任务（避免 runProgram 继续推进）
            currentRunJob?.cancel()
            currentRunJob = null

            // 取消监控
            monitorJob?.cancel()
            monitorJob = null

            // 确保 stop 只有一个在跑
            stopJob?.cancel()
            stopJob = scope.launch {
                // 尝试优雅中断（Ctrl+C）
                runCatching { handle.sendSignal(ProcessHandle.SIGINT) }
                delay(50)

                // 立即终止（不等待 5 秒）
                runCatching { handle.destroy() }

                // 给予极短时间让 isRunning() 收敛（不同实现可能稍有滞后）
                var waitCount = 0
                while (handle.isRunning() && waitCount < 10) {
                    delay(20)
                    waitCount++
                }

                processLock.withLock {
                    // 避免覆盖新进程状态
                    if (currentHandle?.id == processId) {
                        currentHandle = null
                        _processState.value = ProcessInfo(
                            id = processId,
                            state = ProcessState.STOPPED,
                            wasTerminated = true
                        )
                    }
                }

                delay(100)
                processLock.withLock {
                    if (currentHandle == null && _processState.value.state == ProcessState.STOPPED) {
                        _processState.value = ProcessInfo(0, ProcessState.IDLE)
                    }
                }
            }

            return true
        }
    }

    /**
     * 强制停止当前进程（立即）
     *
     * 这个方法会立即销毁进程并清理状态。
     * 状态会转换为 STOPPED，然后自动转换为 IDLE。
     */
    fun forceStopCurrentProcess() {
        processLock.withLock {
            val handle = currentHandle
            if (handle == null) {
                // 没有进程在运行，确保状态为 IDLE
                if (_processState.value.state != ProcessState.IDLE) {
                    Timber.tag(TAG).i("No process running, resetting state to IDLE")
                    _processState.value = ProcessInfo(0, ProcessState.IDLE)
                }
                return
            }

            Timber.tag(TAG).i("Force stopping process: id=${handle.id}")

            // 取消运行任务
            currentRunJob?.cancel()
            currentRunJob = null

            // 取消监控
            monitorJob?.cancel()
            monitorJob = null

            // 取消可能存在的 stop 协程
            stopJob?.cancel()
            stopJob = null

            // 立即销毁
            runCatching { handle.destroy() }

            // 立即清理状态
            val processId = handle.id
            currentHandle = null
            wasManuallyTerminated = true

            // 先设置为 STOPPED
            _processState.value = ProcessInfo(processId, ProcessState.STOPPED, wasTerminated = true)
        }

        // 延迟后重置为 IDLE（在锁外执行，避免死锁）
        scope.launch {
            delay(100)
            processLock.withLock {
                if (currentHandle == null && _processState.value.state == ProcessState.STOPPED) {
                    _processState.value = ProcessInfo(0, ProcessState.IDLE)
                }
            }
        }
    }

    /**
     * 静默停止当前进程（用于新运行时自动停止旧进程）
     * 不会标记为手动终止
     */
    private fun stopCurrentProcessSilently() {
        processLock.withLock {
            stopCurrentProcessSilentlyInternal()
        }
    }

    /**
     * 静默停止当前进程（内部方法，需要在锁内调用）
     */
    private fun stopCurrentProcessSilentlyInternal() {
        val handle = currentHandle ?: return

        Timber.tag(TAG).i("Silently stopping process: id=${handle.id}")

        // 取消监控
        monitorJob?.cancel()
        monitorJob = null

        // 静默停止：不阻塞线程，不等待超时，直接销毁以保证状态干净。
        runCatching { handle.sendSignal(ProcessHandle.SIGINT) }
        runCatching { handle.destroy() }

        // 清理旧进程状态
        currentHandle = null
    }

    /**
     * 检查是否有进程在运行
     */
    fun isRunning(): Boolean = processLock.withLock {
        currentHandle?.isRunning() == true
    }

    /**
     * 检查是否处于活跃状态（正在启动、运行中或正在停止）
     */
    fun isActive(): Boolean = _processState.value.isActive

    /**
     * 获取当前进程句柄
     */
    fun getCurrentHandle(): ProcessHandle? = processLock.withLock { currentHandle }

    /**
     * 标记进程已完成
     */
    fun markProcessCompleted(id: Long, exitCode: Int) {
        processLock.withLock {
            val handle = currentHandle
            if (handle?.id == id) {
                Timber.tag(TAG).i("Process completed: id=$id, exitCode=$exitCode")

                monitorJob?.cancel()
                monitorJob = null

                currentHandle = null
                _processState.value = ProcessInfo(id, ProcessState.STOPPED, exitCode = exitCode)
            }
        }

        // 延迟后重置为 IDLE
        scope.launch {
            delay(100)
            processLock.withLock {
                if (currentHandle == null && _processState.value.state == ProcessState.STOPPED) {
                    _processState.value = ProcessInfo(0, ProcessState.IDLE)
                }
            }
        }
    }

    /**
     * 标记进程出错
     */
    fun markProcessError(id: Long, error: String) {
        processLock.withLock {
            val handle = currentHandle
            if (handle?.id == id) {
                Timber.tag(TAG).e("Process error: id=$id, error=$error")

                monitorJob?.cancel()
                monitorJob = null

                runCatching { handle.destroy() }

                currentHandle = null
                _processState.value = ProcessInfo(id, ProcessState.STOPPED, error = error)
            }
        }

        // 延迟后重置为 IDLE
        scope.launch {
            delay(100)
            processLock.withLock {
                if (currentHandle == null && _processState.value.state == ProcessState.STOPPED) {
                    _processState.value = ProcessInfo(0, ProcessState.IDLE)
                }
            }
        }
    }

    /**
     * 启动进程监控
     *
     * 注意：监控只用于检测进程是否意外结束，正常的结束处理由 executeWithPtyOutput() 中的 waitFor() 处理。
     */
    private fun startMonitoring(handle: ProcessHandle) {
        monitorJob?.cancel()
        monitorJob = scope.launch {
            while (isActive && handle.isRunning()) {
                delay(100)
            }

            // 进程已结束（可能是自然结束或被停止）
            // 不要在这里清空 currentHandle，让 executeWithPtyOutput 中的 waitFor 来处理
            if (currentHandle?.id == handle.id) {
                Timber.tag(TAG).i("Process monitor detected end: id=${handle.id}")
            }
        }
    }

    /**
     * 清理进程状态（由 executeWithPtyOutput 调用）
     */
    fun clearCurrentProcess(id: Long, exitCode: Int) {
        processLock.withLock {
            val handle = currentHandle
            if (handle?.id == id) {
                val terminated = wasManuallyTerminated
                Timber.tag(TAG).i("Clearing process: id=$id, exitCode=$exitCode, wasTerminated=$terminated")
                currentHandle = null
                wasManuallyTerminated = false
                _processState.value = ProcessInfo(id, ProcessState.STOPPED, exitCode = exitCode, wasTerminated = terminated)
            }
        }

        // 延迟后重置为 IDLE
        scope.launch {
            delay(100)
            processLock.withLock {
                if (currentHandle == null && _processState.value.state == ProcessState.STOPPED) {
                    _processState.value = ProcessInfo(0, ProcessState.IDLE)
                }
            }
        }
    }

    /**
     * 检查当前进程是否被手动终止
     */
    fun wasTerminated(): Boolean = wasManuallyTerminated

    /**
     * 清理所有资源
     */
    fun cleanup() {
        processLock.withLock {
            currentRunJob?.cancel()
            currentRunJob = null
            monitorJob?.cancel()
            monitorJob = null
            currentHandle?.let { runCatching { it.destroy() } }
            currentHandle = null
            wasManuallyTerminated = false
            _processState.value = ProcessInfo(0, ProcessState.IDLE)
        }
        scope.cancel()
    }
}
