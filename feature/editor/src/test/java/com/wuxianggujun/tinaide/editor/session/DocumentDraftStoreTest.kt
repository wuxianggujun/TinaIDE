package com.wuxianggujun.tinaide.editor.session

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DocumentDraftStoreTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun corruptRecordIsRetainedAndNeverReturnedAsDocumentText() {
        val root = temp.newFolder("drafts")
        val source = temp.newFile("main.cpp")
        val store = DocumentDraftStore(root)
        store.write(source, draft(source))
        val record = root.listFiles()!!.single()
        record.writeText("{broken", Charsets.UTF_8)
        assertThrows(Exception::class.java) { store.read(source) }
        assertThat(record.readText()).isEqualTo("{broken")
    }

    @Test fun recordsAreKeyedByFullPathAndPreserveUnicode() {
        val root = temp.newFolder("drafts")
        val source = temp.newFile("main.cpp")
        val other = File(temp.newFolder("other"), "main.cpp")
        val store = DocumentDraftStore(root)
        store.write(source, draft(source))
        assertThat(DocumentDraftStore(root).read(source)?.text).isEqualTo("中文🙂\n")
        assertThat(store.read(other)).isNull()
        assertThat(root.listFiles()!!.single().name).matches("[a-f0-9]{64}\\.json")
    }

    @Test fun oversizedReplacementKeepsPreviousDraft() {
        val root = temp.newFolder("drafts")
        val source = temp.newFile("main.cpp")
        val store = DocumentDraftStore(root)
        store.write(source, draft(source))
        assertThrows(IllegalArgumentException::class.java) {
            store.write(source, draft(source).copy(text = "x".repeat(DocumentDraftStore.MAX_TEXT_CHARS + 1)))
        }
        assertThat(store.read(source)?.text).isEqualTo("中文🙂\n")
    }

    private fun draft(source: File) = DocumentDraftStore.Draft(
        sourcePath = source.canonicalPath, text = "中文🙂\n", charsetName = "UTF-8",
        baselineLength = 0, baselineHash = 0, cursorLine = 0, cursorColumn = 0, scrollX = 0, scrollY = 0
    )
}
