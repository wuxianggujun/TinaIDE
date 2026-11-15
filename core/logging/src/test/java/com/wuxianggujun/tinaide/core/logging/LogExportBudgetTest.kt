package com.wuxianggujun.tinaide.core.logging

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Test

class LogExportBudgetTest {
    private fun zip(): Pair<ZipOutputStream, ByteArrayOutputStream> {
        val sink = ByteArrayOutputStream()
        return ZipOutputStream(sink) to sink
    }

    @Test
    fun `write clips at the budget and marks truncation`() {
        val (zip, _) = zip()
        val budget = LogExportBudget(10)
        zip.putNextEntry(ZipEntry("a.txt"))

        assertThat(budget.writeText(zip, "0123456789ABC")).isFalse()
        assertThat(budget.truncated).isTrue()
        assertThat(budget.remaining()).isEqualTo(0L)

        zip.closeEntry()
        zip.close()
    }

    @Test
    fun `write keeps whole payload when it fits`() {
        val (zip, _) = zip()
        val budget = LogExportBudget(32)
        zip.putNextEntry(ZipEntry("a.txt"))

        assertThat(budget.writeText(zip, "hello")).isTrue()
        assertThat(budget.truncated).isFalse()
        assertThat(budget.remaining()).isEqualTo(27L)

        zip.closeEntry()
        zip.close()
    }

    @Test
    fun `copy stops at the budget instead of failing the whole entry`() {
        val (zip, _) = zip()
        val budget = LogExportBudget(4)
        zip.putNextEntry(ZipEntry("copy.bin"))

        val copied = budget.copy(zip, ByteArrayInputStream(ByteArray(10) { 7 }))

        assertThat(copied).isEqualTo(4L)
        assertThat(budget.truncated).isTrue()
        assertThat(budget.exhausted()).isTrue()

        zip.closeEntry()
        zip.close()
    }

    @Test
    fun `exhausted budget writes nothing and reports truncation`() {
        val (zip, _) = zip()
        val budget = LogExportBudget(1)
        zip.putNextEntry(ZipEntry("a.txt"))
        budget.writeText(zip, "x")

        assertThat(budget.writeText(zip, "more")).isFalse()
        assertThat(budget.exhausted()).isTrue()

        zip.closeEntry()
        zip.close()
    }

    @Test
    fun `reserved child cannot exceed its slice and still charges the parent`() {
        val (zip, _) = zip()
        val parent = LogExportBudget(100)
        val child = parent.reserve(0.5)
        zip.putNextEntry(ZipEntry("child.txt"))

        // Child slice is 50 of 100 bytes.
        assertThat(child.writeText(zip, "a".repeat(80))).isFalse()
        assertThat(child.truncated).isTrue()
        assertThat(child.remaining()).isEqualTo(0L)
        assertThat(parent.truncated).isTrue()
        // Only the 50 bytes the child actually wrote are charged to the parent.
        assertThat(parent.remaining()).isEqualTo(50L)

        zip.closeEntry()
        zip.putNextEntry(ZipEntry("b.txt"))
        assertThat(parent.writeText(zip, "b".repeat(50))).isTrue()
        assertThat(parent.exhausted()).isTrue()

        zip.closeEntry()
        zip.close()
    }

    @Test
    fun `reserved child leaves the whole parent available when unused`() {
        val parent = LogExportBudget(1_000)
        val logcat = parent.reserve(0.5)
        parent.reserve(0.7)

        assertThat(logcat.remaining()).isEqualTo(500L)
        assertThat(parent.remaining()).isEqualTo(1_000L)
    }
}
