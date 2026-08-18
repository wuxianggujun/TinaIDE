package com.wuxianggujun.tinaide.core.editorlsp

import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Test

class LspEditorManagerCompileConfigTest {

    @Test
    fun isRootMakeBuildFile_shouldRecognizeOnlyRootBuildFiles() {
        val projectRoot = createTempDirectory(prefix = "lsp-root-makefile-").toFile()
        try {
            listOf("Makefile", "makefile", "GNUmakefile").forEach { fileName ->
                val makefile = File(projectRoot, fileName).apply { writeText("all:\n\t@true\n") }
                assertThat(
                    LspEditorManager.isRootMakeBuildFile(makefile, projectRoot.absolutePath)
                ).isTrue()
            }

            val nestedMakefile = File(projectRoot, "module/Makefile").apply {
                parentFile?.mkdirs()
                writeText("all:\n\t@true\n")
            }
            val includedRules = File(projectRoot, "rules.mk").apply { writeText("CXXFLAGS += -Wall\n") }

            assertThat(
                LspEditorManager.isRootMakeBuildFile(nestedMakefile, projectRoot.absolutePath)
            ).isFalse()
            assertThat(
                LspEditorManager.isRootMakeBuildFile(includedRules, projectRoot.absolutePath)
            ).isFalse()
        } finally {
            projectRoot.deleteRecursively()
        }
    }
}
