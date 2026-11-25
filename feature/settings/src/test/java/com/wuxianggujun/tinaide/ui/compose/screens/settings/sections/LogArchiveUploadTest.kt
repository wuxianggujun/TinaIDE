package com.wuxianggujun.tinaide.ui.compose.screens.settings.sections

import android.app.Application
import android.content.ContentResolver
import android.content.Context
import android.database.MatrixCursor
import android.net.Uri
import android.provider.OpenableColumns
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import timber.log.Timber

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class LogArchiveUploadTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var context: Context
    private lateinit var resolver: ContentResolver
    private var declaredSize: Long? = null

    @Before fun setUp() {
        context = mockk()
        resolver = mockk()
        every { context.cacheDir } returns temporary.root
        every { context.contentResolver } returns resolver
        every { context.getString(any()) } returns "localized import error"
        every { resolver.query(any(), any(), any(), any(), any()) } answers {
            MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply {
                addRow(arrayOf("现场🙂.zip", declaredSize))
            }
        }
    }

    @Test fun concurrentDocumentsWithTheSameNameKeepIndependentOwnedCopies() = runTest {
        val failures = ConcurrentLinkedQueue<Throwable>()
        val diagnosticTree = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                t?.let { failures.add(it) }
            }
        }
        Timber.plant(diagnosticTree)
        try {
            val first = Uri.parse("content://fixture/first")
            val second = Uri.parse("content://fixture/second")
            every { resolver.openInputStream(first) } answers { ByteArrayInputStream(byteArrayOf(80, 75, 3, 4, 1)) }
            every { resolver.openInputStream(second) } answers { ByteArrayInputStream(byteArrayOf(80, 75, 3, 4, 2)) }
            repeat(10) {
                val parent = temporary.newFolder()
                val start = CyclicBarrier(2)
                // Both imports see a fresh parent before either creates its shared cache directory.
                every { context.cacheDir } answers {
                    start.await(5, TimeUnit.SECONDS)
                    parent
                }
                val results = listOf(first, second).map { uri -> async { importLogArchive(context, uri) } }.awaitAll()
                assertWithMessage("Import failures: %s", failures.joinToString { it.stackTraceToString() })
                    .that(results.all { it is LogArchiveImport.Success }).isTrue()
                val imports = results.map { (it as LogArchiveImport.Success).archive }
                assertThat(imports.map { it.file }).containsNoDuplicates()
                assertThat(imports.map { it.displayName }).containsExactly("现场🙂.zip", "现场🙂.zip")
                assertThat(imports[0].file.readBytes().last()).isEqualTo(1.toByte())
                deleteTemporaryArchive(imports[0].file)
                assertThat(imports[1].file.readBytes().last()).isEqualTo(2.toByte())
                assertThat(imports.all { IMPORTED_ARCHIVE_NAME.matches(it.file.name) }).isTrue()
            }
        } finally {
            Timber.uproot(diagnosticTree)
        }
    }

    @Test fun declaredAndActualSizeLimitsDoNotTrustTheDocumentProvider() = runTest {
        val uri = Uri.parse("content://fixture/oversize")
        declaredSize = LOG_ARCHIVE_MAX_BYTES + 1
        assertThat(importLogArchive(context, uri)).isInstanceOf(LogArchiveImport.TooLarge::class.java)
        verify(exactly = 0) { resolver.openInputStream(any()) }
        declaredSize = null
        every { resolver.openInputStream(uri) } returns object : InputStream() {
            override fun read() = 0
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                buffer.fill(0, offset, offset + length)
                return length
            }
        }
        assertThat(importLogArchive(context, uri)).isInstanceOf(LogArchiveImport.TooLarge::class.java)
        assertThat(importedArchiveCache(context).listFiles().orEmpty()).isEmpty()
    }

    @Test fun cancelledCopyPropagatesCancellationAndLeavesNoPartialOrOwnedFile() = runTest {
        val uri = Uri.parse("content://fixture/cancelled")
        val task = async {
            val caller = coroutineContext.job
            every { resolver.openInputStream(uri) } returns object : ByteArrayInputStream(byteArrayOf(80, 75, 3, 4)) {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    caller.cancel(CancellationException("fixture cancelled"))
                    return super.read(buffer, offset, length)
                }
            }
            importLogArchive(context, uri)
        }
        assertThat(runCatching { task.await() }.exceptionOrNull()).isInstanceOf(CancellationException::class.java)
        assertThat(importedArchiveCache(context).listFiles().orEmpty()).isEmpty()
    }

    @Test fun unreadableOrUnsupportedDocumentsHaveLocalizedErrorsAndNoCachedAttachment() = runTest {
        val uri = Uri.parse("content://fixture/broken")
        every { resolver.openInputStream(uri) } throws IOException("private provider detail")
        assertThat((importLogArchive(context, uri) as LogArchiveImport.Failed).message).isEqualTo("localized import error")
        every { resolver.openInputStream(uri) } returns ByteArrayInputStream("not an archive".toByteArray(Charsets.UTF_8))
        assertThat(importLogArchive(context, uri)).isEqualTo(LogArchiveImport.UnsupportedFormat)
        assertThat(importedArchiveCache(context).listFiles().orEmpty()).isEmpty()
    }

    @Test fun expirationReclaimsOnlyOwnedFilesAndProtectsTheRestoredDraft() {
        val cache = importedArchiveCache(context).apply { mkdirs() }
        val now = TimeUnit.DAYS.toMillis(30)
        fun fixture(name: String, age: Long) = File(cache, name).apply {
            writeText("fixture", Charsets.UTF_8)
            setLastModified(now - TimeUnit.DAYS.toMillis(age))
        }
        val expired = fixture("archive-1.zip", 8)
        val partial = fixture("import-2.part", 8)
        val selected = fixture("archive-3.zip", 8)
        val recent = fixture("archive-4.zip", 1)
        val unrelated = fixture("user-log.zip", 8)
        removeExpiredLogArchives(cache, selected.name, now)
        assertThat(expired.exists()).isFalse()
        assertThat(partial.exists()).isFalse()
        assertThat(selected.isFile).isTrue()
        assertThat(recent.isFile).isTrue()
        assertThat(unrelated.isFile).isTrue()
    }
}
