package com.wuxianggujun.tinaide.core.common.io

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ArchivePathSafetyTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun resolveEntryFile_rejectsTraversalAbsoluteAndDrivePaths() {
        val targetDir = tempFolder.newFolder("target")

        assertThrows(IllegalArgumentException::class.java) {
            ArchivePathSafety.resolveEntryFile(targetDir, "../evil.txt")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArchivePathSafety.resolveEntryFile(targetDir, "/tmp/evil.txt")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArchivePathSafety.resolveEntryFile(targetDir, "C:/tmp/evil.txt")
        }
    }

    @Test
    fun resolveEntryFile_allowsNormalizedRelativePathInsideTarget() {
        val targetDir = tempFolder.newFolder("target")

        val entryFile = ArchivePathSafety.resolveEntryFile(targetDir, "./include/./stdio.h")

        assertThat(entryFile.canonicalPath)
            .isEqualTo(File(targetDir.canonicalFile, "include/stdio.h").canonicalPath)
    }

    @Test
    fun requireNoSymlinkComponents_allowsCandidateResolvedFromCanonicalTargetAlias() {
        val canonicalTarget = tempFolder.newFolder("canonical-target").canonicalFile
        val aliasedTarget = CanonicalAliasFile(
            path = File(tempFolder.root, "alias-target").absolutePath,
            canonicalFile = canonicalTarget,
        )
        val candidate = ArchivePathSafety.resolveEntryFile(
            targetDir = aliasedTarget,
            entryName = "android-sysroot",
        )

        ArchivePathSafety.requireNoSymlinkComponents(
            targetDir = aliasedTarget,
            candidate = candidate,
        )

        assertThat(candidate).isEqualTo(File(canonicalTarget, "android-sysroot"))
    }

    @Test
    fun symlinkTargetMustStayInsideTargetDir() {
        val targetDir = tempFolder.newFolder("target")
        val linkFile = File(targetDir, "bin/tool")

        assertThat(
            ArchivePathSafety.requireSymlinkTargetInsideTargetDir(
                targetDir = targetDir,
                linkFile = linkFile,
                linkTarget = "../lib/tool"
            )
        ).isEqualTo("../lib/tool")

        assertThrows(IllegalArgumentException::class.java) {
            ArchivePathSafety.requireSymlinkTargetInsideTargetDir(
                targetDir = targetDir,
                linkFile = linkFile,
                linkTarget = "../../outside"
            )
        }
    }

    private class CanonicalAliasFile(
        path: String,
        private val canonicalFile: File,
    ) : File(path) {
        override fun getCanonicalFile(): File = canonicalFile
    }
}
