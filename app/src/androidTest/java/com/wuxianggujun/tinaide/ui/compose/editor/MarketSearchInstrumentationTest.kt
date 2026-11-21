package com.wuxianggujun.tinaide.ui.compose.editor

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.packages.VcpkgCatalogStore
import com.wuxianggujun.tinaide.ui.regressionEvidenceDirectory
import com.wuxianggujun.tinaide.ui.emitRegressionReport
import com.wuxianggujun.tinaide.ui.compose.screens.main.MainScreen
import com.wuxianggujun.tinaide.ui.compose.components.MainBottomNavItems
import com.wuxianggujun.tinaide.tutorial.TutorialViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import com.wuxianggujun.tinaide.ui.theme.TinaIDETheme
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private val MinimumLandscapeMarketListHeight = 120.dp
private const val MaximumInlineScreenshotBytes = 512 * 1024

/** Exercises the actual shared search bar, tab handoff and saved state, rather than a controlled query fixture. */
@RunWith(AndroidJUnit4::class)
class MarketSearchInstrumentationTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun navigation(route: String) = composeRule.onNodeWithTag(MainBottomNavItems.single { it.route == route }.testTag)

    @Test fun searchSurvivesTabSwitchAndStateRestorationAndCanBeClosed() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val landscape = arguments.getString("editorOrientation", "portrait") == "landscape"
        instrumentation.runOnMainSync {
            composeRule.activity.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        composeRule.waitUntil(10_000) {
            composeRule.activity.resources.configuration.orientation == if (landscape) Configuration.ORIENTATION_LANDSCAPE
                else Configuration.ORIENTATION_PORTRAIT
        }
        val version = runBlocking { VcpkgCatalogStore(context).readLocal().ports.first { it.name == "fmt" }.version }
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent {
            val tutorial: TutorialViewModel = viewModel()
            LaunchedEffect(tutorial) { tutorial.skipOnboarding() }
            TinaIDETheme { MainScreen() }
        }
        navigation("market").performClick()
        composeRule.onNodeWithContentDescription(context.getString(Strings.market_search_action)).performClick()
        composeRule.onNode(hasSetTextAction()).performTextInput(" FMT ")
        composeRule.onNodeWithText(context.getString(Strings.market_tab_vcpkg)).performClick()
        composeRule.waitUntil(15_000) {
            composeRule.onAllNodes(hasText("fmt $version")).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("fmt $version").performScrollTo().assertIsDisplayed()
        for (label in listOf(Strings.market_tab_plugins, Strings.market_tab_packages, Strings.market_tab_vcpkg)) {
            val layouts = mutableListOf<TextLayoutResult>()
            composeRule.onNodeWithText(context.getString(label), useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue("Market tab ${context.getString(label)} must fit: " +
                layouts.joinToString {
                    "lines=${it.lineCount}, overflowWidth=${it.didOverflowWidth}, overflowHeight=${it.didOverflowHeight}, " +
                        "ellipsized=${it.isLineEllipsized(0)}, size=${it.size}, constraints=${it.layoutInput.constraints}, " +
                        "paragraphWidth=${it.multiParagraph.width}, lineRight=${it.getLineRight(0)}, " +
                        "fontScale=${it.layoutInput.density.fontScale}, lineHeight=${it.layoutInput.style.lineHeight}"
                }, layouts.size == 1 &&
                layouts.single().lineCount == 1 && !layouts.single().hasVisualOverflow)
        }
        // Exercise the real top-level routes, including the scrollable short-window rail.
        for (destination in listOf("project", "tutorial", "profile", "market")) {
            val item = navigation(destination)
            if (landscape) item.performScrollTo()
            item.performClick().assertIsSelected()
        }
        composeRule.onNode(hasSetTextAction()).assertTextContains(" FMT ")
        composeRule.onNodeWithText(context.getString(Strings.market_tab_vcpkg)).assertIsSelected()
        composeRule.onNodeWithText(context.getString(Strings.market_tab_packages)).performClick()
        composeRule.onNode(hasSetTextAction()).assertTextContains(" FMT ")
        restoration.emulateSavedInstanceStateRestore()
        composeRule.onNode(hasSetTextAction()).assertTextContains(" FMT ")
        composeRule.onNodeWithText(context.getString(Strings.market_tab_vcpkg)).performClick()
        composeRule.waitUntil(15_000) {
            composeRule.onAllNodes(hasText("fmt $version")).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("fmt $version").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithContentDescription(context.getString(Strings.content_desc_navigate_back)).performClick()
        composeRule.onNodeWithContentDescription(context.getString(Strings.market_search_action)).assertIsDisplayed()
        composeRule.waitForIdle()
        val listHeight = composeRule.onNodeWithTag("vcpkg-market-list").fetchSemanticsNode().boundsInRoot.height
        if (landscape) {
            val minimum = with(composeRule.density) { MinimumLandscapeMarketListHeight.toPx() }
            assertTrue("Landscape must leave a usable list viewport ($listHeight < $minimum)", listHeight >= minimum)
        }
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            assertTrue("The active test viewport must match the requested orientation", (bitmap.width > bitmap.height) == landscape)
            val evidence = regressionEvidenceDirectory(context)
            val name = "market-search-${if (landscape) "landscape" else "portrait"}"
            val screenshot = File(evidence, "$name.png")
            screenshot.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val report = JSONObject()
                .put("runId", arguments.getString("regressionRunId", "manual"))
                .put("passed", true).put("width", bitmap.width).put("height", bitmap.height)
                .put("listHeightPx", listHeight).put("density", composeRule.density.density)
                .put("navigationDestinations", 4)
                .put("tabLabelsSingleLine", true)
                .put("landscape", landscape)
            if (arguments.getString("regressionInlineScreenshot") == "true") {
                val png = screenshot.readBytes()
                check(png.size <= MaximumInlineScreenshotBytes) { "Screenshot exceeds the instrumentation evidence limit" }
                report.put("screenshotPng", Base64.encodeToString(png, Base64.NO_WRAP))
            }
            File(evidence, "$name.json").writeText(report.toString(2), Charsets.UTF_8)
            emitRegressionReport(report)
        } finally {
            bitmap.recycle()
        }
    }
}
