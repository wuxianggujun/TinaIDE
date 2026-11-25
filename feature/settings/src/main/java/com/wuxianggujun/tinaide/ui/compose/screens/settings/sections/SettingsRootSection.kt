package com.wuxianggujun.tinaide.ui.compose.screens.settings.sections

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wuxianggujun.tinaide.core.config.Prefs
import com.wuxianggujun.tinaide.core.i18n.Drawables
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.ui.compose.icons.TinaTabIcons
import com.wuxianggujun.tinaide.ui.compose.screens.settings.SettingsRoute
import com.wuxianggujun.tinaide.ui.compose.screens.settings.components.SettingsCard
import com.wuxianggujun.tinaide.ui.compose.screens.settings.components.SettingsMenuItemWithIcon

// 定义图标颜色
private val EditorIconColor = Color(0xFF4A90D9)
private val LspIconColor = Color(0xFF9C27B0)
private val CompilerIconColor = Color(0xFFF5A623)
private val ProjectIconColor = Color(0xFF4CAF50)
private val StorageIconColor = Color(0xFF4A90D9)
private val TerminalIconColor = Color(0xFF4A90D9)
private val AiIconColor = Color(0xFF6750A4)
private val GitIconColor = Color(0xFF34A853)
private val AppearanceIconColor = Color(0xFFE91E63)
private val KeyboardIconColor = Color(0xFF9C27B0)
private val PluginsIconColor = Color(0xFF4A90D9)
private val PackagesIconColor = Color(0xFF00BCD4)
private val HelpIconColor = Color(0xFF2196F3)
private val DeveloperIconColor = Color(0xFFFF9800)
private val AboutIconColor = Color(0xFF4A90D9)

@Composable
internal fun SettingsRootSection(onNavigateTo: (SettingsRoute) -> Unit) {
    // 监听开发者选项启用状态
    val developerOptionsEnabled by Prefs.developerOptionsEnabledFlow.collectAsState()

    Spacer(modifier = Modifier.height(8.dp))

    // 开发相关设置（编辑器、LSP、编译器、项目、存储）
    SettingsCard {
        SettingsMenuItemWithIcon(
            iconRes = Drawables.ic_settings_editor,
            iconBackgroundColor = EditorIconColor,
            title = stringResource(Strings.settings_title_editor),
            subtitle = stringResource(Strings.settings_desc_editor),
            onClick = { onNavigateTo(SettingsRoute.Editor) },
            showDivider = true
        )
        SettingsMenuItemWithIcon(
            iconRes = Drawables.ic_settings_plugins,
            iconBackgroundColor = LspIconColor,
            title = stringResource(Strings.settings_title_lsp),
            subtitle = stringResource(Strings.settings_desc_lsp),
            onClick = { onNavigateTo(SettingsRoute.Lsp) },
            showDivider = true
        )
        SettingsMenuItemWithIcon(
            iconRes = Drawables.ic_settings_compiler,
            iconBackgroundColor = CompilerIconColor,
            title = stringResource(Strings.settings_title_compiler),
            subtitle = stringResource(Strings.settings_desc_compiler),
            onClick = { onNavigateTo(SettingsRoute.Compiler) },
            showDivider = true
        )
        SettingsMenuItemWithIcon(
            iconRes = Drawables.ic_settings_project,
            iconBackgroundColor = ProjectIconColor,
            title = stringResource(Strings.settings_title_project),
            subtitle = stringResource(Strings.settings_desc_project),
            onClick = { onNavigateTo(SettingsRoute.Project) },
            showDivider = true
        )
        SettingsMenuItemWithIcon(
            iconRes = Drawables.ic_settings_storage,
            iconBackgroundColor = StorageIconColor,
            title = stringResource(Strings.settings_title_storage),
            subtitle = stringResource(Strings.settings_desc_storage),
            onClick = { onNavigateTo(SettingsRoute.Storage) },
            showDivider = true
        )
        SettingsMenuItemWithIcon(
            imageVector = TinaTabIcons.Git,
            iconBackgroundColor = GitIconColor,
            title = stringResource(Strings.settings_title_git),
            subtitle = stringResource(Strings.settings_desc_git),
            onClick = { onNavigateTo(SettingsRoute.Git) },
            showDivider = true
        )
        SettingsMenuItemWithIcon(
            iconRes = Drawables.ic_settings_plugins,
            iconBackgroundColor = PluginsIconColor,
            title = stringResource(Strings.settings_title_plugins),
            subtitle = stringResource(Strings.settings_desc_plugins),
            onClick = { onNavigateTo(SettingsRoute.Plugins) },
            showDivider = true
        )
        SettingsMenuItemWithIcon(
            iconRes = Drawables.ic_settings_storage,
            iconBackgroundColor = PackagesIconColor,
            title = stringResource(Strings.settings_title_packages),
            subtitle = stringResource(Strings.settings_desc_packages),
            onClick = { onNavigateTo(SettingsRoute.Packages) },
            showDivider = false
        )
    }

    // 界面相关设置（终端、AI、外观、快捷键）
    SettingsCard {
        SettingsMenuItemWithIcon(
            iconRes = Drawables.ic_settings_terminal,
            iconBackgroundColor = TerminalIconColor,
            title = stringResource(Strings.settings_title_terminal),
            subtitle = stringResource(Strings.settings_desc_terminal),
            onClick = { onNavigateTo(SettingsRoute.Terminal) },
            showDivider = true
        )
        SettingsMenuItemWithIcon(
            imageVector = TinaTabIcons.RikkaHub,
            iconBackgroundColor = AiIconColor,
            title = stringResource(Strings.settings_title_ai),
            subtitle = stringResource(Strings.settings_desc_ai),
            onClick = { onNavigateTo(SettingsRoute.Ai) },
            showDivider = true
        )
        SettingsMenuItemWithIcon(
            iconRes = Drawables.ic_settings_appearance,
            iconBackgroundColor = AppearanceIconColor,
            title = stringResource(Strings.settings_title_appearance),
            subtitle = stringResource(Strings.settings_desc_appearance),
            onClick = { onNavigateTo(SettingsRoute.Appearance) },
            showDivider = true
        )
        SettingsMenuItemWithIcon(
            iconRes = Drawables.ic_settings_keyboard,
            iconBackgroundColor = KeyboardIconColor,
            title = stringResource(Strings.settings_title_keyboard),
            subtitle = stringResource(Strings.settings_desc_keyboard),
            onClick = { onNavigateTo(SettingsRoute.Keyboard) },
            showDivider = false
        )
    }

    // 其他设置（帮助、开发者选项、关于）
    SettingsCard {
        SettingsMenuItemWithIcon(
            iconRes = Drawables.ic_help_book,
            iconBackgroundColor = HelpIconColor,
            title = stringResource(Strings.settings_title_help),
            subtitle = stringResource(Strings.settings_desc_help),
            onClick = { onNavigateTo(SettingsRoute.Help) },
            showDivider = true
        )
        // 只有在本机启用开发者选项后才显示
        if (developerOptionsEnabled) {
            SettingsMenuItemWithIcon(
                iconRes = Drawables.ic_settings_developer,
                iconBackgroundColor = DeveloperIconColor,
                title = stringResource(Strings.settings_title_developer),
                subtitle = stringResource(Strings.settings_desc_developer),
                onClick = { onNavigateTo(SettingsRoute.Developer) },
                showDivider = true
            )
        }

        SettingsMenuItemWithIcon(
            iconRes = Drawables.ic_settings_about,
            iconBackgroundColor = AboutIconColor,
            title = stringResource(Strings.settings_title_about),
            subtitle = stringResource(Strings.settings_desc_about),
            onClick = { onNavigateTo(SettingsRoute.About) },
            showDivider = false
        )
    }

    Spacer(modifier = Modifier.height(16.dp))
}
