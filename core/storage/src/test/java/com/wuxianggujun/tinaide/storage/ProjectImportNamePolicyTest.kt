package com.wuxianggujun.tinaide.storage

import com.google.common.truth.Truth.assertThat
import java.nio.charset.StandardCharsets
import org.junit.Test

class ProjectImportNamePolicyTest {
    @Test
    fun projectName_boundsUtf8BytesWithoutSplittingCodePoint() {
        val normalized = ProjectImportNamePolicy.projectName(
            "a".repeat(239) + "\uD83D\uDE00",
        )

        assertThat(normalized).isEqualTo("a".repeat(239))
        assertThat(normalized.toByteArray(StandardCharsets.UTF_8).size).isAtMost(240)
    }

    @Test
    fun projectName_boundsMultibyteCharactersByEncodedSize() {
        val normalized = ProjectImportNamePolicy.projectName("\u9879".repeat(200))

        assertThat(normalized).isNotEmpty()
        assertThat(normalized.toByteArray(StandardCharsets.UTF_8).size).isAtMost(240)
    }

    @Test
    fun cacheFileName_leavesSpaceForGeneratedPrefix() {
        val normalized = ProjectImportNamePolicy.cacheFileName("\u9879".repeat(200) + ".zip")
        val generatedName = "project-import-00000000-0000-0000-0000-000000000000-$normalized"

        assertThat(generatedName.toByteArray(StandardCharsets.UTF_8).size).isAtMost(255)
    }

    @Test
    fun projectName_removesPathSegmentsAndControlCharacters() {
        val normalized = ProjectImportNamePolicy.projectName("../folder/\u0000bad:name.")

        assertThat(normalized).isEqualTo("_bad_name")
    }
}
