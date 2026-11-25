package com.wuxianggujun.tinaide.ui.compose.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.wuxianggujun.tinaide.core.i18n.Strings

/**
 * 市场/列表类页面的统一空状态。
 *
 * 插件市场、包管理和 vcpkg 市场共用，避免各页面各写一套导致视觉不一致。
 */
@Composable
fun TinaEmptyState(
    icon: ImageVector,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // A 96.dp icon plus generous spacing needs ~270.dp; short content areas (fixed header plus footer,
        // small screens, landscape) would otherwise push the actual message out of view.
        val compact = maxHeight < 240.dp
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.wrapContentSize()
                .padding(horizontal = 24.dp, vertical = if (compact) 8.dp else 32.dp),
        ) {
            if (!compact) {
                Box(
                    modifier = Modifier.size(96.dp).clip(RoundedCornerShape(48.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                }
                Spacer(modifier = Modifier.height(24.dp))
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (actionText != null && onAction != null) {
                Spacer(modifier = Modifier.height(if (compact) 8.dp else 16.dp))
                TinaOutlinedButton(text = actionText, onClick = onAction)
            }
        }
    }
}

/**
 * 市场/列表类页面的统一错误状态，带重试入口。
 */
@Composable
fun TinaErrorState(
    icon: ImageVector,
    errorMessage: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // Same constraint as the empty state: the icon plus a long error message and the retry button
        // do not fit the short content area left by a fixed header and footer.
        val compact = maxHeight < 300.dp
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (compact) Modifier.verticalScroll(rememberScrollState()) else Modifier.wrapContentSize())
                .padding(horizontal = 24.dp, vertical = if (compact) 8.dp else 32.dp),
        ) {
            if (!compact) {
                Box(
                    modifier = Modifier.size(96.dp).clip(RoundedCornerShape(48.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
                Spacer(modifier = Modifier.height(24.dp))
            }
            Text(
                text = stringResource(Strings.market_error_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = errorMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Spacer(modifier = Modifier.height(if (compact) 12.dp else 24.dp))
            TinaOutlinedButton(
                text = stringResource(Strings.market_error_retry),
                leadingIcon = Icons.Default.Refresh,
                onClick = onRetry,
            )
        }
    }
}
