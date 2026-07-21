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

/**
 * Terminal settings helper resolvers and font picker utilities.
 */

@Composable
internal fun resolveTerminalSettingsText(spec: TerminalSettingsTextSpec): String = if (spec.formatArgs.isEmpty()) {
    stringResource(spec.labelRes)
} else {
    stringResource(spec.labelRes, *spec.formatArgs.toTypedArray())
}

@Composable
internal fun resolveTerminalSettingsDisplay(spec: TerminalSettingsDisplaySpec): String = when (spec) {
    is TerminalSettingsDisplaySpec.ResourceText -> stringResource(spec.labelRes)
    is TerminalSettingsDisplaySpec.RawText -> spec.value
}

internal fun buildTerminalOptionLabel(label: String, description: String?): String = if (description.isNullOrBlank()) {
    label
} else {
    "$label\n$description"
}

internal fun createTerminalFontPickerIntent(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
    addCategory(Intent.CATEGORY_OPENABLE)
    type = "*/*"
    putExtra(
        Intent.EXTRA_MIME_TYPES,
        arrayOf("font/ttf", "font/otf", "application/x-font-ttf")
    )
}

/**
 * 处理终端自定义字体选择
 */
internal fun handleTerminalFontSelected(
    context: android.content.Context,
    uri: Uri,
    prefs: ITerminalPreferences,
    toastTerminalFontSetTemplate: String,
    errorInvalidFontFile: String,
    errorFontSetFailedTemplate: String,
) {
    try {
        val fontsDir = com.wuxianggujun.tinaide.storage.ProjectPaths.ensureDir(
            com.wuxianggujun.tinaide.storage.ProjectPaths.getTerminalFontsRoot(context)
        )
        val fileName = uri.lastPathSegment?.substringAfterLast('/') ?: "custom_terminal_font.ttf"
        val destFile = File(fontsDir, fileName)

        // 先复制文件
        context.contentResolver.openInputStream(uri)?.use { input ->
            destFile.outputStream().use { output -> input.copyTo(output) }
        }

        // 验证并设置字体
        val success = try {
            prefs.setCustomFont(destFile.absolutePath)
            true
        } catch (e: Exception) {
            Timber.e(e, "Failed to set custom font")
            false
        }

        if (success) {
            Toast.makeText(
                context,
                String.format(Locale.getDefault(), toastTerminalFontSetTemplate, destFile.name),
                Toast.LENGTH_SHORT
            ).show()
        } else {
            // 删除无效的字体文件
            destFile.delete()
            Toast.makeText(context, errorInvalidFontFile, Toast.LENGTH_LONG).show()
        }
    } catch (e: Exception) {
        Timber.tag("TerminalSettings").e(e, "Failed to set custom font")
        Toast.makeText(
            context,
            String.format(Locale.getDefault(), errorFontSetFailedTemplate, e.message ?: ""),
            Toast.LENGTH_SHORT
        ).show()
    }
}
