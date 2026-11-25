package com.wuxianggujun.tinaide.ui.compose.editor

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wuxianggujun.tinaide.project.ProjectBuildSystem
import com.wuxianggujun.tinaide.project.ProjectListItem
import com.wuxianggujun.tinaide.project.ProjectSourceLocation
import com.wuxianggujun.tinaide.ui.RegressionStageTracker
import com.wuxianggujun.tinaide.ui.regressionEvidenceDirectory
import com.wuxianggujun.tinaide.ui.compose.components.EditorMarkdownViewer
import com.wuxianggujun.tinaide.ui.projectlist.ProjectListCard
import com.wuxianggujun.tinaide.ui.theme.TinaIDETheme
import java.io.File
import java.nio.file.Files
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TutorialUiInstrumentationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun markdownUsesActualSyntaxColorsAndCopiesWithoutEditing() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val report = report()
        val tracker = RegressionStageTracker(context, "tutorial-ui-markdown", report)
        val cpp = "#include <iostream>\nint main() {\n    std::cout << \"highlighted\" << 42;\n    return 0;\n}"
        val cmake = "cmake_minimum_required(VERSION 3.22)\nproject(native_lab LANGUAGES CXX)\nadd_executable(native_lab main.cpp)\ntarget_compile_features(native_lab PRIVATE cxx_std_17)"
        val json = "{\"dependencies\": [\"fmt\"]}"
        val copied = mutableListOf<String>()
        try {
            tracker.checkpoint("render-editor-code-blocks")
            compose.setContent {
                TinaIDETheme {
                    Column(Modifier.width(360.dp).verticalScroll(rememberScrollState())) {
                        EditorMarkdownViewer("```cpp\n$cpp\n```\n\n```cmake\n$cmake\n```\n\n```json\n$json\n```", onCodeCopy = { copied += it })
                    }
                }
            }
            var colored = emptyList<Int>()
            compose.waitUntil(15_000) {
                val viewers = compose.onAllNodesWithTag("tina-code-viewer")
                if (viewers.fetchSemanticsNodes().size != 3) false else {
                    colored = (0..2).map { index ->
                        val bitmap = viewers[index].captureToImage().asAndroidBitmap()
                        coloredPixels(bitmap)
                    }
                    colored.all { it > 20 }
                }
            }
            assertEquals(3, colored.size)
            val viewers = compose.onAllNodesWithTag("tina-code-viewer").fetchSemanticsNodes()
            assertTrue(viewers.all { !it.config.contains(SemanticsActions.SetText) && !it.config.contains(SemanticsActions.RequestFocus) })
            compose.onAllNodesWithTag("markdown-code-copy")[0].performClick()
            assertEquals(listOf(cpp), copied)
            report.put("highlightedBlocks", 3).put("coloredSamples", JSONArray(colored))
                .put("readOnly", true).put("copiedWholeCode", true).put("passed", true)
            screenshot("tutorial-ui-markdown")
            tracker.checkpoint("complete")
        } finally { tracker.finish() }
    }

    @Test fun projectNamesStayCompleteAndBadgesUseTheirOwnRows() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = Files.createTempDirectory(context.cacheDir.toPath(), "project-card-layout-").toFile()
        val names = listOf("tina_course_07_vcpkg", "tina_course_07_vcpkg_long_name_to_distinguish_each_practice_project")
        val cases = names.flatMap { name -> listOf("zh", "en").flatMap { locale ->
            listOf(280, 320).flatMap { width -> listOf(1f, 1.5f, 2f).map { scale -> CardCase(name, locale, width, scale) } }
        } }
        val active = mutableStateOf(cases.first())
        val variants = JSONArray()
        val report = report().put("variants", variants)
        val tracker = RegressionStageTracker(context, "tutorial-ui-project-titles", report)
        val density = compose.density.density
        try {
            compose.setContent {
                val selected = active.value
                val configuration = remember(selected.language, selected.scale) {
                    Configuration(context.resources.configuration).apply { setLocale(Locale(selected.language)); fontScale = selected.scale }
                }
                val localized = remember(configuration) { context.createConfigurationContext(configuration) }
                CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides configuration,
                    LocalDensity provides Density(density, selected.scale)) {
                    TinaIDETheme {
                        Column(Modifier.width(selected.width.dp).padding(8.dp).verticalScroll(rememberScrollState())) {
                            ProjectListCard(ProjectListItem(fixture, selected.name, buildSystem = ProjectBuildSystem.CMAKE,
                                sourceLocation = ProjectSourceLocation.PRIVATE), onClick = {}, onAction = {})
                        }
                    }
                }
            }
            for (case in cases) {
                compose.runOnIdle { active.value = case }
                val title = compose.onNodeWithTag("project-card-title", useUnmergedTree = true)
                val layouts = mutableListOf<TextLayoutResult>()
                title.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                val layout = layouts.single()
                assertEquals(case.name, layout.layoutInput.text.text)
                assertFalse("Full name must fit for $case", layout.hasVisualOverflow)
                assertFalse((0 until layout.lineCount).any { layout.isLineEllipsized(it) })
                val titleNode = title.fetchSemanticsNode()
                val tags = compose.onNodeWithTag("project-card-tags", useUnmergedTree = true).fetchSemanticsNode()
                assertTrue(tags.positionInRoot.y >= titleNode.positionInRoot.y + titleNode.size.height)
                variants.put(JSONObject().put("name", case.name).put("language", case.language)
                    .put("widthDp", case.width).put("fontScale", case.scale).put("completeName", true).put("tagsBelowTitle", true))
                tracker.checkpoint("title:${case.language}:${case.width}:${case.scale}")
            }
            compose.runOnIdle { active.value = cases.first() }
            compose.waitForIdle()
            screenshot("tutorial-ui-project-titles")
            report.put("variantCount", cases.size).put("passed", true)
        } finally {
            check(fixture.canonicalFile.parentFile == context.cacheDir.canonicalFile)
            check(fixture.deleteRecursively())
            tracker.finish()
        }
    }

    private fun report() = JSONObject().put("runId", InstrumentationRegistry.getArguments().getString("regressionRunId", "manual"))
        .put("passed", false)

    private fun screenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(regressionEvidenceDirectory(instrumentation.targetContext), "$name.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally { bitmap.recycle() }
    }

    private fun coloredPixels(bitmap: Bitmap): Int {
        var count = 0
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            val pixel = bitmap.getPixel(x, y)
            val red = android.graphics.Color.red(pixel)
            val green = android.graphics.Color.green(pixel)
            val blue = android.graphics.Color.blue(pixel)
            if (maxOf(red, green, blue) - minOf(red, green, blue) > 45) count++
        }
        return count
    }

    private data class CardCase(val name: String, val language: String, val width: Int, val scale: Float)
}
