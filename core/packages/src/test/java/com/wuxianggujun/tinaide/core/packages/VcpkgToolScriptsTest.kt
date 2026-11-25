package com.wuxianggujun.tinaide.core.packages

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.network.registry.GitHubRegistryProxySettings
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VcpkgToolScriptsTest {
    @get:Rule val temporary = TemporaryFolder()

    private val shell: File by lazy {
        val candidates = listOf(File("/bin/sh")) + System.getenv("PATH").orEmpty().split(File.pathSeparator).map {
            File(File(it).parentFile, "bin/sh.exe")
        }
        candidates.firstOrNull { it.isFile } ?: error("A POSIX shell (Git Bash on Windows) is required")
    }

    private fun portable(script: String, runtime: File): String = script
        .replace(runtime.absolutePath, runtime.absolutePath.replace('\\', '/'))
        .replace("/system/bin/linker64", "\"${shell.absolutePath.replace('\\', '/')}\"")
        .replace("/system/bin/sh", "\"${shell.absolutePath.replace('\\', '/')}\"")

    private fun run(script: File, arguments: List<String>, environment: Map<String, String>): Int {
        val output = temporary.newFile()
        val process = ProcessBuilder(listOf(shell.absolutePath, script.absolutePath.replace('\\', '/')) + arguments)
            .redirectErrorStream(true).redirectOutput(output).apply { environment().putAll(environment) }.start()
        try {
            check(process.waitFor(10, TimeUnit.SECONDS)) { "Script timed out" }
            return process.exitValue()
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    @Test fun assetScriptOnlyRewritesGithubAndPreservesUrlAndDestinationArguments() {
        val runtime = temporary.newFolder("runtime with spaces")
        val capture = temporary.newFile()
        File(runtime, "wrappers").mkdirs()
        File(runtime, "wrappers/curl").writeText("printf '%s\\n' \"\$@\" > \"\$CAPTURE\"\n", Charsets.UTF_8)
        val script = File(runtime, "download.sh").apply { writeText(portable(VcpkgToolScripts.downloadScript(runtime), runtime)) }
        val environment = mapOf("CAPTURE" to capture.absolutePath.replace('\\', '/'), VcpkgToolScripts.MIRROR_ENV to "https://mirror.example/proxy/")
        val url = "https://github.com/org/repo/archive/tag.tar.gz?value=a&other=b"
        val destination = "path with spaces/download.part"
        assertThat(run(script, listOf(url, destination), environment)).isEqualTo(0)
        val args = capture.readLines()
        assertThat(args.takeLast(4)).containsExactly("--output", destination, "--", environment.getValue(VcpkgToolScripts.MIRROR_ENV) + url).inOrder()
        capture.writeText("")
        assertThat(run(script, listOf("https://github.com.evil.example/file", destination), environment)).isEqualTo(1)
        assertThat(capture.readText()).isEmpty()
    }

    @Test fun gitWrapperFallsBackToOriginWhenMirrorFailsWithoutChangingArguments() {
        val runtime = temporary.newFolder("runtime")
        val capture = temporary.newFile()
        File(runtime, "bin").mkdirs()
        File(runtime, "bin/git").writeText("printf '%s\\n' \"\$@\" >> \"\$CAPTURE\"\n[ \"\$1\" != '-c' ]\n", Charsets.UTF_8)
        val script = File(runtime, "git.sh").apply { writeText(portable(VcpkgToolScripts.wrapper(runtime, "git"), runtime)) }
        val args = listOf("-C", runtime.absolutePath.replace('\\', '/'), "fetch", "https://github.com/microsoft/vcpkg.git", "a".repeat(40))
        assertThat(run(script, args, mapOf("CAPTURE" to capture.absolutePath.replace('\\', '/'),
            VcpkgToolScripts.MIRROR_ENV to "https://mirror.example/"))).isEqualTo(0)
        assertThat(capture.readLines()).containsExactlyElementsIn(listOf("-c",
            "url.https://mirror.example/https://github.com/.insteadOf=https://github.com/") + args + args).inOrder()
    }

    @Test fun gitWrapperDoesNotRetryLocalFailureOrMistakeConfigValueForSubcommand() {
        val runtime = temporary.newFolder("runtime with spaces")
        val capture = temporary.newFile()
        File(runtime, "bin").mkdirs()
        File(runtime, "bin/git").writeText("printf '%s\\n' \"\$@\" >> \"\$CAPTURE\"\nexit 23\n", Charsets.UTF_8)
        val script = File(runtime, "git.sh").apply { writeText(portable(VcpkgToolScripts.wrapper(runtime, "git"), runtime), Charsets.UTF_8) }
        val args = listOf("-C", runtime.absolutePath.replace('\\', '/'), "config", "core.test", "fetch")

        assertThat(run(script, args, mapOf("CAPTURE" to capture.absolutePath.replace('\\', '/'),
            VcpkgToolScripts.MIRROR_ENV to "https://mirror.example/"))).isEqualTo(23)

        assertThat(capture.readLines(Charsets.UTF_8)).containsExactlyElementsIn(args).inOrder()
    }

    @Test fun automaticModeDoesNotOverrideAssetSourcesAndMirrorUsesVcpkgChecksumValidation() {
        val runtime = temporary.newFolder("runtime")
        assertThat(VcpkgToolScripts.environment(runtime, GitHubRegistryProxySettings())).isEmpty()
        val environment = VcpkgToolScripts.environment(runtime, GitHubRegistryProxySettings(customMirrorUrl = "mirror.example"))
        assertThat(environment[VcpkgToolScripts.MIRROR_ENV]).isEqualTo("https://mirror.example/")
        assertThat(environment["X_VCPKG_ASSET_SOURCES"]).startsWith("x-script,/system/bin/sh ")
        assertThat(environment["X_VCPKG_ASSET_SOURCES"]).endsWith(" {url} {dst}")
        assertThat(environment["X_VCPKG_ASSET_SOURCES"]).doesNotContain("x-block-origin")
    }
}
