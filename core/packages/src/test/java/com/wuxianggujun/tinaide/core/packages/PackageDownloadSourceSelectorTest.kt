package com.wuxianggujun.tinaide.core.packages

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.packages.model.DownloadSource
import org.junit.Test

class PackageDownloadSourceSelectorTest {

    @Test
    fun select_shouldOnlyReturnMatchingAbiSourcesWhenTheyExist() {
        val selected = PackageDownloadSourceSelector.select(
            sources = listOf(
                source(id = 1, abi = null, priority = 300),
                source(id = 2, abi = "x86_64", priority = 200),
                source(id = 3, abi = "arm64-v8a", priority = 100),
            ),
            targetAbi = "arm64-v8a",
        )

        assertThat(selected.map(DownloadSource::id)).containsExactly(3)
    }

    @Test
    fun select_shouldUseLegacySourcesOnlyWhenNoAbiSpecificSourceExists() {
        val selected = PackageDownloadSourceSelector.select(
            sources = listOf(
                source(id = 1, abi = null, priority = 100),
                source(id = 2, abi = "x86_64", priority = 300),
                source(id = 3, abi = "", priority = 200),
            ),
            targetAbi = "arm64-v8a",
        )

        assertThat(selected.map(DownloadSource::id)).containsExactly(3, 1).inOrder()
    }

    @Test
    fun select_shouldRejectSourcesForOtherAbis() {
        val selected = PackageDownloadSourceSelector.select(
            sources = listOf(source(id = 1, abi = "x86_64", priority = 100)),
            targetAbi = "arm64-v8a",
        )

        assertThat(selected).isEmpty()
    }

    private fun source(id: Int, abi: String?, priority: Int): DownloadSource = DownloadSource(
        id = id,
        name = "source-$id",
        url = "https://example.test/$id.tar.xz",
        priority = priority,
        abi = abi,
    )
}
