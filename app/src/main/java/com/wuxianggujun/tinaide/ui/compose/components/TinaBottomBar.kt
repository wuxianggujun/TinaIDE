package com.wuxianggujun.tinaide.ui.compose.components

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Store
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Store
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.tutorial.spotlight.SpotlightTargets
import com.wuxianggujun.tinaide.tutorial.spotlight.spotlightTarget

private val NavigationRailItemHeight = 64.dp

/**
 * TinaIDE 底部导航栏组件库
 *
 * 设计规范：
 * - 高度：80dp（底部导航标准高度）
 * - 图标大小：24dp
 * - 文字样式：labelSmall
 * - 选中态：有颜色 + 文字
 * - 未选中态：灰色 + 文字
 */

/**
 * 底部导航项数据类
 */
data class BottomNavItem(
    val route: String,
    val titleResId: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val contentDescriptionResId: Int,
    /** Spotlight 引导目标 ID */
    val spotlightTargetId: String? = null
) {
    val testTag: String get() = "main-nav-$route"
}

/**
 * 底部导航栏
 *
 * @param items 导航项列表
 * @param selectedIndex 当前选中的索引
 * @param onItemSelected 选中回调
 * @param modifier Modifier
 */
@Composable
fun TinaBottomBar(
    items: List<BottomNavItem>,
    selectedIndex: Int,
    onItemSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val selectedIconBackgroundShape = RoundedCornerShape(14.dp)
    // 禁用底部导航栏 item 的 ripple / pressed state layer（点击"黑影"）
    CompositionLocalProvider(LocalRippleConfiguration provides null) {
        NavigationBar(
            modifier = modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(80.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp
        ) {
            items.forEachIndexed { index, item ->
                val selected = selectedIndex == index
                val itemModifier = if (item.spotlightTargetId != null) {
                    Modifier.spotlightTarget(item.spotlightTargetId)
                } else {
                    Modifier
                }

                NavigationBarItem(
                    selected = selected,
                    onClick = { onItemSelected(index) },
                    modifier = itemModifier.testTag(item.testTag),
                    icon = {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(selectedIconBackgroundShape)
                                    .background(
                                        if (selected) {
                                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                                        } else {
                                            Color.Transparent
                                        }
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                                    contentDescription = stringResource(item.contentDescriptionResId),
                                    modifier = Modifier.size(24.dp),
                                    tint = if (selected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                        }
                    },
                    label = {
                        Text(
                            text = stringResource(item.titleResId),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            textAlign = TextAlign.Center
                        )
                    },
                    colors = NavigationBarItemDefaults.colors(
                        // 隐藏 MD3 默认 pill indicator，改用上面的“正方形圆角”选中背景
                        indicatorColor = Color.Transparent
                    )
                )
            }
        }
    }
}

/** Short landscape windows reuse the same routes and Spotlight targets without a tall bottom bar. */
@Composable
fun TinaNavigationRail(
    items: List<BottomNavItem>,
    selectedIndex: Int,
    onItemSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    NavigationRail(
        modifier = modifier.fillMaxHeight().navigationBarsPadding(),
        containerColor = MaterialTheme.colorScheme.surface,
        windowInsets = WindowInsets(0, 0, 0, 0),
    ) {
        Column(modifier = Modifier.fillMaxHeight().verticalScroll(rememberScrollState())) {
            items.forEachIndexed { index, item ->
                val selected = selectedIndex == index
                val target = item.spotlightTargetId?.let { Modifier.spotlightTarget(it) } ?: Modifier
                NavigationRailItem(
                    selected = selected,
                    onClick = { onItemSelected(index) },
                    modifier = target.height(NavigationRailItemHeight).testTag(item.testTag),
                    icon = {
                        Icon(
                            imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                            contentDescription = stringResource(item.contentDescriptionResId),
                            modifier = Modifier.size(24.dp),
                        )
                    },
                    label = { Text(stringResource(item.titleResId), style = MaterialTheme.typography.labelSmall) },
                    colors = NavigationRailItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                    ),
                )
            }
        }
    }
}

/**
 * 主界面底部导航项
 */
val MainBottomNavItems = listOf(
    BottomNavItem(
        route = "project",
        titleResId = Strings.nav_project,
        selectedIcon = Icons.Filled.Folder,
        unselectedIcon = Icons.Outlined.Folder,
        contentDescriptionResId = Strings.nav_project,
        spotlightTargetId = SpotlightTargets.BOTTOM_NAV_PROJECT
    ),
    BottomNavItem(
        route = "market",
        titleResId = Strings.nav_market,
        selectedIcon = Icons.Filled.Store,
        unselectedIcon = Icons.Outlined.Store,
        contentDescriptionResId = Strings.nav_market,
        spotlightTargetId = SpotlightTargets.BOTTOM_NAV_MARKET
    ),
    BottomNavItem(
        route = "tutorial",
        titleResId = Strings.nav_tutorial,
        selectedIcon = Icons.Filled.School,
        unselectedIcon = Icons.Outlined.School,
        contentDescriptionResId = Strings.nav_tutorial,
        spotlightTargetId = SpotlightTargets.BOTTOM_NAV_TUTORIAL
    ),
    BottomNavItem(
        route = "profile",
        titleResId = Strings.nav_profile,
        selectedIcon = Icons.Filled.Person,
        unselectedIcon = Icons.Outlined.Person,
        contentDescriptionResId = Strings.nav_profile,
        spotlightTargetId = SpotlightTargets.BOTTOM_NAV_PROFILE
    )
)
