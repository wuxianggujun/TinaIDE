package com.wuxianggujun.tinaide.core.packages

import android.content.Context

import com.wuxianggujun.tinaide.project.ProjectMetadataStore
import com.wuxianggujun.tinaide.project.VcpkgProjectSettings
import com.wuxianggujun.tinaide.core.i18n.Strings
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException
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

data class VcpkgPort(val name: String, val version: String, val description: String, val supports: String)
data class VcpkgPortIndex(val ports: List<VcpkgPort>, val skippedPorts: Int)
data class VcpkgProjectCatalog(
    val settings: VcpkgProjectSettings,
    val ports: List<VcpkgPort>,
    val declared: Set<String>,
    val installed: Map<String, String>,
    val cliPresent: Boolean,
    val skippedPorts: Int = 0,
)

class VcpkgRepository(private val context: Context? = null) {
    suspend fun read(project: File, androidAbi: String, availablePorts: List<VcpkgPort>? = null): VcpkgProjectCatalog = withContext(Dispatchers.IO) {
        val settings = VcpkgProjectSettings.from(ProjectMetadataStore.read(project)?.normalizedNativeCMakeArgs().orEmpty())
        val root = resolve(project, settings.root)
        val index = if (availablePorts != null) VcpkgPortIndex(availablePorts, 0)
            else if (settings.root.isBlank()) VcpkgPortIndex(emptyList(), 0) else readPorts(root)
        val manifest = File(project, "vcpkg.json")
        val declared = if (manifest.exists()) dependencyNames(readJson(manifest)) else emptySet()
        val installedRoot = context?.let { VcpkgManagedPaths.installed(it, project, settings) } ?: settings.installationRoot(project)
        val installed = linkedMapOf<String, String>()
        val status = File(installedRoot, "vcpkg/status")
        if (settings.enabled && status.isFile) {
            readInstalledStatus(status, settings.targetTriplet(androidAbi), installed)
        }
        val catalog = index.ports.associateByTo(linkedMapOf()) { it.name }
        (declared + installed.keys).filter { PORT_NAME.matches(it) }.forEach { name ->
            catalog.putIfAbsent(name, VcpkgPort(name, installed[name].orEmpty(), "", ""))
        }
        VcpkgProjectCatalog(settings, catalog.values.sortedBy { it.name }, declared, installed,
            settings.root.isNotBlank() && File(root, "vcpkg").isFile, index.skippedPorts)
    }

    suspend fun readPorts(root: File): VcpkgPortIndex = withContext(Dispatchers.IO) {
        var skippedPorts = 0
        val ports = File(root, "ports").listFiles().orEmpty()
            .filter { it.isDirectory && PORT_NAME.matches(it.name) }
            .mapNotNull { directory ->
                val manifest = File(directory, "vcpkg.json")
                if (!manifest.isFile) return@mapNotNull null
                currentCoroutineContext().ensureActive()
                val json = try {
                    readJson(manifest)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    skippedPorts++
                    // JSON exceptions can contain arbitrary package text. Log only the error type.
                    Timber.tag("VcpkgRepository").w("Ignoring unreadable vcpkg port %s: %s", directory.name, error.javaClass.simpleName)
                    return@mapNotNull null
                }
                portFromJson(directory.name, json)
            }.sortedBy { it.name }
        VcpkgPortIndex(ports, skippedPorts)
    }

    suspend fun setDependency(project: File, portName: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        // The repository is created per screen. A process-wide lock prevents
        // concurrent read/modify/write operations from losing another screen's
        // dependency when both instances point at the same project.
        manifestMutex.withLock { editDependency(project, portName, enabled) }
    }

    suspend fun enableManagedManifest(project: File) = withContext(Dispatchers.IO) {
        manifestMutex.withLock {
            if (!File(project, "CMakeLists.txt").isFile) throw VcpkgException(Strings.vcpkg_cmake_required)
            val metadata = ProjectMetadataStore.ensure(project)
            val arguments = metadata.normalizedNativeCMakeArgs()
            val settings = VcpkgProjectSettings.from(arguments)
            if (settings.mode == VcpkgProjectSettings.PREBUILT) throw VcpkgException(Strings.vcpkg_manifest_mode_required)
            if (!ProjectMetadataStore.write(project, metadata.copy(nativeCMakeArgs =
                    VcpkgProjectSettings(VcpkgProjectSettings.MANIFEST).applyTo(arguments)))) {
                throw VcpkgException(Strings.vcpkg_write_failed)
            }
        }
    }

    private suspend fun editDependency(project: File, portName: String, enabled: Boolean) {
        val operationContext = currentCoroutineContext()
        operationContext.ensureActive()
        if (!PORT_NAME.matches(portName)) throw VcpkgException(Strings.vcpkg_invalid_manifest)
        val manifest = File(project, "vcpkg.json")
        requireManifestLocation(project, manifest)
        val original = if (manifest.exists()) readBytesLimited(manifest, MAX_MANIFEST_BYTES) else null
        if (original == null && !enabled) return
        val json = original?.let { parseJsonUtf8(it) } ?: JSONObject()
        dependencyNames(json) // Refuse to silently overwrite a malformed dependency declaration.
        val dependencies = json.optJSONArray("dependencies") ?: JSONArray()
        val existing = (0 until dependencies.length()).map { dependencies.get(it) }
        val name: (Any) -> String = { if (it is JSONObject) it.optString("name") else it.toString() }
        val alreadyDeclared = existing.any { name(it) == portName }
        if (alreadyDeclared == enabled) return
        val next = existing.filterNot { !enabled && name(it) == portName }.toMutableList()
        if (enabled) next += portName
        json.put("dependencies", JSONArray(next))
        val serialized = (json.toString(2) + "\n").toByteArray(Charsets.UTF_8)
        if (serialized.size > MAX_MANIFEST_BYTES) throw VcpkgException(Strings.vcpkg_invalid_manifest)
        operationContext.ensureActive()
        val temporary = File.createTempFile(".vcpkg-manifest-", ".json", project)
        try {
            temporary.writeBytes(serialized)
            operationContext.ensureActive()
            requireManifestLocation(project, manifest)
            val current = if (manifest.exists()) readBytesLimited(manifest, MAX_MANIFEST_BYTES) else null
            if (!(if (original == null) current == null else current != null && original.contentEquals(current))) {
                throw VcpkgException(Strings.vcpkg_manifest_changed)
            }
            operationContext.ensureActive()
            try {
                Files.move(temporary.toPath(), manifest.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), manifest.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (temporary.exists() && !temporary.delete()) {
                Timber.tag("VcpkgRepository").w("Failed to remove temporary vcpkg manifest")
            }
        }
    }

    private fun dependencyNames(json: JSONObject): Set<String> {
        if (json.has("dependencies") && json.opt("dependencies") !is JSONArray) throw VcpkgException(Strings.vcpkg_invalid_manifest)
        val dependencies = json.optJSONArray("dependencies") ?: return emptySet()
        return (0 until dependencies.length()).map { index ->
            val dependency = dependencies.get(index)
            val name = when (dependency) {
                is JSONObject -> dependency.optString("name")
                is String -> dependency
                else -> throw VcpkgException(Strings.vcpkg_invalid_manifest)
            }
            if (!PORT_NAME.matches(name)) throw VcpkgException(Strings.vcpkg_invalid_manifest)
            name
        }.toSet()
    }

    private suspend fun readJson(file: File): JSONObject = parseJsonUtf8(readBytesLimited(file, MAX_MANIFEST_BYTES))

    private fun parseJsonUtf8(bytes: ByteArray): JSONObject = try {
        JSONObject(PackageMetadataFiles.decodeUtf8(bytes))
    } catch (error: Exception) {
        // Keep parser input fragments out of caller logs.
        Timber.tag("VcpkgRepository").w("Invalid vcpkg JSON: %s", error.javaClass.simpleName)
        throw VcpkgException(Strings.vcpkg_invalid_manifest)
    }

    private suspend fun readBytesLimited(file: File, maxBytes: Int): ByteArray {
        val operationContext = currentCoroutineContext()
        return try {
            PackageMetadataFiles.readBytes(file, maxBytes, operationContext::ensureActive)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            throw VcpkgException(Strings.vcpkg_invalid_manifest, error)
        }
    }

    private suspend fun readInstalledStatus(status: File, triplet: String, installed: MutableMap<String, String>) {
        val text = try {
            PackageMetadataFiles.decodeUtf8(readBytesLimited(status, MAX_STATUS_BYTES))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            throw VcpkgException(Strings.vcpkg_invalid_manifest, error)
        }
        val fields = mutableMapOf<String, String>()
        fun finishRecord() {
            val name = fields["Package"]
            if (fields["Architecture"] == triplet && fields["Status"] == "install ok installed" &&
                "Feature" !in fields && name != null && PORT_NAME.matches(name)) {
                installed[name] = fields["Version"].orEmpty()
            }
            fields.clear()
        }
        // Iterate lines rather than copying the full status file into paragraphs.
        for (line in text.lineSequence()) {
            currentCoroutineContext().ensureActive()
            if (line.isBlank()) {
                finishRecord()
            } else if (!line.first().isWhitespace() && ':' in line) {
                fields[line.substringBefore(':')] = line.substringAfter(':').trim()
            }
        }
        finishRecord()
    }

    private fun requireManifestLocation(project: File, manifest: File) {
        if (manifest.canonicalFile.parentFile != project.canonicalFile) {
            throw VcpkgException(Strings.vcpkg_invalid_path)
        }
    }

    private fun resolve(project: File, value: String): File = File(value).let { if (it.isAbsolute) it else File(project, value) }

    internal companion object {
        fun portFromJson(name: String, json: JSONObject): VcpkgPort {
            val description = json.opt("description").let { value ->
                if (value is JSONArray) (0 until value.length()).joinToString(" ") { value.optString(it) }
                else if (value == null || value == JSONObject.NULL) "" else value.toString()
            }
            return VcpkgPort(name, listOf("version", "version-semver", "version-date", "version-string")
                .firstNotNullOfOrNull { json.optString(it).takeIf(String::isNotBlank) }.orEmpty(), description, json.optString("supports"))
        }
        private val manifestMutex = Mutex()
        val PORT_NAME = Regex("[a-z0-9]+(?:-[a-z0-9]+)*")
        const val MAX_MANIFEST_BYTES = 1024 * 1024
        const val MAX_STATUS_BYTES = 16 * 1024 * 1024
    }
}
