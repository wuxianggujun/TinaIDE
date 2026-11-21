package com.wuxianggujun.tinaide.core.compile.strategy

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.compile.cmake.NativeCMakeBuildExecutor
import com.wuxianggujun.tinaide.core.compile.cmake.NativeCMakeBuildExecutor.Companion.CMakeCompilerCommand
import org.junit.Test

class CMakeStrategyCompilerCacheTest {
    @Test
    fun nativeShellLauncherAndItsClangArgumentMatchTheExistingCache() {
        for (language in listOf("C", "CXX")) {
            val compiler = if (language == "C") "clang" else "clang++"
            val shim = "/private/toolchain-shims/current/bin/$compiler"
            val expected = NativeCMakeBuildExecutor.resolveCMakeCompilerCommand(
                realCompilerPath = "/private/toolchains/current/bin/$compiler",
                shimCompilerPath = shim,
                preferLinker64 = true,
                linker64Path = "/system/bin/linker64",
            )
            val cache = """
                CMAKE_${language}_COMPILER:STRING=/system/bin/sh
                CMAKE_${language}_COMPILER_ARG1:STRING=$shim
            """.trimIndent()

            assertThat(CMakeStrategy.compilerCacheMatches(cache, language, expected)).isTrue()
            assertThat(CMakeStrategy.compilerCacheMatches(cache.replace("/current/", "/previous/"), language, expected)).isFalse()
            assertThat(CMakeStrategy.compilerCacheMatches(cache.lineSequence().first(), language, expected)).isFalse()
        }
    }

    @Test
    fun directCompilerCacheStillRejectsAChangedCompilerOrAnUnexpectedLauncherArgument() {
        val expected = CMakeCompilerCommand("/usr/bin/clang")
        val cache = "CMAKE_C_COMPILER:FILEPATH=/usr/bin/clang\n"

        assertThat(CMakeStrategy.compilerCacheMatches(cache, "C", expected)).isTrue()
        assertThat(CMakeStrategy.compilerCacheMatches(cache.replace("clang", "gcc"), "C", expected)).isFalse()
        assertThat(CMakeStrategy.compilerCacheMatches(cache + "CMAKE_C_COMPILER_ARG1:STRING=/old/compiler\n", "C", expected)).isFalse()
    }

    @Test
    fun linker64FallbackRequiresTheSameRealCompilerArgument() {
        val expected = CMakeCompilerCommand("/system/bin/linker64", "/private/bin/clang")
        val cache = "CMAKE_C_COMPILER:FILEPATH=/system/bin/linker64\nCMAKE_C_COMPILER_ARG1:STRING=/private/bin/clang\n"

        assertThat(CMakeStrategy.compilerCacheMatches(cache, "C", expected)).isTrue()
        assertThat(CMakeStrategy.compilerCacheMatches(cache.replace("/private/bin/clang", "/other/bin/clang"), "C", expected)).isFalse()
    }
}
