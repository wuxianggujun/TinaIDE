package com.wuxianggujun.tinaide.ui.compose.editor

import android.app.Application
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.config.Prefs
import com.wuxianggujun.tinaide.core.editorview.EditorObservableState
import com.wuxianggujun.tinaide.core.editorview.EditorRuntimeOptions
import com.wuxianggujun.tinaide.core.editorview.EditorState
import com.wuxianggujun.tinaide.core.editorview.TinaEditor
import com.wuxianggujun.tinaide.core.textengine.RopeTextBuffer
import io.mockk.mockk
import java.nio.file.spi.FileSystemProvider
import org.junit.After
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorFontSizeHostIntegrationTest {
    companion object {
        @BeforeClass
        @JvmStatic
        fun initializeJvmFileSystemProviders() {
            // Native font loading discovers SSHD providers outside the Android sandbox.
            // Their reflective security providers must use that same JVM class loader.
            val thread = Thread.currentThread()
            val previousClassLoader = thread.contextClassLoader
            try {
                thread.contextClassLoader = RobolectricTestRunner::class.java.classLoader
                FileSystemProvider.installedProviders()
            } finally {
                thread.contextClassLoader = previousClassLoader
            }
        }
    }

    @get:Rule
    val composeRule = createComposeRule()

    private val buffer = RopeTextBuffer(List(200) { "int value_$it = 123;" }.joinToString("\n"))

    @After
    fun tearDown() {
        buffer.close()
    }

    @Test
    fun pinchReleaseAndPreferenceEcho_preserveFractionalSizeAndCommittedViewport() {
        Prefs.initialize(RuntimeEnvironment.getApplication(), mockk(relaxed = true))
        Prefs.setEditorFontSize(14f)
        Prefs.setEditorWordWrap(false)
        val hostOptions = editorRuntimeOptionsFromPrefs()
        var committedViewport: EditorObservableState? = null
        var committedFontSize: Float? = null
        var touchSlopPx = 0f
        var pinchDetails = "No touch input was injected"
        val publishedSizes = mutableListOf<Float>()
        lateinit var editor: EditorState
        editor = EditorState(
            buffer,
            config = editorConfigFromPrefs(),
            runtimeOptions = EditorRuntimeOptions(
                onFontSizeChanged = { size ->
                    publishedSizes += size
                    committedFontSize = size
                    committedViewport = editor.observableState.value
                    hostOptions.onFontSizeChanged(size)
                }
            )
        )
        composeRule.setContent {
            touchSlopPx = LocalViewConfiguration.current.touchSlop
            // Same live preference feedback path used by EditorContainerState/TinaCodeEditorPage.
            LaunchedEffect(editor) {
                Prefs.editorSettingsFlow.collect { settings ->
                    editor.config = editorConfigFromPrefs()
                    editor.fontSizeSp = settings.fontSize
                }
            }
            TinaEditor(editor, Modifier.fillMaxSize().testTag("font-scale-editor"))
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle { editor.scrollToLine(60) }
        composeRule.onNodeWithTag("font-scale-editor").performTouchInput {
            val startGap = width * 0.15f
            val endGap = width * 0.4f
            pinchDetails = "width=$width startGap=$startGap endGap=$endGap touchSlop=$touchSlopPx"
            // Cross the device's real gesture threshold, even on Robolectric's small viewport.
            check(endGap - startGap > touchSlopPx) { pinchDetails }
            pinch(
                start0 = center - Offset(startGap, 0f),
                end0 = center - Offset(endGap, 0f),
                start1 = center + Offset(startGap, 0f),
                end1 = center + Offset(endGap, 0f),
                durationMillis = 320L
            )
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            val size = checkNotNull(committedFontSize) { "Pinch was not committed: $pinchDetails" }
            assertThat(size).isGreaterThan(14f)
            assertThat(size - size.toInt()).isGreaterThan(0.001f)
            assertThat(Prefs.editorFontSize).isEqualTo(size)
            assertThat(Prefs.editorSettingsFlow.value.fontSize).isEqualTo(size)
            assertThat(editor.fontSizeSp).isEqualTo(size)
            assertThat(editor.config.fontSizeSp).isEqualTo(size)
            assertThat(publishedSizes).containsExactly(size)
            assertThat(editor.observableState.value).isEqualTo(committedViewport)
        }
        repeat(3) { composeRule.mainClock.advanceTimeByFrame() }
        composeRule.runOnIdle {
            assertThat(editor.fontSizeSp).isEqualTo(committedFontSize)
            assertThat(editor.observableState.value).isEqualTo(committedViewport)
        }
    }
}
