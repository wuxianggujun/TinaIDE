package com.wuxianggujun.tinaide.core.linuxdistro

import java.io.File

/**
 * apt 源地址重写规则。
 *
 * rootfs tarball 自带的源指向 `ports.ubuntu.com`（arm64/armhf）或
 * `archive.ubuntu.com`（amd64）。桌面软件包体量在几百 MB 级，直连这两个域名在国内
 * 经常慢到超出安装超时，所以按与 manifest 镜像规则一致的策略改写。
 */
data class AptMirrorRule(
    val matchPrefix: String,
    val replaceWith: String,
) {
    init {
        require(matchPrefix.isNotBlank()) { "apt mirror matchPrefix must not be blank" }
        require(replaceWith.isNotBlank()) { "apt mirror replaceWith must not be blank" }
    }
}

data class LinuxDistroRootfsConfig(
    val nameservers: List<String> = listOf("1.1.1.1", "8.8.8.8"),
    val hostname: String = "tinaide",
    val environment: Map<String, String> = mapOf(
        "LANG" to "C.UTF-8",
        "TERM" to "xterm-256color",
    ),
    /**
     * 默认与 `linux-distro/manifest.json` 的 rootfs 下载镜像保持同一家，避免
     * "tarball 走清华、apt 走官方"这种一半快一半慢的组合。传空列表可关闭改写。
     */
    val aptMirrors: List<AptMirrorRule> = DEFAULT_APT_MIRRORS,
) {
    companion object {
        val DEFAULT_APT_MIRRORS: List<AptMirrorRule> = listOf(
            // arm64 / armhf：Ubuntu 把非 x86 架构放在 ports 仓库。
            AptMirrorRule(
                matchPrefix = "http://ports.ubuntu.com/ubuntu-ports",
                replaceWith = "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports",
            ),
            AptMirrorRule(
                matchPrefix = "https://ports.ubuntu.com/ubuntu-ports",
                replaceWith = "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports",
            ),
            // amd64：模拟器场景。
            AptMirrorRule(
                matchPrefix = "http://archive.ubuntu.com/ubuntu",
                replaceWith = "https://mirrors.tuna.tsinghua.edu.cn/ubuntu",
            ),
            AptMirrorRule(
                matchPrefix = "https://archive.ubuntu.com/ubuntu",
                replaceWith = "https://mirrors.tuna.tsinghua.edu.cn/ubuntu",
            ),
            AptMirrorRule(
                matchPrefix = "http://security.ubuntu.com/ubuntu",
                replaceWith = "https://mirrors.tuna.tsinghua.edu.cn/ubuntu",
            ),
            AptMirrorRule(
                matchPrefix = "https://security.ubuntu.com/ubuntu",
                replaceWith = "https://mirrors.tuna.tsinghua.edu.cn/ubuntu",
            ),
        )
    }
}

interface LinuxDistroRootfsConfigurator {
    fun configure(rootfsDir: File, config: LinuxDistroRootfsConfig = LinuxDistroRootfsConfig())
}

class BasicLinuxDistroRootfsConfigurator : LinuxDistroRootfsConfigurator {
    override fun configure(rootfsDir: File, config: LinuxDistroRootfsConfig) {
        require(rootfsDir.isDirectory) { "Rootfs directory does not exist: ${rootfsDir.absolutePath}" }
        writeResolvConf(rootfsDir, config.nameservers)
        writeHosts(rootfsDir, config.hostname)
        writeProfileEnvironment(rootfsDir, config.environment)
        rewriteAptMirrors(rootfsDir, config.aptMirrors)
    }

    /**
     * 就地改写 apt 源。
     *
     * 24.04 默认用 deb822 格式的 `etc/apt/sources.list.d/ubuntu.sources`，
     * 旧格式 `etc/apt/sources.list` 在部分镜像里仍然存在，两者都处理。
     * 只做前缀替换而不重写整份文件：suite / components 的组合各版本不同，
     * 照抄一份模板反而容易和实际发行版对不上。
     */
    private fun rewriteAptMirrors(rootfsDir: File, mirrors: List<AptMirrorRule>) {
        if (mirrors.isEmpty()) return

        val candidates = buildList {
            add(File(rootfsDir, "etc/apt/sources.list"))
            File(rootfsDir, "etc/apt/sources.list.d")
                .listFiles()
                .orEmpty()
                .filter { file ->
                    file.isFile && (file.name.endsWith(".sources") || file.name.endsWith(".list"))
                }
                .sortedBy { file -> file.name }
                .forEach(::add)
        }

        candidates.filter { file -> file.isFile && file.length() > 0L }.forEach { file ->
            val original = file.readText(Charsets.UTF_8)
            val rewritten = mirrors.fold(original) { text, rule ->
                text.replace(rule.matchPrefix, rule.replaceWith)
            }
            if (rewritten != original) {
                // 保留原始副本：镜像不可用时用户/诊断脚本可以手工还原。
                val backup = File(file.parentFile, "${file.name}.tinaide-orig")
                if (!backup.exists()) {
                    backup.writeText(original, Charsets.UTF_8)
                }
                file.writeText(rewritten, Charsets.UTF_8)
            }
        }
    }

    private fun writeResolvConf(rootfsDir: File, nameservers: List<String>) {
        val target = File(rootfsDir, "etc/resolv.conf")
        target.parentFile?.mkdirs()
        target.writeText(
            nameservers.joinToString(separator = "\n", postfix = "\n") { nameserver -> "nameserver $nameserver" },
            Charsets.UTF_8,
        )
    }

    private fun writeHosts(rootfsDir: File, hostname: String) {
        val safeHostname = hostname.takeIf { it.isSafeId() } ?: "tinaide"
        val target = File(rootfsDir, "etc/hosts")
        target.parentFile?.mkdirs()
        target.writeText(
            "127.0.0.1 localhost\n127.0.1.1 $safeHostname\n::1 localhost ip6-localhost ip6-loopback\n",
            Charsets.UTF_8,
        )
    }

    private fun writeProfileEnvironment(rootfsDir: File, environment: Map<String, String>) {
        if (environment.isEmpty()) return
        val target = File(rootfsDir, "etc/profile.d/tinaide.sh")
        target.parentFile?.mkdirs()
        val content = buildString {
            appendLine("# TinaIDE Linux distro environment")
            environment.entries
                .sortedBy { entry -> entry.key }
                .forEach { (key, value) -> appendLine("export ${shellName(key)}=${shellSingleQuoted(value)}") }
        }
        target.writeText(content, Charsets.UTF_8)
        target.setReadable(true, false)
    }

    private fun shellName(value: String): String {
        require(value.isNotBlank() && value.all { char -> char.isLetterOrDigit() || char == '_' }) {
            "Unsafe shell variable name: $value"
        }
        return value
    }

    private fun shellSingleQuoted(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
