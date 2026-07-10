package com.wuxianggujun.tinaide.core.treesitter

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

class TreeSitterHighlighterTest {

    @Test
    fun languageNameForFile_shouldMapCMakeEntryFiles() {
        assertThat(TreeSitterLanguageRegistry.languageNameForFile(File("CMakeLists.txt")))
            .isEqualTo("cmake")
        assertThat(TreeSitterLanguageRegistry.languageNameForFile(File("toolchain.cmake")))
            .isEqualTo("cmake")
    }

    @Test
    fun languageNameForFile_shouldMapMakeVariants() {
        assertThat(TreeSitterLanguageRegistry.languageNameForFile(File("Makefile.in")))
            .isEqualTo("make")
        assertThat(TreeSitterLanguageRegistry.languageNameForFile(File("BSDmakefile")))
            .isEqualTo("make")
        assertThat(TreeSitterLanguageRegistry.languageNameForFile(File("rules.mak")))
            .isEqualTo("make")
    }

    @Test
    fun shouldRenderOverlay_shouldIgnoreDefaultCaptures() {
        assertThat(HighlightType.DEFAULT.shouldRenderOverlay()).isFalse()
        assertThat(HighlightType.COMMENT.shouldRenderOverlay()).isTrue()
        assertThat(HighlightType.KEYWORD.shouldRenderOverlay()).isTrue()
    }

    @Test
    fun classifyCaptureName_shouldTreatNoneAndSpellAsDefault() {
        assertThat(TreeSitterHighlighter.classifyCaptureName("none"))
            .isEqualTo(HighlightType.DEFAULT)
        assertThat(TreeSitterHighlighter.classifyCaptureName("spell"))
            .isEqualTo(HighlightType.DEFAULT)
    }

    @Test
    fun classifyCaptureName_shouldTreatBooleansAsConstant() {
        assertThat(TreeSitterHighlighter.classifyCaptureName("boolean"))
            .isEqualTo(HighlightType.CONSTANT)
    }

    @Test
    fun classifyCaptureName_shouldTreatBuiltinConstantsAsBuiltin() {
        assertThat(TreeSitterHighlighter.classifyCaptureName("constant.builtin"))
            .isEqualTo(HighlightType.BUILTIN)
    }

    @Test
    fun classifyCaptureName_shouldMapBareConstantToConstant() {
        assertThat(TreeSitterHighlighter.classifyCaptureName("constant"))
            .isEqualTo(HighlightType.CONSTANT)
    }

    @Test
    fun classifyCaptureName_shouldKeepFunctionAndTypeCategories() {
        assertThat(TreeSitterHighlighter.classifyCaptureName("function.call"))
            .isEqualTo(HighlightType.FUNCTION)
        assertThat(TreeSitterHighlighter.classifyCaptureName("type.builtin"))
            .isEqualTo(HighlightType.BUILTIN)
    }

    @Test
    fun classifyCaptureName_shouldDifferentiatePropertiesFromVariables() {
        assertThat(TreeSitterHighlighter.classifyCaptureName("property"))
            .isEqualTo(HighlightType.PROPERTY)
        assertThat(TreeSitterHighlighter.classifyCaptureName("attribute"))
            .isEqualTo(HighlightType.PROPERTY)
        assertThat(TreeSitterHighlighter.classifyCaptureName("variable.parameter"))
            .isEqualTo(HighlightType.VARIABLE)
    }

    @Test
    fun classifyCaptureName_shouldMapLabelsWithoutSubstringGuessing() {
        assertThat(TreeSitterHighlighter.classifyCaptureName("label"))
            .isEqualTo(HighlightType.CONSTANT)
        assertThat(TreeSitterHighlighter.classifyCaptureName("prototype"))
            .isEqualTo(HighlightType.DEFAULT)
    }

    @Test
    fun classifyCaptureName_shouldTreatModifierAndModuleAsKeywords() {
        assertThat(TreeSitterHighlighter.classifyCaptureName("keyword.modifier"))
            .isEqualTo(HighlightType.KEYWORD)
        assertThat(TreeSitterHighlighter.classifyCaptureName("module"))
            .isEqualTo(HighlightType.KEYWORD)
    }

    @Test
    fun classifyCaptureName_shouldTreatKeywordOperatorAsKeyword() {
        assertThat(TreeSitterHighlighter.classifyCaptureName("keyword.operator"))
            .isEqualTo(HighlightType.KEYWORD)
    }
}
