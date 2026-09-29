package com.wuxianggujun.tinaide.core.editorview

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.textengine.RopeTextBuffer
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EditorRuntimeOptionsTest {
    @Test
    fun fontScale_shouldNotifyHostWithoutOwningPreferences() {
        var persistedSize: Float? = null
        val state = EditorState(
            textBuffer = RopeTextBuffer(),
            runtimeOptions = EditorRuntimeOptions(onFontSizeChanged = { persistedSize = it })
        )

        EditorFontScaleCoordinator(state).apply(18f)

        assertThat(state.fontSizeSp).isEqualTo(18f)
        assertThat(persistedSize).isEqualTo(18f)
    }

    @Test
    fun touchDiagnostics_shouldReadCurrentHostFlags() {
        var flags = EditorTouchDiagnosticsFlags()
        val diagnostics = EditorTouchDiagnostics { flags }
        assertThat(diagnostics.isScaleEnabled()).isFalse()

        flags = EditorTouchDiagnosticsFlags(enabled = true, scale = true)

        assertThat(diagnostics.isScaleEnabled()).isTrue()
        assertThat(diagnostics.isFocusEnabled()).isFalse()
    }
}
