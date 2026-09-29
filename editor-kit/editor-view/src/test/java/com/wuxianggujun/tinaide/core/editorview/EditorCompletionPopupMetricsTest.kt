package com.wuxianggujun.tinaide.core.editorview

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EditorCompletionPopupMetricsTest {

    @Test
    fun completionPopupMetrics_shouldStayCompactForDenseCompletionList() {
        assertThat(completionPopupRowMinHeight).isEqualTo(28.dp)
        assertThat(completionPopupRowHorizontalPadding).isEqualTo(8.dp)
        assertThat(completionPopupRowVerticalPadding).isEqualTo(2.dp)
        assertThat(completionPopupRowSpacing).isEqualTo(6.dp)
        assertThat(completionPopupDetailSpacing).isEqualTo(0.dp)
        assertThat(completionPopupKindSlotSize).isEqualTo(18.dp)
        assertThat(completionPopupKindIconSize).isEqualTo(14.dp)
        assertThat(completionPopupLabelFontSize).isEqualTo(13.sp)
        assertThat(completionPopupDetailFontSize).isEqualTo(10.sp)
    }
}
