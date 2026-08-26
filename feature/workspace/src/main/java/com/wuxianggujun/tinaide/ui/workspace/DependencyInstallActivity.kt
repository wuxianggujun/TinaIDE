package com.wuxianggujun.tinaide.ui.workspace

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gyf.immersionbar.ktx.immersionBar
import com.wuxianggujun.tinaide.core.IAppNavigator
import com.wuxianggujun.tinaide.core.i18n.Drawables
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.proot.ToolchainConfig
import com.wuxianggujun.tinaide.ui.compose.components.TinaConfirmDialog
import com.wuxianggujun.tinaide.ui.theme.TinaIDETheme
import com.wuxianggujun.tinaide.ui.workspace.components.*
import com.wuxianggujun.tinaide.ui.workspace.model.*
import org.koin.androidx.viewmodel.ext.android.viewModel as koinViewModel
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

private const val PACKAGE_LINUX_DISTRO_RUNTIME = "linux-distro-runtime"
private const val PACKAGE_LINUX_ROOTFS = "linux-rootfs"

/**
 * 依赖库安装页面
 *
 * 使用 MVVM 架构，状态管理逻辑在 ViewModel 中，
 * UI 组件拆分到 components 包中，提高代码可维护性。
 *
 * 在环境配置步骤中安装 Linux 依赖包（rootfs 解压和工具链安装）
 * Linux 环境由自研 Linux 发行版管理器安装和管理。
 */
class DependencyInstallActivity :
    ComponentActivity(),
    KoinComponent {

    companion object {
        const val EXTRA_TOOLCHAIN_CONFIG = "toolchain_config"
        const val EXTRA_PREFERRED_LLVM_MAJOR_VERSION = "preferred_llvm_major_version"
        const val EXTRA_INSTALL_LINUX_ENVIRONMENT = "install_linux_environment"

        private const val LLVM_MAJOR_VERSION_AUTO = -1

        /**
         * 创建启动 Intent
         *
         * @param context 上下文
         * @param config 工具链配置（可选）
         */
        fun createIntent(
            context: Context,
            config: ToolchainConfig? = null,
            preferredLlvmMajorVersion: Int? = null,
            installLinuxEnvironment: Boolean = true
        ): Intent = Intent(context, DependencyInstallActivity::class.java).apply {
            config?.let { putExtra(EXTRA_TOOLCHAIN_CONFIG, it) }
            putExtra(EXTRA_PREFERRED_LLVM_MAJOR_VERSION, preferredLlvmMajorVersion ?: LLVM_MAJOR_VERSION_AUTO)
            putExtra(EXTRA_INSTALL_LINUX_ENVIRONMENT, installLinuxEnvironment)
        }
    }

    private val toolchainConfig: ToolchainConfig by lazy {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_TOOLCHAIN_CONFIG, ToolchainConfig::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_TOOLCHAIN_CONFIG)
        } ?: ToolchainConfig.recommended()
    }

    private val preferredLlvmMajorVersion: Int? by lazy {
        val value = intent.getIntExtra(EXTRA_PREFERRED_LLVM_MAJOR_VERSION, LLVM_MAJOR_VERSION_AUTO)
        value.takeIf { it != LLVM_MAJOR_VERSION_AUTO }
    }

    private val installLinuxEnvironment: Boolean by lazy {
        intent.getBooleanExtra(EXTRA_INSTALL_LINUX_ENVIRONMENT, true)
    }

    private val viewModel: DependencyInstallViewModel by koinViewModel {
        org.koin.core.parameter.parametersOf(
            toolchainConfig,
            preferredLlvmMajorVersion,
            installLinuxEnvironment
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 设置沉浸式状态栏（深色背景使用浅色图标）
        immersionBar {
            transparentStatusBar()
            statusBarDarkFont(false)
            navigationBarColor(android.R.color.transparent)
            navigationBarDarkIcon(false)
        }

        setContent {
            TinaIDETheme {
                DependencyInstallScreen(
                    viewModel = viewModel,
                    onInstallComplete = ::navigateToProjectManager,
                    onOpenTerminal = ::openTerminal,
                    onOpenInstallLog = ::openInstallLog,
                    onCancel = ::finish,
                    onBack = ::finish
                )
            }
        }
    }

    private fun navigateToProjectManager() {
        get<IAppNavigator>().navigateToProjectManager(this)
    }

    private fun openTerminal() {
        val workDir = filesDir.absolutePath
        get<IAppNavigator>().navigateToTerminal(this, workDir)
    }

    private fun openInstallLog() {
        startActivity(Intent(this, InstallLogActivity::class.java))
    }
}

/**
 * 依赖安装主屏幕
 */
@Composable
fun DependencyInstallScreen(
    viewModel: DependencyInstallViewModel,
    onInstallComplete: () -> Unit,
    onOpenTerminal: (() -> Unit)? = null,
    onOpenInstallLog: (() -> Unit)? = null,
    onCancel: () -> Unit,
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // 取消安装确认对话框状态
    var showCancelDialog by remember { mutableStateOf(false) }

    // 处理一次性事件
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is DependencyInstallEvent.NavigateToProjectManager -> onInstallComplete()
                is DependencyInstallEvent.ShowToast -> {
                    // Toast 已在 ViewModel 中处理
                }
                is DependencyInstallEvent.InstallCompleted -> onInstallComplete()
            }
        }
    }

    // 取消安装确认对话框
    if (showCancelDialog) {
        CancelInstallConfirmDialog(
            onConfirm = {
                showCancelDialog = false
                viewModel.cancelInstallation()
                onCancel()
            },
            onDismiss = { showCancelDialog = false }
        )
    }

    // 获取已安装组件列表
    val hasLinuxRuntime = uiState.packageList.any {
        it.name == PACKAGE_LINUX_DISTRO_RUNTIME || it.name == PACKAGE_LINUX_ROOTFS
    }
    val installedComponents = buildList {
        if (hasLinuxRuntime) {
            add(
                InstalledComponent(
                    stringResource(Strings.settings_cat_linux_system),
                    stringResource(Strings.proot_package_already_done),
                    Drawables.ic_linux_default
                )
            )
        }
        add(
            InstalledComponent(
                stringResource(Strings.proot_package_android_sysroot),
                stringResource(Strings.proot_package_already_done),
                Drawables.ic_build
            )
        )
        add(
            InstalledComponent(
                stringResource(Strings.proot_package_native_toolchain),
                stringResource(Strings.proot_package_already_done),
                Drawables.ic_package
            )
        )
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        // 根据安装阶段显示不同界面
        AnimatedContent(
            targetState = uiState.installPhase,
            transitionSpec = {
                fadeIn(animationSpec = tween(300)) togetherWith
                    fadeOut(animationSpec = tween(300))
            },
            label = "phaseTransition"
        ) { phase ->
            when (phase) {
                InstallPhase.INSTALLING -> {
                    InstallingContent(
                        isPaused = uiState.isPaused,
                        overallProgress = uiState.progress,
                        isInstalling = true,
                        statusMessage = uiState.statusMessage,
                        installStage = uiState.installStage,
                        packageList = uiState.packageList,
                        currentPackage = uiState.currentPackage,
                        onBack = onBack,
                        onPauseToggle = { viewModel.togglePause() },
                        onCancel = { showCancelDialog = true },
                    )
                }
                InstallPhase.COMPLETED -> {
                    InstallCompletedContent(
                        installedComponents = installedComponents,
                        rootfsHealth = uiState.rootfsHealth,
                        onEnterWorkspace = { viewModel.onInstallComplete() },
                        onRefreshRootfsHealth = viewModel::refreshRootfsHealth,
                        onOpenLog = onOpenInstallLog,
                        onOpenTerminal = onOpenTerminal,
                    )
                }
                InstallPhase.FAILED -> {
                    InstallFailedContent(
                        errorMessage = uiState.failedMessage,
                        isNetworkRelated = uiState.isNetworkRelated,
                        onRetry = { viewModel.retry() },
                        onOpenTerminal = onOpenTerminal,
                        onOpenLog = onOpenInstallLog,
                        onBack = onBack
                    )
                }
            }
        }
    }
}

/**
 * 取消安装确认对话框
 */
@Composable
private fun CancelInstallConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    TinaConfirmDialog(
        title = stringResource(Strings.dialog_cancel_install_title),
        message = stringResource(Strings.dialog_cancel_install_message),
        confirmText = stringResource(Strings.btn_confirm_cancel_install),
        dismissText = stringResource(Strings.btn_continue_install),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        isDanger = true,
        icon = {
            Icon(
                painter = rememberWorkspacePainter(Drawables.ic_warning_amber),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(32.dp)
            )
        }
    )
}
