package com.wuxianggujun.tinaide.core.packages

import com.wuxianggujun.tinaide.core.network.registry.GitHubRegistryProxySettings
import java.io.File

/** Native tools inherit a per-install mirror selection, without modifying the user's Git config. */
internal object VcpkgToolScripts {
    const val MIRROR_ENV = "TINA_GITHUB_MIRROR"
    private const val DOWNLOAD_SCRIPT = "download-mirror.sh"

    fun write(directory: File, runtime: File, tools: List<String>) {
        val wrappers = File(directory, "wrappers")
        check(wrappers.isDirectory || wrappers.mkdirs())
        tools.forEach { name -> writeExecutable(File(wrappers, name), wrapper(runtime, name)) }
        writeExecutable(File(wrappers, DOWNLOAD_SCRIPT), downloadScript(runtime))
    }

    fun environment(runtime: File, settings: GitHubRegistryProxySettings): Map<String, String> {
        val mirror = settings.customMirrorPrefix ?: return emptyMap()
        val command = "/system/bin/sh ${shellQuote(File(runtime, "wrappers/$DOWNLOAD_SCRIPT").absolutePath)} {url} {dst}"
        // vcpkg uses backticks to escape provider separators, independently of shell quoting.
        val escaped = command.replace("`", "``").replace(",", "`,").replace(";", "`;")
        return mapOf(MIRROR_ENV to mirror, "X_VCPKG_ASSET_SOURCES" to "x-script,$escaped")
    }

    internal fun wrapper(runtime: File, name: String): String = buildString {
        appendLine("#!/system/bin/sh")
        appendLine("export LD_LIBRARY_PATH=${shellQuote(File(runtime, "lib").absolutePath + ":/system/lib64")}")
        appendLine("export GIT_EXEC_PATH=${shellQuote(File(runtime, "libexec/git-core").absolutePath)}")
        val ca = shellQuote(File(runtime, "etc/tls/cert.pem").absolutePath)
        appendLine("export GIT_SSL_CAINFO=$ca")
        appendLine("export CURL_CA_BUNDLE=$ca")
        appendLine("export SSL_CERT_FILE=$ca")
        val executable = "/system/bin/linker64 ${shellQuote(File(runtime, "bin/$name").absolutePath)}"
        if (name == "git") {
            // Locate the subcommand without changing the original argument list.
            appendLine("git_command=")
            appendLine("git_skip_value=0")
            appendLine("for git_argument do")
            appendLine("  if [ \"\$git_skip_value\" -eq 1 ]; then git_skip_value=0; continue; fi")
            appendLine("  case \"\$git_argument\" in")
            appendLine("    -C|-c|--git-dir|--work-tree|--namespace|--super-prefix|--config-env) git_skip_value=1 ;;")
            appendLine("    -*) ;;")
            appendLine("    *) git_command=\"\$git_argument\"; break ;;")
            appendLine("  esac")
            appendLine("done")
            appendLine("case \"\$git_command\" in")
            appendLine("  fetch|clone|ls-remote)")
            appendLine("    if [ -n \"\${$MIRROR_ENV:-}\" ]; then")
            appendLine("      $executable -c \"url.\${$MIRROR_ENV}https://github.com/.insteadOf=https://github.com/\" \"\$@\" && exit 0")
            appendLine("    fi ;;")
            appendLine("esac")
        }
        appendLine("exec $executable \"\$@\"")
    }

    internal fun downloadScript(runtime: File): String = """
        |#!/system/bin/sh
        |[ -n "${'$'}{$MIRROR_ENV:-}" ] || exit 1
        |case "${'$'}1" in
        |  https://github.com/*|https://api.github.com/*|https://raw.githubusercontent.com/*|https://codeload.github.com/*|https://objects.githubusercontent.com/*|https://release-assets.githubusercontent.com/*) ;;
        |  *) exit 1 ;;
        |esac
        |exec /system/bin/sh ${shellQuote(File(runtime, "wrappers/curl").absolutePath)} \
        |  --fail --location --proto '=https' --proto-redir '=https' \
        |  --connect-timeout 15 --max-time 300 --output "${'$'}2" -- "${'$'}{$MIRROR_ENV}${'$'}1"
        |
    """.trimMargin()

    private fun writeExecutable(file: File, contents: String) {
        if (file.isFile && file.readText(Charsets.UTF_8) == contents) return
        val next = File.createTempFile(".wrapper-", ".tmp", file.parentFile)
        try {
            next.writeText(contents, Charsets.UTF_8)
            check(next.setExecutable(true, true))
            VcpkgCatalogStore.atomicReplace(next, file)
        } finally {
            next.delete()
        }
    }

    private fun shellQuote(value: String) = "'" + value.replace("'", "'\\''") + "'"
}
