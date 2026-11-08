package com.wuxianggujun.tinaide.ui.compose.screens.main.profile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.VolunteerActivism
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wuxianggujun.tinaide.BuildConfig
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.ui.compose.components.TinaCard
import com.wuxianggujun.tinaide.ui.compose.components.TinaTopBar

/**
 * 开源版“我的”页。
 *
 * 账号登录、第三方登录、激活码和会员入口已从主 APK 移除；这里仅保留本地内容、
 * 日志、关于等仍可用能力。
 */
@Composable
fun ProfileScreen(
    modifier: Modifier = Modifier,
    onNavigateToSettings: () -> Unit = {},
    onNavigateToDonation: () -> Unit = {},
    onNavigateToAbout: () -> Unit = {},
    onNavigateToPlugins: () -> Unit = {},
    onNavigateToPackages: () -> Unit = {},
    onNavigateToFavorites: () -> Unit = {},
    onNavigateToDownloadHistory: () -> Unit = {},
) {
    Scaffold(
        topBar = {
            TinaTopBar(
                title = stringResource(Strings.nav_profile)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                SectionHeader(title = stringResource(Strings.profile_section_my_content))
            }

            item {
                MyContentSection(
                    onMyPluginsClick = onNavigateToPlugins,
                    onMyPackagesClick = onNavigateToPackages,
                    onDownloadHistoryClick = onNavigateToDownloadHistory,
                    onFavoritesClick = onNavigateToFavorites
                )
            }

            item {
                Spacer(modifier = Modifier.height(8.dp))
                SectionHeader(title = stringResource(Strings.profile_section_settings_help))
            }

            item {
                SettingsSection(
                    onSettingsClick = onNavigateToSettings,
                    onDonationClick = onNavigateToDonation,
                    onAboutClick = onNavigateToAbout
                )
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
                VersionInfo()
            }
        }
    }
}

@Composable
private fun MyContentSection(
    onMyPluginsClick: () -> Unit,
    onMyPackagesClick: () -> Unit,
    onDownloadHistoryClick: () -> Unit,
    onFavoritesClick: () -> Unit
) {
    TinaCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            ProfileMenuItem(
                icon = Icons.Default.Extension,
                title = stringResource(Strings.profile_my_plugins),
                subtitle = stringResource(Strings.profile_my_plugins_desc),
                onClick = onMyPluginsClick
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ProfileMenuItem(
                icon = Icons.Default.ShoppingCart,
                title = stringResource(Strings.profile_my_packages),
                subtitle = stringResource(Strings.profile_my_packages_desc),
                onClick = onMyPackagesClick
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ProfileMenuItem(
                icon = Icons.Default.History,
                title = stringResource(Strings.profile_download_history),
                subtitle = stringResource(Strings.profile_download_history_desc),
                onClick = onDownloadHistoryClick
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ProfileMenuItem(
                icon = Icons.Default.Favorite,
                title = stringResource(Strings.profile_favorites),
                subtitle = stringResource(Strings.profile_favorites_desc),
                onClick = onFavoritesClick
            )
        }
    }
}

@Composable
private fun SettingsSection(
    onSettingsClick: () -> Unit,
    onDonationClick: () -> Unit,
    onAboutClick: () -> Unit
) {
    TinaCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            ProfileMenuItem(
                icon = Icons.Default.Settings,
                title = stringResource(Strings.profile_settings),
                subtitle = stringResource(Strings.profile_settings_desc),
                onClick = onSettingsClick
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ProfileMenuItem(
                icon = Icons.Default.VolunteerActivism,
                title = stringResource(Strings.donation_title),
                subtitle = stringResource(Strings.donation_entry_desc),
                onClick = onDonationClick
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ProfileMenuItem(
                icon = Icons.Default.Info,
                title = stringResource(Strings.profile_about),
                subtitle = stringResource(Strings.profile_about_desc),
                onClick = onAboutClick
            )
        }
    }
}

@Composable
private fun ProfileMenuItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp)
    )
}

@Composable
private fun VersionInfo() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(Strings.profile_version_format, BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(Strings.app_slogan),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline
        )
    }
}
