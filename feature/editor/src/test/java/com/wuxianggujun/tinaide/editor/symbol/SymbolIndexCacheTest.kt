package com.wuxianggujun.tinaide.editor.symbol

import android.app.Application
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = Application::class)
class SymbolIndexCacheTest {
    @Test
    fun cacheRoundTrip_preservesSignaturesDocumentationOwnersAndPositions() = runTest {
        val cache = SymbolIndexCache(RuntimeEnvironment.getApplication())
        val project = "/projects/symbol-cache-test"
        val symbols = listOf(
            ProjectSymbol("run", SymbolKind.Method, "method", "$project/main.cpp", SymbolLocation(4, 8),
                "void run(int)", "执行任务，保留中文说明", "app::Worker"),
            ProjectSymbol("run", SymbolKind.Method, "method", "$project/main.cpp", SymbolLocation(5, 8),
                "void run(double)", null, "app::Worker"),
        )
        val snapshots = listOf(SymbolIndexCache.CachedFileSnapshot("$project/main.cpp", symbols))
        try {
            assertThat(cache.saveIndex(project, snapshots, mapOf("$project/main.cpp" to 123L))).isTrue()
            val loaded = requireNotNull(cache.loadIndex(project))
            assertThat(loaded.fileSnapshots).containsExactlyElementsIn(snapshots)
            assertThat(loaded.fileTimestamps).containsExactly("$project/main.cpp", 123L)
        } finally {
            cache.clearCache(project)
        }
    }
}
