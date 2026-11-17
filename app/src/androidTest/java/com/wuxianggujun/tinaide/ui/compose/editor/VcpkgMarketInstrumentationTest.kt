package com.wuxianggujun.tinaide.ui.compose.editor

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wuxianggujun.tinaide.core.packages.VcpkgCatalogStore
import com.wuxianggujun.tinaide.core.packages.VcpkgAndroidCatalog
import com.wuxianggujun.tinaide.core.packages.PackageAbiCompatibility
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.project.ProjectListItem
import com.wuxianggujun.tinaide.project.ProjectMetadataStore
import com.wuxianggujun.tinaide.project.VcpkgProjectSettings
import kotlinx.coroutines.runBlocking
import com.wuxianggujun.tinaide.ui.compose.screens.packages.VcpkgMarketContent
import com.wuxianggujun.tinaide.ui.theme.TinaIDETheme
import com.wuxianggujun.tinaide.ui.regressionEvidenceDirectory
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.runner.RunWith
import org.junit.rules.TemporaryFolder
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class VcpkgMarketInstrumentationTest {
    @get:Rule val composeRule = createComposeRule()
    @get:Rule val temporary = TemporaryFolder(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)

    @Test fun noProjectStillShowsCatalogAndSupportsSearch() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val available = runBlocking { VcpkgCatalogStore(context).readLocal().ports }
        val version = available.first { it.name == "fmt" }.version
        val abi = PackageAbiCompatibility.currentAppAbi(context.applicationInfo.nativeLibraryDir, Build.SUPPORTED_ABIS)
        val expectedCount = context.getString(Strings.vcpkg_catalog_count, VcpkgAndroidCatalog(available, abi).ports.size, abi)
        var guideOpened = false
        // 顶栏持有搜索文本；用受控 query 驱动内容区，比依赖输入框焦点更稳定。
        val query = mutableStateOf("")
        composeRule.setContent { TinaIDETheme { VcpkgMarketContent(emptyList(), onOpenGuide = { guideOpened = true }, query = query.value) } }
        composeRule.waitUntil(15_000) {
            composeRule.onAllNodes(hasText(expectedCount)).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(expectedCount).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(Strings.vcpkg_open_guide)).performClick()
        composeRule.runOnIdle { assertEquals(true, guideOpened) }
        composeRule.runOnIdle { query.value = "directx-headers" }
        val incompatibleTitle = context.getString(Strings.vcpkg_empty_incompatible_title)
        composeRule.waitUntil(15_000) {
            composeRule.onAllNodes(hasText(incompatibleTitle)).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(incompatibleTitle).performScrollTo().assertIsDisplayed()
        composeRule.runOnIdle { query.value = " FMT " }
        composeRule.waitUntil(15_000) {
            composeRule.onAllNodes(androidx.compose.ui.test.hasText("fmt $version")).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("fmt $version").performScrollTo().assertIsDisplayed()
        File(regressionEvidenceDirectory(context), "vcpkg-no-project-search.png").outputStream().use {
            instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun unavailableProjectDependencyCanBeRemovedWithoutEnablingIntegration() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val project = temporary.newFolder("market-project")
        File(project, "CMakeLists.txt").writeText("cmake_minimum_required(VERSION 3.22)", Charsets.UTF_8)
        runBlocking { ProjectMetadataStore.ensure(project) }
        val manifest = File(project, "vcpkg.json").apply {
            writeText("""{"dependencies":["directx-headers","removed-tina-test-library"]}""", Charsets.UTF_8)
        }
        val query = mutableStateOf("")
        composeRule.setContent { TinaIDETheme {
            VcpkgMarketContent(listOf(ProjectListItem(project, "Market regression project")), onOpenGuide = {}, query = query.value)
        } }
        val selectProject = context.getString(Strings.vcpkg_select_project)
        composeRule.waitUntil(15_000) { composeRule.onAllNodes(hasText(selectProject) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText(selectProject).performClick()
        composeRule.onNodeWithText("Market regression project").performClick()
        // The declared-but-deleted port must stay in the single market list so it can still be removed;
        // the search field lives in the shared market top bar, so drive the filter through the controlled query.
        composeRule.runOnIdle { query.value = "removed-tina-test-library" }
        // Wait for the requested card itself: a negative check can succeed before the async project read finishes.
        composeRule.waitUntil(15_000) {
            composeRule.onAllNodes(hasText("removed-tina-test-library")).fetchSemanticsNodes().isNotEmpty()
        }
        // The content area no longer renders a text field, so the port card is the only node holding this text.
        composeRule.onNode(hasText("removed-tina-test-library"))
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(Strings.vcpkg_port_unknown)).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(Strings.vcpkg_remove_dependency)).performScrollTo().performClick()
        composeRule.onNodeWithText(context.getString(Strings.vcpkg_remove_confirm_title)).assertIsDisplayed()
        // The confirm button repeats the card action label, so scope the click to the dialog.
        composeRule.onNode(hasText(context.getString(Strings.vcpkg_remove_confirm_action)) and hasAnyAncestor(isDialog()))
            .performClick()
        composeRule.waitUntil(15_000) {
            composeRule.onAllNodes(hasText(context.getString(Strings.market_empty_search_title))).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals("[\"directx-headers\"]", JSONObject(manifest.readText(Charsets.UTF_8)).getJSONArray("dependencies").toString())
        assertEquals(VcpkgProjectSettings.DISABLED,
            VcpkgProjectSettings.from(runBlocking { ProjectMetadataStore.read(project) }!!.normalizedNativeCMakeArgs()).mode)
    }
}
