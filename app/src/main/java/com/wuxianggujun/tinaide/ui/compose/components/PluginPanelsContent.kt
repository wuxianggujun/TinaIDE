package com.wuxianggujun.tinaide.ui.compose.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.plugin.PluginPanelKey
import com.wuxianggujun.tinaide.plugin.ResolvedPluginPanel

@Composable
internal fun PluginPanelsContent(
    panels: List<ResolvedPluginPanel>,
    contents: Map<PluginPanelKey, String>,
    modifier: Modifier = Modifier,
) {
    var selectedKey by remember { mutableStateOf(panels.firstOrNull()?.key) }
    LaunchedEffect(panels) {
        if (panels.none { panel -> panel.key == selectedKey }) {
            selectedKey = panels.firstOrNull()?.key
        }
    }
    val selectedPanel = panels.find { panel -> panel.key == selectedKey }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = TinaSpacing.md, vertical = TinaSpacing.sm),
            horizontalArrangement = Arrangement.spacedBy(TinaSpacing.sm),
        ) {
            panels.forEach { panel ->
                FilterChip(
                    selected = selectedKey == panel.key,
                    onClick = { selectedKey = panel.key },
                    label = {
                        Text(
                            stringResource(
                                Strings.plugin_panel_tab_label,
                                panel.pluginName,
                                panel.title,
                            ),
                        )
                    },
                )
            }
        }

        SelectionContainer {
            Text(
                text = selectedPanel?.let { panel -> contents[panel.key] }
                    ?.takeIf(String::isNotEmpty)
                    ?: stringResource(Strings.plugin_panel_empty),
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(TinaSpacing.md),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
