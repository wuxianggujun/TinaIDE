package com.wuxianggujun.tinaide.ui.compose.state.editor

import android.content.Context
import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

internal class SplitEditorSessionStorage(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(projectPath: String): SplitEditorStateSnapshot? {
        return synchronized(STATE_LOCK) {
            val raw = prefs.getString(keyForProject(projectPath), null) ?: return@synchronized null
            runCatching { decodeSnapshot(JSONObject(raw)) }.getOrNull()
        }
    }

    fun save(projectPath: String, snapshot: SplitEditorStateSnapshot) {
        synchronized(STATE_LOCK) {
            if (!File(projectPath).isDirectory) return
            prefs.edit()
                .putString(keyForProject(projectPath), encodeSnapshot(snapshot.normalized()).toString())
                .apply()
        }
    }

    fun clear(projectPath: String) {
        synchronized(STATE_LOCK) {
            prefs.edit()
                .remove(keyForProject(projectPath))
                .apply()
        }
    }

    fun migrateProjectPath(oldProjectPath: String, newProjectPath: String) {
        require(oldProjectPath.isNotBlank() && newProjectPath.isNotBlank())
        if (oldProjectPath == newProjectPath) return

        synchronized(STATE_LOCK) {
            val oldKey = keyForProject(oldProjectPath)
            val raw = prefs.getString(oldKey, null) ?: return
            val migrated = retargetSnapshotPaths(
                decodeSnapshot(JSONObject(raw)),
                oldProjectPath,
                newProjectPath
            )
            check(
                prefs.edit()
                    .remove(oldKey)
                    .putString(keyForProject(newProjectPath), encodeSnapshot(migrated).toString())
                    .commit()
            ) { "Failed to migrate split editor state" }
        }
    }

    private fun encodeSnapshot(snapshot: SplitEditorStateSnapshot): JSONObject = JSONObject()
        .put(KEY_VERSION, SNAPSHOT_VERSION)
        .put(KEY_ENABLED, snapshot.isEnabled)
        .put(KEY_FOCUSED_PANE, snapshot.focusedPane.name)
        .put(KEY_LAYOUT, snapshot.layout.name)
        .put(KEY_PRIMARY_RATIO, snapshot.primaryRatio.toDouble())
        .put(KEY_TAB_ASSIGNMENTS, encodeAssignments(snapshot.tabPaneAssignments))
        .put(KEY_MIRRORED_PATHS, encodePanePathSets(snapshot.mirroredFilePathsByPane))
        .put(KEY_ACTIVE_PATHS, encodeActivePaths(snapshot.activeFilePathByPane))

    private fun decodeSnapshot(json: JSONObject): SplitEditorStateSnapshot = SplitEditorStateSnapshot(
        isEnabled = json.optBoolean(KEY_ENABLED, false),
        focusedPane = parsePane(json.optString(KEY_FOCUSED_PANE)),
        layout = parseLayout(json.optString(KEY_LAYOUT)),
        primaryRatio = json.optDouble(
            KEY_PRIMARY_RATIO,
            DEFAULT_PRIMARY_RATIO.toDouble()
        ).toFloat(),
        tabPaneAssignments = decodeAssignments(json.optJSONArray(KEY_TAB_ASSIGNMENTS)),
        mirroredFilePathsByPane = decodePanePathSets(json.optJSONArray(KEY_MIRRORED_PATHS)),
        activeFilePathByPane = decodeActivePaths(json.optJSONArray(KEY_ACTIVE_PATHS))
    ).normalized()

    private fun encodeAssignments(assignments: Map<String, EditorPaneId>): JSONArray {
        val array = JSONArray()
        assignments.forEach { (path, pane) ->
            array.put(
                JSONObject()
                    .put(KEY_PATH, path)
                    .put(KEY_PANE, pane.name)
            )
        }
        return array
    }

    private fun decodeAssignments(array: JSONArray?): Map<String, EditorPaneId> {
        if (array == null) return emptyMap()
        val result = linkedMapOf<String, EditorPaneId>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val path = item.optString(KEY_PATH).takeIf { it.isNotBlank() } ?: continue
            result[path] = parsePane(item.optString(KEY_PANE))
        }
        return result
    }

    private fun encodePanePathSets(pathsByPane: Map<EditorPaneId, Set<String>>): JSONArray {
        val array = JSONArray()
        pathsByPane.forEach { (pane, paths) ->
            array.put(
                JSONObject()
                    .put(KEY_PANE, pane.name)
                    .put(KEY_PATHS, JSONArray(paths.toList()))
            )
        }
        return array
    }

    private fun decodePanePathSets(array: JSONArray?): Map<EditorPaneId, Set<String>> {
        if (array == null) return emptyMap()
        val result = linkedMapOf<EditorPaneId, Set<String>>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val paths = item.optJSONArray(KEY_PATHS) ?: continue
            val decodedPaths = linkedSetOf<String>()
            for (pathIndex in 0 until paths.length()) {
                paths.optString(pathIndex).takeIf { it.isNotBlank() }?.let(decodedPaths::add)
            }
            if (decodedPaths.isNotEmpty()) {
                result[parsePane(item.optString(KEY_PANE))] = decodedPaths
            }
        }
        return result
    }

    private fun encodeActivePaths(pathsByPane: Map<EditorPaneId, String>): JSONArray {
        val array = JSONArray()
        pathsByPane.forEach { (pane, path) ->
            array.put(
                JSONObject()
                    .put(KEY_PANE, pane.name)
                    .put(KEY_PATH, path)
            )
        }
        return array
    }

    private fun decodeActivePaths(array: JSONArray?): Map<EditorPaneId, String> {
        if (array == null) return emptyMap()
        val result = linkedMapOf<EditorPaneId, String>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val path = item.optString(KEY_PATH).takeIf { it.isNotBlank() } ?: continue
            result[parsePane(item.optString(KEY_PANE))] = path
        }
        return result
    }

    private fun retargetSnapshotPaths(
        snapshot: SplitEditorStateSnapshot,
        oldProjectPath: String,
        newProjectPath: String,
    ): SplitEditorStateSnapshot = snapshot.copy(
        tabPaneAssignments = snapshot.tabPaneAssignments.mapKeys { (path, _) ->
            retargetPath(path, oldProjectPath, newProjectPath)
        },
        mirroredFilePathsByPane = snapshot.mirroredFilePathsByPane.mapValues { (_, paths) ->
            paths.mapTo(linkedSetOf()) { path -> retargetPath(path, oldProjectPath, newProjectPath) }
        },
        activeFilePathByPane = snapshot.activeFilePathByPane.mapValues { (_, path) ->
            retargetPath(path, oldProjectPath, newProjectPath)
        }
    ).normalized()

    private fun retargetPath(path: String, oldProjectPath: String, newProjectPath: String): String {
        val oldRoot = oldProjectPath.replace('\\', '/').trimEnd('/')
        val normalizedPath = path.replace('\\', '/')
        return when {
            normalizedPath == oldRoot -> newProjectPath
            normalizedPath.startsWith("$oldRoot/") -> {
                newProjectPath.trimEnd('/', '\\') + normalizedPath.substring(oldRoot.length)
            }
            else -> path
        }
    }

    private fun parsePane(value: String?): EditorPaneId = runCatching {
        EditorPaneId.valueOf(value.orEmpty())
    }.getOrDefault(EditorPaneId.PRIMARY)

    private fun parseLayout(value: String?): SplitEditorLayout = runCatching {
        SplitEditorLayout.valueOf(value.orEmpty())
    }.getOrDefault(SplitEditorLayout.HORIZONTAL)

    private fun keyForProject(projectPath: String): String = "$KEY_PROJECT_PREFIX${projectPath.sha256Hex()}"

    private fun String.sha256Hex(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private companion object {
        val STATE_LOCK = Any()
        const val PREFS_NAME = "tinaide_editor_split_state"
        const val SNAPSHOT_VERSION = 1
        const val DEFAULT_PRIMARY_RATIO = 0.5f
        const val KEY_PROJECT_PREFIX = "project:"
        const val KEY_VERSION = "version"
        const val KEY_ENABLED = "enabled"
        const val KEY_FOCUSED_PANE = "focusedPane"
        const val KEY_LAYOUT = "layout"
        const val KEY_PRIMARY_RATIO = "primaryRatio"
        const val KEY_TAB_ASSIGNMENTS = "tabPaneAssignments"
        const val KEY_MIRRORED_PATHS = "mirroredFilePathsByPane"
        const val KEY_ACTIVE_PATHS = "activeFilePathByPane"
        const val KEY_PATH = "path"
        const val KEY_PATHS = "paths"
        const val KEY_PANE = "pane"
    }
}
