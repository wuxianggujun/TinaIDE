package com.wuxianggujun.tinaide.core.apkbuilder

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ApkBuilderTemplateReplacementTest {

    @Test
    fun `shouldSkipTemplateLibEntry skips template library when replacement exists`() {
        val shouldSkip = ApkBuilder.shouldSkipTemplateLibEntry(
            entryName = "lib/arm64-v8a/libSDL3.so",
            replacementEntryNames = setOf(
                "lib/arm64-v8a/libmain.so",
                "lib/arm64-v8a/libSDL3.so"
            ),
            targetAbis = setOf("arm64-v8a"),
        )

        assertThat(shouldSkip).isTrue()
    }

    @Test
    fun `shouldSkipTemplateLibEntry removes library for an untargeted ABI`() {
        val shouldSkip = ApkBuilder.shouldSkipTemplateLibEntry(
            entryName = "lib/x86_64/libSDL3.so",
            replacementEntryNames = setOf("lib/arm64-v8a/libSDL3.so"),
            targetAbis = setOf("arm64-v8a"),
        )

        assertThat(shouldSkip).isTrue()
    }

    @Test
    fun `shouldSkipTemplateLibEntry keeps untouched library for a targeted ABI`() {
        val shouldSkip = ApkBuilder.shouldSkipTemplateLibEntry(
            entryName = "lib/x86_64/libSDL3.so",
            replacementEntryNames = setOf("lib/arm64-v8a/libSDL3.so"),
            targetAbis = setOf("arm64-v8a", "x86_64"),
        )

        assertThat(shouldSkip).isFalse()
    }

    @Test
    fun `shouldSkipTemplateLibEntry keeps unrelated template entry`() {
        val shouldSkip = ApkBuilder.shouldSkipTemplateLibEntry(
            entryName = "assets/config.json",
            replacementEntryNames = setOf("lib/arm64-v8a/libSDL3.so"),
            targetAbis = setOf("arm64-v8a"),
        )

        assertThat(shouldSkip).isFalse()
    }
}
