package com.wuxianggujun.tinaide.ui.compose.components

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntRect
import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.ui.runtime.GraphicalOverlayWindowState
import com.wuxianggujun.tinaide.ui.runtime.GraphicalRuntimeLogEntry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], manifest = Config.NONE)
class GraphicalRuntimeOverlayTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun enabledLogStartsWithExactlyOneControlAndNoExpandedPanel() {
        val state = render(enableLog = true)
        compose.onNodeWithTag("graphical-overlay-control").assertIsDisplayed()
        compose.onNodeWithTag("graphical-log-window").assertDoesNotExist()
        assertThat(compose.onAllNodesWithTag("graphical-overlay-control").fetchSemanticsNodes()).hasSize(1)
        assertThat(state.expanded).isFalse()
    }

    @Test
    fun disabledLogKeepsReturnControlWithoutLogPanel() {
        render(enableLog = false)
        compose.onNodeWithTag("graphical-overlay-control").performClick()
        compose.onNodeWithTag("graphical-overlay-open-log").assertDoesNotExist()
        compose.onNodeWithTag("graphical-overlay-exit").assertIsDisplayed()
    }

    @Test
    fun logOpensFromTheMenuAndMinimizingDoesNotExitTheProgram() {
        var exited = 0
        val state = render(enableLog = true, onExit = { exited++ })
        openLog()
        compose.onNodeWithTag("graphical-log-window").assertIsDisplayed()
        compose.onNodeWithTag("graphical-overlay-control").assertDoesNotExist()
        compose.onNodeWithTag("graphical-overlay-exit").assertDoesNotExist()
        compose.onNodeWithTag("graphical-log-collapse").performClick()
        compose.onNodeWithTag("graphical-log-window").assertDoesNotExist()
        compose.onNodeWithTag("graphical-overlay-control").assertIsDisplayed()
        openLog()
        compose.runOnIdle {
            assertThat(state.expanded).isTrue()
            assertThat(exited).isEqualTo(0)
        }
    }

    @Test
    fun returnRequiresConfirmationAndCancelKeepsTheRuntimeOpen() {
        var exited = 0
        render(enableLog = false, onExit = { exited++ })
        compose.onNodeWithTag("graphical-overlay-control").performClick()
        compose.onNodeWithTag("graphical-overlay-exit").performClick()
        compose.runOnIdle { assertThat(exited).isEqualTo(0) }
        compose.onNodeWithText(text(Strings.floating_overlay_exit_confirm_no)).performClick()
        compose.runOnIdle { assertThat(exited).isEqualTo(0) }
        compose.onNodeWithTag("graphical-overlay-control").performClick()
        compose.onNodeWithTag("graphical-overlay-exit").performClick()
        compose.onNodeWithText(text(Strings.floating_overlay_exit_confirm_yes)).performClick()
        compose.runOnIdle { assertThat(exited).isEqualTo(1) }
    }

    @Test
    fun panelReturnIsInOverflowRatherThanALargeRepeatedFooterButton() {
        render(enableLog = true)
        openLog()
        compose.onNodeWithText(text(Strings.floating_overlay_exit)).assertDoesNotExist()
        compose.onNodeWithTag("graphical-log-more").performClick()
        compose.onNodeWithTag("graphical-overlay-exit").assertIsDisplayed()
        compose.onNodeWithTag("graphical-overlay-exit").performClick()
        compose.onNodeWithText(text(Strings.floating_overlay_exit_confirm)).assertIsDisplayed()
        compose.onNodeWithText(text(Strings.floating_overlay_exit_confirm_no)).performClick()
        compose.onNodeWithTag("graphical-log-window").assertIsDisplayed()
    }

    @Test
    fun toolbarMaximizeRestoreAndCollapseKeepThePreviousSize() {
        val state = render(enableLog = true)
        openLog()
        var original = state.bounds
        compose.runOnIdle {
            state.resizeBy(Offset(-24f, -24f))
            original = state.bounds
        }
        compose.onNodeWithTag("graphical-log-maximize").performClick()
        compose.runOnIdle { assertThat(state.maximized).isTrue() }
        compose.onNodeWithTag("graphical-log-resize").assertDoesNotExist()
        compose.onNodeWithTag("graphical-log-maximize").performClick()
        compose.runOnIdle { assertThat(state.bounds).isEqualTo(original) }
        compose.onNodeWithTag("graphical-log-collapse").performClick()
        openLog()
        compose.runOnIdle { assertThat(state.bounds).isEqualTo(original) }
    }

    @Test
    fun titleDraggingAndCornerResizingHaveDedicatedTouchRegions() {
        val state = render(enableLog = true)
        openLog()
        val beforeDrag = state.bounds
        compose.onNodeWithTag("graphical-log-drag-handle").performTouchInput {
            swipe(center, center + Offset(20f, 30f), durationMillis = 250)
        }
        compose.runOnIdle { assertThat(state.bounds.top).isGreaterThan(beforeDrag.top) }
        val beforeResize = state.bounds
        compose.onNodeWithTag("graphical-log-resize").performTouchInput {
            swipe(center, center + Offset(-30f, -30f), durationMillis = 250)
        }
        compose.runOnIdle {
            assertThat(state.bounds.width).isLessThan(beforeResize.width)
            assertThat(state.bounds.height).isLessThan(beforeResize.height)
        }
    }

    @Test
    fun resizeGripOffersAccessibleGrowAndShrinkActions() {
        val state = render(enableLog = true)
        openLog()
        val before = state.bounds
        val actions = compose.onNodeWithTag("graphical-log-resize").fetchSemanticsNode().config[SemanticsActions.CustomActions]
        compose.runOnIdle {
            assertThat(actions.map { it.label }).containsExactly(text(Strings.floating_overlay_window_grow), text(Strings.floating_overlay_window_shrink))
            actions[1].action()
        }
        compose.runOnIdle { assertThat(state.bounds.width).isLessThan(before.width) }
        compose.runOnIdle { actions[0].action() }
        compose.runOnIdle { assertThat(state.bounds.width).isGreaterThan(before.width - 32) }
    }

    @Test
    fun narrowWindowWithLargeFontsKeepsAllToolbarActionsInsideWithoutOverlap() {
        render(enableLog = true, fontScale = 2f)
        openLog()
        val panel = compose.onNodeWithTag("graphical-log-window").fetchSemanticsNode().boundsInRoot
        val buttons = listOf("graphical-log-maximize", "graphical-log-collapse", "graphical-log-more").map { tag ->
            compose.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        }
        buttons.forEach {
            assertThat(it.left).isAtLeast(panel.left)
            assertThat(it.right).isAtMost(panel.right)
        }
        assertThat(buttons.zipWithNext().all { (left, right) -> left.right <= right.left }).isTrue()
    }

    @Test
    fun logsUseTheReadOnlyEditorWithoutInputFocusOrTruncatingLongMessages() {
        val message = "开始😀 " + "x".repeat(600) + " 完整末尾"
        render(enableLog = true, initialLogs = listOf(GraphicalRuntimeLogEntry('E', message)))
        openLog()
        val viewer = compose.onNodeWithTag("tina-code-viewer").assertIsDisplayed()
        val semantics = viewer.fetchSemanticsNode().config
        assertThat(semantics.contains(SemanticsActions.SetText)).isFalse()
        assertThat(semantics.contains(SemanticsActions.RequestFocus)).isFalse()
        assertThat(semantics.contains(SemanticsProperties.EditableText)).isFalse()
        assertThat(semantics[SemanticsProperties.Text].single().text).isEqualTo(message)
        assertThat(semantics.contains(SemanticsActions.SetSelection)).isTrue()
        assertThat(semantics.contains(SemanticsActions.CopyText)).isTrue()
        assertThat(semantics[SemanticsProperties.HorizontalScrollAxisRange].maxValue()).isGreaterThan(0f)
        viewer.performSemanticsAction(SemanticsActions.ScrollBy) { it(100f, 0f) }
        assertThat(viewer.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange].value()).isGreaterThan(0f)
    }

    @Test
    fun logWrappingStartsOffAndCanBeToggledInTheWindowMenu() {
        render(enableLog = true, initialLogs = listOf(GraphicalRuntimeLogEntry('I', "x".repeat(600))))
        openLog()
        compose.onNodeWithTag("graphical-log-more").performClick()
        compose.onNodeWithTag("graphical-log-word-wrap").assertIsOff().performClick()
        compose.onNodeWithTag("graphical-log-word-wrap").assertIsOn()
        val viewer = compose.onNodeWithTag("tina-code-viewer")
        assertThat(viewer.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange].maxValue()).isEqualTo(0f)
        compose.onNodeWithTag("graphical-log-word-wrap").performClick().assertIsOff()
        assertThat(viewer.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange].maxValue()).isGreaterThan(0f)
    }

    @Test
    fun minimizingAndReopeningPreserveTheLogAndWrapChoice() {
        render(enableLog = true, initialLogs = listOf(GraphicalRuntimeLogEntry('I', "persisted log")))
        openLog()
        compose.onNodeWithTag("graphical-log-more").performClick()
        compose.onNodeWithTag("graphical-log-word-wrap").performClick()
        compose.onNodeWithTag("graphical-log-collapse").performClick()
        openLog()
        assertThat(compose.onNodeWithTag("tina-code-viewer").fetchSemanticsNode().config[SemanticsProperties.Text].single().text)
            .isEqualTo("persisted log")
        compose.onNodeWithTag("graphical-log-more").performClick()
        compose.onNodeWithTag("graphical-log-word-wrap").assertIsOn()
    }

    @Test
    fun theEditorBodyHasOnlyTwoDpOfPaddingInsteadOfTwoNestedEightDpInsets() {
        render(enableLog = true)
        openLog()
        val panel = compose.onNodeWithTag("graphical-log-window").fetchSemanticsNode().boundsInRoot
        val content = compose.onNodeWithTag("graphical-log-content").fetchSemanticsNode().boundsInRoot
        val density = RuntimeEnvironment.getApplication().resources.displayMetrics.density
        assertThat(content.left - panel.left).isWithin(1f).of(2f * density)
        assertThat(panel.right - content.right).isWithin(1f).of(2f * density)
        assertThat(panel.bottom - content.bottom).isWithin(1f).of(2f * density)
    }

    @Test
    fun clearingTheLogDoesNotRemoveItsEditorOrEnableEditing() {
        render(enableLog = true, initialLogs = listOf(GraphicalRuntimeLogEntry('I', "clear me")))
        openLog()
        compose.onNodeWithTag("graphical-log-more").performClick()
        compose.onNodeWithTag("graphical-overlay-clear-log").performClick()
        val viewer = compose.onNodeWithTag("tina-code-viewer").assertIsDisplayed().fetchSemanticsNode().config
        assertThat(viewer[SemanticsProperties.Text].single().text).isEmpty()
        assertThat(viewer.contains(SemanticsActions.SetText)).isFalse()
        compose.onNodeWithText(text(Strings.floating_overlay_log_empty)).assertIsDisplayed()
    }

    private fun render(
        enableLog: Boolean,
        fontScale: Float = 1f,
        onExit: () -> Unit = {},
        initialLogs: List<GraphicalRuntimeLogEntry> = emptyList(),
    ): GraphicalOverlayWindowState {
        val density = RuntimeEnvironment.getApplication().resources.displayMetrics.density
        val state = GraphicalOverlayWindowState(IntRect(0, 0, (320 * density).toInt(), (600 * density).toInt()), density)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                MaterialTheme {
                    GraphicalRuntimeOverlay(enableLog, onExit, state, captureLogs = { _, append -> append(initialLogs) })
                }
            }
        }
        return state
    }

    private fun openLog() {
        compose.onNodeWithTag("graphical-overlay-control").performClick()
        compose.onNodeWithTag("graphical-overlay-open-log").performClick()
    }

    private fun text(id: Int): String = RuntimeEnvironment.getApplication().getString(id)
}
