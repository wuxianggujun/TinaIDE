package com.wuxianggujun.tinaide.core.packages

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.i18n.AppStrings
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.str
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class InstalledPackageMetadataReaderTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var metadata: File
    private val context = mockk<Context>()

    @Before fun setup() {
        AppStrings.initialize(RuntimeEnvironment.getApplication())
        every { context.filesDir } returns temporary.root
        metadata = File(temporary.root, "installed-packages/demo/package.json").apply { parentFile!!.mkdirs() }
    }

    @Test fun validUtf8KeepsChineseAndSupplementaryCharacters() {
        metadata.writeText("""{"id":"demo","name":"中文资源😀","version":"1.0"}""", Charsets.UTF_8)
        assertThat(InstalledPackageMetadataReader.read(context, "demo")?.name).isEqualTo("中文资源😀")
    }

    @Test fun oversizedMetadataIsRejectedBeforeReadingAndNotListedAsValid() {
        RandomAccessFile(metadata, "rw").use { it.setLength(InstalledPackageMetadataReader.MAX_METADATA_BYTES + 1L) }
        val failure = assertThrows(IllegalArgumentException::class.java) { InstalledPackageMetadataReader.readFile(metadata) }
        assertThat(failure.message).isEqualTo(Strings.pkg_manager_error_invalid_metadata.str())
        assertThat(InstalledPackageMetadataReader.read(context, "demo")).isNull()
    }

    @Test fun fileGrowthCannotBypassTheBoundedRead() {
        metadata.writeText("""{"id":"demo","name":"Demo","version":"1.0"}""" +
            " ".repeat(InstalledPackageMetadataReader.MAX_METADATA_BYTES), Charsets.UTF_8)
        val staleLength = object : File(metadata.path) { override fun length(): Long = 40L }
        assertThrows(IllegalArgumentException::class.java) { InstalledPackageMetadataReader.readFile(staleLength) }
    }

    @Test fun malformedUtf8IsNotSilentlyReplacedInsideAValidJsonString() {
        metadata.writeBytes("""{"id":"demo","name":"""".toByteArray(Charsets.UTF_8) +
            byteArrayOf(0x80.toByte()) + """","version":"1.0"}""".toByteArray(Charsets.UTF_8))
        assertThrows(IllegalArgumentException::class.java) { InstalledPackageMetadataReader.readFile(metadata) }
        assertThat(InstalledPackageMetadataReader.read(context, "demo")).isNull()
    }

    @Test fun malformedJsonAndMismatchedPackageIdentityAreRejected() {
        metadata.writeText("not JSON", Charsets.UTF_8)
        assertThat(InstalledPackageMetadataReader.read(context, "demo")).isNull()
        metadata.writeText("""{"id":"different","name":"Demo","version":"1.0"}""", Charsets.UTF_8)
        assertThat(InstalledPackageMetadataReader.read(context, "demo")).isNull()
    }
}
