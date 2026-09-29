package com.wuxianggujun.tinaide.core.editorview

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.textengine.RopeTextBuffer
import org.junit.Test

class EditorGutterInteractionTest {

    @Test
    fun dispatchGutterTap_foldableLine_shouldPreferFoldToggleHandler() {
        val state = EditorState(RopeTextBuffer())
        state.gutterDecorations[12] = GutterDecoration(foldable = true)

        var foldToggleLine = -1
        var bookmarkTapCount = 0
        state.onGutterFoldToggle = { line -> foldToggleLine = line }
        state.onGutterTap = { bookmarkTapCount++ }

        state.dispatchGutterTap(12)

        assertThat(foldToggleLine).isEqualTo(12)
        assertThat(bookmarkTapCount).isEqualTo(0)
    }

    @Test
    fun dispatchGutterTap_nonFoldableLine_shouldCallBookmarkTapHandler() {
        val state = EditorState(RopeTextBuffer())
        state.gutterDecorations[7] = GutterDecoration(foldable = false)

        var foldToggleCount = 0
        var bookmarkTapLine = -1
        state.onGutterFoldToggle = { foldToggleCount++ }
        state.onGutterTap = { line -> bookmarkTapLine = line }

        state.dispatchGutterTap(7)

        assertThat(foldToggleCount).isEqualTo(0)
        assertThat(bookmarkTapLine).isEqualTo(7)
    }

    @Test
    fun dispatchGutterTap_foldableLineWithoutFoldHandler_shouldNotFallbackToBookmark() {
        val state = EditorState(RopeTextBuffer())
        state.gutterDecorations[3] = GutterDecoration(foldable = true)

        var bookmarkTapCount = 0
        state.onGutterTap = { bookmarkTapCount++ }
        state.onGutterFoldToggle = null

        state.dispatchGutterTap(3)

        assertThat(bookmarkTapCount).isEqualTo(0)
    }

    @Test
    fun dispatchGutterLongPress_shouldCallLongPressHandler() {
        val state = EditorState(RopeTextBuffer())
        var longPressLine = -1
        state.onGutterLongPress = { line -> longPressLine = line }

        state.dispatchGutterLongPress(19)

        assertThat(longPressLine).isEqualTo(19)
    }
}
