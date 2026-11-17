package com.wuxianggujun.tinaide.core.packages

import android.content.Context
import com.wuxianggujun.tinaide.core.common.io.ArchiveExtractionLimits
import com.wuxianggujun.tinaide.core.common.io.DebDataExtractor
import com.wuxianggujun.tinaide.core.common.io.TarExtractor
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.network.registry.GitHubRegistryProxyConfig
import com.wuxianggujun.tinaide.core.network.registry.GitHubRegistryProxySettings
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
                    val downloads = File(staging, "packages")
                    val helpersHash = manifest.getString("helpersArchiveSha256")
                    require(helpersHash.matches(Regex("[0-9a-f]{64}")))
                    val bundled = File(staging, "helpers.tar")
                    context.assets.open("vcpkg/helpers.tar").use { input ->
                        bundled.outputStream().use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            var totalBytes = 0L
                            while (true) {
                                operation.ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                totalBytes += count
                                if (totalBytes > HELPER_ARCHIVE_LIMITS.maxArchiveBytes) throw VcpkgException(Strings.vcpkg_download_invalid)
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                    if (sha256(bundled.readBytes()) != helpersHash) throw VcpkgException(Strings.vcpkg_download_invalid)
                    TarExtractor.extract(bundled, downloads, symlinkPolicy = TarExtractor.SymlinkPolicy.REJECT_LINKS,
                        ensureActive = operation::ensureActive, limits = HELPER_ARCHIVE_LIMITS)
                    val packages = manifest.getJSONArray("packages")
                    require(packages.length() in 1..64)
                    val total = (0 until packages.length()).sumOf { packages.getJSONObject(it).getLong("Size") }
                    var completed = 0L
                    for (index in 0 until packages.length()) {
                        operation.ensureActive()
                        val record = packages.getJSONObject(index)
                        val checksum = record.getString("SHA256")
                        require(checksum.matches(Regex("[0-9a-f]{64}")))
                        val archive = File(downloads, "$checksum.deb")
                        if (!archive.isFile || archive.length() != record.getLong("Size") || sha256(archive.readBytes()) != checksum) {
                            throw VcpkgException(Strings.vcpkg_download_invalid)
                        }
                        DebDataExtractor.extract(archive, File(staging, "unpacked"), HELPER_LIMITS, operation::ensureActive)
                        completed += record.getLong("Size")
                        progress(completed, total)
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
                    VcpkgToolScripts.write(prepared, runtime, REQUIRED)
                    File(prepared, ".complete").writeText(revision, Charsets.UTF_8)
                    operation.ensureActive()
                    Files.move(prepared.toPath(), runtime.toPath())
                } finally {
                    if (!staging.deleteRecursively()) Timber.tag("VcpkgRuntime").w("Could not remove runtime staging directory")
                }
            }
            // Upgrade scripts in existing verified runtimes without downloading the tools again.
            VcpkgToolScripts.write(runtime, runtime, REQUIRED)
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
            val networkSettings = GitHubRegistryProxyConfig.load(context)
            VcpkgRuntime(root, mapOf(
                "PATH" to File(runtime, "wrappers").absolutePath,
                "GIT_EXEC_PATH" to File(runtime, "libexec/git-core").absolutePath,
                "GIT_TEMPLATE_DIR" to File(runtime, "share/git-core/templates").absolutePath,
                "GIT_SSL_CAINFO" to File(runtime, "etc/tls/cert.pem").absolutePath,
                "CURL_CA_BUNDLE" to File(runtime, "etc/tls/cert.pem").absolutePath,
                "SSL_CERT_FILE" to File(runtime, "etc/tls/cert.pem").absolutePath,
                "VCPKG_DOWNLOADS" to File(base, "downloads").apply { mkdirs() }.absolutePath,
            ) + proxyEnvironment(networkSettings) + VcpkgToolScripts.environment(runtime, networkSettings))
        }
    }

    private fun proxyEnvironment(settings: GitHubRegistryProxySettings): Map<String, String> {
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
        val HELPER_ARCHIVE_LIMITS = ArchiveExtractionLimits(32L * 1024 * 1024, 32L * 1024 * 1024,
            16L * 1024 * 1024, 64, 128, 2)
        val HELPER_LIMITS = ArchiveExtractionLimits(64L * 1024 * 1024, 256L * 1024 * 1024,
            64L * 1024 * 1024, 30_000, 300, 32)
        fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
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
