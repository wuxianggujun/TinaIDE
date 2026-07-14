package com.wuxianggujun.tinaide.plugin

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import java.util.Random
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Before
import org.junit.Test

class ZipUtilsTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("plugin-archive-test").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `zip slip path is rejected before extraction`() {
        val archive = createZip("escape.zip", mapOf("../manifest.json" to byteArrayOf(1)))

        val error = archiveFailure { ZipUtils.unzipToDirectory(archive, File(root, "output")) }

        assertThat(error.failure).isEqualTo(PluginArchiveFailure.INVALID_ENTRY)
        assertThat(File(root.parentFile, "manifest.json").exists()).isFalse()
    }

    @Test
    fun `compression ratio over one hundred to one is rejected`() {
        val archive = createZip(
            "ratio.zip",
            mapOf("payload.bin" to ByteArray(2 * 1024 * 1024)),
        )

        val error = archiveFailure { ZipUtils.unzipToDirectory(archive, File(root, "output")) }

        assertThat(error.failure).isEqualTo(PluginArchiveFailure.COMPRESSION_RATIO_TOO_HIGH)
        assertThat(error.entryName).isEqualTo("payload.bin")
    }

    @Test
    fun `single lua source over one MiB is rejected`() {
        val bytes = ByteArray((ZipUtils.MAX_LUA_FILE_BYTES + 1).toInt())
        Random(7).nextBytes(bytes)
        val archive = createZip("lua-size.zip", mapOf("main.lua" to bytes))

        val error = archiveFailure { ZipUtils.unzipToDirectory(archive, File(root, "output")) }

        assertThat(error.failure).isEqualTo(PluginArchiveFailure.LUA_FILE_TOO_LARGE)
        assertThat(error.entryName).isEqualTo("main.lua")
    }

    @Test
    fun `package larger than sixty four MiB is rejected without reading it`() {
        val archive = File(root, "oversized.zip")
        archive.outputStream().use { it.write(0) }
        java.io.RandomAccessFile(archive, "rw").use { file ->
            file.setLength(ZipUtils.MAX_PACKAGE_BYTES + 1)
        }

        val error = archiveFailure { ZipUtils.unzipToDirectory(archive, File(root, "output")) }

        assertThat(error.failure).isEqualTo(PluginArchiveFailure.PACKAGE_TOO_LARGE)
    }

    private fun createZip(name: String, entries: Map<String, ByteArray>): File =
        File(root, name).also { archive ->
            ZipOutputStream(archive.outputStream()).use { zip ->
                entries.forEach { (entryName, bytes) ->
                    zip.putNextEntry(ZipEntry(entryName))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        }

    private fun archiveFailure(block: () -> Unit): PluginArchiveException {
        val error = runCatching(block).exceptionOrNull()
        assertThat(error).isInstanceOf(PluginArchiveException::class.java)
        return error as PluginArchiveException
    }
}
