package com.wuxianggujun.tinaide.ui.activity

import android.content.pm.ApplicationInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wuxianggujun.tinaide.core.config.Prefs
import com.wuxianggujun.tinaide.core.editorview.EditorConfig
import com.wuxianggujun.tinaide.core.editorview.EditorState
import com.wuxianggujun.tinaide.core.format.CodeFormatter
import com.wuxianggujun.tinaide.core.format.FormatResult
import com.wuxianggujun.tinaide.core.format.FormatterIndentation
import com.wuxianggujun.tinaide.core.format.FormatterIndentationResult
import com.wuxianggujun.tinaide.core.textengine.RopeTextBuffer
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the installed native formatter using only temporary sources and restores user preferences. */
@RunWith(AndroidJUnit4::class)
class FormattingIndentationInstrumentationTest {
    @Test
    fun nativeFormatter_andEditorShareGlobalsAndResolvedProjectIndentation() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(0, context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE)
        val before = Prefs.editorSettingsFlow.value
        val beforeStyle = Prefs.codeFormatStyle
        val beforeMode = Prefs.clangFormatRunMode
        val workspace = File(context.cacheDir, "formatting-acceptance-${System.nanoTime()}")
        check(workspace.mkdirs())
        try {
            Prefs.setEditorIndentSize(4)
            Prefs.setEditorTabSize(4)
            Prefs.setEditorInsertSpacesForTabs(true)
            Prefs.setCodeFormatStyle("LLVM")
            Prefs.setClangFormatRunMode("native")
            val formatter = CodeFormatter(context)
            assertTrue("Native clang-format must be installed for device acceptance", formatter.isAvailable())

            val globalFile = File(workspace, "global/main.cpp")
            check(globalFile.parentFile?.mkdirs() == true)
            globalFile.writeText(SOURCE, Charsets.UTF_8)
            val globalResult = formatter.format(SOURCE, globalFile)
            assertTrue(globalResult.toString(), globalResult is FormatResult.Success)
            assertEquals("    ", leadingPrefix((globalResult as FormatResult.Success).formattedContent, "if ("))
            assertEquals("\n    ", newLineIndent(FormatterIndentation.fromPreferences(), "if (ready) {"))

            Prefs.setEditorInsertSpacesForTabs(false)
            val tabResult = formatter.format(SOURCE, globalFile)
            assertTrue(tabResult.toString(), tabResult is FormatResult.Success)
            assertEquals("\t", leadingPrefix((tabResult as FormatResult.Success).formattedContent, "if ("))
            assertEquals("\n\t", newLineIndent(FormatterIndentation.fromPreferences(), "if (ready) {"))

            val projectFile = File(workspace, "configured/main.cpp")
            check(projectFile.parentFile?.mkdirs() == true)
            projectFile.writeText(SOURCE, Charsets.UTF_8)
            File(projectFile.parentFile, ".clang-format").writeText(
                "BasedOnStyle: LLVM\nIndentWidth: 2\nTabWidth: 8\nContinuationIndentWidth: 4\nUseTab: ForIndentation\n",
                Charsets.UTF_8,
            )
            val resolved = formatter.resolveIndentation(projectFile)
            assertTrue(resolved.toString(), resolved is FormatterIndentationResult.Success)
            val policy = resolved as FormatterIndentationResult.Success
            assertTrue(policy.usesProjectConfig)
            assertEquals(FormatterIndentation(2, 8, false, 4), policy.indentation)
            val projectResult = formatter.format(SOURCE, projectFile)
            assertTrue(projectResult.toString(), projectResult is FormatResult.Success)
            assertEquals("  ", leadingPrefix((projectResult as FormatResult.Success).formattedContent, "if ("))
            assertEquals("\n    ", newLineIndent(policy.indentation, "  if (ready) {"))
            assertEquals("\n      ", newLineIndent(policy.indentation, "  call("))
        } finally {
            Prefs.setEditorTabSize(before.tabSize)
            Prefs.setEditorIndentSize(before.indentSize)
            Prefs.setEditorInsertSpacesForTabs(before.insertSpacesForTabs)
            Prefs.setCodeFormatStyle(beforeStyle)
            Prefs.setClangFormatRunMode(beforeMode)
            // Only the fixture directory created above is removed; no project or user log is touched.
            check(workspace.parentFile == context.cacheDir && workspace.name.startsWith("formatting-acceptance-"))
            workspace.deleteRecursively()
        }
    }

    private fun newLineIndent(indentation: FormatterIndentation, line: String): String {
        val state = EditorState(
            RopeTextBuffer(line),
            config = EditorConfig(
                indentSize = indentation.indentWidth,
                tabSize = indentation.tabWidth,
                continuationIndentSize = indentation.continuationIndentWidth,
                insertSpacesForTabs = indentation.insertSpacesForTabs,
            ),
        )
        state.moveCursorTo(state.textBuffer.length)
        state.insertUserInput("\n")
        return state.textBuffer.toString().removePrefix(line)
    }

    private fun leadingPrefix(source: String, marker: String): String = source.lineSequence()
        .first { marker in it }.takeWhile { it == ' ' || it == '\t' }

    private companion object {
        const val SOURCE = "int main() {\nif (1) {\nreturn 2;\n}\nreturn 0;\n}\n"
    }
}
