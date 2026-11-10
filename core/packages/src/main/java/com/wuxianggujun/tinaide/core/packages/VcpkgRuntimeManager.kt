package com.wuxianggujun.tinaide.core.packages

import android.content.Context
import com.wuxianggujun.tinaide.core.common.io.ArchiveExtractionLimits
import com.wuxianggujun.tinaide.core.common.io.DebDataExtractor
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.network.registry.GitHubRegistryHttpClientFactory
import com.wuxianggujun.tinaide.core.network.registry.GitHubRegistryProxyConfig
import com.wuxianggujun.tinaide.core.packages.download.DownloadResult
import com.wuxianggujun.tinaide.core.packages.download.ResumableDownloader
import com.wuxianggujun.tinaide.storage.ProjectPaths
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber

data class VcpkgRuntime(val root: File, val environment: Map<String, String>)

/** Supplies Android/Bionic tools without altering the active compiler or asking for filesystem paths. */
class VcpkgRuntimeManager(private val context: Context) {
    suspend fun prepare(abi: String, progress: (Long, Long) -> Unit = { _, _ -> }): VcpkgRuntime = withContext(Dispatchers.IO) {
        mutex.withLock {
            val operation = currentCoroutineContext()
            val base = ProjectPaths.getVcpkgRoot(context)
            val manifestBytes = context.assets.open("vcpkg/runtime.json").use { it.readBytesBounded(128 * 1024) }
            val manifest = JSONObject(PackageMetadataFiles.decodeUtf8(manifestBytes))
            if (manifest.getString("abi") != abi) throw VcpkgException(Strings.vcpkg_invalid_cli)
            val manifestId = sha256(manifestBytes).take(20)
            val revision = "$manifestId-1"
            val runtime = File(base, "tools/$abi/$revision")
            if (!File(runtime, ".complete").isFile) {
                check(runtime.parentFile!!.isDirectory || runtime.parentFile!!.mkdirs())
                val staging = Files.createTempDirectory(runtime.parentFile!!.toPath(), ".runtime-").toFile()
                try {
                    // Keep verified downloads/partial ranges across cancellation, then remove them after publication.
                    val downloads = File(ProjectPaths.getDownloadCacheRoot(context), "vcpkg/$manifestId").apply { mkdirs() }
                    val downloader = ResumableDownloader(downloads, GitHubRegistryHttpClientFactory.download(context))
                    val packages = manifest.getJSONArray("packages")
                    require(packages.length() in 1..64)
                    val repository = manifest.getString("repository")
                    require(repository == "https://packages.termux.dev/apt/termux-main")
                    val total = (0 until packages.length()).sumOf { packages.getJSONObject(it).getLong("Size") }
                    var completed = 0L
                    for (index in 0 until packages.length()) {
                        operation.ensureActive()
                        val record = packages.getJSONObject(index)
                        val path = record.getString("Filename")
                        require(path.matches(Regex("pool/[A-Za-z0-9_+.:/~-]+\\.deb")) && path.split('/').none { it == ".." })
                        val checksum = record.getString("SHA256")
                        require(checksum.matches(Regex("[0-9a-f]{64}")))
                        val archive = File(downloads, "$checksum.deb")
                        if (!archive.isFile || archive.length() != record.getLong("Size") || sha256(archive.readBytes()) != checksum) {
                            val result = downloader.download("$repository/$path", archive,
                                "sha256:$checksum", expectedSize = record.getLong("Size")) { downloaded, _, _ ->
                                progress(completed + downloaded, total)
                            }
                            if (result !is DownloadResult.Success) throw VcpkgException(Strings.vcpkg_update_failed)
                        }
                        DebDataExtractor.extract(archive, File(staging, "unpacked"), HELPER_LIMITS, operation::ensureActive)
                        completed += record.getLong("Size")
                    }
                    val prefix = File(staging, "unpacked/data/data/com.termux/files/usr")
                    if (!REQUIRED.all { File(prefix, "bin/$it").isFile }) throw VcpkgException(Strings.vcpkg_download_invalid)
                    val prepared = File(staging, "prepared")
                    Files.move(prefix.toPath(), prepared.toPath())
                    val executable = File(prepared, "vcpkg")
                    context.assets.open("vcpkg/vcpkg").use { input -> executable.outputStream().use { input.copyTo(it) } }
                    require(sha256(executable.readBytes()) == manifest.getString("cliSha256"))
                    requireExecutable(executable, abi)
                    REQUIRED.forEach { requireExecutable(File(prepared, "bin/$it"), abi) }
                    File(prepared, "wrappers").mkdirs()
                    REQUIRED.forEach { name ->
                        File(prepared, "wrappers/$name").apply {
                            writeText("#!/system/bin/sh\nexport LD_LIBRARY_PATH=${shellQuote(File(runtime, "lib").absolutePath + ":/system/lib64")}\n" +
                                "export GIT_EXEC_PATH=${shellQuote(File(runtime, "libexec/git-core").absolutePath)}\n" +
                                "export GIT_SSL_CAINFO=${shellQuote(File(runtime, "etc/tls/cert.pem").absolutePath)}\n" +
                                "export CURL_CA_BUNDLE=${shellQuote(File(runtime, "etc/tls/cert.pem").absolutePath)}\n" +
                                "export SSL_CERT_FILE=${shellQuote(File(runtime, "etc/tls/cert.pem").absolutePath)}\n" +
                                "exec /system/bin/linker64 ${shellQuote(File(runtime, "bin/$name").absolutePath)} \"\$@\"\n", Charsets.UTF_8)
                            check(setExecutable(true, true))
                        }
                    }
                    File(prepared, ".complete").writeText(revision, Charsets.UTF_8)
                    operation.ensureActive()
                    Files.move(prepared.toPath(), runtime.toPath())
                    if (!downloads.deleteRecursively()) Timber.tag("VcpkgRuntime").w("Could not remove completed runtime downloads")
                } finally {
                    if (!staging.deleteRecursively()) Timber.tag("VcpkgRuntime").w("Could not remove runtime staging directory")
                }
            }
            val root = VcpkgCatalogStore(context).ensureSources(progress)
            val cli = File(root, "vcpkg")
            val expectedHash = manifest.getString("cliSha256")
            if (!cli.isFile || sha256(cli.readBytes()) != expectedHash) {
                val next = File.createTempFile(".vcpkg-", ".tmp", root)
                try {
                    File(runtime, "vcpkg").copyTo(next, overwrite = true)
                    check(next.setExecutable(true, true))
                    VcpkgCatalogStore.atomicReplace(next, cli)
                } finally { next.delete() }
            }
            VcpkgRuntime(root, mapOf(
                "PATH" to File(runtime, "wrappers").absolutePath,
                "GIT_EXEC_PATH" to File(runtime, "libexec/git-core").absolutePath,
                "GIT_TEMPLATE_DIR" to File(runtime, "share/git-core/templates").absolutePath,
                "GIT_SSL_CAINFO" to File(runtime, "etc/tls/cert.pem").absolutePath,
                "CURL_CA_BUNDLE" to File(runtime, "etc/tls/cert.pem").absolutePath,
                "SSL_CERT_FILE" to File(runtime, "etc/tls/cert.pem").absolutePath,
                "VCPKG_DOWNLOADS" to File(base, "downloads").apply { mkdirs() }.absolutePath,
            ) + proxyEnvironment())
        }
    }

    private fun proxyEnvironment(): Map<String, String> {
        val settings = GitHubRegistryProxyConfig.load(context)
        val endpoint = if (settings.isUsable) InetSocketAddress.createUnresolved(settings.host, settings.port)
            else ProxySelector.getDefault()?.select(URI("https://github.com"))
                ?.firstOrNull { it.type() == Proxy.Type.HTTP }?.address() as? InetSocketAddress
        if (endpoint == null) return emptyMap()
        val proxy = URI("http", null, endpoint.hostString, endpoint.port, null, null, null).toASCIIString()
        return listOf("http_proxy", "https_proxy", "HTTP_PROXY", "HTTPS_PROXY").associateWith { proxy }
    }

    private companion object {
        val mutex = Mutex()
        val REQUIRED = listOf("git", "curl", "zip", "unzip", "pkg-config")
        val HELPER_LIMITS = ArchiveExtractionLimits(64L * 1024 * 1024, 256L * 1024 * 1024,
            64L * 1024 * 1024, 30_000, 300, 32)
        fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        fun shellQuote(value: String) = "'" + value.replace("'", "'\\''") + "'"
        fun requireExecutable(file: File, abi: String) {
            val header = file.inputStream().use { input -> ByteArray(20).also { require(input.read(it) == it.size) } }
            val machine = (header[18].toInt() and 255) or ((header[19].toInt() and 255) shl 8)
            if (!header.copyOfRange(0, 4).contentEquals(byteArrayOf(0x7f, 0x45, 0x4c, 0x46)) ||
                header[4] != 2.toByte() || header[5] != 1.toByte() || machine != if (abi == "arm64-v8a") 183 else 62) {
                throw VcpkgException(Strings.vcpkg_invalid_cli)
            }
        }
    }
}
