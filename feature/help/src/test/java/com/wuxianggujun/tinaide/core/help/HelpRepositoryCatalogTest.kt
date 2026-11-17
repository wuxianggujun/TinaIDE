package com.wuxianggujun.tinaide.core.help

import android.app.Application
import android.content.res.Configuration
import com.google.common.truth.Truth.assertThat
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
class HelpRepositoryCatalogTest {

    @Test fun practicalCourseAssetsLoadInBothLanguagesAndAllNextLinksResolve() = runTest {
        val app = RuntimeEnvironment.getApplication() as Application
        val lessons = listOf("getting-started", "ide-workflow", "cpp-basics", "cpp-functions", "cpp-collections",
            "cpp-raii", "cmake-practice", "vcpkg-guide", "graphics-sdl3", "ai-practice")
        for (language in listOf("zh", "en")) {
            val configuration = Configuration(app.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) }
            val repository = HelpRepository(app.createConfigurationContext(configuration))
            for (lesson in lessons) {
                val content = repository.loadDocumentContentByLinkTarget("$lesson.md").getOrThrow()
                assertThat(content.length).isGreaterThan(500)
                assertThat(content).doesNotContain("`n+")
                assertThat(content).doesNotContain("PRoot")
                Regex("\\]\\(([^)#]+\\.md)(?:#[^)]*)?\\)").findAll(content).forEach { link ->
                    assertThat(repository.resolveDocumentByLinkTarget(link.groupValues[1])).isNotNull()
                }
            }
        }
    }

    @Test fun missingAssetIsFailureInsteadOfAPlaceholderLesson() = runTest {
        val repository = HelpRepository(RuntimeEnvironment.getApplication())
        val missing = repository.getDocumentById("getting-started")!!.copy(fileName = "missing-course.md")
        assertThat(repository.loadDocumentContent(missing).isFailure).isTrue()
    }

    @Test fun vcpkgGuideCanBeOpenedFromMarketAndFoundBySearch() = runTest {
        val repository = HelpRepository(RuntimeEnvironment.getApplication())
        val document = repository.getDocumentById("vcpkg-guide")
        assertThat(document?.fileName).isEqualTo("vcpkg-guide.md")
        assertThat(repository.resolveDocumentByLinkTarget("vcpkg-guide.md")).isEqualTo(document)
        assertThat(document?.keywords).contains("vcpkg")
        assertThat(repository.search("vcpkg").map { it.document.id }).contains("vcpkg-guide")
    }

    @Test fun vcpkgGuideLoadsRealChineseAndEnglishContentOffline() = runTest {
        val app = RuntimeEnvironment.getApplication() as Application
        for ((language, heading) in mapOf("zh" to "# CMake 使用 vcpkg", "en" to "# Using vcpkg with CMake")) {
            val configuration = Configuration(app.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) }
            val repository = HelpRepository(app.createConfigurationContext(configuration))
            val document = checkNotNull(repository.getDocumentById("vcpkg-guide"))
            val content = repository.loadDocumentContent(document).getOrThrow()
            assertThat(content).startsWith(heading)
            assertThat(content).contains("target_link_libraries(tina_vcpkg_example PRIVATE fmt::fmt)")
        }
    }

    @Test
    fun allDocuments_shouldBeUniquelyIdentifiedSortedAndBackedByAssets() {
        val context = RuntimeEnvironment.getApplication() as Application
        val repository = HelpRepository(context)

        val documents = repository.getAllDocuments()
        val sortedDocuments = documents.sortedWith(compareBy({ it.category.ordinal }, { it.order }))
        val assetNames = context.assets.list("help").orEmpty().toSet()
        val englishAssetNames = context.assets.list("help/en").orEmpty().toSet()

        assertThat(documents.map { it.id }).containsNoDuplicates()
        assertThat(documents).containsExactlyElementsIn(sortedDocuments).inOrder()
        assertThat(assetNames).containsAtLeastElementsIn(documents.map { it.fileName })
        assertThat(englishAssetNames).containsAtLeastElementsIn(documents.map { it.fileName })
    }

    @Test
    fun allCategories_shouldExposeEveryHelpCategoryInEnumOrder() {
        val repository = HelpRepository(RuntimeEnvironment.getApplication())

        assertThat(repository.getAllCategories())
            .containsExactlyElementsIn(HelpCategory.entries)
            .inOrder()
    }
}
