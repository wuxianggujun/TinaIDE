package com.wuxianggujun.tinaide.ui.compose.components

import android.app.Application
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.WindowManager
import android.view.inspector.WindowInspector
import android.widget.FrameLayout
import android.widget.PopupWindow
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.isPopupLayout
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.ui.runtime.GraphicalOverlayWindowState
import com.wuxianggujun.tinaide.ui.runtime.GraphicalRuntimeMenuPositionProvider
import com.wuxianggujun.tinaide.ui.runtime.GraphicalRuntimeLogEntry
import kotlin.math.roundToInt
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Reproduce the real native PopupWindow -> Compose menu boundary, not a root-screen preview. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w480dp-h800dp", manifest = Config.NONE)
class GraphicalRuntimePopupAnchorTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var popup: PopupWindow
    private lateinit var state: GraphicalOverlayWindowState
    private var density = 1f

    @After
    fun detachTheOwnedNativePopup() {
        if (::popup.isInitialized) compose.runOnIdle { popup.dismiss() }
    }

    @Test
    fun controlMenuUsesTheNativeWindowPositionAndTracksMovementWhileOpen() {
        attachNativePopup()
        compose.onNodeWithTag("graphical-overlay-control").performClick()
        assertMenuPosition(state.bounds)
        val before = menuPosition()
        compose.runOnIdle { state.dragBy(Offset(60f * density, 90f * density)) }
        compose.waitForIdle()
        assertMenuPosition(state.bounds)
        assertThat(menuPosition() - before).isEqualTo(IntOffset((60f * density).roundToInt(), (90f * density).roundToInt()))
    }

    @Test
    fun logSelectionCopiesFromANativePopupWithoutShowingEditingActions() {
        attachNativePopup()
        compose.onNodeWithTag("graphical-overlay-control").performClick()
        compose.onNodeWithTag("graphical-overlay-open-log").performClick()
        val viewer = compose.onNodeWithTag("tina-code-viewer")
        viewer.performTouchInput { longClick(Offset(width * .4f, 8f * density)) }
        compose.onNodeWithTag("editor_selection_context_menu_action_cut").assertDoesNotExist()
        compose.onNodeWithTag("editor_selection_context_menu_action_paste").assertDoesNotExist()
        val menuPosition = menuPosition()
        assertThat(menuPosition.x).isAtLeast(state.bounds.left)
        assertThat(menuPosition.y).isGreaterThan(0)
        compose.onNodeWithTag("editor_selection_context_menu_action_select_all").performClick()
        compose.onNodeWithTag("editor_selection_context_menu_action_copy").performClick()
        compose.runOnIdle {
            val clipboard = compose.activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            assertThat(clipboard.primaryClip!!.getItemAt(0).text.toString()).isEqualTo("warning sample 你好😀\nerror detail")
        }
    }

    @Test
    fun windowOverflowMenuUsesItsButtonAnchorRatherThanTheDisplayOrigin() {
        attachNativePopup()
        compose.onNodeWithTag("graphical-overlay-control").performClick()
        compose.onNodeWithTag("graphical-overlay-open-log").performClick()
        val button = compose.onNodeWithTag("graphical-log-more").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("graphical-log-more").performClick()
        val anchor = IntRect(button.left.roundToInt(), button.top.roundToInt(), button.right.roundToInt(), button.bottom.roundToInt())
            .translate(state.bounds.topLeft)
        assertMenuPosition(anchor)
    }

    private fun attachNativePopup() {
        compose.activityRule.scenario.onActivity { activity ->
            density = activity.resources.displayMetrics.density
            popup = PopupWindow(activity).apply {
                isFocusable = false
                inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            }
            state = GraphicalOverlayWindowState(
                IntRect(0, 0, (480f * density).roundToInt(), (800f * density).roundToInt()),
                density,
                restored = GraphicalOverlayWindowState.Snapshot(false, false, Rect(112f, 200f, 432f, 520f), Offset(112f, 200f)),
                onBoundsChanged = { if (popup.isShowing) popup.update(it.left, it.top, it.width, it.height) },
            )
            val content = ComposeView(activity).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                setContent { MaterialTheme { GraphicalRuntimeOverlay(true, {}, state, captureLogs = { _, append ->
                    append(listOf(GraphicalRuntimeLogEntry('W', "warning sample 你好😀"), GraphicalRuntimeLogEntry('E', "error detail")))
                }) } }
            }
            popup.contentView = object : FrameLayout(activity) {
                override fun onAttachedToWindow() {
                    rootView.setViewTreeLifecycleOwner(activity)
                    rootView.setViewTreeViewModelStoreOwner(activity)
                    rootView.setViewTreeSavedStateRegistryOwner(activity)
                    super.onAttachedToWindow()
                }
            }.apply { addView(content, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)) }
            popup.width = state.bounds.width
            popup.height = state.bounds.height
            popup.showAtLocation(activity.window.decorView, Gravity.TOP or Gravity.LEFT, state.bounds.left, state.bounds.top)
        }
        compose.waitForIdle()
    }

    private fun menuView() = WindowInspector.getGlobalWindowViews().single { isPopupLayout(it) }
    private fun menuPosition() = (menuView().layoutParams as WindowManager.LayoutParams).let { IntOffset(it.x, it.y) }

    private fun assertMenuPosition(anchor: IntRect) {
        compose.waitForIdle()
        compose.runOnIdle {
            val menu = menuView()
            assertThat(menu.width).isGreaterThan(0)
            assertThat(menu.height).isGreaterThan(0)
            val expected = GraphicalRuntimeMenuPositionProvider({ anchor }, { state.safeMenuBounds }, (4f * density).roundToInt())
                .calculatePosition(IntRect.Zero, IntSize(48, 48), LayoutDirection.Ltr, IntSize(menu.width, menu.height))
            assertThat(menuPosition()).isEqualTo(expected)
            assertThat(menuPosition().y).isGreaterThan(0)
        }
    }
}
