package com.wuxianggujun.tinaide.ui.compose.screens.settings.sections

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wuxianggujun.tinaide.core.font.AppFontManager
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.proot.PRootBootstrap
import com.wuxianggujun.tinaide.core.proot.RootfsPackageManager
import com.wuxianggujun.tinaide.core.terminal.BackendMode
import com.wuxianggujun.tinaide.core.terminal.GuestDevPackagesCommandGroupStatus
import com.wuxianggujun.tinaide.core.terminal.GuestDevPackagesInstallResult
import com.wuxianggujun.tinaide.core.terminal.IGuestDevPackagesInstaller
import com.wuxianggujun.tinaide.core.terminal.ILocaleInstaller
import com.wuxianggujun.tinaide.core.terminal.IShellInstaller
import com.wuxianggujun.tinaide.core.terminal.IShellResolver
import com.wuxianggujun.tinaide.core.terminal.ITerminalPreferences
import com.wuxianggujun.tinaide.core.terminal.ITerminalThemeProvider
import com.wuxianggujun.tinaide.core.terminal.LocaleInstallResult
import com.wuxianggujun.tinaide.core.terminal.ShellAvailabilityInfo
import com.wuxianggujun.tinaide.core.terminal.ShellInstallResult
import com.wuxianggujun.tinaide.core.terminal.ShellType
import com.wuxianggujun.tinaide.core.terminal.TerminalBackendType
import com.wuxianggujun.tinaide.ui.compose.components.TinaActionChoiceDialog
import com.wuxianggujun.tinaide.ui.compose.components.TinaConfirmDialog
import com.wuxianggujun.tinaide.ui.compose.components.TinaErrorDialog
import com.wuxianggujun.tinaide.ui.compose.components.TinaLoadingDialog
import com.wuxianggujun.tinaide.ui.compose.components.TinaSingleChoiceDialog
import com.wuxianggujun.tinaide.ui.compose.components.TinaSliderDialog
import com.wuxianggujun.tinaide.ui.compose.screens.settings.components.SettingsCard
import com.wuxianggujun.tinaide.ui.compose.screens.settings.components.SettingsCategoryTitle
import com.wuxianggujun.tinaide.ui.compose.screens.settings.components.SettingsClickableItem
import com.wuxianggujun.tinaide.ui.compose.screens.settings.components.SettingsDisplayItem
import com.wuxianggujun.tinaide.ui.compose.screens.settings.components.SettingsSwitchItem
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import timber.log.Timber

private const val DEPENDENCY_INSTALL_ACTIVITY_CLASS_NAME =
    "com.wuxianggujun.tinaide.ui.workspace.DependencyInstallActivity"
private const val EXTRA_INSTALL_LINUX_ENVIRONMENT = "install_linux_environment"

@Composable
internal fun TerminalSettingsSection(linuxEnvironmentEnabled: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 使用 Koin 注入接口
    val prefs: ITerminalPreferences = koinInject()
    val themeProvider: ITerminalThemeProvider = koinInject()
    val localeInstaller: ILocaleInstaller = koinInject()
    val guestDevPackagesInstaller: IGuestDevPackagesInstaller = koinInject()
    val shellResolver: IShellResolver = koinInject()
    val zshInstaller: IShellInstaller = koinInject()

    val themeName by prefs.themeNameFlow.collectAsStateWithLifecycle()
    val fontSizeSp by prefs.fontSizeFlow.collectAsStateWithLifecycle()
    val locale by prefs.localeFlow.collectAsStateWithLifecycle()
    val fontName by prefs.fontNameFlow.collectAsStateWithLifecycle()
    val customFontPath = remember { prefs.getCustomFontPath() }
    val cursorBlinkEnabled by prefs.cursorBlinkEnabledFlow.collectAsStateWithLifecycle()
    val cursorBlinkRate by prefs.cursorBlinkRateFlow.collectAsStateWithLifecycle()
    val shellType by prefs.shellTypeFlow.collectAsStateWithLifecycle()
    val backendMode by prefs.backendModeFlow.collectAsStateWithLifecycle()

    val toastShellTypeChanged = stringResource(Strings.toast_shell_type_changed)
    val toastZshUnavailableOnHost = stringResource(Strings.toast_zsh_unavailable_on_host)
    val toastZshInstallSuccess = stringResource(Strings.toast_zsh_install_success)
    val toastGuestDevPackagesInstallSuccess = stringResource(Strings.toast_guest_dev_packages_install_success)
    val toastTerminalFontSetTemplate = stringResource(Strings.toast_terminal_font_set)
    val errorInvalidFontFile = stringResource(Strings.error_invalid_font_file)
    val errorFontSetFailedTemplate = stringResource(Strings.error_font_set_failed)
    val toastBackendModeChanged = stringResource(Strings.toast_backend_mode_changed)
    val toastPRootNotInstalled = stringResource(Strings.toast_proot_not_installed)

    var showThemeDialog by remember { mutableStateOf(false) }
    var showFontSizeDialog by remember { mutableStateOf(false) }
    var showFontFamilyDialog by remember { mutableStateOf(false) }
    var showLocaleDialog by remember { mutableStateOf(false) }
    var showCursorBlinkRateDialog by remember { mutableStateOf(false) }
    var showShellTypeDialog by remember { mutableStateOf(false) }
    var showBackendModeDialog by remember { mutableStateOf(false) }

    var shellAvailability by remember { mutableStateOf<ShellAvailabilityInfo?>(null) }
    var shellAvailabilityError by remember { mutableStateOf<String?>(null) }

    // 自定义字体选择器
    val fontPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                handleTerminalFontSelected(
                    context,
                    uri,
                    prefs,
                    toastTerminalFontSetTemplate,
                    errorInvalidFontFile,
                    errorFontSetFailedTemplate
                )
            }
        }
    }

    // 语言包安装相关状态
    var showInstallDialog by remember { mutableStateOf(false) }
    var isInstalling by remember { mutableStateOf(false) }
    var installProgress by remember { mutableStateOf("") }
    var installError by remember { mutableStateOf<String?>(null) }
    var pendingLocale by remember { mutableStateOf<String?>(null) }

    // Zsh 安装相关状态
    var showInstallZshDialog by remember { mutableStateOf(false) }
    var isInstallingZsh by remember { mutableStateOf(false) }
    var zshInstallProgress by remember { mutableStateOf("") }
    var zshInstallError by remember { mutableStateOf<String?>(null) }

    // Guest 开发基础包安装相关状态
    var showInstallDevPackagesDialog by remember { mutableStateOf(false) }
    var isInstallingDevPackages by remember { mutableStateOf(false) }
    var devPackagesInstallProgress by remember { mutableStateOf("") }
    var devPackagesInstallError by remember { mutableStateOf<String?>(null) }
    var showLinuxToolsActions by remember { mutableStateOf(false) }
    var showDevPackagesActions by remember { mutableStateOf(false) }
    var forceDevPackagesInstall by remember { mutableStateOf(false) }
    var showZshActions by remember { mutableStateOf(false) }
    var forceZshInstall by remember { mutableStateOf(false) }
    var showLocaleActions by remember { mutableStateOf(false) }
    var forceLocaleInstall by remember { mutableStateOf(false) }
    var linuxToolsStatusVersion by remember { mutableStateOf(0) }
    var zshInstalled by remember { mutableStateOf<Boolean?>(null) }
    var localeSupportInstalled by remember { mutableStateOf<Boolean?>(null) }
    var devPackagesInstalled by remember { mutableStateOf<Boolean?>(null) }
    var devPackagesPlannedPackages by remember { mutableStateOf<List<String>>(emptyList()) }
    var devPackagesCommandGroupStatuses by remember {
        mutableStateOf<List<GuestDevPackagesCommandGroupStatus>>(emptyList())
    }
    var devPackagesMissingCommandGroups by remember { mutableStateOf<List<List<String>>>(emptyList()) }

    Spacer(modifier = Modifier.height(8.dp))

    androidx.compose.runtime.LaunchedEffect(Unit) {
        runCatching { shellResolver.probeAvailability() }
            .onSuccess {
                shellAvailability = it
                shellAvailabilityError = null
            }
            .onFailure {
                shellAvailability = null
                shellAvailabilityError = it.message ?: it::class.java.simpleName
            }
    }

    androidx.compose.runtime.LaunchedEffect(
        linuxEnvironmentEnabled,
        locale,
        linuxToolsStatusVersion
    ) {
        if (!linuxEnvironmentEnabled) {
            zshInstalled = false
            localeSupportInstalled = false
            devPackagesInstalled = false
            devPackagesPlannedPackages = emptyList()
            devPackagesCommandGroupStatuses = emptyList()
            devPackagesMissingCommandGroups = emptyList()
            return@LaunchedEffect
        }

        if (!shellResolver.isPRootInstalled()) {
            zshInstalled = false
            localeSupportInstalled = locale == "C.UTF-8"
            devPackagesInstalled = false
            devPackagesPlannedPackages = emptyList()
            devPackagesCommandGroupStatuses = emptyList()
            devPackagesMissingCommandGroups = emptyList()
            return@LaunchedEffect
        }

        zshInstalled = null
        localeSupportInstalled = if (locale == "C.UTF-8") true else null
        devPackagesInstalled = null
        devPackagesPlannedPackages = emptyList()
        devPackagesCommandGroupStatuses = emptyList()
        devPackagesMissingCommandGroups = emptyList()

        zshInstalled = runCatching { zshInstaller.isInstalled() }.getOrDefault(false)
        localeSupportInstalled = if (locale == "C.UTF-8") {
            true
        } else {
            runCatching { !localeInstaller.needsLocalePackage(locale) }.getOrDefault(false)
        }
        val devPackagesStatus = runCatching { guestDevPackagesInstaller.inspectStatus() }.getOrNull()
        devPackagesInstalled = devPackagesStatus?.installed ?: false
        devPackagesPlannedPackages = devPackagesStatus?.plannedPackages.orEmpty()
        devPackagesCommandGroupStatuses = devPackagesStatus?.commandGroupStatuses.orEmpty()
        devPackagesMissingCommandGroups = devPackagesStatus?.missingCommandGroups.orEmpty()
    }

    val shellTypeLabels = TerminalSettingsHelper.getAllShellTypes().associateWith { shell ->
        stringResource(TerminalSettingsSectionSupport.resolveShellTypeLabelRes(shell))
    }

    fun shellTypeLabel(type: ShellType): String = shellTypeLabels[type] ?: type.value

    val backendModeLabels = TerminalSettingsHelper.getAllBackendModes().associateWith { mode ->
        stringResource(TerminalSettingsSectionSupport.resolveBackendModeLabelRes(mode))
    }

    fun backendModeLabel(mode: BackendMode): String = backendModeLabels[mode] ?: mode.value

    @Composable
    fun packageManagerLabel(packageManager: RootfsPackageManager): String = stringResource(
        TerminalSettingsSectionSupport.resolvePackageManagerLabelRes(packageManager)
    )

    @Composable
    fun linuxToolStatusLabel(status: Boolean?, notRequired: Boolean = false): String = stringResource(
        TerminalSettingsSectionSupport.resolveLinuxToolStatusLabelRes(status, notRequired)
    )

    @Composable
    fun linuxToolsHealthValue(requiredStates: List<Boolean?>): String = resolveTerminalSettingsText(
        TerminalSettingsSectionSupport.resolveLinuxToolsHealthText(requiredStates)
    )

    @Composable
    fun guestDevPackagesSubtitle(): String {
        val missingSummary = if (devPackagesMissingCommandGroups.isEmpty()) {
            null
        } else {
            devPackagesMissingCommandGroups.joinToString(separator = " · ") { group ->
                group.joinToString(separator = "/")
            }
        }
        return TerminalSettingsSectionSupport.buildGuestDevPackagesSubtitle(
            baseDescription = stringResource(Strings.settings_guest_dev_packages_desc),
            devPackagesInstalled = devPackagesInstalled,
            missingComponentsText = missingSummary?.let { summary ->
                context.getString(Strings.settings_linux_tool_missing_components, summary)
            }
        )
    }

    @Composable
    fun guestDevPackagesGroupLabel(status: GuestDevPackagesCommandGroupStatus): String = resolveTerminalSettingsDisplay(
        TerminalSettingsSectionSupport.resolveGuestDevPackagesGroupLabel(status)
    )

    @Composable
    fun guestDevPackagesDiagnosticMessage(): String = TerminalSettingsSectionSupport.buildGuestDevPackagesDiagnosticMessage(
        baseDescription = stringResource(Strings.settings_guest_dev_packages_desc),
        packagesToInstallText = devPackagesPlannedPackages
            .takeIf { it.isNotEmpty() }
            ?.joinToString(separator = ", ")
            ?.let { packages ->
                stringResource(Strings.settings_linux_tool_packages_to_install, packages)
            },
        commandDiagnosticsTitle = stringResource(Strings.settings_linux_tool_command_diagnostics),
        checkingText = stringResource(Strings.settings_linux_tool_status_checking),
        readyText = stringResource(Strings.settings_linux_tool_status_ready),
        missingText = stringResource(Strings.settings_linux_tool_status_missing),
        commandGroupDiagnostics = devPackagesCommandGroupStatuses.map { status ->
            TerminalSettingsCommandGroupDiagnostic(
                label = guestDevPackagesGroupLabel(status),
                commands = status.commands,
                available = status.available,
            )
        }
    )

    @Composable
    fun guestDevPackagesMissingSummary(): String? {
        val missing = devPackagesCommandGroupStatuses.filterNot { it.available }
        val labels = mutableListOf<String>()
        for (status in missing) {
            labels += guestDevPackagesGroupLabel(status)
        }
        return TerminalSettingsSectionSupport.buildGuestDevPackagesMissingSummary(labels)
    }

    @Composable
    fun linuxToolsDiagnosticMessage(
        activeProfileName: String,
        packageManagerName: String,
        localeLabel: String,
    ): String = TerminalSettingsSectionSupport.buildLinuxToolsDiagnosticMessage(
        systemNameLabel = stringResource(Strings.settings_linux_tools_system_name),
        activeProfileName = activeProfileName,
        packageManagerLabel = stringResource(Strings.settings_linux_tools_package_manager),
        packageManagerName = packageManagerName,
        guestDevPackagesLabel = stringResource(Strings.settings_guest_dev_packages),
        guestDevPackagesStatusText = linuxToolStatusLabel(devPackagesInstalled),
        guestDevPackagesMissingComponentsText = guestDevPackagesMissingSummary()?.let { summary ->
            stringResource(Strings.settings_linux_tool_missing_components, summary)
        },
        zshLabel = stringResource(Strings.shell_type_zsh),
        zshStatusText = linuxToolStatusLabel(zshInstalled),
        localeSupportLabel = stringResource(Strings.settings_linux_locale_support),
        localeSupportStatusText = linuxToolStatusLabel(
            status = localeSupportInstalled,
            notRequired = locale == "C.UTF-8"
        ),
        localeSupportDescription = stringResource(
            Strings.settings_linux_locale_support_desc,
            localeLabel
        ),
    )

    // 主题设置
    SettingsCategoryTitle(stringResource(Strings.settings_cat_terminal_theme))

    SettingsCard {
        SettingsClickableItem(
            title = stringResource(Strings.settings_theme),
            value = themeName,
            onClick = { showThemeDialog = true },
            showDivider = false
        )
    }

    // 字体设置
    SettingsCategoryTitle(stringResource(Strings.settings_cat_font))

    SettingsCard {
        SettingsClickableItem(
            title = stringResource(Strings.settings_cat_font),
            subtitle = stringResource(Strings.settings_terminal_font_desc),
            value = prefs.getFontDisplayName(),
            onClick = { showFontFamilyDialog = true },
            showDivider = true
        )

        SettingsClickableItem(
            title = stringResource(Strings.settings_font_size),
            value = "${fontSizeSp.roundToInt()} sp",
            onClick = { showFontSizeDialog = true },
            showDivider = false
        )
    }

    // Shell 配置
    SettingsCategoryTitle(stringResource(Strings.settings_cat_shell))

    val selectedShell = TerminalSettingsHelper.getShellTypeFromValue(shellType)
    val shellSubtitle = TerminalSettingsSectionSupport.buildShellSubtitle(
        baseDescription = stringResource(Strings.settings_shell_type_desc),
        availability = shellAvailability,
        shellAvailabilityError = shellAvailabilityError,
        selectedShell = selectedShell,
        backendLabelProvider = { backend ->
            when (backend) {
                TerminalBackendType.PROOT -> context.getString(Strings.terminal_shell_backend_proot)
                TerminalBackendType.HOST -> context.getString(Strings.terminal_shell_backend_host)
            }
        },
        shellLabelProvider = ::shellTypeLabel,
        autoResolvedTextProvider = { resolvedLabel ->
            context.getString(Strings.terminal_shell_auto_resolved, resolvedLabel)
        },
        availableTextProvider = { availableText ->
            context.getString(Strings.terminal_shell_available, availableText)
        },
        probeFailedTextProvider = { error ->
            context.getString(Strings.terminal_shell_probe_failed, error)
        }
    )

    val selectedBackendMode = TerminalSettingsSectionSupport.resolveSelectedBackendMode(
        linuxEnvironmentEnabled = linuxEnvironmentEnabled,
        backendMode = backendMode,
    )

    androidx.compose.runtime.LaunchedEffect(linuxEnvironmentEnabled, backendMode) {
        if (TerminalSettingsSectionSupport.shouldForceHostBackend(
                linuxEnvironmentEnabled = linuxEnvironmentEnabled,
                backendMode = backendMode,
            )
        ) {
            prefs.backendMode = BackendMode.HOST.value
        }
    }

    val selectedLocale = TerminalSettingsHelper.getTerminalLocaleFromValue(locale)
    val activeLinuxProfile = if (linuxEnvironmentEnabled) {
        runCatching { PRootBootstrap.getActiveProfile(context) }.getOrNull()
    } else {
        null
    }

    SettingsCard {
        SettingsClickableItem(
            title = stringResource(Strings.settings_terminal_backend),
            subtitle = stringResource(Strings.settings_terminal_backend_desc),
            value = backendModeLabel(selectedBackendMode),
            onClick = { showBackendModeDialog = true },
            showDivider = true
        )

        SettingsClickableItem(
            title = stringResource(Strings.settings_shell_type),
            subtitle = shellSubtitle,
            value = shellTypeLabel(selectedShell),
            onClick = { showShellTypeDialog = true },
            showDivider = false
        )
    }

    if (linuxEnvironmentEnabled) {
        SettingsCategoryTitle(stringResource(Strings.settings_cat_linux_tools))
        val requiredLinuxToolStates = TerminalSettingsSectionSupport.buildRequiredLinuxToolStates(
            locale = locale,
            devPackagesInstalled = devPackagesInstalled,
            zshInstalled = zshInstalled,
            localeSupportInstalled = localeSupportInstalled,
        )

        SettingsCard {
            SettingsClickableItem(
                title = stringResource(Strings.settings_linux_tools_health),
                value = linuxToolsHealthValue(requiredLinuxToolStates),
                onClick = { showLinuxToolsActions = true },
                showDivider = true
            )
            SettingsDisplayItem(
                title = stringResource(Strings.settings_linux_tools_system_name),
                value = activeLinuxProfile?.displayName ?: stringResource(Strings.value_not_installed),
                showDivider = true
            )
            SettingsDisplayItem(
                title = stringResource(Strings.settings_linux_tools_package_manager),
                value = packageManagerLabel(activeLinuxProfile?.packageManager ?: RootfsPackageManager.UNKNOWN),
                showDivider = true
            )

            AlpineMirrorSettingsItem(
                profile = activeLinuxProfile,
                showDivider = true,
            )

            SettingsClickableItem(
                title = stringResource(Strings.settings_guest_dev_packages),
                subtitle = guestDevPackagesSubtitle(),
                value = linuxToolStatusLabel(devPackagesInstalled),
                onClick = {
                    if (shellResolver.isPRootInstalled()) {
                        showDevPackagesActions = true
                    } else {
                        Toast.makeText(context, toastPRootNotInstalled, Toast.LENGTH_LONG).show()
                    }
                },
                showDivider = true
            )

            SettingsClickableItem(
                title = stringResource(Strings.shell_type_zsh),
                subtitle = stringResource(Strings.settings_linux_tool_zsh_desc),
                value = linuxToolStatusLabel(zshInstalled),
                onClick = {
                    if (!shellResolver.isPRootInstalled()) {
                        Toast.makeText(context, toastPRootNotInstalled, Toast.LENGTH_LONG).show()
                    } else if (zshInstalled == true) {
                        showZshActions = true
                    } else {
                        forceZshInstall = false
                        showInstallZshDialog = true
                    }
                },
                showDivider = true
            )

            SettingsClickableItem(
                title = stringResource(Strings.settings_linux_locale_support),
                subtitle = stringResource(
                    Strings.settings_linux_locale_support_desc,
                    stringResource(selectedLocale.displayNameResId)
                ),
                value = linuxToolStatusLabel(
                    status = localeSupportInstalled,
                    notRequired = locale == "C.UTF-8"
                ),
                onClick = {
                    if (!shellResolver.isPRootInstalled()) {
                        Toast.makeText(context, toastPRootNotInstalled, Toast.LENGTH_LONG).show()
                    } else if (locale != "C.UTF-8" && localeSupportInstalled == false) {
                        pendingLocale = locale
                        forceLocaleInstall = false
                        showInstallDialog = true
                    } else if (locale != "C.UTF-8" && localeSupportInstalled == true) {
                        showLocaleActions = true
                    } else {
                        showLocaleDialog = true
                    }
                },
                showDivider = false
            )
        }
    }

    // 语言设置
    SettingsCategoryTitle(stringResource(Strings.settings_cat_interaction))

    SettingsCard {
        SettingsClickableItem(
            title = stringResource(Strings.settings_locale),
            value = stringResource(TerminalSettingsHelper.getTerminalLocaleFromValue(locale).displayNameResId),
            onClick = { showLocaleDialog = true },
            showDivider = true
        )

        SettingsSwitchItem(
            title = stringResource(Strings.settings_cursor_blink),
            subtitle = stringResource(Strings.settings_cursor_blink_desc),
            checked = cursorBlinkEnabled,
            onCheckedChange = { prefs.cursorBlinkEnabled = it },
            showDivider = cursorBlinkEnabled
        )

        if (cursorBlinkEnabled) {
            SettingsClickableItem(
                title = stringResource(Strings.settings_cursor_blink_rate),
                value = "$cursorBlinkRate ms",
                onClick = { showCursorBlinkRateDialog = true },
                showDivider = false
            )
        }
    }

    Spacer(modifier = Modifier.height(16.dp))

    // 对话框
    if (showThemeDialog) {
        val options = themeProvider.getAllThemes().map { it.name to it.name }
        TinaSingleChoiceDialog(
            title = stringResource(Strings.settings_cat_terminal_theme),
            options = options,
            selectedValue = themeName,
            onSelected = { value ->
                prefs.themeName = value
                showThemeDialog = false
            },
            onDismiss = { showThemeDialog = false }
        )
    }

    if (showFontFamilyDialog) {
        val hasBuiltIn = AppFontManager.hasBuiltInFont(context)
        val builtInName = TerminalSettingsSectionSupport.resolveBuiltInFontDisplayName(
            hasBuiltIn = hasBuiltIn,
            currentFontName = AppFontManager.getCurrentFontName(context),
            builtInNotInstalledText = stringResource(Strings.font_builtin_not_installed),
        )
        val customFontName = TerminalSettingsSectionSupport.resolveCustomFontDisplayName(
            customFontPath = customFontPath,
            selectFileText = stringResource(Strings.font_select_file),
        )
        val builtInLabel = stringResource(Strings.font_builtin)
        val systemMonoLabel = stringResource(Strings.font_system_mono)
        val customLabel = stringResource(Strings.font_custom)
        val actions = TerminalSettingsSectionSupport.buildFontFamilyActionSpecs(
            builtInLabel = builtInLabel,
            builtInDisplayName = builtInName,
            systemMonoLabel = systemMonoLabel,
            customLabel = customLabel,
            customDisplayName = customFontName,
            customFontPath = customFontPath,
            changeCustomFontLabel = stringResource(Strings.btn_change_custom_font),
        ).map { spec ->
            spec.label to {
                showFontFamilyDialog = false
                when (spec.action) {
                    TerminalSettingsFontAction.SelectBuiltIn -> {
                        prefs.fontName = TerminalSettingsHelper.FONT_TYPE_BUILTIN
                    }

                    TerminalSettingsFontAction.SelectSystemMono -> {
                        prefs.fontName = TerminalSettingsHelper.FONT_TYPE_SYSTEM
                    }

                    TerminalSettingsFontAction.UseCustomFont -> {
                        prefs.fontName = TerminalSettingsHelper.FONT_TYPE_CUSTOM
                    }

                    TerminalSettingsFontAction.PickCustomFont,
                    TerminalSettingsFontAction.ChangeCustomFont -> {
                        fontPickerLauncher.launch(createTerminalFontPickerIntent())
                    }
                }
            }
        }

        TinaActionChoiceDialog(
            title = stringResource(Strings.dialog_title_terminal_font),
            actions = actions,
            onDismiss = { showFontFamilyDialog = false }
        )
    }

    if (showFontSizeDialog) {
        val min = TerminalSettingsHelper.MIN_FONT_SIZE
        val max = TerminalSettingsHelper.MAX_FONT_SIZE
        TinaSliderDialog(
            title = stringResource(Strings.dialog_title_terminal_font_size),
            value = fontSizeSp,
            valueRange = min..max,
            steps = TerminalSettingsSectionSupport.resolveFontSizeDialogSteps(min, max),
            valueLabel = { v -> "${v.roundToInt()} sp" },
            onValueSelected = { v ->
                prefs.fontSize = v
                showFontSizeDialog = false
            },
            onDismiss = { showFontSizeDialog = false }
        )
    }

    if (showLocaleDialog) {
        val options = TerminalSettingsHelper.getAllTerminalLocales().map {
            it.value to stringResource(it.displayNameResId)
        }
        TinaSingleChoiceDialog(
            title = stringResource(Strings.dialog_title_terminal_locale),
            options = options,
            selectedValue = locale,
            onSelected = { value ->
                showLocaleDialog = false
                if (value != "C.UTF-8") {
                    pendingLocale = value
                    scope.launch {
                        if (localeInstaller.needsLocalePackage(value)) {
                            showInstallDialog = true
                        } else {
                            prefs.locale = value
                        }
                    }
                } else {
                    prefs.locale = value
                }
            },
            onDismiss = { showLocaleDialog = false }
        )
    }

    if (showCursorBlinkRateDialog) {
        val min = TerminalSettingsHelper.CURSOR_BLINK_RATE_MIN.toFloat()
        val max = TerminalSettingsHelper.CURSOR_BLINK_RATE_MAX.toFloat()
        val steps = ((max - min) / 50).toInt().coerceAtLeast(0) - 1
        TinaSliderDialog(
            title = stringResource(Strings.dialog_title_cursor_blink_rate),
            value = cursorBlinkRate.toFloat(),
            valueRange = min..max,
            steps = steps.coerceAtLeast(0),
            valueLabel = { v -> "${v.roundToInt()} ms" },
            onValueSelected = { v ->
                prefs.cursorBlinkRate = v.roundToInt()
                showCursorBlinkRateDialog = false
            },
            onDismiss = { showCursorBlinkRateDialog = false }
        )
    }

    if (showShellTypeDialog) {
        val options = TerminalSettingsSectionSupport.buildShellTypeOptions().map { option ->
            option.value to buildTerminalOptionLabel(
                label = stringResource(option.labelRes),
                description = option.descriptionRes?.let { stringResource(it) }
            )
        }
        TinaSingleChoiceDialog(
            title = stringResource(Strings.dialog_title_shell_type),
            options = options,
            selectedValue = shellType,
            onSelected = { value ->
                val newType = TerminalSettingsHelper.getShellTypeFromValue(value)
                if (newType == ShellType.ZSH) {
                    scope.launch {
                        val available = shellResolver.isShellAvailable(TerminalSettingsHelper.SHELL_TYPE_ZSH)
                        if (available) {
                            prefs.shellType = value
                            showShellTypeDialog = false
                            Toast.makeText(
                                context,
                                toastShellTypeChanged,
                                Toast.LENGTH_LONG
                            ).show()
                            shellAvailability = runCatching { shellResolver.probeAvailability() }.getOrNull()
                        } else {
                            val backend = shellAvailability?.backend
                                ?: runCatching { shellResolver.probeAvailability().also { shellAvailability = it } }
                                    .getOrNull()
                                    ?.backend

                            showShellTypeDialog = false

                            if (backend == TerminalBackendType.PROOT) {
                                showInstallZshDialog = true
                            } else {
                                Toast.makeText(
                                    context,
                                    toastZshUnavailableOnHost,
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                } else {
                    prefs.shellType = value
                    showShellTypeDialog = false
                    Toast.makeText(
                        context,
                        toastShellTypeChanged,
                        Toast.LENGTH_LONG
                    ).show()
                    scope.launch {
                        shellAvailability = runCatching { shellResolver.probeAvailability() }.getOrNull()
                    }
                }
            },
            onDismiss = { showShellTypeDialog = false }
        )
    }

    if (showBackendModeDialog) {
        val options = TerminalSettingsSectionSupport.buildBackendModeOptions(
            linuxEnvironmentEnabled = linuxEnvironmentEnabled
        ).map { option ->
            option.value to buildTerminalOptionLabel(
                label = stringResource(option.labelRes),
                description = option.descriptionRes?.let { stringResource(it) }
            )
        }
        TinaSingleChoiceDialog(
            title = stringResource(Strings.dialog_title_terminal_backend),
            options = options,
            selectedValue = selectedBackendMode.value,
            onSelected = { value ->
                val newMode = TerminalSettingsHelper.getBackendModeFromValue(value)
                if (newMode == BackendMode.PROOT && !shellResolver.isPRootInstalled()) {
                    // PRoot 未安装，提示用户
                    Toast.makeText(context, toastPRootNotInstalled, Toast.LENGTH_LONG).show()
                    showBackendModeDialog = false
                    val installIntent = Intent()
                        .setClassName(context.packageName, DEPENDENCY_INSTALL_ACTIVITY_CLASS_NAME)
                        .putExtra(EXTRA_INSTALL_LINUX_ENVIRONMENT, true)
                    if (context !is Activity) {
                        installIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    runCatching {
                        context.startActivity(installIntent)
                    }.onFailure { error ->
                        Timber.w(error, "Failed to open dependency installer from terminal settings")
                    }
                } else {
                    prefs.backendMode = value
                    showBackendModeDialog = false
                    Toast.makeText(context, toastBackendModeChanged, Toast.LENGTH_LONG).show()
                    // 后端模式改变后重新探测可用 shell
                    scope.launch {
                        shellAvailability = runCatching { shellResolver.probeAvailability() }.getOrNull()
                    }
                }
            },
            onDismiss = { showBackendModeDialog = false }
        )
    }

    // Zsh 安装确认对话框
    if (showInstallZshDialog && !isInstallingZsh && zshInstallError == null) {
        val dialogSpec = TerminalSettingsSectionSupport.resolveZshInstallDialogSpec(
            forceReinstall = forceZshInstall
        )
        TinaConfirmDialog(
            title = resolveTerminalSettingsText(dialogSpec.title),
            message = resolveTerminalSettingsText(dialogSpec.message),
            confirmText = stringResource(Strings.btn_install),
            onConfirm = {
                isInstallingZsh = true
                zshInstallError = null
                scope.launch {
                    val result = zshInstaller.install(force = forceZshInstall) { progress ->
                        zshInstallProgress = progress
                    }
                    isInstallingZsh = false
                    when (result) {
                        ShellInstallResult.Success -> {
                            prefs.shellType = TerminalSettingsHelper.SHELL_TYPE_ZSH
                            showInstallZshDialog = false
                            forceZshInstall = false
                            linuxToolsStatusVersion++
                            Toast.makeText(
                                context,
                                toastZshInstallSuccess,
                                Toast.LENGTH_SHORT
                            ).show()
                            shellAvailability = runCatching { shellResolver.probeAvailability() }.getOrNull()
                        }
                        is ShellInstallResult.Error -> {
                            zshInstallError = result.message
                        }
                    }
                }
            },
            onDismiss = {
                showInstallZshDialog = false
                zshInstallError = null
                forceZshInstall = false
            }
        )
    }

    // Zsh 安装进度对话框
    val pleaseWaitText = stringResource(Strings.progress_please_wait)
    if (isInstallingZsh) {
        TinaLoadingDialog(
            title = stringResource(Strings.dialog_title_installing_zsh),
            message = zshInstallProgress.ifBlank { pleaseWaitText }
        )
    }

    if (showLinuxToolsActions) {
        val activeProfileName = activeLinuxProfile?.displayName ?: stringResource(Strings.value_not_installed)
        val packageManagerName = packageManagerLabel(
            activeLinuxProfile?.packageManager ?: RootfsPackageManager.UNKNOWN
        )
        val localeLabel = stringResource(selectedLocale.displayNameResId)
        val actions = TerminalSettingsSectionSupport.buildLinuxToolsActionSpecs(
            isPRootInstalled = shellResolver.isPRootInstalled(),
            devPackagesInstalled = devPackagesInstalled,
            zshInstalled = zshInstalled,
            locale = locale,
            localeSupportInstalled = localeSupportInstalled,
        ).map { spec ->
            stringResource(spec.labelRes) to {
                showLinuxToolsActions = false
                when (spec.action) {
                    TerminalSettingsAction.RefreshLinuxToolsStatus -> {
                        linuxToolsStatusVersion++
                        Unit
                    }

                    TerminalSettingsAction.InstallGuestDevPackages -> {
                        forceDevPackagesInstall = false
                        showInstallDevPackagesDialog = true
                    }

                    TerminalSettingsAction.ReinstallGuestDevPackages -> {
                        forceDevPackagesInstall = true
                        showInstallDevPackagesDialog = true
                    }

                    TerminalSettingsAction.ChangeShell -> {
                        showShellTypeDialog = true
                    }

                    TerminalSettingsAction.InstallZsh -> {
                        forceZshInstall = false
                        showInstallZshDialog = true
                    }

                    TerminalSettingsAction.ChangeLocale -> {
                        showLocaleDialog = true
                    }

                    TerminalSettingsAction.InstallLocale -> {
                        pendingLocale = locale
                        forceLocaleInstall = false
                        showInstallDialog = true
                    }

                    TerminalSettingsAction.ReinstallZsh,
                    TerminalSettingsAction.RebuildLocale -> Unit
                }
            }
        }

        TinaActionChoiceDialog(
            title = stringResource(Strings.settings_linux_tools_health),
            message = linuxToolsDiagnosticMessage(
                activeProfileName = activeProfileName,
                packageManagerName = packageManagerName,
                localeLabel = localeLabel,
            ),
            actions = actions,
            onDismiss = { showLinuxToolsActions = false }
        )
    }

    if (showInstallDevPackagesDialog && !isInstallingDevPackages && devPackagesInstallError == null) {
        val dialogSpec = TerminalSettingsSectionSupport.resolveGuestDevPackagesInstallDialogSpec(
            forceReinstall = forceDevPackagesInstall
        )
        TinaConfirmDialog(
            title = resolveTerminalSettingsText(dialogSpec.title),
            message = resolveTerminalSettingsText(dialogSpec.message),
            confirmText = stringResource(Strings.btn_install),
            onConfirm = {
                isInstallingDevPackages = true
                devPackagesInstallError = null
                scope.launch {
                    val result = guestDevPackagesInstaller.install(force = forceDevPackagesInstall) { progress ->
                        devPackagesInstallProgress = progress
                    }
                    isInstallingDevPackages = false
                    when (result) {
                        GuestDevPackagesInstallResult.Success -> {
                            showInstallDevPackagesDialog = false
                            forceDevPackagesInstall = false
                            linuxToolsStatusVersion++
                            Toast.makeText(
                                context,
                                toastGuestDevPackagesInstallSuccess,
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        is GuestDevPackagesInstallResult.Error -> {
                            devPackagesInstallError = result.message
                        }
                    }
                }
            },
            onDismiss = {
                showInstallDevPackagesDialog = false
                devPackagesInstallError = null
                forceDevPackagesInstall = false
            }
        )
    }

    if (showDevPackagesActions) {
        val actions = TerminalSettingsSectionSupport.buildGuestDevPackagesActionSpecs(
            devPackagesInstalled = devPackagesInstalled
        ).map { spec ->
            stringResource(spec.labelRes) to {
                showDevPackagesActions = false
                when (spec.action) {
                    TerminalSettingsAction.InstallGuestDevPackages -> {
                        forceDevPackagesInstall = false
                        showInstallDevPackagesDialog = true
                    }

                    TerminalSettingsAction.ReinstallGuestDevPackages -> {
                        forceDevPackagesInstall = true
                        showInstallDevPackagesDialog = true
                    }

                    TerminalSettingsAction.RefreshLinuxToolsStatus,
                    TerminalSettingsAction.ChangeShell,
                    TerminalSettingsAction.InstallZsh,
                    TerminalSettingsAction.ReinstallZsh,
                    TerminalSettingsAction.ChangeLocale,
                    TerminalSettingsAction.InstallLocale,
                    TerminalSettingsAction.RebuildLocale -> Unit
                }
            }
        }
        TinaActionChoiceDialog(
            title = stringResource(Strings.settings_guest_dev_packages),
            message = guestDevPackagesDiagnosticMessage(),
            actions = actions,
            onDismiss = { showDevPackagesActions = false }
        )
    }

    if (showZshActions) {
        TinaActionChoiceDialog(
            title = stringResource(Strings.shell_type_zsh),
            message = stringResource(Strings.settings_linux_tool_zsh_desc),
            actions = TerminalSettingsSectionSupport.buildZshActionSpecs().map { spec ->
                stringResource(spec.labelRes) to {
                    showZshActions = false
                    when (spec.action) {
                        TerminalSettingsAction.ChangeShell -> {
                            showShellTypeDialog = true
                        }

                        TerminalSettingsAction.ReinstallZsh -> {
                            forceZshInstall = true
                            showInstallZshDialog = true
                        }

                        TerminalSettingsAction.RefreshLinuxToolsStatus,
                        TerminalSettingsAction.InstallGuestDevPackages,
                        TerminalSettingsAction.ReinstallGuestDevPackages,
                        TerminalSettingsAction.InstallZsh,
                        TerminalSettingsAction.ChangeLocale,
                        TerminalSettingsAction.InstallLocale,
                        TerminalSettingsAction.RebuildLocale -> Unit
                    }
                }
            },
            onDismiss = { showZshActions = false }
        )
    }

    if (isInstallingDevPackages) {
        TinaLoadingDialog(
            title = stringResource(Strings.dialog_title_installing_guest_dev_packages),
            message = devPackagesInstallProgress.ifBlank { pleaseWaitText }
        )
    }

    if (showLocaleActions) {
        TinaActionChoiceDialog(
            title = stringResource(Strings.settings_linux_locale_support),
            message = stringResource(
                Strings.settings_linux_locale_support_desc,
                stringResource(TerminalSettingsHelper.getTerminalLocaleFromValue(locale).displayNameResId)
            ),
            actions = TerminalSettingsSectionSupport.buildLocaleActionSpecs().map { spec ->
                stringResource(spec.labelRes) to {
                    showLocaleActions = false
                    when (spec.action) {
                        TerminalSettingsAction.ChangeLocale -> {
                            showLocaleDialog = true
                        }

                        TerminalSettingsAction.RebuildLocale -> {
                            pendingLocale = locale
                            forceLocaleInstall = true
                            showInstallDialog = true
                        }

                        TerminalSettingsAction.RefreshLinuxToolsStatus,
                        TerminalSettingsAction.InstallGuestDevPackages,
                        TerminalSettingsAction.ReinstallGuestDevPackages,
                        TerminalSettingsAction.ChangeShell,
                        TerminalSettingsAction.InstallZsh,
                        TerminalSettingsAction.ReinstallZsh,
                        TerminalSettingsAction.InstallLocale -> Unit
                    }
                }
            },
            onDismiss = { showLocaleActions = false }
        )
    }

    devPackagesInstallError?.let { error ->
        TinaErrorDialog(
            title = stringResource(Strings.dialog_title_install_failed),
            message = error,
            onRetry = {
                devPackagesInstallError = null
                showInstallDevPackagesDialog = true
            },
            onDismiss = {
                devPackagesInstallError = null
                showInstallDevPackagesDialog = false
                forceDevPackagesInstall = false
            }
        )
    }

    // Zsh 安装错误对话框
    zshInstallError?.let { error ->
        TinaErrorDialog(
            title = stringResource(Strings.dialog_title_install_failed),
            message = error,
            onRetry = {
                zshInstallError = null
                showInstallZshDialog = true
            },
            onDismiss = {
                zshInstallError = null
                showInstallZshDialog = false
            }
        )
    }

    // 语言包安装确认对话框
    if (showInstallDialog && !isInstalling && installError == null) {
        val dialogSpec = TerminalSettingsSectionSupport.resolveLocaleInstallDialogSpec(
            forceReinstall = forceLocaleInstall,
            pendingLocale = pendingLocale,
            currentLocale = locale,
        )
        TinaConfirmDialog(
            title = resolveTerminalSettingsText(dialogSpec.title),
            message = resolveTerminalSettingsText(dialogSpec.message),
            confirmText = stringResource(Strings.btn_install),
            dismissText = stringResource(Strings.btn_skip),
            onConfirm = {
                isInstalling = true
                installError = null
                scope.launch {
                    val localeValue = TerminalSettingsSectionSupport.resolveLocaleInstallTarget(
                        pendingLocale = pendingLocale
                    )
                    val result = localeInstaller.installLocalePackage(localeValue, force = forceLocaleInstall) { progress ->
                        installProgress = progress
                    }
                    isInstalling = false
                    when (result) {
                        is LocaleInstallResult.Success -> {
                            pendingLocale?.let { prefs.locale = it }
                            showInstallDialog = false
                            pendingLocale = null
                            forceLocaleInstall = false
                            linuxToolsStatusVersion++
                        }
                        is LocaleInstallResult.Error -> {
                            installError = result.message
                        }
                    }
                }
            },
            onDismiss = {
                showInstallDialog = false
                pendingLocale = null
                forceLocaleInstall = false
            }
        )
    }

    // 安装进度对话框（Locale）
    if (isInstalling) {
        TinaLoadingDialog(
            title = stringResource(Strings.dialog_title_installing_locale),
            message = installProgress.ifBlank { pleaseWaitText }
        )
    }

    // 安装错误对话框
    installError?.let { error ->
        TinaErrorDialog(
            title = stringResource(Strings.dialog_title_install_failed),
            message = error,
            onRetry = { installError = null },
            onDismiss = {
                installError = null
                showInstallDialog = false
                pendingLocale = null
                forceLocaleInstall = false
            }
        )
    }
}

