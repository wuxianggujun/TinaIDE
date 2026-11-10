package com.wuxianggujun.tinaide.core.packages

import android.content.Context
import com.wuxianggujun.tinaide.core.common.io.ArchiveExtractionLimits
import com.wuxianggujun.tinaide.core.common.io.TarExtractor
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.storage.ProjectPaths
import java.io.File
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

data class VcpkgCatalogSnapshot(val revision: String, val archiveSha256: String, val ports: List<VcpkgPort>)

/** A catalog is usable before either a project or a native runtime exists. */
class VcpkgCatalogStore internal constructor(
    private val directory: File,
    private val bundledCatalog: () -> InputStream,
    private val source: VcpkgCatalogSource,
) {
    constructor(context: Context) : this(ProjectPaths.getVcpkgRoot(context),
        { context.assets.open("vcpkg/catalog.json") }, VcpkgCatalogSource.github(context))

    private val cache get() = File(directory, "catalog.json")

    suspend fun readLocal(): VcpkgCatalogSnapshot = withContext(Dispatchers.IO) {
        if (cache.isFile) {
            try {
                return@withContext decode(PackageMetadataFiles.readBytes(cache, MAX_CATALOG_BYTES))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Timber.tag("VcpkgCatalog").w("Discarding unreadable catalog cache: %s", error.javaClass.simpleName)
            }
        }
        bundledCatalog().use { decode(it.readBytesBounded(MAX_CATALOG_BYTES)) }
    }

    suspend fun update(onProgress: (Long, Long) -> Unit = { _, _ -> }): VcpkgCatalogSnapshot = withContext(Dispatchers.IO) {
        mutex.withLock {
            removeAbandonedStaging()
            val previous = readLocal()
            val revision = source.latestRevision()
            requireRevision(revision)
            if (revision == previous.revision && cache.isFile) return@withLock previous
            prepareRevision(revision, previous.archiveSha256.takeIf { revision == previous.revision }, onProgress)
        }
    }

    /** Installs the selected immutable source revision without making an unrelated network update. */
    suspend fun ensureSources(onProgress: (Long, Long) -> Unit = { _, _ -> }): File = withContext(Dispatchers.IO) {
        mutex.withLock {
            removeAbandonedStaging()
            val catalog = readLocal()
            val root = sourceRoot(catalog.revision)
            if (!File(root, ".tina-source-ready").isFile) {
                check(directory.isDirectory || directory.mkdirs())
                val staging = Files.createTempDirectory(directory.toPath(), ".sources-").toFile()
                try {
                    val (archive, _) = obtainArchive(catalog.revision, catalog.archiveSha256, staging, onProgress)
                    validateArchive(archive, catalog.revision)
                    val extracted = File(staging, "extracted")
                    extractSources(archive, extracted, staging)
                    val prepared = File(extracted, "vcpkg-${catalog.revision}")
                    check(File(prepared, "scripts/buildsystems/vcpkg.cmake").isFile)
                    File(prepared, ".tina-source-ready").writeText(catalog.revision, Charsets.UTF_8)
                    check(root.parentFile!!.isDirectory || root.parentFile!!.mkdirs())
                    currentCoroutineContext().ensureActive()
                    Files.move(prepared.toPath(), root.toPath())
                } finally {
                    if (!staging.deleteRecursively()) Timber.tag("VcpkgCatalog").w("Could not remove source staging directory")
                }
            }
            root
        }
    }

    private fun sourceRoot(revision: String): File = File(directory, "sources/$revision")

    private fun removeAbandonedStaging() {
        directory.listFiles().orEmpty().filter { it.name.startsWith(".catalog-") || it.name.startsWith(".sources-") }
            .forEach { staging ->
                if (Files.isSymbolicLink(staging.toPath()) || !staging.deleteRecursively()) {
                    Timber.tag("VcpkgCatalog").w("Could not remove abandoned source staging directory")
                }
            }
    }

    private suspend fun prepareRevision(revision: String, expectedHash: String?, progress: (Long, Long) -> Unit): VcpkgCatalogSnapshot {
        val context = currentCoroutineContext()
        check(directory.isDirectory || directory.mkdirs())
        val staging = Files.createTempDirectory(directory.toPath(), ".catalog-").toFile()
        try {
            val (archive, hash) = obtainArchive(revision, expectedHash, staging, progress)
            val ports = validateArchive(archive, revision, collectPorts = true)
            val snapshot = VcpkgCatalogSnapshot(revision, hash, ports)
            // Only metadata is materialized while browsing. Full source extraction happens at installation.
            val next = File(staging, "catalog.json").apply { writeText(encode(snapshot), Charsets.UTF_8) }
            context.ensureActive()
            atomicReplace(next, cache)
            return snapshot
        } finally {
            if (!staging.deleteRecursively()) Timber.tag("VcpkgCatalog").w("Could not remove catalog staging directory")
        }
    }

    private suspend fun obtainArchive(revision: String, expectedHash: String?, staging: File, progress: (Long, Long) -> Unit): Pair<File, String> {
        val archive = File(directory, "archives/$revision.tar.gz")
        if (archive.isFile) {
            val hash = hashArchive(archive)
            if (expectedHash == null || hash == expectedHash) return archive to hash
        }
        val next = File(staging, "source.tar.gz")
        source.download(revision, next, progress)
        val hash = hashArchive(next)
        if (expectedHash != null && hash != expectedHash) throw VcpkgException(Strings.vcpkg_download_invalid)
        check(archive.parentFile!!.isDirectory || archive.parentFile!!.mkdirs())
        currentCoroutineContext().ensureActive()
        atomicReplace(next, archive)
        return archive to hash
    }

    private suspend fun hashArchive(archive: File): String {
        if (archive.length() > SOURCE_LIMITS.maxArchiveBytes) throw VcpkgException(Strings.vcpkg_download_invalid)
        val context = currentCoroutineContext()
        return archive.inputStream().use { stream ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                context.ensureActive()
                val count = stream.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
    }

    private suspend fun validateArchive(archive: File, revision: String, collectPorts: Boolean = false): List<VcpkgPort> {
        val context = currentCoroutineContext()
        val prefix = "vcpkg-$revision/"
        val required = mutableSetOf(".vcpkg-root", "LICENSE.txt", "scripts/buildsystems/vcpkg.cmake")
        val ports = mutableListOf<VcpkgPort>()
        val manifest = Regex("ports/([a-z0-9]+(?:-[a-z0-9]+)*)/vcpkg\\.json")
        TarExtractor.visitRegularFiles(archive, SOURCE_LIMITS, context::ensureActive, include = { name ->
            require(name == prefix.dropLast(1) || name.startsWith(prefix))
            val relative = name.removePrefix(prefix)
            relative in required || collectPorts && manifest.matches(relative)
        }, onFile = { name, bytes ->
            val relative = name.removePrefix(prefix)
            required.remove(relative)
            manifest.matchEntire(relative)?.let { match ->
                require(bytes.size <= 1024 * 1024)
                ports += VcpkgRepository.portFromJson(match.groupValues[1], JSONObject(PackageMetadataFiles.decodeUtf8(bytes)))
            }
        })
        if (required.isNotEmpty() || collectPorts && ports.isEmpty()) throw VcpkgException(Strings.vcpkg_download_invalid)
        return ports.sortedBy { it.name }
    }

    private suspend fun extractSources(archive: File, target: File, staging: File) {
        val context = currentCoroutineContext()
        if (!File("/system/bin/tar").canExecute()) {
            TarExtractor.extract(archive, target, symlinkPolicy = TarExtractor.SymlinkPolicy.REJECT_LINKS,
                ensureActive = context::ensureActive, limits = SOURCE_LIMITS)
            return
        }
        // Every member was size/path/type checked above. No links or special entries are permitted.
        // Native tar avoids hundreds of thousands of JNI path-stat calls for an immutable source snapshot.
        check(target.mkdirs())
        val process = ProcessBuilder("/system/bin/tar", "-xzof", archive.absolutePath, "-C", target.absolutePath)
            .redirectErrorStream(true).redirectOutput(File(staging, "tar.log")).start()
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
            while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                context.ensureActive()
                if (System.nanoTime() > deadline) throw VcpkgException(Strings.vcpkg_update_failed)
            }
            context.ensureActive()
            if (process.exitValue() != 0) throw VcpkgException(Strings.vcpkg_download_invalid)
        } finally {
            if (process.isAlive) process.destroyForcibly()
            process.inputStream.close()
            process.errorStream.close()
            process.outputStream.close()
        }
    }

    internal companion object {
        private val mutex = Mutex()
        private const val MAX_CATALOG_BYTES = 8 * 1024 * 1024
        private val PORT_NAME = Regex("[a-z0-9]+(?:-[a-z0-9]+)*")
        val SOURCE_LIMITS = ArchiveExtractionLimits(64L * 1024 * 1024, 256L * 1024 * 1024,
            16L * 1024 * 1024, 60_000, 200, 32)

        fun requireRevision(value: String) {
            if (!value.matches(Regex("[0-9a-f]{40}"))) throw VcpkgException(Strings.vcpkg_download_invalid)
        }

        fun decode(bytes: ByteArray): VcpkgCatalogSnapshot {
            val json = JSONObject(PackageMetadataFiles.decodeUtf8(bytes))
            val revision = json.getString("revision").also(::requireRevision)
            val checksum = json.getString("archiveSha256")
            require(checksum.matches(Regex("[0-9a-f]{64}")))
            val entries = json.getJSONArray("ports")
            require(entries.length() in 1..20_000)
            val names = HashSet<String>()
            val ports = (0 until entries.length()).map { index ->
                val port = entries.getJSONObject(index)
                val name = port.getString("name")
                require(name.length <= 128 && PORT_NAME.matches(name) && names.add(name))
                VcpkgPort(name, port.getString("version"), port.getString("description"), port.getString("supports"))
            }
            return VcpkgCatalogSnapshot(revision, checksum, ports.sortedBy { it.name })
        }

        fun encode(snapshot: VcpkgCatalogSnapshot): String = JSONObject().put("revision", snapshot.revision)
            .put("archiveSha256", snapshot.archiveSha256).put("ports", JSONArray(snapshot.ports.map {
                JSONObject().put("name", it.name).put("version", it.version).put("description", it.description).put("supports", it.supports)
            })).toString()

        fun atomicReplace(source: File, target: File) {
            try {
                Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }
}

internal fun InputStream.readBytesBounded(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        if (count > limit - output.size()) throw VcpkgException(Strings.vcpkg_download_invalid)
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
