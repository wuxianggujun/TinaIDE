package com.wuxianggujun.tinaide.ui.runtime

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GraphicalRuntimeMenuPositionProviderTest {
    private val viewport = IntRect(8, 28, 712, 1260)

    @Test
    fun nativeScreenAnchorIsUsedInsteadOfTheLocalWindowOrigin() {
        val provider = provider(IntRect(120, 240, 168, 288))
        assertThat(position(provider)).isEqualTo(IntOffset(120, 292))
    }

    @Test
    fun movingTheControlUpdatesTheSameOpenMenuProvider() {
        var anchor = IntRect(120, 240, 168, 288)
        val provider = GraphicalRuntimeMenuPositionProvider({ anchor }, { viewport }, 4)
        val before = position(provider)
        anchor = anchor.translate(IntOffset(100, 180))
        assertThat(position(provider) - before).isEqualTo(IntOffset(100, 180))
    }

    @Test
    fun bottomRightControlFlipsAboveAndKeepsTheMenuInsideTheSafeArea() {
        val provider = provider(IntRect(664, 1204, 712, 1252))
        assertThat(position(provider)).isEqualTo(IntOffset(492, 1020))
    }

    @Test
    fun rtlAlignsTheMenuWithTheRightEdgeOfItsAnchor() {
        val provider = provider(IntRect(320, 240, 368, 288))
        assertThat(position(provider, LayoutDirection.Rtl)).isEqualTo(IntOffset(148, 292))
    }

    @Test
    fun oversizedContentAndUnexpectedFrameworkAnchorsDoNotProduceNegativeCoordinates() {
        val provider = provider(IntRect(0, 0, 48, 48))
        val offset = provider.calculatePosition(IntRect(900, 1500, 948, 1548), IntSize(48, 48), LayoutDirection.Ltr, IntSize(900, 1400))
        assertThat(offset).isEqualTo(viewport.topLeft)
    }

    @Test
    fun viewportChangesReclampAnAlreadyOpenMenu() {
        var safeBounds = viewport
        val provider = GraphicalRuntimeMenuPositionProvider({ IntRect(400, 400, 448, 448) }, { safeBounds }, 4)
        assertThat(position(provider)).isEqualTo(IntOffset(400, 452))
        safeBounds = IntRect(8, 28, 480, 620)
        assertThat(position(provider)).isEqualTo(IntOffset(260, 216))
    }

    private fun provider(anchor: IntRect) = GraphicalRuntimeMenuPositionProvider({ anchor }, { viewport }, 4)

    private fun position(provider: GraphicalRuntimeMenuPositionProvider, direction: LayoutDirection = LayoutDirection.Ltr): IntOffset =
        provider.calculatePosition(IntRect.Zero, IntSize(48, 48), direction, IntSize(220, 180))
}
