package com.wuxianggujun.tinaide.ui.compose.screens.settings.sections

import android.app.Application
import androidx.compose.runtime.saveable.SaverScope
import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.logging.LogExportUtils
import com.wuxianggujun.tinaide.core.network.ApiResult
import com.wuxianggujun.tinaide.core.network.aegis.AegisFeedback
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockkObject
import io.mockk.unmockkObject
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class FeedbackDiagnosticsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun restoredChoiceControlsTheSubmittedFileAndDeviceFields() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val cache = importedArchiveCache(context).apply { mkdirs() }
        val file = File.createTempFile("archive-", ".zip", cache).apply { writeBytes(byteArrayOf(80, 75, 3, 4)) }
        val selected = FeedbackDiagnostics.Selected(file.name, "测试.zip")
        for (choice in listOf(FeedbackDiagnostics.None, selected)) {
            val saved = with(FeedbackDiagnostics.Saver) {
                object : SaverScope {
                    override fun canBeSaved(value: Any) = true
                }.save(choice)
            }
            val restored = requireNotNull(FeedbackDiagnostics.Saver.restore(requireNotNull(saved)))
            var captured: AegisFeedback.Draft? = null
            val result = submitFeedback(context, "fixture", "", restored) { _, draft ->
                captured = draft
                ApiResult.Success(AegisFeedback.Result("fixture"))
            }
            assertThat(result.isSuccess).isTrue()
            assertThat(requireNotNull(captured).device).isNull()
            assertThat(requireNotNull(captured).attachment).isEqualTo(if (choice == selected) file else null)
            assertThat(requireNotNull(captured).attachmentDisplayName).isEqualTo(if (choice == selected) "测试.zip" else null)
        }
        assertThat(file.delete()).isTrue()
        val missing = submitFeedback(context, "fixture", "", selected) { _, _ -> error("missing file reached transport") }
        assertThat(missing.isError).isTrue()
        assertThat(FeedbackDiagnostics.Selected("../outside.zip", "bad").resolve(cache)).isNull()
        assertThat(FeedbackDiagnostics.restore(listOf("obsolete"))).isEqualTo(FeedbackDiagnostics.None)
    }

    @Test fun onlyExplicitAutomaticConsentGeneratesDeviceDiagnosticsAndAlwaysCleansItsBundle() = runTest {
        val context = RuntimeEnvironment.getApplication()
        mockkObject(LogExportUtils)
        try {
            for (fail in listOf(false, true)) {
                val file = temporary.newFile().apply { writeText("fixture", Charsets.UTF_8) }
                coEvery { LogExportUtils.exportLogs(any(), any(), any()) } returns file
                val result = submitFeedback(context, "fixture", "", FeedbackDiagnostics.Automatic) { _, draft ->
                    assertThat(draft.device).isNotEmpty()
                    assertThat(draft.attachment).isEqualTo(file)
                    assertThat(draft.attachmentDisplayName).isNull()
                    if (fail) ApiResult.NetworkError("fixture offline") else ApiResult.Success(AegisFeedback.Result("fixture"))
                }
                assertThat(result.isSuccess).isEqualTo(!fail)
                assertThat(file.exists()).isFalse()
            }
            val cancelled = temporary.newFile().apply { writeText("fixture", Charsets.UTF_8) }
            coEvery { LogExportUtils.exportLogs(any(), any(), any()) } returns cancelled
            val failure = runCatching {
                submitFeedback(context, "fixture", "", FeedbackDiagnostics.Automatic) { _, _ ->
                    throw CancellationException("fixture cancelled")
                }
            }.exceptionOrNull()
            assertThat(failure).isInstanceOf(CancellationException::class.java)
            assertThat(cancelled.exists()).isFalse()
            coVerify(exactly = 3) { LogExportUtils.exportLogs(any(), any(), any()) }
        } finally {
            unmockkObject(LogExportUtils)
        }
    }

    @Test fun failedSelectedSubmissionRetainsItsAttachmentAndNeverGeneratesOtherLogs() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val file = File.createTempFile("archive-", ".zip", importedArchiveCache(context).apply { mkdirs() })
            .apply { writeBytes(byteArrayOf(80, 75, 3, 4)) }
        val choice = FeedbackDiagnostics.Selected(file.name, "selected.zip")
        mockkObject(LogExportUtils)
        try {
            assertThat(submitFeedback(context, "fixture", "", choice) { _, _ ->
                ApiResult.NetworkError("fixture offline")
            }.isSuccess).isFalse()
            assertThat(file.isFile).isTrue()
            file.delete()
            assertThat(submitFeedback(context, "fixture", "", choice) { _, _ -> error("Unexpected send") }.isError).isTrue()
            coVerify(exactly = 0) { LogExportUtils.exportLogs(any(), any(), any()) }
        } finally {
            unmockkObject(LogExportUtils)
            choice.remove(importedArchiveCache(context))
        }
    }

    @Test fun removalOwnsOnlyTheCacheKeyEvenWhenTheAttachmentBecomesInvalid() {
        val cache = temporary.newFolder()
        val file = File.createTempFile("archive-", ".zip", cache)
        RandomAccessFile(file, "rw").use { it.setLength(LOG_ARCHIVE_MAX_BYTES + 1) }
        val choice = FeedbackDiagnostics.Selected(file.name, "selected.zip")
        assertThat(choice.resolve(cache)).isNull()
        choice.remove(cache)
        assertThat(file.exists()).isFalse()
        val outside = temporary.newFile("outside.zip").apply { writeText("fixture", Charsets.UTF_8) }
        FeedbackDiagnostics.Selected("../outside.zip", "outside.zip").remove(cache)
        assertThat(outside.isFile).isTrue()
    }
}
