package com.wuxianggujun.tinaide.ui.compose.editor

import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wuxianggujun.tinaide.core.packages.VcpkgCatalogStore
import kotlinx.coroutines.runBlocking
import com.wuxianggujun.tinaide.ui.compose.screens.packages.VcpkgMarketContent
import com.wuxianggujun.tinaide.ui.theme.TinaIDETheme
import com.wuxianggujun.tinaide.ui.regressionEvidenceDirectory
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VcpkgMarketInstrumentationTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun noProjectStillShowsCatalogAndSupportsSearch() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val version = runBlocking { VcpkgCatalogStore(context).readLocal().ports.first { it.name == "fmt" }.version }
        composeRule.setContent { TinaIDETheme { VcpkgMarketContent(emptyList(), {}) } }
        composeRule.waitUntil(15_000) {
            composeRule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNode(hasSetTextAction()).performScrollTo().performTextReplacement("fmt")
        composeRule.waitUntil(15_000) {
            composeRule.onAllNodes(androidx.compose.ui.test.hasText("fmt $version")).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("fmt $version").performScrollTo().assertIsDisplayed()
        File(regressionEvidenceDirectory(context), "vcpkg-no-project-search.png").outputStream().use {
            instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
