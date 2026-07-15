package com.wuxianggujun.tinaide.core.help

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.i18n.Strings
import java.util.Locale
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
class HelpRepositoryTest {

    @Test
    fun pluginQuickStart_shouldBeListedLoadedAndSearchable() = runTest {
        val repository = HelpRepository(localizedContext(Locale.SIMPLIFIED_CHINESE))

        val document = repository.getDocumentById("plugin-quick-start")

        assertThat(document).isNotNull()
        assertThat(document!!.category).isEqualTo(HelpCategory.GETTING_STARTED)
        assertThat(repository.getDocumentsByCategory(HelpCategory.GETTING_STARTED))
            .contains(document)

        val content = repository.loadDocumentContent(document).getOrThrow()
        assertThat(content).contains("# 插件开发快速开始")
        assertThat(content).contains(".tinaplug")
        assertThat(content).contains("热安装")

        val searchResults = repository.search("tinaplug")
        assertThat(searchResults.map { result -> result.document.id })
            .contains("plugin-quick-start")
    }

    @Test
    fun englishLocale_shouldLoadEnglishMarkdown() = runTest {
        val repository = HelpRepository(localizedContext(Locale.ENGLISH))
        val document = repository.getDocumentById("plugin-quick-start")!!

        val content = repository.loadDocumentContent(document).getOrThrow()

        assertThat(content).contains("# Plugin Development Quick Start")
        assertThat(content).contains(".tinaplug")
        assertThat(content).doesNotContain("# 插件开发快速开始")
    }

    @Test
    fun search_shouldMatchBodyOnlyAndReturnSnippet() = runTest {
        val repository = HelpRepository(localizedContext(Locale.SIMPLIFIED_CHINESE))
        val document = repository.getDocumentById("plugin-testing-recovery")!!
        val bodyOnlyQuery = "进程 generation 会替换"

        assertThat(
            (document.keywords + document.title + document.summary).any { metadata ->
                metadata.contains(bodyOnlyQuery, ignoreCase = true)
            }
        ).isFalse()

        repository.preloadAllContent()
        val result = repository.search(bodyOnlyQuery)
            .single { searchResult -> searchResult.document.id == document.id }

        assertThat(result.matchedContent).contains(bodyOnlyQuery)
        assertThat(result.relevanceScore).isGreaterThan(0f)
    }

    @Test
    fun contentCache_shouldKeepChineseAndEnglishContentIsolated() = runTest {
        val context = LocaleSwitchingContext(
            baseContext = RuntimeEnvironment.getApplication(),
            initialLocale = Locale.SIMPLIFIED_CHINESE,
        )
        val repository = HelpRepository(context)
        val document = repository.getDocumentById("plugin-testing-recovery")!!

        val chineseContent = repository.loadDocumentContent(document).getOrThrow()
        context.switchTo(Locale.ENGLISH)
        val englishContent = repository.loadDocumentContent(document).getOrThrow()

        assertThat(chineseContent).contains("runtime 被杀后进程 generation 会替换")
        assertThat(englishContent).contains("Killing the runtime replaces its process generation")
        assertThat(englishContent).doesNotContain("runtime 被杀后进程 generation 会替换")
    }

    @Test
    fun pluginCourse_shouldLoadEveryChineseAndEnglishArticleByLinkTarget() = runTest {
        val courseFiles = listOf(
            "plugin-quick-start.md",
            "plugin-manifest-compatibility.md",
            "plugin-script-api.md",
            "plugin-panels-events.md",
            "plugin-lsp-troubleshooting.md",
            "plugin-testing-recovery.md",
        )

        listOf(Locale.SIMPLIFIED_CHINESE, Locale.ENGLISH).forEach { locale ->
            val repository = HelpRepository(localizedContext(locale))
            courseFiles.forEach { fileName ->
                val content = repository.loadDocumentContentByLinkTarget(fileName).getOrThrow()

                assertThat(content.trim()).isNotEmpty()
                assertThat(content.length).isGreaterThan(500)
                assertThat(content).contains("# ")

                internalMarkdownLinkRegex.findAll(content).forEach { match ->
                    val target = match.groupValues[1]
                    val resolvedDocument = repository.resolveDocumentByLinkTarget(target)
                    assertThat(resolvedDocument).isNotNull()
                }
            }
        }
    }

    @Test
    fun loadDocumentContentByLinkTarget_shouldRejectUnknownOrExternalTargets() = runTest {
        val repository = HelpRepository(RuntimeEnvironment.getApplication())

        assertThat(repository.loadDocumentContentByLinkTarget("missing.md").isFailure).isTrue()
        assertThat(repository.loadDocumentContentByLinkTarget("https://example.com/help.md").isFailure)
            .isTrue()
    }

    @Test
    fun pluginPanelsCourse_shouldUseStableCustomEventId() = runTest {
        listOf(Locale.SIMPLIFIED_CHINESE, Locale.ENGLISH).forEach { locale ->
            val repository = HelpRepository(localizedContext(locale))
            val content = repository.loadDocumentContentByLinkTarget("plugin-panels-events.md").getOrThrow()

            assertThat(content).contains("tina.events.on(\"custom\"")
            assertThat(content).contains("tina.events.emit(\"custom\"")
            assertThat(content).doesNotContain("custom.refresh")
        }
    }

    @Test
    fun localizedAssetCandidates_shouldPreferEnglishAndFallbackToChinese() {
        assertThat(HelpAssetPathResolver.candidatePaths("getting-started.md", "en-US"))
            .containsExactly(
                "help/en/getting-started.md",
                "help/getting-started.md",
            ).inOrder()
        assertThat(HelpAssetPathResolver.candidatePaths("getting-started.md", "zh-CN"))
            .containsExactly("help/getting-started.md")
    }

    @Test
    fun quickActions_shouldOnlyAppearForPluginQuickStart() {
        val repository = HelpRepository(RuntimeEnvironment.getApplication())
        val pluginQuickStart = repository.getDocumentById("plugin-quick-start")!!
        val gettingStarted = repository.getDocumentById("getting-started")!!

        assertThat(HelpQuickActionSupport.resolveActions(pluginQuickStart)).containsExactly(
            HelpQuickAction.CREATE_PLUGIN_PROJECT,
            HelpQuickAction.OPEN_PLUGIN_SETTINGS,
        ).inOrder()
        assertThat(HelpQuickActionSupport.resolveActions(gettingStarted)).isEmpty()
    }

    @Test
    fun quickActionStrings_shouldHaveResources() {
        val context = RuntimeEnvironment.getApplication() as Application

        assertThat(context.getString(Strings.help_quick_actions_title)).isNotEmpty()
        assertThat(context.getString(Strings.help_action_create_plugin_project)).isNotEmpty()
        assertThat(context.getString(Strings.help_action_open_plugin_settings)).isNotEmpty()
    }

    @Test
    fun resolveDocumentByLinkTarget_shouldHandleRelativeLinksAndFragments() {
        val repository = HelpRepository(RuntimeEnvironment.getApplication())

        assertThat(repository.resolveDocumentByLinkTarget("plugin-quick-start.md")?.id)
            .isEqualTo("plugin-quick-start")
        assertThat(repository.resolveDocumentByLinkTarget("./plugins-settings.md")?.id)
            .isEqualTo("plugins-settings")
        assertThat(repository.resolveDocumentByLinkTarget("help/known-issues.md#common-issues")?.id)
            .isEqualTo("known-issues")
        assertThat(repository.resolveDocumentByLinkTarget("plugin-script-api.md")?.id)
            .isEqualTo("plugin-script-api")
        assertThat(repository.resolveDocumentByLinkTarget("plugin-testing-recovery.md")?.id)
            .isEqualTo("plugin-testing-recovery")
        assertThat(repository.resolveDocumentByLinkTarget("https://example.com/help.md"))
            .isNull()
    }

    private fun localizedContext(locale: Locale): Context {
        val application: Application = RuntimeEnvironment.getApplication()
        val configuration = Configuration(application.resources.configuration).apply {
            setLocale(locale)
        }
        return application.createConfigurationContext(configuration)
    }

    companion object {
        private val internalMarkdownLinkRegex = Regex(
            """(?<!!)\[[^]]+]\(([^)#?]+\.md)(?:#[^)]+)?\)"""
        )
    }

    private class LocaleSwitchingContext(
        baseContext: Context,
        initialLocale: Locale,
    ) : ContextWrapper(baseContext) {
        private var localizedResources: Resources = resourcesFor(initialLocale)

        override fun getResources(): Resources = localizedResources

        fun switchTo(locale: Locale) {
            localizedResources = resourcesFor(locale)
        }

        private fun resourcesFor(locale: Locale): Resources {
            val configuration = Configuration(baseContext.resources.configuration).apply {
                setLocale(locale)
            }
            return baseContext.createConfigurationContext(configuration).resources
        }
    }
}
