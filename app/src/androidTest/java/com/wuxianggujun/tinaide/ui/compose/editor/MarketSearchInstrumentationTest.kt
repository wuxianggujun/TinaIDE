package com.wuxianggujun.tinaide.ui.compose.editor

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.packages.VcpkgCatalogStore
import com.wuxianggujun.tinaide.ui.compose.screens.main.market.MarketScreen
import com.wuxianggujun.tinaide.ui.theme.TinaIDETheme
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the actual shared search bar, tab handoff and saved state, rather than a controlled query fixture. */
@RunWith(AndroidJUnit4::class)
class MarketSearchInstrumentationTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun searchSurvivesTabSwitchAndStateRestorationAndCanBeClosed() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val version = runBlocking { VcpkgCatalogStore(context).readLocal().ports.first { it.name == "fmt" }.version }
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent { TinaIDETheme { MarketScreen() } }
        composeRule.onNodeWithContentDescription(context.getString(Strings.market_search_action)).performClick()
        composeRule.onNode(hasSetTextAction()).performTextInput(" FMT ")
        composeRule.onNodeWithText(context.getString(Strings.market_tab_vcpkg)).performClick()
        composeRule.waitUntil(15_000) {
            composeRule.onAllNodes(hasText("fmt $version")).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("fmt $version").performScrollTo().assertIsDisplayed()
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
    }
}
