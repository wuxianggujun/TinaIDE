package com.wuxianggujun.tinaide.core.format

import android.app.Application
import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.config.AppPreferences
import com.wuxianggujun.tinaide.core.config.Prefs
import io.mockk.mockk
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = Application::class)
class FormattingIndentationPreferencesTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        AppPreferences.get(context).edit().clear().commit()
        Prefs.initialize(context, mockk(relaxed = true))
        Prefs.setEditorTabSize(4)
        Prefs.setEditorIndentSize(4)
        Prefs.setEditorInsertSpacesForTabs(true)
        Prefs.setCodeFormatStyle("LLVM")
    }

    @Test
    fun noProjectConfig_formattingUsesEditorWidthInsteadOfPresetWidth() {
        val sourceFile = tempFolder.newFolder("project").resolve("main.cpp")

        val argument = FormatStyleResolver().resolve(sourceFile).toClangFormatArgument()

        assertThat(argument).contains("BasedOnStyle: LLVM")
        assertThat(argument).contains("IndentWidth: 4")
        assertThat(argument).contains("TabWidth: 4")
        assertThat(argument).contains("UseTab: Never")
    }

    @Test
    fun noProjectConfig_formattingHonorsTabsAndNewSettingsOnEveryRequest() {
        val sourceFile = tempFolder.newFolder("tabs-project").resolve("main.cpp")
        val resolver = FormatStyleResolver()
        Prefs.setEditorTabSize(8)
        Prefs.setEditorIndentSize(8)
        Prefs.setEditorInsertSpacesForTabs(false)

        val argument = resolver.resolve(sourceFile).toClangFormatArgument()

        assertThat(argument).contains("IndentWidth: 8")
        assertThat(argument).contains("TabWidth: 8")
        assertThat(argument).contains("UseTab: ForIndentation")
    }

    @Test
    fun projectConfig_remainsAuthoritativeOverGlobalIndentation() {
        val project = tempFolder.newFolder("configured-project")
        project.resolve(".clang-format").writeText("BasedOnStyle: Google\nIndentWidth: 2\n")

        val argument = FormatStyleResolver().resolve(project.resolve("main.cpp"))
            .toClangFormatArgument()

        assertThat(argument).isEqualTo("--style=file")
    }
}
