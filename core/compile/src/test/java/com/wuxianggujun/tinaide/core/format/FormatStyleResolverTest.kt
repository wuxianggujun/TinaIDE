package com.wuxianggujun.tinaide.core.format

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FormatStyleResolverTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun resolve_withoutProjectConfig_readsCurrentUserStyleOnEveryCall() {
        var selectedStyle = "GOOGLE"
        val resolver = FormatStyleResolver { selectedStyle }
        val sourceFile = tempFolder.newFolder("project", "src")
            .resolve("main.cpp")
            .apply { writeText("int main() { return 0; }") }

        assertThat(resolver.resolve(sourceFile)).isSameInstanceAs(FormatStyle.GOOGLE)

        selectedStyle = "MICROSOFT"

        assertThat(resolver.resolve(sourceFile)).isSameInstanceAs(FormatStyle.MICROSOFT)
    }

    @Test
    fun resolve_withClangFormatInAncestor_prefersProjectConfig() {
        val projectDir = tempFolder.newFolder("clang-format-project")
        projectDir.resolve(".clang-format").writeText("BasedOnStyle: GNU")
        val sourceFile = projectDir.resolve("src/main.cpp").apply {
            requireNotNull(parentFile).mkdirs()
            writeText("int main() { return 0; }")
        }
        val resolver = FormatStyleResolver { "GOOGLE" }

        assertThat(resolver.resolve(sourceFile)).isSameInstanceAs(FormatStyle.FILE)
    }

    @Test
    fun resolve_withClangFormatAboveTenLevels_prefersProjectConfig() {
        val projectDir = tempFolder.newFolder("deep-clang-format-project")
        projectDir.resolve(".clang-format").writeText("BasedOnStyle: GNU")
        var sourceDir = projectDir
        repeat(12) { level ->
            sourceDir = sourceDir.resolve("level-$level").apply { mkdir() }
        }
        val sourceFile = sourceDir.resolve("main.cpp").apply {
            writeText("int main() { return 0; }")
        }
        val resolver = FormatStyleResolver { "GOOGLE" }

        assertThat(resolver.resolve(sourceFile)).isSameInstanceAs(FormatStyle.FILE)
    }

    @Test
    fun resolve_withUnderscoreClangFormat_prefersProjectConfig() {
        val projectDir = tempFolder.newFolder("underscore-clang-format-project")
        projectDir.resolve("_clang-format").writeText("BasedOnStyle: WebKit")
        val sourceFile = projectDir.resolve("main.cpp").apply {
            writeText("int main() { return 0; }")
        }
        val resolver = FormatStyleResolver { "LLVM" }

        assertThat(resolver.resolve(sourceFile)).isSameInstanceAs(FormatStyle.FILE)
    }

    @Test
    fun resolve_withUnknownUserValue_fallsBackToLlvm() {
        val sourceFile = tempFolder.newFile("main.cpp")
        val resolver = FormatStyleResolver { "unknown-style" }

        assertThat(resolver.resolve(sourceFile)).isSameInstanceAs(FormatStyle.LLVM)
    }

    @Test
    fun resolve_withoutProjectConfigAndLegacyFilePreference_fallsBackToLlvm() {
        val sourceFile = tempFolder.newFile("legacy-file-style.cpp")
        val resolver = FormatStyleResolver { "FILE" }

        assertThat(resolver.resolve(sourceFile)).isSameInstanceAs(FormatStyle.LLVM)
    }

    @Test
    fun toClangFormatArgument_mapsEverySupportedStyle() {
        val expectedArguments = mapOf(
            FormatStyle.FILE to "--style=file",
            FormatStyle.LLVM to "--style=LLVM",
            FormatStyle.GOOGLE to "--style=Google",
            FormatStyle.CHROMIUM to "--style=Chromium",
            FormatStyle.MOZILLA to "--style=Mozilla",
            FormatStyle.WEBKIT to "--style=WebKit",
            FormatStyle.MICROSOFT to "--style=Microsoft",
            FormatStyle.GNU to "--style=GNU",
            FormatStyle.Custom("{IndentWidth: 8}") to "--style={IndentWidth: 8}",
        )

        expectedArguments.forEach { (style, expectedArgument) ->
            assertThat(style.toClangFormatArgument()).isEqualTo(expectedArgument)
        }
    }
}
