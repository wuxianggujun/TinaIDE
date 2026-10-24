package com.wuxianggujun.tinaide.editor.session

import com.wuxianggujun.tinaide.editor.io.AtomicTextFileWriter
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Private, bounded recovery records. Never writes to the source document. */
internal class DocumentDraftStore(private val root: File) {
    @Serializable
    data class Draft(
        val schemaVersion: Int = 1,
        val sourcePath: String,
        val text: String,
        val charsetName: String,
        val baselineLength: Int,
        val baselineHash: Long,
        val cursorLine: Int,
        val cursorColumn: Int,
        val scrollX: Int,
        val scrollY: Int,
    )

    fun read(source: File): Draft? {
        val record = recordFor(source)
        if (!record.exists()) return null
        require(record.length() <= MAX_RECORD_BYTES) { "Recovery record exceeds size limit" }
        val draft = Json.decodeFromString<Draft>(record.readText(Charsets.UTF_8))
        require(draft.schemaVersion == 1 && draft.sourcePath == source.canonicalPath) { "Invalid recovery record" }
        require(draft.text.length <= MAX_TEXT_CHARS && draft.baselineLength >= 0) { "Invalid recovery content" }
        require(listOf(draft.cursorLine, draft.cursorColumn, draft.scrollX, draft.scrollY).all { it >= 0 }) {
            "Invalid recovery view state"
        }
        return draft
    }

    fun write(source: File, draft: Draft) {
        require(draft.sourcePath == source.canonicalPath && draft.text.length <= MAX_TEXT_CHARS) {
            "Recovery content exceeds size limit"
        }
        val serialized = Json.encodeToString(draft)
        require(serialized.toByteArray(Charsets.UTF_8).size <= MAX_RECORD_BYTES) { "Recovery record exceeds size limit" }
        if (!root.isDirectory && !root.mkdirs()) throw IOException("Cannot create recovery directory")
        AtomicTextFileWriter.write(recordFor(source), serialized, Charsets.UTF_8)
    }

    fun delete(source: File) {
        val record = recordFor(source)
        if (record.exists() && !record.delete()) throw IOException("Cannot remove recovery record")
    }

    private fun recordFor(source: File): File {
        val key = MessageDigest.getInstance("SHA-256")
            .digest(source.canonicalPath.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(root, "$key.json")
    }

    companion object {
        const val MAX_TEXT_CHARS = 10 * 1024 * 1024
        private const val MAX_RECORD_BYTES = 64L * 1024 * 1024
    }
}
