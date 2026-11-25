package com.wuxianggujun.tinaide.ui.compose.screens.main.market

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Inventory
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LeadingIconTab
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wuxianggujun.tinaide.core.i18n.Strings

private val LeadingMarketTabsMinWidth = 480.dp
private val MarketTabHorizontalPadding = 16.dp
private val MarketTabIconAndGapWidth = 32.dp
private val MinimumScrollableMarketTabWidth = 90.dp

/** Use equal tabs while labels fit; preserve complete labels by scrolling at larger font scales. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MarketTabRow(selectedTab: Int, onTabSelected: (Int) -> Unit, modifier: Modifier = Modifier) {
    val destinations = listOf(
        stringResource(Strings.market_tab_plugins) to Icons.Default.Extension,
        stringResource(Strings.market_tab_packages) to Icons.Default.Inventory,
        stringResource(Strings.market_tab_vcpkg) to Icons.Default.Build,
    )
    val style = MaterialTheme.typography.labelLarge
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    // Give each label a bounded slot, with a pixel of rounding tolerance for intrinsic widths.
    val labelWidths = destinations.map { (label, _) ->
        with(density) { (measurer.measure(label, style = style, softWrap = false, maxLines = 1).size.width + 1).toDp() }
    }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val leadingIcons = maxWidth >= LeadingMarketTabsMinWidth
        val requiredWidth = labelWidths.max() + MarketTabHorizontalPadding * 2 +
            if (leadingIcons) MarketTabIconAndGapWidth else 0.dp
        val scrollable = requiredWidth * destinations.size > maxWidth
        val tabs: @Composable () -> Unit = {
            destinations.forEachIndexed { index, (label, imageVector) ->
                val text: @Composable () -> Unit = {
                    Text(
                        text = label,
                        modifier = if (leadingIcons) Modifier.width(labelWidths[index]) else Modifier.fillMaxWidth(),
                        style = style,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val icon: @Composable () -> Unit = { Icon(imageVector, contentDescription = null) }
                val tabModifier = if (scrollable) Modifier.width(maxOf(requiredWidth, MinimumScrollableMarketTabWidth)) else Modifier
                if (leadingIcons) {
                    LeadingIconTab(
                        selected = selectedTab == index, onClick = { onTabSelected(index) },
                        modifier = tabModifier, text = text, icon = icon,
                    )
                } else {
                    Tab(
                        selected = selectedTab == index, onClick = { onTabSelected(index) },
                        modifier = tabModifier, text = text, icon = icon,
                    )
                }
            }
        }
        if (scrollable) {
            PrimaryScrollableTabRow(
                selectedTabIndex = selectedTab,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary,
                edgePadding = 0.dp,
                tabs = tabs,
            )
        } else {
            PrimaryTabRow(
                selectedTabIndex = selectedTab,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary,
                tabs = tabs,
            )
        }
    }
}
