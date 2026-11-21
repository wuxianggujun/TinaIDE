package com.wuxianggujun.tinaide.core.compile

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wuxianggujun.tinaide.core.compile.cmake.NativeCMakeBuildExecutor
import com.wuxianggujun.tinaide.core.ndk.AndroidElfExecutor
import com.wuxianggujun.tinaide.core.packages.VcpkgRepository
import com.wuxianggujun.tinaide.project.VcpkgProjectSettings
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Offline ASM regression by default; vcpkgAssemblyPort=7zip opts into a real port round trip. */
@RunWith(AndroidJUnit4::class)
class NativeCMakeAssemblyDeviceTest {
    @Test
    fun clangShellWrapperBuildsAndRunsAssembly() = runBlocking {
        assertAssemblyBuild(managedVcpkg = false)
    }

    @Test
    fun managedVcpkgChainloadBuildsAndRunsAssembly() = runBlocking {
        assertAssemblyBuild(managedVcpkg = true)
    }

    private suspend fun assertAssemblyBuild(managedVcpkg: Boolean) {
        withTimeout(50_000) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val sevenZip = managedVcpkg &&
                InstrumentationRegistry.getArguments().getString("vcpkgAssemblyPort") == "7zip"
            val projectName = if (managedVcpkg) "native-asm-vcpkg-acceptance" else "native-asm-acceptance"
            val project = File(context.filesDir, projectName).apply { mkdirs() }
            File(project, "CMakeLists.txt").writeText(
                """
                cmake_minimum_required(VERSION 3.22)
                project(native_asm_probe LANGUAGES C CXX ASM)
                add_executable(native_asm_probe main.c answer.S)
                """.trimIndent() + if (sevenZip) "\nfind_package(7zip CONFIG REQUIRED)\ntarget_link_libraries(native_asm_probe PRIVATE 7zip::7zip)\ntarget_compile_definitions(native_asm_probe PRIVATE TEST_SEVEN_ZIP)\n" else "\n",
                Charsets.UTF_8,
            )
            File(project, "main.c").writeText(
                """
                #include <stdio.h>
                #ifdef TEST_SEVEN_ZIP
                #include <string.h>
                #include <LzmaLib.h>
                #endif
                extern int asm_answer(void);
                int main(void) {
                #ifdef TEST_SEVEN_ZIP
                    const unsigned char input[] = "TinaIDE 7zip acceptance";
                    unsigned char compressed[1024], restored[sizeof(input)], props[LZMA_PROPS_SIZE];
                    size_t compressed_size = sizeof(compressed), restored_size = sizeof(restored);
                    size_t props_size = sizeof(props);
                    int status = LzmaCompress(compressed, &compressed_size, input, sizeof(input),
                        props, &props_size, 1, 1u << 16, 3, 0, 2, 32, 1);
                    if (status != SZ_OK) { printf("compress=%d\n", status); return 2; }
                    size_t consumed = compressed_size;
                    status = LzmaUncompress(restored, &restored_size, compressed, &consumed, props, props_size);
                    if (status != SZ_OK || restored_size != sizeof(input) || memcmp(input, restored, sizeof(input))) {
                        printf("decompress=%d\n", status); return 3;
                    }
                    puts("7zip=ok");
                #endif
                    int answer = asm_answer();
                    printf("asm=%d\n", answer);
                    return answer == 42 ? 0 : 1;
                }
                """.trimIndent(),
                Charsets.UTF_8,
            )
            File(project, "answer.S").writeText(
                """
                #include <asm/unistd.h>
                #ifndef __ANDROID__
                #error Expected the Android target
                #endif
                .text
                .globl asm_answer
                asm_answer:
                #if defined(__aarch64__)
                    mov w0, #42
                    ret
                #elif defined(__x86_64__)
                    mov ${'$'}42, %eax
                    ret
                #else
                #error Unsupported acceptance ABI
                #endif
                .section .note.GNU-stack,"",%progbits
                """.trimIndent(), Charsets.UTF_8,
            )
            val cmakeArguments = if (managedVcpkg) {
                val dependencies = if (sevenZip) "[\"7zip\"]" else "[]"
                File(project, "vcpkg.json").writeText("{\"dependencies\":$dependencies}\n", Charsets.UTF_8)
                VcpkgRepository(context).enableManagedManifest(project)
                VcpkgProjectSettings(VcpkgProjectSettings.MANIFEST).applyTo(emptyList())
            } else {
                emptyList()
            }
            val output = StringBuilder()
            val executor = NativeCMakeBuildExecutor(context)
            val options = NativeCMakeBuildExecutor.Options(
                parallelJobs = if (sevenZip) 4 else 1,
                extraCMakeArgs = cmakeArguments,
                onProgress = { output.appendLine(it) },
            )
            val build = File(project, "build")
            try {
                val configured = executor.configure(project, build, options)
                assertTrue("$configured\n$output", configured is ConfigureResult.Success)
                val built = executor.build(project, build, options = options)
                assertTrue("$built\n$output", built is BuildResult.Success)
                val result = AndroidElfExecutor.exec(context, File(build, "native_asm_probe"), emptyList(), workDir = project, timeoutMs = 5_000)
                File(project, "acceptance-output.txt").writeText(result.output, Charsets.UTF_8)
                assertEquals(result.output, 0, result.exitCode)
                assertTrue(result.output, result.output.contains("asm=42"))
                if (sevenZip) assertTrue(result.output, result.output.contains("7zip=ok"))
            } finally {
                File(project, "acceptance-build.log").writeText(output.toString(), Charsets.UTF_8)
            }
        }
    }
}
