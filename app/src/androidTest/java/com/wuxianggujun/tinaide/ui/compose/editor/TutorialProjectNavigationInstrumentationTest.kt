package com.wuxianggujun.tinaide.ui.compose.editor

import android.app.Activity
import android.graphics.Bitmap
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wuxianggujun.tinaide.MainActivity
import com.wuxianggujun.tinaide.core.compile.ProjectRunConfigBootstrapper
import com.wuxianggujun.tinaide.core.compile.RunConfigurationManager
import com.wuxianggujun.tinaide.core.config.ConfigKeys
import com.wuxianggujun.tinaide.core.config.IConfigManager
import com.wuxianggujun.tinaide.file.IProjectContext
import com.wuxianggujun.tinaide.tutorial.TutorialViewModel
import com.wuxianggujun.tinaide.tutorial.repository.TutorialProgressStore
import com.wuxianggujun.tinaide.tutorial.repository.TutorialRepository
import com.wuxianggujun.tinaide.ui.MainPortalActivity
import com.wuxianggujun.tinaide.ui.RegressionStageTracker
import com.wuxianggujun.tinaide.ui.regressionEvidenceDirectory
import com.wuxianggujun.tinaide.ui.compose.components.MainBottomNavItems
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

@RunWith(AndroidJUnit4::class)
class TutorialProjectNavigationInstrumentationTest : KoinComponent {
    @get:Rule val composeRule = createEmptyComposeRule()

    @Test fun chapterButtonOpensRealEditorAndReopensWithoutRewritingFiles() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext.applicationContext
        val progressStore = TutorialProgressStore(context)
        val courseId = TutorialRepository.CPP_PRACTICE_COURSE_ID
        val savedProgress = runBlocking { progressStore.getProgress(courseId) }
        val configManager = get<IConfigManager>()
        val savedProject = configManager.get(ConfigKeys.CurrentProject)
        val report = JSONObject().put("runId", InstrumentationRegistry.getArguments().getString("regressionRunId", "manual"))
            .put("passed", false)
        val tracker = RegressionStageTracker(context, "course-project-navigation", report)
        val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
        var editor: Activity? = null
        try {
            runBlocking { progressStore.resetProgress(courseId) }
            ActivityScenario.launch(MainPortalActivity::class.java).use { scenario ->
                lateinit var model: TutorialViewModel
                scenario.onActivity {
                    model = ViewModelProvider(it)[TutorialViewModel::class.java]
                    model.dismissOnboardingPrompt()
                }
                val navigationTag = MainBottomNavItems.single { it.route == "tutorial" }.testTag
                composeRule.waitUntil(15_000) { composeRule.onAllNodes(hasTestTag(navigationTag)).fetchSemanticsNodes().isNotEmpty() }
                composeRule.onNodeWithTag(navigationTag).performClick()
                composeRule.runOnIdle { model.openTutorialByLinkTarget("ide-workflow.md") }
                composeRule.waitUntil(5_000) { model.uiState.value.showTutorialContent }
                scenario.recreate()
                composeRule.waitUntil(10_000) {
                    composeRule.onAllNodes(hasTestTag("tutorial-course-prepare-project")).fetchSemanticsNodes().isNotEmpty()
                }
                tracker.checkpoint("open-editor")
                composeRule.onNodeWithTag("tutorial-course-prepare-project").performScrollTo()
                composeRule.waitForIdle()
                val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                try {
                    File(regressionEvidenceDirectory(context), "course-project-navigation.png").outputStream().use {
                        check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
                    }
                } finally {
                    screenshot.recycle()
                }
                composeRule.onNodeWithTag("tutorial-course-prepare-project").performClick()
                // Keep Compose frames advancing while the creation result is delivered to LaunchedEffect.
                composeRule.waitUntil(15_000) { monitor.lastActivity?.isFinishing == false }
                editor = checkNotNull(monitor.lastActivity) { "Course button did not open MainActivity" }
                instrumentation.waitForIdleSync()
                val project = checkNotNull(get<IProjectContext>().getCurrentProject())
                val root = File(project.rootPath)
                assertTrue(File(root, "main.cpp").isFile && File(root, "CMakeLists.txt").isFile)
                val source = File(root, "main.cpp")
                val bytes = source.readBytes()
                val modified = source.lastModified()
                runBlocking {
                    ProjectRunConfigBootstrapper.ensureInitialized(root)
                    assertEquals("native_lab", RunConfigurationManager.load(root.absolutePath).selectedConfig.targetName)
                    assertTrue(progressStore.getProgress(courseId)?.completedStepIds.orEmpty().isEmpty())
                }
                instrumentation.runOnMainSync { editor?.finish() }
                editor = null
                composeRule.waitUntil(10_000) {
                    scenario.state == Lifecycle.State.RESUMED &&
                        composeRule.onAllNodes(hasTestTag("tutorial-course-prepare-project")).fetchSemanticsNodes().isNotEmpty()
                }
                tracker.checkpoint("reopen-editor")
                composeRule.onNodeWithTag("tutorial-course-prepare-project").performScrollTo().performClick()
                composeRule.waitUntil(15_000) { monitor.lastActivity?.isFinishing == false }
                editor = checkNotNull(monitor.lastActivity)
                instrumentation.waitForIdleSync()
                assertEquals(root.absolutePath, get<IProjectContext>().getCurrentProject()?.rootPath)
                assertArrayEquals(bytes, source.readBytes())
                assertEquals(modified, source.lastModified())
                instrumentation.runOnMainSync { editor?.finish() }
                editor = null
                report.put("openedRealEditor", true).put("reopenedSameProject", true).put("preservedSource", true)
                    .put("activityRecreated", true).put("passed", true)
                tracker.checkpoint("complete")
            }
        } finally {
            instrumentation.runOnMainSync { editor?.finish() }
            instrumentation.removeMonitor(monitor)
            runBlocking {
                if (savedProgress == null) progressStore.resetProgress(courseId) else progressStore.saveProgress(savedProgress)
            }
            configManager.set(ConfigKeys.CurrentProject, savedProject)
            tracker.finish()
        }
    }
}
