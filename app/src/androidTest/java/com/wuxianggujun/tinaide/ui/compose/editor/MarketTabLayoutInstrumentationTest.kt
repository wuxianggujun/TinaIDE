package com.wuxianggujun.tinaide.ui.compose.editor

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.ui.RegressionStageTracker
import com.wuxianggujun.tinaide.ui.compose.screens.main.market.MarketTabRow
import com.wuxianggujun.tinaide.ui.theme.TinaIDETheme
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MarketTabLayoutInstrumentationTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun labelsRemainCompleteAcrossLocalesWidthsAndFontScales() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val cases = listOf("zh", "en").flatMap { language ->
            listOf(240, 320).flatMap { width ->
                listOf(1f, 1.5f, 2f).map { scale -> TabCase(language, width, scale) }
            }
        }
        var activeCase by mutableStateOf(cases.first())
        val density = composeRule.density.density
        val variants = JSONArray()
        val report = JSONObject()
            .put("runId", InstrumentationRegistry.getArguments().getString("regressionRunId", "manual"))
            .put("passed", false).put("variants", variants)
        val tracker = RegressionStageTracker(context, "market-tab-labels", report)
        try {
            composeRule.setContent {
                val selected = activeCase
                val configuration = remember(selected.language, selected.fontScale) {
                    Configuration(context.resources.configuration).apply {
                        setLocale(Locale(selected.language))
                        fontScale = selected.fontScale
                    }
                }
                val localizedContext = remember(configuration) { context.createConfigurationContext(configuration) }
                CompositionLocalProvider(
                    LocalContext provides localizedContext,
                    LocalConfiguration provides configuration,
                    LocalDensity provides Density(density, selected.fontScale),
                ) {
                    TinaIDETheme {
                        Box(Modifier.width(selected.widthDp.dp)) {
                            MarketTabRow(selectedTab = 0, onTabSelected = {})
                        }
                    }
                }
            }
            for (case in cases) {
                composeRule.runOnIdle { activeCase = case }
                composeRule.waitForIdle()
                tracker.checkpoint("labels:${case.language}:${case.widthDp}:${case.fontScale}")
                val localizedContext = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
                    setLocale(Locale(case.language))
                })
                val labels = JSONArray()
                variants.put(JSONObject().put("language", case.language).put("widthDp", case.widthDp)
                    .put("fontScale", case.fontScale).put("labels", labels))
                for (resource in listOf(Strings.market_tab_plugins, Strings.market_tab_packages, Strings.market_tab_vcpkg)) {
                    val label = localizedContext.getString(resource)
                    val node = composeRule.onNodeWithText(label, useUnmergedTree = true)
                    if (composeRule.onAllNodes(hasScrollAction()).fetchSemanticsNodes().isNotEmpty()) node.performScrollTo()
                    val layouts = mutableListOf<TextLayoutResult>()
                    node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                    assertTrue("Expected exactly one layout for $case: $label", layouts.size == 1)
                    val layout = layouts.single()
                    labels.put(JSONObject().put("label", label).put("lineCount", layout.lineCount)
                        .put("overflowWidth", layout.didOverflowWidth).put("overflowHeight", layout.didOverflowHeight)
                        .put("ellipsized", layout.isLineEllipsized(0)))
                    assertTrue("Label must remain complete for $case: $label (size=${layout.size}, paragraph=${layout.multiParagraph.width})",
                        layout.lineCount == 1 && !layout.hasVisualOverflow && !layout.isLineEllipsized(0))
                }
            }
            report.put("passed", true)
        } catch (error: Throwable) {
            report.put("failedStage", report.optString("stage")).put("failureType", error.javaClass.simpleName)
            throw error
        } finally {
            tracker.finish()
        }
    }

    private data class TabCase(val language: String, val widthDp: Int, val fontScale: Float)
}
