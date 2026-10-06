package com.wuxianggujun.tinaide.core.config

import android.app.Application
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = Application::class)
class EditorFontSizePreferencesTest {
    @Before
    fun setUp() {
        Prefs.initialize(RuntimeEnvironment.getApplication(), mockk(relaxed = true))
        Prefs.setEditorFontSize(14f)
    }

    @Test
    fun fractionalSizes_roundTripThroughStorageAndSettingsWithoutQuantization() = runTest {
        val observed = mutableListOf<Float>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            Prefs.editorSettingsFlow.collect { observed += it.fontSize }
        }

        for (size in listOf(14.025f, 16.125f, 16.1875f, 23.75f, 8f, 48f)) {
            Prefs.setEditorFontSize(size)
            assertThat(Prefs.editorFontSize).isEqualTo(size)
            assertThat(Prefs.editorSettingsFlow.value.fontSize).isEqualTo(size)
            assertThat(observed.last()).isEqualTo(size)
        }
        assertThat(observed).containsExactly(14f, 14.025f, 16.125f, 16.1875f, 23.75f, 8f, 48f).inOrder()
        Prefs.setEditorFontSize(48f)
        assertThat(observed).hasSize(7)
    }

    @Test
    fun nonFiniteAndOutOfRangeValues_neverReachEditorSettings() {
        for (size in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            Prefs.setEditorFontSize(size)
            assertThat(Prefs.editorFontSize).isEqualTo(14f)
            assertThat(Prefs.editorSettingsFlow.value.fontSize).isEqualTo(14f)
        }
        Prefs.setEditorFontSize(1f)
        assertThat(Prefs.editorFontSize).isEqualTo(8f)
        Prefs.setEditorFontSize(100f)
        assertThat(Prefs.editorFontSize).isEqualTo(48f)
    }

    @Test
    fun legacyIntegerStringsAndCorruptValues_areReadWithoutAMigration() {
        val preferences = AppPreferences.get(RuntimeEnvironment.getApplication())
        for ((stored, expected) in listOf(
            "18" to 18f,
            "16.1875" to 16.1875f,
            "NaN" to 14f,
            "Infinity" to 14f,
            "not-a-size" to 14f,
            "1" to 8f,
            "100" to 48f
        )) {
            preferences.edit().putString("editor_font_size", stored).commit()
            assertThat(Prefs.editorFontSize).isEqualTo(expected)
        }
    }
}
