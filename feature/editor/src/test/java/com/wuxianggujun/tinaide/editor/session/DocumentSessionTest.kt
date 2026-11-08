package com.wuxianggujun.tinaide.editor.session

import android.os.FileObserver
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class DocumentSessionTest {

    @Test
    fun deletedFileEvent_shouldMarkExternalModification() = runTest {
        val file = Files.createTempFile("document-session-deleted", ".txt").toFile()
        val session = createSession(file, this)
        try {
            file.delete()

            session.handleFileEvent(FileObserver.DELETE, file.name)
            advancePastExternalEventDebounce()

            assertThat(session.state.value.hasExternalModification).isTrue()
        } finally {
            session.stopFileWatcher()
            file.delete()
        }
    }

    @Test
    fun reloadFromDisk_shouldRefreshUndoRedoAndCharset() = runTest {
        val gbk = Charset.forName("GBK")
        val file = Files.createTempFile("document-session-reload", ".txt").toFile()
        file.writeText("中文内容", gbk)
        val session = createSession(file, this)
        val binding = FakeEditorBinding(
            text = "stale",
            canUndo = true,
            canRedo = true
        )

        try {
            session.attachEditor(binding)

            val reloaded = session.reloadFromDisk()

            assertThat(reloaded).isTrue()
            assertThat(binding.readText()).isEqualTo("中文内容")
            assertThat(session.state.value.canUndo).isFalse()
            assertThat(session.state.value.canRedo).isFalse()
            assertThat(session.state.value.charsetName).isEqualTo(gbk.name())
        } finally {
            session.stopFileWatcher()
            file.delete()
        }
    }

    @Test
    fun reloadFromDisk_shouldUseStreamingBindingWhenAvailable() = runTest {
        val file = Files.createTempFile("document-session-stream-reload", ".txt").toFile()
        file.writeText("streamed content")
        val session = createSession(file, this)
        val binding = FakeEditorBinding(
            text = "stale",
            canUndo = true,
            canRedo = true,
            supportsStreamingReload = true,
        )

        try {
            session.attachEditor(binding)

            assertThat(session.reloadFromDisk()).isTrue()
            assertThat(binding.readText()).isEqualTo("streamed content")
            assertThat(binding.streamingReloadCalls).isEqualTo(1)
            assertThat(binding.setTextCalls).isEqualTo(0)
        } finally {
            session.stopFileWatcher()
            file.delete()
        }
    }

    @Test
    fun detachEditor_shouldNotMaterializeCleanDocument() = runTest {
        val file = Files.createTempFile("document-session-clean-detach", ".txt").toFile()
        file.writeText("unchanged")
        val session = createSession(file, this)
        val binding = FakeEditorBinding(
            text = "unchanged",
            canUndo = false,
            canRedo = false,
            supportsFingerprint = true,
        )

        try {
            session.attachEditor(binding)
            session.markEditorSnapshotClean()
            val readsBeforeDetach = binding.readTextCalls

            session.detachEditor(binding)

            assertThat(binding.readTextCalls).isEqualTo(readsBeforeDetach)
            assertThat(session.detachedEditorSnapshot()).isNull()
            assertThat(session.state.value.isDirty).isFalse()
        } finally {
            session.stopFileWatcher()
            file.delete()
        }
    }

    @Test
    fun save_shouldPreserveDetectedCharset() = runTest {
        val gbk = Charset.forName("GBK")
        val file = Files.createTempFile("document-session-save", ".txt").toFile()
        file.writeText("旧内容", gbk)
        val session = createSession(file, this)
        val binding = FakeEditorBinding(
            text = "新的中文内容",
            canUndo = true,
            canRedo = false
        )

        try {
            session.attachEditor(binding)

            val result = session.save(SaveReason.MANUAL)

            assertThat(result).isInstanceOf(SaveResult.Success::class.java)
            assertThat(file.readBytes()).isEqualTo("新的中文内容".toByteArray(gbk))
            assertThat(session.state.value.charsetName).isEqualTo(gbk.name())
        } finally {
            session.stopFileWatcher()
            file.delete()
        }
    }

    @Test
    fun save_shouldPreserveUnsavedContentWhenGbkCannotEncodeIt() = runTest {
        val gbk = Charset.forName("GBK")
        val file = Files.createTempFile("document-session-encoding", ".txt").toFile()
        file.writeText("旧内容", gbk)
        val original = file.readBytes()
        val session = createSession(file, this)
        val binding = FakeEditorBinding(text = "旧内容", canUndo = false, canRedo = false)
        try {
            session.attachEditor(binding)
            binding.setText("中文🙂")
            session.notifyEditorContentChanged(canUndo = true, canRedo = false, changeCausedByUndoManager = false)
            val result = session.save(SaveReason.MANUAL)
            assertThat(result).isInstanceOf(SaveResult.Failure::class.java)
            assertThat(file.readBytes()).isEqualTo(original)
            assertThat(session.state.value.isDirty).isTrue()
            assertThat(session.state.value.isSaving).isFalse()
            assertThat(binding.readText()).isEqualTo("中文🙂")
        } finally {
            session.stopFileWatcher()
            file.delete()
        }
    }

    @Test
    fun delayedDeleteEventFromAtomicSave_shouldNotReportExternalModification() = runTest {
        val file = Files.createTempFile("document-session-atomic-save", ".txt").toFile()
        file.writeText("old")
        val session = createSession(file, this)
        val binding = FakeEditorBinding(
            text = "new",
            canUndo = true,
            canRedo = false,
        )

        try {
            session.attachEditor(binding)
            assertThat(session.save(SaveReason.MANUAL)).isInstanceOf(SaveResult.Success::class.java)

            session.handleFileEvent(FileObserver.DELETE, file.name)
            advancePastExternalEventDebounce()

            assertThat(file.exists()).isTrue()
            assertThat(session.state.value.hasExternalModification).isFalse()
        } finally {
            session.stopFileWatcher()
            file.delete()
        }
    }

    @Test
    fun externalAtomicReplacementWithSameSizeAndTimestamp_shouldReportModification() = runTest {
        val file = Files.createTempFile("document-session-external-replace", ".txt").toFile()
        file.writeText("old")
        val session = createSession(file, this)
        val binding = FakeEditorBinding(
            text = "new",
            canUndo = true,
            canRedo = false,
        )

        try {
            session.attachEditor(binding)
            assertThat(session.save(SaveReason.MANUAL)).isInstanceOf(SaveResult.Success::class.java)
            val savedAttributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
            val savedKey = savedAttributes.fileKey() ?: return@runTest
            val replacement = Files.createTempFile(file.toPath().parent, ".external-", ".tmp")
            Files.write(replacement, "ext".toByteArray(Charsets.UTF_8))
            Files.setLastModifiedTime(replacement, FileTime.fromMillis(savedAttributes.lastModifiedTime().toMillis()))
            Files.move(replacement, file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            val replacementKey = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java).fileKey()
                ?: return@runTest
            if (replacementKey == savedKey) return@runTest

            session.handleFileEvent(FileObserver.DELETE, file.name)
            advancePastExternalEventDebounce()

            assertThat(session.state.value.hasExternalModification).isTrue()
        } finally {
            session.stopFileWatcher()
            file.delete()
        }
    }

    @Test
    fun ownSaveCloseWriteEventDeliveredLate_shouldNotReportExternalModification() = runTest {
        val file = Files.createTempFile("document-session-late-own-event", ".txt").toFile()
        file.writeText("old")
        val session = createSession(file, this)
        val binding = FakeEditorBinding(
            text = "new",
            canUndo = true,
            canRedo = false,
        )

        try {
            session.attachEditor(binding)
            assertThat(session.save(SaveReason.MANUAL)).isInstanceOf(SaveResult.Success::class.java)

            // 模拟进程冻结/事件线程繁忙：自家保存的 CLOSE_WRITE 事件在任意时间后被补发，
            // 磁盘签名与最近一次内部写入一致，不应判为外部修改。
            session.handleFileEvent(FileObserver.CLOSE_WRITE, file.name)
            advancePastExternalEventDebounce()

            assertThat(session.state.value.hasExternalModification).isFalse()

            binding.setText("second edit")
            session.notifyEditorContentChanged(canUndo = true, canRedo = false)
            assertThat(session.save(SaveReason.MANUAL)).isInstanceOf(SaveResult.Success::class.java)
        } finally {
            session.stopFileWatcher()
            file.delete()
        }
    }

    @Test
    fun externalRewriteWithIdenticalContent_shouldNotReportExternalModification() = runTest {
        val file = Files.createTempFile("document-session-identical-rewrite", ".txt").toFile()
        file.writeText("same")
        val session = createSession(file, this)
        val binding = FakeEditorBinding(
            text = "same",
            canUndo = false,
            canRedo = false,
        )

        try {
            session.attachEditor(binding)
            session.markEditorSnapshotClean()

            // 备份/同步类工具保留内容重写磁盘：内容与最近一次保存一致，
            // 只有元数据（mtime）变化，不应弹出冲突。
            file.writeText("same")
            Files.setLastModifiedTime(file.toPath(), FileTime.fromMillis(System.currentTimeMillis() + 60_000L))
            session.handleFileEvent(FileObserver.CLOSE_WRITE, file.name)
            advancePastExternalEventDebounce()

            assertThat(session.state.value.hasExternalModification).isFalse()

            binding.setText("edited")
            session.notifyEditorContentChanged(canUndo = true, canRedo = false)
            assertThat(session.save(SaveReason.MANUAL)).isInstanceOf(SaveResult.Success::class.java)
        } finally {
            session.stopFileWatcher()
            file.delete()
        }
    }

    @Test
    fun externalRewriteWithDifferentContent_shouldReportModification() = runTest {
        val file = Files.createTempFile("document-session-external-content", ".txt").toFile()
        file.writeText("old")
        val session = createSession(file, this)
        val binding = FakeEditorBinding(
            text = "new",
            canUndo = true,
            canRedo = false,
        )

        try {
            session.attachEditor(binding)
            assertThat(session.save(SaveReason.MANUAL)).isInstanceOf(SaveResult.Success::class.java)

            file.writeText("external")
            session.handleFileEvent(FileObserver.CLOSE_WRITE, file.name)
            advancePastExternalEventDebounce()

            assertThat(session.state.value.hasExternalModification).isTrue()
        } finally {
            session.stopFileWatcher()
            file.delete()
        }
    }

    @Test
    fun suspiciousEventWithoutBaseline_shouldMarkExternalModification() = runTest {
        val file = Files.createTempFile("document-session-no-baseline", ".txt").toFile()
        file.writeText("old")
        val session = createSession(file, this)
        try {
            // 编辑器尚未挂载、基线未建立：保持保守策略，直接视为外部修改。
            file.writeText("external")
            session.handleFileEvent(FileObserver.CLOSE_WRITE, file.name)
            advancePastExternalEventDebounce()

            assertThat(session.state.value.hasExternalModification).isTrue()
        } finally {
            session.stopFileWatcher()
            file.delete()
        }
    }

    @Test
    fun metadataOnlyTouchAfterSave_shouldNotBlockSave() = runTest {
        val file = Files.createTempFile("document-session-metadata-touch", ".txt").toFile()
        file.writeText("old")
        val session = createSession(file, this)
        val binding = FakeEditorBinding(
            text = "new",
            canUndo = true,
            canRedo = false,
        )

        try {
            session.attachEditor(binding)
            assertThat(session.save(SaveReason.MANUAL)).isInstanceOf(SaveResult.Success::class.java)

            // 跨 uid 写入不投递事件，只能靠保存前兜底比对；仅元数据变化（touch）
            // 且内容与最近一次保存一致时，不应阻断保存。
            Files.setLastModifiedTime(file.toPath(), FileTime.fromMillis(System.currentTimeMillis() + 60_000L))

            binding.setText("edited")
            session.notifyEditorContentChanged(canUndo = true, canRedo = false)
            val result = session.save(SaveReason.MANUAL)

            assertThat(result).isInstanceOf(SaveResult.Success::class.java)
            assertThat(file.readText()).isEqualTo("edited")
        } finally {
            session.stopFileWatcher()
            file.delete()
        }
    }

    @Test
    fun realExternalContentChangeWithoutEvent_shouldBlockSave() = runTest {
        val file = Files.createTempFile("document-session-external-no-event", ".txt").toFile()
        file.writeText("old")
        val session = createSession(file, this)
        val binding = FakeEditorBinding(
            text = "new",
            canUndo = true,
            canRedo = false,
        )

        try {
            session.attachEditor(binding)
            assertThat(session.save(SaveReason.MANUAL)).isInstanceOf(SaveResult.Success::class.java)

            // 磁盘内容确实变化（跨 uid 写入、无事件）：保存必须被阻断。
            file.writeText("external")

            binding.setText("edited")
            session.notifyEditorContentChanged(canUndo = true, canRedo = false)
            val result = session.save(SaveReason.MANUAL)

            assertThat(result).isInstanceOf(SaveResult.Failure::class.java)
            assertThat(file.readText()).isEqualTo("external")
            assertThat(session.state.value.hasExternalModification).isTrue()
        } finally {
            session.stopFileWatcher()
            file.delete()
        }
    }

    @Test
    fun detachedSnapshot_shouldSaveDirtyTextWithoutActiveEditor() = runTest {
        val file = Files.createTempFile("document-session-detached", ".txt").toFile()
        file.writeText("old")
        val session = createSession(file, this)
        val binding = FakeEditorBinding(
            text = "old",
            canUndo = false,
            canRedo = false,
            viewState = EditorViewState(cursorLine = 3, cursorColumn = 4, scrollX = 16, scrollY = 32)
        )

        try {
            session.attachEditor(binding)
            session.markEditorSnapshotClean()
            binding.setText("changed while tab is alive")
            session.notifyEditorContentChanged(canUndo = true, canRedo = false)

            session.detachEditor(binding)
            val snapshot = session.detachedEditorSnapshot()

            assertThat(snapshot?.text).isEqualTo("changed while tab is alive")
            assertThat(snapshot?.viewState?.cursorLine).isEqualTo(3)
            assertThat(session.state.value.isDirty).isTrue()

            val result = session.save(SaveReason.MANUAL)

            assertThat(result).isInstanceOf(SaveResult.Success::class.java)
            assertThat(file.readText()).isEqualTo("changed while tab is alive")
            assertThat(session.state.value.isDirty).isFalse()
        } finally {
            session.stopFileWatcher()
            file.delete()
        }
    }

    @Test
    fun refreshDirtyStateForSave_shouldRefreshDirtyStateFromActiveBinding() = runTest {
        val file = Files.createTempFile("document-session-dirty-refresh", ".txt").toFile()
        file.writeText("template")
        val session = createSession(file, this)
        val binding = FakeEditorBinding(
            text = "template",
            canUndo = false,
            canRedo = false
        )

        try {
            session.attachEditor(binding)
            session.markEditorSnapshotClean()

            binding.setText("pasted code")

            assertThat(session.state.value.isDirty).isFalse()
            assertThat(session.refreshDirtyStateForSave()).isTrue()
            assertThat(session.state.value.isDirty).isTrue()

            val result = session.save(SaveReason.MANUAL)

            assertThat(result).isInstanceOf(SaveResult.Success::class.java)
            assertThat((result as SaveResult.Success).target)
                .isEqualTo(SaveTarget(tabId = "tab-id", file = file))
            assertThat(file.readText()).isEqualTo("pasted code")
        } finally {
            session.stopFileWatcher()
            file.delete()
        }
    }

    @Test
    fun save_shouldRetrySnapshotReadWhenDocumentChangesDuringCapture() = runTest {
        val file = Files.createTempFile("document-session-racing-save", ".txt").toFile()
        file.writeText("old")
        val session = createSession(file, this)
        val binding = FakeEditorBinding(
            text = "old",
            canUndo = false,
            canRedo = false
        )

        try {
            session.attachEditor(binding)
            session.markEditorSnapshotClean()
            binding.setText("first edit")
            session.notifyEditorContentChanged(canUndo = true, canRedo = false)
            binding.mutateAfterNextRead("second edit")

            val result = session.save(SaveReason.MANUAL)

            assertThat(result).isInstanceOf(SaveResult.Success::class.java)
            assertThat(file.readText()).isEqualTo("second edit")
            assertThat(session.state.value.isDirty).isFalse()
        } finally {
            session.stopFileWatcher()
            file.delete()
        }
    }

    @Test
    fun save_shouldProtectExternalChangesUntilForceOverwrite() = runTest {
        val file = Files.createTempFile("document-session-conflict", ".txt").toFile()
        file.writeText("old")
        val session = createSession(file, this)
        val binding = FakeEditorBinding(
            text = "old",
            canUndo = false,
            canRedo = false
        )

        try {
            session.attachEditor(binding)
            session.markEditorSnapshotClean()
            binding.setText("mine")
            session.notifyEditorContentChanged(canUndo = true, canRedo = false)
            file.writeText("external")
            session.markExternalModification()

            val blocked = session.save(SaveReason.MANUAL)

            assertThat(blocked).isInstanceOf(SaveResult.Failure::class.java)
            assertThat(file.readText()).isEqualTo("external")

            val overwritten = session.forceOverwrite(SaveReason.MANUAL)

            assertThat(overwritten).isInstanceOf(SaveResult.Success::class.java)
            assertThat(file.readText()).isEqualTo("mine")
            assertThat(session.state.value.hasExternalModification).isFalse()
        } finally {
            session.stopFileWatcher()
            file.delete()
        }
    }

    @Test
    fun draftSurvivesNewSessionWithoutChangingSource() = runTest {
        val file = File.createTempFile("recovery", ".cpp").apply { writeText("original", Charsets.UTF_8) }
        val first = createSession(file, this)
        val binding = FakeEditorBinding("original", false, false)
        first.attachEditor(binding)
        first.markEditorSnapshotClean()
        binding.setText("未保存的正文🙂")
        first.notifyEditorContentChanged(false, false)
        first.checkpointDraft()
        first.stopFileWatcher()
        val reopened = createSession(file, this)
        try {
            val draft = reopened.prepareRecovery()
            assertThat(draft?.text).isEqualTo("未保存的正文🙂")
            assertThat(file.readText()).isEqualTo("original")
            assertThat(reopened.state.value.hasRecoveredDraft).isTrue()
            assertThat(reopened.state.value.hasExternalModification).isFalse()
        } finally {
            reopened.discardDraft()
            reopened.stopFileWatcher()
            file.delete()
        }
    }

    @Test
    fun recoveredDraftDetectsDiskChangesAndBlocksSave() = runTest {
        val file = File.createTempFile("recovery", ".cpp").apply { writeText("original") }
        val first = createSession(file, this)
        val binding = FakeEditorBinding("original", false, false)
        first.attachEditor(binding)
        first.markEditorSnapshotClean()
        binding.setText("")
        first.notifyEditorContentChanged(false, false)
        first.checkpointDraft()
        first.stopFileWatcher()
        file.writeText("external")
        val reopened = createSession(file, this)
        try {
            assertThat(reopened.prepareRecovery()?.text).isEmpty()
            assertThat(reopened.state.value.hasExternalModification).isTrue()
            assertThat(reopened.save(SaveReason.MANUAL)).isInstanceOf(SaveResult.Failure::class.java)
            assertThat(file.readText()).isEqualTo("external")
        } finally {
            reopened.discardDraft()
            reopened.stopFileWatcher()
            file.delete()
        }
    }

    @Test
    fun discardedDraftCannotBeResurrectedByDelayedCheckpoint() = runTest {
        val file = File.createTempFile("recovery", ".cpp").apply { writeText("original") }
        val session = createSession(file, this)
        val binding = FakeEditorBinding("original", false, false)
        session.attachEditor(binding)
        session.markEditorSnapshotClean()
        binding.setText("discarded")
        session.notifyEditorContentChanged(false, false)
        session.checkpointDraft()
        assertThat(session.discardDraft()).isTrue()
        session.checkpointDraft()
        session.stopFileWatcher()
        val reopened = createSession(file, this)
        try {
            assertThat(reopened.prepareRecovery()).isNull()
            assertThat(file.readText()).isEqualTo("original")
        } finally {
            reopened.stopFileWatcher()
            file.delete()
        }
    }

    @Test
    fun savingRemovesRecoveryRecord() = runTest {
        val file = File.createTempFile("recovery", ".cpp").apply { writeText("original") }
        val session = createSession(file, this)
        val binding = FakeEditorBinding("original", false, false)
        session.attachEditor(binding)
        session.markEditorSnapshotClean()
        binding.setText("saved")
        session.notifyEditorContentChanged(false, false)
        session.checkpointDraft()
        assertThat(session.save(SaveReason.MANUAL)).isInstanceOf(SaveResult.Success::class.java)
        session.stopFileWatcher()
        val reopened = createSession(file, this)
        try {
            assertThat(reopened.prepareRecovery()).isNull()
            assertThat(file.readText()).isEqualTo("saved")
        } finally {
            reopened.stopFileWatcher()
            file.delete()
        }
    }

    @Test
    fun recoveredDraftRemainsDirtyAfterBindingReplacementAndReloadClearsRecord() = runTest {
        val file = File.createTempFile("recovery", ".cpp").apply { writeText("original") }
        val first = createSession(file, this)
        val initial = FakeEditorBinding("original", false, false)
        first.attachEditor(initial)
        first.markEditorSnapshotClean()
        initial.setText("recovered")
        first.notifyEditorContentChanged(false, false)
        first.checkpointDraft()
        first.stopFileWatcher()
        val reopened = createSession(file, this)
        try {
            val binding = FakeEditorBinding("", false, false)
            reopened.attachEditor(binding)
            val draft = requireNotNull(reopened.prepareRecovery())
            binding.setText(draft.text)
            reopened.markDetachedEditorSnapshotRestored(draft)
            assertThat(reopened.state.value.isDirty).isTrue()
            reopened.notifyEditorContentChanged(false, false)
            assertThat(reopened.state.value.isDirty).isTrue()
            assertThat(reopened.reloadFromDisk()).isTrue()
            assertThat(binding.readText()).isEqualTo("original")
            assertThat(reopened.state.value.hasRecoveredDraft).isFalse()
            assertThat(reopened.state.value.isDirty).isFalse()
            val third = createSession(file, this)
            try { assertThat(third.prepareRecovery()).isNull() } finally { third.stopFileWatcher() }
        } finally {
            reopened.discardDraft()
            reopened.stopFileWatcher()
            file.delete()
        }
    }

    @Test
    fun recoveredConflictRemainsDirtyWhenDetachedAfterUndoToOldBaseline() = runTest {
        val file = File.createTempFile("recovery", ".cpp").apply { writeText("original", Charsets.UTF_8) }
        val first = createSession(file, this)
        val initial = FakeEditorBinding("original", false, false)
        first.attachEditor(initial)
        first.markEditorSnapshotClean()
        initial.setText("draft")
        first.notifyEditorContentChanged(false, false)
        first.checkpointDraft()
        first.stopFileWatcher()
        file.writeText("external", Charsets.UTF_8)
        val reopened = createSession(file, this)
        try {
            val binding = FakeEditorBinding("", false, false)
            reopened.attachEditor(binding)
            val draft = requireNotNull(reopened.prepareRecovery())
            binding.setText(draft.text)
            reopened.markDetachedEditorSnapshotRestored(draft)
            binding.setText("original")
            reopened.notifyEditorContentChanged(false, false, changeCausedByUndoManager = true)
            reopened.detachEditor(binding)
            assertThat(reopened.state.value.isDirty).isTrue()
            assertThat(reopened.detachedEditorSnapshot()?.text).isEqualTo("original")
            assertThat(file.readText()).isEqualTo("external")
        } finally {
            reopened.discardDraft()
            reopened.stopFileWatcher()
            file.delete()
        }
    }

    private fun createSession(
        file: File,
        scope: TestScope = TestScope(StandardTestDispatcher())
    ): DocumentSession = DocumentSession(
        context = RuntimeEnvironment.getApplication(),
        tabId = "tab-id",
        file = file,
        coroutineScope = scope,
        verificationDispatcher = StandardTestDispatcher(scope.testScheduler)
    )

    /** 驱动虚拟时间越过外部事件防抖窗口，让校验逻辑执行完毕。 */
    private fun TestScope.advancePastExternalEventDebounce() {
        advanceTimeBy(DocumentSession.EXTERNAL_EVENT_DEBOUNCE_MS)
        runCurrent()
    }

    private class FakeEditorBinding(
        text: String,
        private var canUndo: Boolean,
        private var canRedo: Boolean,
        private val viewState: EditorViewState? = null,
        private val supportsStreamingReload: Boolean = false,
        private val supportsFingerprint: Boolean = false,
    ) : DocumentSession.EditorBinding {
        private var currentText = text
        private var version = 0L
        private var mutationAfterRead: String? = null
        var readTextCalls: Int = 0
            private set
        var setTextCalls: Int = 0
            private set
        var streamingReloadCalls: Int = 0
            private set

        override fun readText(): String {
            readTextCalls++
            val snapshot = currentText
            mutationAfterRead?.let { replacement ->
                mutationAfterRead = null
                currentText = replacement
                version++
            }
            return snapshot
        }

        override fun setText(text: CharSequence) {
            setTextCalls++
            currentText = text.toString()
            version++
            canUndo = false
            canRedo = false
        }

        override fun textLength(): Int = currentText.length

        override fun canUndo(): Boolean = canUndo

        override fun canRedo(): Boolean = canRedo

        override fun undo() = Unit

        override fun redo() = Unit

        override fun currentDocumentVersion(): Long = version

        override fun currentViewState(): EditorViewState? = viewState

        override fun readFingerprintSnapshot(): DocumentSession.FingerprintSnapshot? {
            if (!supportsFingerprint) return null
            var hash = -0x340d631b8c4675d9L
            val prime = 0x100000001b3L
            currentText.forEach { char ->
                hash = hash xor char.code.toLong()
                hash *= prime
            }
            return DocumentSession.FingerprintSnapshot(
                length = currentText.length,
                hash = hash,
                documentVersion = version,
            )
        }

        override suspend fun reloadFromFile(file: File, charset: Charset): Result<Unit>? {
            if (!supportsStreamingReload) return null
            streamingReloadCalls++
            currentText = file.readText(charset)
            version++
            canUndo = false
            canRedo = false
            return Result.success(Unit)
        }

        fun mutateAfterNextRead(text: String) {
            mutationAfterRead = text
        }
    }
}
