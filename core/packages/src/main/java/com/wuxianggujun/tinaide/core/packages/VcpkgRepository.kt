package com.wuxianggujun.tinaide.core.packages

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
data class VcpkgProjectCatalog(
    val settings: VcpkgProjectSettings,
    val ports: List<VcpkgPort>,
    val declared: Set<String>,
    val installed: Map<String, String>,
    val cliPresent: Boolean,
    val skippedPorts: Int = 0,
)

class VcpkgRepository {
    private val manifestMutex = Mutex()

    suspend fun read(project: File, androidAbi: String): VcpkgProjectCatalog = withContext(Dispatchers.IO) {
        val settings = VcpkgProjectSettings.from(ProjectMetadataStore.read(project)?.normalizedNativeCMakeArgs().orEmpty())
        val root = resolve(project, settings.root)
        var skippedPorts = 0
        val ports = if (settings.root.isBlank()) emptyList() else File(root, "ports").listFiles().orEmpty()
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
                    Timber.tag("VcpkgRepository").w(error, "Ignoring unreadable vcpkg port: %s", directory.name)
                    return@mapNotNull null
                }
                val description = json.opt("description").let { value ->
                    if (value is JSONArray) (0 until value.length()).joinToString(" ") { value.optString(it) }
                    else if (value == null || value == JSONObject.NULL) "" else value.toString()
                }
                VcpkgPort(directory.name,
                    listOf("version", "version-semver", "version-date", "version-string").firstNotNullOfOrNull {
                        json.optString(it).takeIf(String::isNotBlank)
                    }.orEmpty(), description, json.optString("supports"))
            }.sortedBy { it.name }
        val manifest = File(project, "vcpkg.json")
        val declared = if (manifest.isFile) dependencyNames(readJson(manifest)) else emptySet()
        val installedRoot = when {
            settings.installedDir.isNotBlank() -> resolve(project, settings.installedDir)
            settings.mode == VcpkgProjectSettings.MANIFEST -> File(project, "vcpkg_installed")
            else -> File(root, "installed")
        }
        val installed = linkedMapOf<String, String>()
        val status = File(installedRoot, "vcpkg/status")
        if (settings.enabled && status.isFile) {
            if (status.length() > MAX_STATUS_BYTES) throw VcpkgException(Strings.vcpkg_invalid_manifest)
            status.readText(Charsets.UTF_8).split(Regex("\\r?\\n\\s*\\r?\\n")).forEach { paragraph ->
                val fields = paragraph.lineSequence().filter { ':' in it }.associate {
                    it.substringBefore(':') to it.substringAfter(':').trim()
                }
                if (fields["Architecture"] == settings.targetTriplet(androidAbi) && fields["Status"] == "install ok installed" && "Feature" !in fields) {
                    fields["Package"]?.let { installed[it] = fields["Version"].orEmpty() }
                }
            }
        }
        // Exported/prebuilt roots may not contain ports. Keep installed and declared entries manageable.
        val catalog = ports.associateByTo(linkedMapOf()) { it.name }
        (declared + installed.keys).filter { PORT_NAME.matches(it) }.forEach { name ->
            catalog.putIfAbsent(name, VcpkgPort(name, installed[name].orEmpty(), "", ""))
        }
        VcpkgProjectCatalog(settings, catalog.values.sortedBy { it.name }, declared, installed,
            settings.root.isNotBlank() && File(root, "vcpkg").isFile, skippedPorts)
    }

    suspend fun setDependency(project: File, portName: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        manifestMutex.withLock { editDependency(project, portName, enabled) }
    }

    private fun editDependency(project: File, portName: String, enabled: Boolean) {
        if (!PORT_NAME.matches(portName)) throw VcpkgException(Strings.vcpkg_invalid_manifest)
        val manifest = File(project, "vcpkg.json")
        if (manifest.canonicalFile.parentFile != project.canonicalFile) throw VcpkgException(Strings.vcpkg_invalid_path)
        if (manifest.exists() && (!manifest.isFile || manifest.length() > MAX_MANIFEST_BYTES)) throw VcpkgException(Strings.vcpkg_invalid_manifest)
        val original = manifest.takeIf { it.isFile }?.readBytes()
        if (original != null && original.size > MAX_MANIFEST_BYTES) throw VcpkgException(Strings.vcpkg_invalid_manifest)
        val json = try {
            original?.let { JSONObject(String(it, Charsets.UTF_8)) } ?: JSONObject()
        } catch (error: org.json.JSONException) {
            throw VcpkgException(Strings.vcpkg_invalid_manifest, error)
        }
        dependencyNames(json) // Refuse to silently overwrite a malformed dependency declaration.
        val dependencies = json.optJSONArray("dependencies") ?: JSONArray()
        val existing = (0 until dependencies.length()).map { dependencies.get(it) }
        val name: (Any) -> String = { if (it is JSONObject) it.optString("name") else it.toString() }
        val next = existing.filterNot { !enabled && name(it) == portName }.toMutableList()
        if (enabled && existing.none { name(it) == portName }) next += portName
        json.put("dependencies", JSONArray(next))
        val temporary = File.createTempFile(".vcpkg-manifest-", ".json", project)
        try {
            temporary.writeText(json.toString(2) + "\n", Charsets.UTF_8)
            val current = manifest.takeIf { it.isFile }?.readBytes()
            if (!(if (original == null) current == null else current != null && original.contentEquals(current))) {
                throw VcpkgException(Strings.vcpkg_manifest_changed)
            }
            try {
                Files.move(temporary.toPath(), manifest.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), manifest.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
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

    private fun readJson(file: File): JSONObject {
        if (file.length() > MAX_MANIFEST_BYTES) throw VcpkgException(Strings.vcpkg_invalid_manifest)
        return try {
            JSONObject(file.readText(Charsets.UTF_8))
        } catch (error: org.json.JSONException) {
            throw VcpkgException(Strings.vcpkg_invalid_manifest, error)
        }
    }

    private fun resolve(project: File, value: String): File = File(value).let { if (it.isAbsolute) it else File(project, value) }

    private companion object {
        val PORT_NAME = Regex("[a-z0-9]+(?:-[a-z0-9]+)*")
        const val MAX_MANIFEST_BYTES = 1024 * 1024
        const val MAX_STATUS_BYTES = 16 * 1024 * 1024
    }
}
