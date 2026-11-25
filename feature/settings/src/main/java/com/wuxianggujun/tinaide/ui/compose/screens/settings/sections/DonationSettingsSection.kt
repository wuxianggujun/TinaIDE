package com.wuxianggujun.tinaide.ui.compose.screens.settings.sections

import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wuxianggujun.tinaide.core.i18n.SettingsDrawables
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.ui.compose.screens.settings.components.SettingsCard
import com.wuxianggujun.tinaide.ui.compose.screens.settings.components.SettingsClickableItem

private const val AFDIAN_URL = "https://ifdian.net/a/wuxianggujun"

@Composable
internal fun DonationSettingsSection() {
    val context = LocalContext.current
    val cannotOpenLinkError = stringResource(Strings.error_cannot_open_link)

    Spacer(modifier = Modifier.height(8.dp))

    SettingsCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(Strings.donation_entry_desc),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(Strings.donation_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                DonationCodeItem(
                    label = stringResource(Strings.donation_wechat),
                    drawableRes = SettingsDrawables.donation_weixin,
                    modifier = Modifier.weight(1f),
                )
                DonationCodeItem(
                    label = stringResource(Strings.donation_alipay),
                    drawableRes = SettingsDrawables.donation_zhifubao,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        SettingsClickableItem(
            title = stringResource(Strings.donation_afdian),
            subtitle = stringResource(Strings.donation_afdian_desc),
            onClick = {
                if (!context.openFirstAvailableExternalUrl(listOf(AFDIAN_URL))) {
                    Toast.makeText(context, cannotOpenLinkError, Toast.LENGTH_SHORT).show()
                }
            },
            showDivider = false,
        )
    }

    Spacer(modifier = Modifier.height(16.dp))
}

@Composable
private fun DonationCodeItem(
    label: String,
    @DrawableRes drawableRes: Int,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Image(
            painter = painterResource(drawableRes),
            contentDescription = label,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(8.dp)),
            contentScale = ContentScale.Fit,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
