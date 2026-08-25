package com.wuxianggujun.tinaide.ui.nativeactivity

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import org.junit.Test

class NativeActivityRuntimeResolverTest {

    @Test
    fun `dependency closure resolves raylib and cxx runtime in dependency-first order`() {
        withRuntimeFiles { main, raylib, cxx ->
            val dependencies = mapOf(
                main.absolutePath to setOf("libraylib.so", "libc++_shared.so", "liblog.so"),
                raylib.absolutePath to setOf("libandroid.so", "libm.so"),
                cxx.absolutePath to setOf("libc.so"),
            )

            val result = NativeActivityRuntimeResolver.resolveDependencyClosure(
                mainLibrary = main,
                runtimeIndex = mapOf(
                    "libraylib.so" to raylib,
                    "libc++_shared.so" to cxx,
                ),
                dependencyReader = { library -> dependencies[library.absolutePath].orEmpty() },
            )

            assertThat(result.scanFailure).isNull()
            assertThat(result.missingLibraries).isEmpty()
            assertThat(result.sdlLibraries).isEmpty()
            assertThat(result.dependencyLibraries).containsExactly(cxx, raylib).inOrder()
        }
    }

    @Test
    fun `dependency closure rejects transitive SDL dependency`() {
        withRuntimeFiles { main, raylib, cxx ->
            val result = NativeActivityRuntimeResolver.resolveDependencyClosure(
                mainLibrary = main,
                runtimeIndex = mapOf("libraylib.so" to raylib, "libc++_shared.so" to cxx),
                dependencyReader = { library ->
                    when (library.absolutePath) {
                        main.absolutePath -> setOf("libraylib.so")
                        raylib.absolutePath -> setOf("libSDL3.so.0")
                        else -> emptySet()
                    }
                },
            )

            assertThat(result.sdlLibraries).containsExactly("libSDL3.so")
        }
    }

    @Test
    fun `dependency closure reports missing non-system library`() {
        withRuntimeFiles { main, _, _ ->
            val result = NativeActivityRuntimeResolver.resolveDependencyClosure(
                mainLibrary = main,
                runtimeIndex = emptyMap(),
                dependencyReader = { setOf("libraylib.so.9", "libandroid.so") },
            )

            assertThat(result.missingLibraries).containsExactly("libraylib.so.9")
        }
    }

    private fun withRuntimeFiles(block: (File, File, File) -> Unit) {
        val root = Files.createTempDirectory("native-activity-resolver-test").toFile()
        try {
            val main = root.resolve("libmain.so").apply { writeText("main") }
            val raylib = root.resolve("libraylib.so").apply { writeText("raylib") }
            val cxx = root.resolve("libc++_shared.so").apply { writeText("cxx") }
            block(main.canonicalFile, raylib.canonicalFile, cxx.canonicalFile)
        } finally {
            root.deleteRecursively()
        }
    }
}
