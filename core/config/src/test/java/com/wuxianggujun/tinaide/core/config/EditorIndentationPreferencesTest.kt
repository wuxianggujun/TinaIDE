package com.wuxianggujun.tinaide.core.config

import android.app.Application
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = Application::class)
class EditorIndentationPreferencesTest {
    private lateinit var preferences: EditorIndentationPreferences

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        val store = AppPreferences.get(context)
        store.edit().clear().commit()
        preferences = EditorIndentationPreferences(store)
        Prefs.initialize(context, mockk(relaxed = true))
    }

    @Test
    fun defaults_useFourColumnsForBothIndependentWidths() {
        assertThat(preferences.indentSize).isEqualTo(4)
        assertThat(preferences.tabSize).isEqualTo(4)
    }

    @Test
    fun legacyWidth_isPreservedWhenTabWidthChanges() {
        AppPreferences.get(RuntimeEnvironment.getApplication()).edit()
            .putString("editor_tab_size", "8").commit()
        assertThat(preferences.indentSize).isEqualTo(8)
        preferences.setTabSize(4)
        assertThat(preferences.indentSize).isEqualTo(8)
        assertThat(preferences.tabSize).isEqualTo(4)
    }

    @Test
    fun numericLegacyStorageAndCorruptValues_doNotThrowClassCastException() {
        val store = AppPreferences.get(RuntimeEnvironment.getApplication())
        store.edit().putInt("editor_tab_size", 8).commit()
        assertThat(preferences.tabSize).isEqualTo(8)
        assertThat(preferences.indentSize).isEqualTo(8)
        store.edit().putBoolean("editor_tab_size", true).commit()
        assertThat(preferences.tabSize).isEqualTo(4)
        store.edit().putString("editor_indent_size", "not-a-width").commit()
        assertThat(preferences.indentSize).isEqualTo(4)
    }

    @Test
    fun changingIndentation_doesNotChangeTabWidthAndNotifiesEditors() {
        preferences.setIndentSize(2)
        assertThat(preferences.tabSize).isEqualTo(4)
        Prefs.setEditorTabSize(4)
        Prefs.setEditorIndentSize(2)
        assertThat(Prefs.editorTabSize).isEqualTo(4)
        assertThat(Prefs.editorSettingsFlow.value.indentSize).isEqualTo(2)
        Prefs.setEditorIndentSize(8)
        assertThat(Prefs.editorSettingsFlow.value.indentSize).isEqualTo(8)
    }
}
