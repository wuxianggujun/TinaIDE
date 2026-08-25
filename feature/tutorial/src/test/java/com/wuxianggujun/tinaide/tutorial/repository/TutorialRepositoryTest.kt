package com.wuxianggujun.tinaide.tutorial.repository

import android.app.Application
import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.tutorial.data.TutorialType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    manifest = Config.NONE,
    application = Application::class,
)
class TutorialRepositoryTest {

    @Test
    fun resolveTutorialByLinkTarget_shouldPreferTutorialEntriesForInternalDocs() {
        val application = RuntimeEnvironment.getApplication() as Application
        val repository = TutorialRepository(application, TutorialProgressStore(application))

        assertThat(repository.resolveTutorialByLinkTarget("build-project.md")?.id)
            .isEqualTo("build_project")
        assertThat(repository.resolveTutorialByLinkTarget("./getting-started.md#overview")?.id)
            .isEqualTo("getting_started")
        assertThat(repository.resolveTutorialByLinkTarget("create-project.md")?.id)
            .isEqualTo("create_project")
        assertThat(repository.resolveTutorialByLinkTarget("plugins-settings.md"))
            .isNull()
        assertThat(repository.resolveTutorialByLinkTarget("plugin-manifest-compatibility.md")?.id)
            .isEqualTo("plugin_manifest_compatibility")
        assertThat(repository.resolveTutorialByLinkTarget("plugin-script-api.md")?.id)
            .isEqualTo("plugin_script_api")
        assertThat(repository.resolveTutorialByLinkTarget("plugin-panels-events.md")?.id)
            .isEqualTo("plugin_panels_events")
        assertThat(repository.resolveTutorialByLinkTarget("plugin-lsp-troubleshooting.md")?.id)
            .isEqualTo("plugin_lsp_troubleshooting")
        assertThat(repository.resolveTutorialByLinkTarget("plugin-testing-recovery.md")?.id)
            .isEqualTo("plugin_testing_recovery")
        assertThat(repository.resolveTutorialByLinkTarget("https://example.com/build-project.md"))
            .isNull()
    }

    @Test
    fun builtInArticles_shouldHaveUniqueResolvableContentAndCompletePluginCourse() = runTest {
        val application = RuntimeEnvironment.getApplication() as Application
        val repository = TutorialRepository(application, TutorialProgressStore(application))
        val tutorials = repository.getAllTutorialsWithProgress().first().map { it.tutorial }
        val articles = tutorials.filter { it.type == TutorialType.ARTICLE }
        val pluginCourseIds = tutorials
            .filter { it.id.startsWith("plugin_") }
            .map { it.id }

        assertThat(tutorials.map { it.id }).containsNoDuplicates()
        assertThat(articles.mapNotNull { it.contentUrl }).hasSize(articles.size)
        assertThat(articles.mapNotNull { it.contentUrl }).containsNoDuplicates()
        articles.forEach { tutorial ->
            assertThat(repository.resolveTutorialByLinkTarget(tutorial.contentUrl!!)?.id)
                .isEqualTo(tutorial.id)
        }
        assertThat(pluginCourseIds).containsExactly(
            "plugin_quick_start",
            "plugin_manifest_compatibility",
            "plugin_script_api",
            "plugin_panels_events",
            "plugin_lsp_troubleshooting",
            "plugin_testing_recovery",
        ).inOrder()
    }
}
