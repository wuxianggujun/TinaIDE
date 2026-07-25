package com.wuxianggujun.tinaide.storage.compose

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.wuxianggujun.tinaide.storage.PermissionStatus
import com.wuxianggujun.tinaide.storage.ProjectPaths
import com.wuxianggujun.tinaide.storage.StorageManager
import com.wuxianggujun.tinaide.storage.StoragePermissionRequest
import java.io.File
import org.koin.compose.koinInject

/**
 * 统一的存储权限请求器（Compose 端）。
 *
 * 职责：
 * - 封装 Android 11+ "所有文件访问" 设置页跳转
 * - 兼容 Android 6-10 的 runtime 权限弹窗
 * - 把两类 launcher 的回调统一成 `onResult(granted)` 单一入口
 *
 * 使用：
 * ```
 * val context = LocalContext.current
 * val requester = rememberStoragePermissionRequester { granted -> ... }
 * requester.request()                                        // 无条件请求
 * requester.requestIfPublicPath(context, projectRoot)        // 仅项目位于公有目录时请求
 * ```
 */
@Composable
fun rememberStoragePermissionRequester(
    storageManager: StorageManager = koinInject(),
    onResult: (granted: Boolean) -> Unit
): StoragePermissionRequester {
    val currentOnResult = rememberUpdatedState(onResult)
    val settingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        currentOnResult.value(storageManager.refreshPermissionStatus() == PermissionStatus.GRANTED)
    }

    val runtimeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val allGranted = grants.isNotEmpty() && grants.values.all { it }
        currentOnResult.value(
            allGranted && storageManager.refreshPermissionStatus() == PermissionStatus.GRANTED
        )
    }

    return remember(storageManager) {
        StoragePermissionRequester(
            storageManager = storageManager,
            launchSettings = { settingsLauncher.launch(it) },
            launchRuntime = { runtimeLauncher.launch(it) },
            onResult = { granted -> currentOnResult.value(granted) }
        )
    }
}

class StoragePermissionRequester internal constructor(
    private val storageManager: StorageManager,
    private val launchSettings: (Intent) -> Unit,
    private val launchRuntime: (Array<String>) -> Unit,
    private val onResult: (Boolean) -> Unit
) {
    /** 无条件申请权限（已授权时立即回调 granted=true） */
    fun request() {
        when (val next = storageManager.nextPermissionRequest()) {
            is StoragePermissionRequest.AlreadyGranted -> onResult(true)
            is StoragePermissionRequest.OpenSettings -> launchSettings(next.intent)
            is StoragePermissionRequest.RequestRuntime -> launchRuntime(next.permissions)
            is StoragePermissionRequest.NoActionNeeded -> onResult(true)
        }
    }

    /**
     * 仅当项目位于 [ProjectPaths.getPublicProjectsRoot] 下时申请权限，
     * 否则视为不需要权限，直接回调 granted=true。
     */
    fun requestIfPublicPath(context: Context, projectRoot: File) {
        if (!ProjectPaths.isUnderPublicProjectsRoot(context, projectRoot)) {
            onResult(true)
            return
        }
        request()
    }
}
