package com.wuxianggujun.tinaide.core.editorview

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.wuxianggujun.tinaide.core.editorlsp.SignatureHelpResult

private const val SIGNATURE_HELP_MAX_VISIBLE_OVERLOADS = 3
internal const val SIGNATURE_HELP_POPUP_TAG = "editor_signature_help_popup"
internal const val SIGNATURE_HELP_POPUP_ROW_TAG_PREFIX = "editor_signature_help_popup_row_"
internal const val SIGNATURE_HELP_POPUP_OVERFLOW_TAG_PREFIX = "editor_signature_help_popup_overflow_"

internal fun signatureHelpPopupRowTag(index: Int): String = "$SIGNATURE_HELP_POPUP_ROW_TAG_PREFIX$index"

internal fun signatureHelpPopupOverflowTag(direction: String): String = "$SIGNATURE_HELP_POPUP_OVERFLOW_TAG_PREFIX$direction"

@Composable
internal fun EditorSignatureHelpPopup(
    result: SignatureHelpResult?,
    displayedSignatureIndex: Int,
    offset: IntOffset,
    widthPx: Float,
    minHeightPx: Float,
    maxHeightPx: Float,
    colorScheme: EditorColorScheme,
    isLoading: Boolean,
    onSelectSignature: (Int) -> Unit,
    onCycleSignature: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val density = LocalDensity.current
    val popupColors = rememberEditorPopupColors(colorScheme)
    val activeSignatureIndex = result?.let {
        it.activeSignature.coerceIn(0, it.signatures.lastIndex.coerceAtLeast(0))
    }
        ?: 0
    val selectedSignatureIndex = result?.let {
        displayedSignatureIndex.coerceIn(0, it.signatures.lastIndex.coerceAtLeast(0))
    } ?: 0
    val visibleSlice = result?.let {
        resolveSignatureHelpVisibleSlice(
            totalCount = it.signatures.size,
            selectedIndex = selectedSignatureIndex,
            maxVisibleItems = SIGNATURE_HELP_MAX_VISIBLE_OVERLOADS
        )
    } ?: SignatureHelpVisibleSlice(
        startIndex = 0,
        endExclusive = 0,
        hiddenBefore = 0,
        hiddenAfter = 0
    )
    val compactMode = visibleSlice.hiddenBefore > 0 || visibleSlice.hiddenAfter > 0
    Popup(
        popupPositionProvider = remember(offset) { AbsoluteWindowPopupPositionProvider(offset) },
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = false)
    ) {
        EditorPopupScaffold(
            colors = popupColors,
            modifier = Modifier
                .testTag(SIGNATURE_HELP_POPUP_TAG)
                .width(with(density) { widthPx.toDp() })
                .heightIn(
                    min = with(density) { minHeightPx.toDp() },
                    max = with(density) { maxHeightPx.toDp() }
                ),
            contentModifier = Modifier.fillMaxWidth()
        ) {
            if (isLoading) {
                EditorPopupLoadingBar(colors = popupColors)
            } else {
                Spacer(modifier = Modifier.height(6.dp))
            }

            if (result != null) {
                if (result.signatures.size > 1) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 8.dp, end = 8.dp, top = 2.dp, bottom = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        EditorPopupActionButton(
                            onClick = { onCycleSignature(-1) },
                            colors = popupColors,
                            contentPadding = editorPopupCompactActionPadding
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                contentDescription = stringResource(R.string.editor_signature_previous),
                                tint = popupColors.primaryTextColor
                            )
                        }
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(popupColors.selectedSurfaceColor)
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "${selectedSignatureIndex + 1}/${result.signatures.size}",
                                fontSize = 11.sp,
                                color = popupColors.secondaryTextColor
                            )
                        }
                        EditorPopupActionButton(
                            onClick = { onCycleSignature(1) },
                            colors = popupColors,
                            contentPadding = editorPopupCompactActionPadding
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = stringResource(R.string.editor_signature_next),
                                tint = popupColors.primaryTextColor
                            )
                        }
                    }
                    EditorPopupDivider(colors = popupColors)
                }

                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .fillMaxWidth()
                        .padding(start = 8.dp, end = 8.dp, bottom = 8.dp)
                ) {
                    if (visibleSlice.hiddenBefore > 0) {
                        SignatureHelpOverflowIndicator(
                            symbol = "\u2191",
                            hiddenCount = visibleSlice.hiddenBefore,
                            tag = signatureHelpPopupOverflowTag("before"),
                            colors = popupColors,
                            onClick = {
                                onSelectSignature((visibleSlice.startIndex - 1).coerceAtLeast(0))
                            }
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                    }

                    for (index in visibleSlice.startIndex until visibleSlice.endExclusive) {
                        val signature = result.signatures[index]
                        val annotatedSignature = remember(
                            signature,
                            result.activeParameter,
                            popupColors.matchTextColor
                        ) {
                            buildSignatureHelpAnnotatedString(
                                signature = signature,
                                activeParameter = result.activeParameter,
                                activeParameterStyle = SpanStyle(
                                    color = popupColors.matchTextColor,
                                    fontWeight = FontWeight.SemiBold
                                )
                            )
                        }
                        val isSelected = index == selectedSignatureIndex
                        val isServerActive = index == activeSignatureIndex
                        val presentation = remember(
                            isSelected,
                            isServerActive,
                            compactMode
                        ) {
                            resolveSignatureHelpRowPresentation(
                                isSelected = isSelected,
                                isServerActive = isServerActive,
                                compactMode = compactMode
                            )
                        }
                        val activeParameterPreview = remember(
                            signature,
                            result.activeParameter,
                            presentation.showActiveParameterPreview
                        ) {
                            if (!presentation.showActiveParameterPreview) {
                                null
                            } else {
                                resolveSignatureActiveParameterPreview(
                                    signature = signature,
                                    activeParameter = result.activeParameter
                                )
                            }
                        }
                        val rowBackground = if (presentation.useSelectedBackground) {
                            popupColors.selectedSurfaceColor
                        } else {
                            popupColors.containerColor
                        }
                        val rowBorderColor = when (presentation.border) {
                            SignatureHelpRowBorder.None -> Color.Transparent
                            SignatureHelpRowBorder.Secondary -> popupColors.dividerColor
                            SignatureHelpRowBorder.Accent -> popupColors.accentColor.copy(alpha = 0.72f)
                        }
                        val textColor = if (isSelected || isServerActive) {
                            popupColors.primaryTextColor
                        } else {
                            popupColors.secondaryTextColor
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(rowBackground)
                                .testTag(signatureHelpPopupRowTag(index))
                                .border(
                                    width = 1.dp,
                                    color = rowBorderColor,
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .clickable { onSelectSignature(index) }
                                .padding(
                                    horizontal = 10.dp,
                                    vertical = if (compactMode && !isSelected) 6.dp else 8.dp
                                ),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.Top
                        ) {
                            SignatureHelpRowMarkerIndicator(
                                marker = presentation.marker,
                                colors = popupColors
                            )
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(
                                    if (activeParameterPreview != null) 6.dp else 0.dp
                                )
                            ) {
                                Text(
                                    text = annotatedSignature,
                                    fontSize = 13.sp,
                                    lineHeight = 19.sp,
                                    color = textColor,
                                    maxLines = presentation.maxLines,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (activeParameterPreview != null) {
                                    SignatureHelpActiveParameterPreviewChip(
                                        preview = activeParameterPreview,
                                        colors = popupColors,
                                        emphasize = presentation.emphasizeActiveParameterPreview
                                    )
                                }
                            }
                        }
                        if (index != visibleSlice.endExclusive - 1) {
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                    }

                    if (visibleSlice.hiddenAfter > 0) {
                        Spacer(modifier = Modifier.height(4.dp))
                        SignatureHelpOverflowIndicator(
                            symbol = "\u2193",
                            hiddenCount = visibleSlice.hiddenAfter,
                            tag = signatureHelpPopupOverflowTag("after"),
                            colors = popupColors,
                            onClick = {
                                onSelectSignature(visibleSlice.endExclusive)
                            }
                        )
                    }
                }
            } else {
                Spacer(modifier = Modifier.height(10.dp))
            }
        }
    }
}

@Composable
private fun SignatureHelpActiveParameterPreviewChip(
    preview: String,
    colors: EditorPopupColors,
    emphasize: Boolean
) {
    val containerColor = if (emphasize) {
        colors.accentColor.copy(alpha = 0.14f)
    } else {
        colors.dividerColor.copy(alpha = 0.18f)
    }
    val borderColor = if (emphasize) {
        colors.accentColor.copy(alpha = 0.4f)
    } else {
        colors.dividerColor.copy(alpha = 0.72f)
    }
    val textColor = if (emphasize) {
        colors.matchTextColor
    } else {
        colors.primaryTextColor
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .border(
                width = 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(999.dp)
            )
            .background(containerColor)
            .widthIn(max = 280.dp)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = preview,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SignatureHelpRowMarkerIndicator(
    marker: SignatureHelpRowMarker,
    colors: EditorPopupColors
) {
    Box(
        modifier = Modifier
            .padding(top = 4.dp)
            .width(10.dp)
            .height(10.dp),
        contentAlignment = androidx.compose.ui.Alignment.Center
    ) {
        when (marker) {
            SignatureHelpRowMarker.None -> Unit
            SignatureHelpRowMarker.Active -> {
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(colors.accentColor)
                        .width(6.dp)
                        .height(6.dp)
                )
            }
            SignatureHelpRowMarker.Selected -> {
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .border(width = 1.5.dp, color = colors.primaryTextColor, shape = CircleShape)
                        .width(8.dp)
                        .height(8.dp)
                )
            }
            SignatureHelpRowMarker.SelectedActive -> {
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .border(width = 1.5.dp, color = colors.accentColor, shape = CircleShape)
                        .width(10.dp)
                        .height(10.dp),
                    contentAlignment = androidx.compose.ui.Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(colors.accentColor)
                            .width(4.dp)
                            .height(4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SignatureHelpOverflowIndicator(
    symbol: String,
    hiddenCount: Int,
    tag: String,
    colors: EditorPopupColors,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.selectedSurfaceColor.copy(alpha = 0.55f))
            .testTag(tag)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        Text(
            text = "$symbol +$hiddenCount",
            fontSize = 11.sp,
            color = colors.secondaryTextColor
        )
    }
}
