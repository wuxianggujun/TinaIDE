package com.wuxianggujun.tinaide.core.treesitter

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

class TreeSitterXmlQueryTest {

    @Test
    fun xmlFile_shouldResolveToXmlLanguage() {
        assertThat(TreeSitterLanguageRegistry.languageNameForFile(File("AndroidManifest.xml")))
            .isEqualTo("xml")
    }

    @Test
    fun xmlHighlights_shouldUseBundledGrammarNodeNames() {
        val queryFile = sequenceOf(
            File("src/main/assets/tree-sitter-queries/xml/highlights.scm"),
            File("core/tree-sitter/src/main/assets/tree-sitter-queries/xml/highlights.scm"),
        ).firstOrNull(File::isFile)
        val query = queryFile?.readText(Charsets.UTF_8)

        assertThat(query).isNotNull()
        assertThat(query).contains("(xml_decl")
        assertThat(query).contains("(tag_start")
        assertThat(query).contains("(xml_attr")
        assertThat(query).doesNotContain("(XMLDecl")
        assertThat(query).doesNotContain("(STag")
    }
}
