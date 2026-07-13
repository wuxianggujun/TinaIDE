package com.wuxianggujun.tinaide.core.help

import android.app.Application
import com.google.common.truth.Truth.assertThat
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
