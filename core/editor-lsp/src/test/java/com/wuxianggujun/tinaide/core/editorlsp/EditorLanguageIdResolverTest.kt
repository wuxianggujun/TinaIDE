package com.wuxianggujun.tinaide.core.editorlsp

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

class EditorLanguageIdResolverTest {

    @Test
    fun resolveEditorLanguageId_shouldMapSnippetFriendlyDefaults() {
        assertThat(File("demo.hpp").resolveEditorLanguageId()).isEqualTo("cpp")
        assertThat(File("script.sh").resolveEditorLanguageId()).isEqualTo("bash")
        assertThat(File("config.yml").resolveEditorLanguageId()).isEqualTo("yaml")
    }

    @Test
    fun resolveEditorLanguageId_shouldAllowCHederOverrideAndPlaintextFallback() {
        assertThat(File("demo.h").resolveEditorLanguageId(cHeaderLanguageId = "c")).isEqualTo("c")
        assertThat(File("README").resolveEditorLanguageId()).isEqualTo("plaintext")
    }

    @Test
    fun resolveLspLanguageId_shouldKeepObjectiveCAndCppHeaders() {
        assertThat(File("demo.m").resolveLspLanguageId()).isEqualTo("objective-c")
        assertThat(File("demo.mm").resolveLspLanguageId()).isEqualTo("objective-cpp")
        assertThat(File("demo.hpp").resolveLspLanguageId()).isEqualTo("cpp")
    }

    @Test
    fun resolveCodeAnalysisLanguageLabel_shouldExposeHeaderAndUnknownLabels() {
        assertThat(File("demo.hh").resolveCodeAnalysisLanguageLabel()).isEqualTo("c/c++ header")
        assertThat(File("README.customext").resolveCodeAnalysisLanguageLabel()).isEqualTo("unknown")
    }
}
