package com.wuxianggujun.tinaide.core.lang

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

/**
 * CxxFileSupport 纯 JVM 单元测试
 *
 * 验证 C/C++ 文件扩展名分类和判断逻辑
 */
class CxxFileSupportTest {

    // ==================== 扩展名集合完整性 ====================

    @Test
    fun `cSourceExtensions contains c`() {
        assertThat(CxxFileSupport.cSourceExtensions).containsExactly("c")
    }

    @Test
    fun `cxxSourceExtensions contains expected extensions`() {
        assertThat(CxxFileSupport.cxxSourceExtensions)
            .containsAtLeast("cpp", "cc", "cxx")
    }

    @Test
    fun `headerExtensions contains h and hpp`() {
        assertThat(CxxFileSupport.headerExtensions)
            .containsAtLeast("h", "hpp", "hh", "hxx")
    }

    @Test
    fun `singleFileBuildSourceExtensions is union of c and cxx`() {
        assertThat(CxxFileSupport.singleFileBuildSourceExtensions)
            .containsAtLeastElementsIn(CxxFileSupport.cSourceExtensions)
        assertThat(CxxFileSupport.singleFileBuildSourceExtensions)
            .containsAtLeastElementsIn(CxxFileSupport.cxxSourceExtensions)
    }

    @Test
    fun `singleFileBuildSourceExtensions does not contain objc`() {
        assertThat(CxxFileSupport.singleFileBuildSourceExtensions)
            .containsNoneIn(CxxFileSupport.objcSourceExtensions)
    }

    @Test
    fun `clangdSupportedExtensions includes sources and headers`() {
        assertThat(CxxFileSupport.clangdSupportedExtensions)
            .containsAtLeastElementsIn(CxxFileSupport.cSourceExtensions)
        assertThat(CxxFileSupport.clangdSupportedExtensions)
            .containsAtLeastElementsIn(CxxFileSupport.cxxSourceExtensions)
        assertThat(CxxFileSupport.clangdSupportedExtensions)
            .containsAtLeastElementsIn(CxxFileSupport.objcSourceExtensions)
        assertThat(CxxFileSupport.clangdSupportedExtensions)
            .containsAtLeastElementsIn(CxxFileSupport.headerExtensions)
    }

    @Test
    fun `clangdTranslationUnitExtensions does not include headers`() {
        assertThat(CxxFileSupport.clangdTranslationUnitExtensions)
            .containsNoneIn(CxxFileSupport.headerExtensions)
    }

    @Test
    fun `editorRelatedExtensions includes headers and sources`() {
        assertThat(CxxFileSupport.editorRelatedExtensions)
            .containsAtLeastElementsIn(CxxFileSupport.clangdSupportedExtensions)
        assertThat(CxxFileSupport.editorRelatedExtensions)
            .containsAtLeastElementsIn(CxxFileSupport.headerExtensions)
    }

    // ==================== 判断方法测试 ====================

    @Test
    fun `isCSourceExtension returns true for c`() {
        assertThat(CxxFileSupport.isCSourceExtension("c")).isTrue()
        assertThat(CxxFileSupport.isCSourceExtension("C")).isTrue()
    }

    @Test
    fun `isCSourceExtension returns false for cpp`() {
        assertThat(CxxFileSupport.isCSourceExtension("cpp")).isFalse()
    }

    @Test
    fun `isCxxSourceExtension returns true for cpp variants`() {
        assertThat(CxxFileSupport.isCxxSourceExtension("cpp")).isTrue()
        assertThat(CxxFileSupport.isCxxSourceExtension("cc")).isTrue()
        assertThat(CxxFileSupport.isCxxSourceExtension("cxx")).isTrue()
        assertThat(CxxFileSupport.isCxxSourceExtension("CPP")).isTrue()
    }

    @Test
    fun `isCxxSourceExtension returns false for c`() {
        assertThat(CxxFileSupport.isCxxSourceExtension("c")).isFalse()
    }

    @Test
    fun `isHeaderExtension returns true for header types`() {
        assertThat(CxxFileSupport.isHeaderExtension("h")).isTrue()
        assertThat(CxxFileSupport.isHeaderExtension("hpp")).isTrue()
        assertThat(CxxFileSupport.isHeaderExtension("H")).isTrue()
    }

    @Test
    fun `isHeaderExtension returns false for source types`() {
        assertThat(CxxFileSupport.isHeaderExtension("c")).isFalse()
        assertThat(CxxFileSupport.isHeaderExtension("cpp")).isFalse()
    }

    @Test
    fun `isSingleFileBuildSourceExtension returns true for c and cpp`() {
        assertThat(CxxFileSupport.isSingleFileBuildSourceExtension("c")).isTrue()
        assertThat(CxxFileSupport.isSingleFileBuildSourceExtension("cpp")).isTrue()
    }

    @Test
    fun `isSingleFileBuildSourceExtension returns false for objc`() {
        assertThat(CxxFileSupport.isSingleFileBuildSourceExtension("m")).isFalse()
    }

    @Test
    fun `isClangdSupportedExtension returns true for all source types`() {
        assertThat(CxxFileSupport.isClangdSupportedExtension("c")).isTrue()
        assertThat(CxxFileSupport.isClangdSupportedExtension("cpp")).isTrue()
        assertThat(CxxFileSupport.isClangdSupportedExtension("m")).isTrue()
        assertThat(CxxFileSupport.isClangdSupportedExtension("mm")).isTrue()
        assertThat(CxxFileSupport.isClangdSupportedExtension("h")).isTrue()
    }

    @Test
    fun `isClangdTranslationUnitExtension returns false for headers`() {
        assertThat(CxxFileSupport.isClangdTranslationUnitExtension("cpp")).isTrue()
        assertThat(CxxFileSupport.isClangdTranslationUnitExtension("h")).isFalse()
    }

    // ==================== extensionOf 测试 ====================

    @Test
    fun `extensionOf returns lowercase extension`() {
        assertThat(CxxFileSupport.extensionOf(File("main.CPP"))).isEqualTo("cpp")
        assertThat(CxxFileSupport.extensionOf(File("test.C"))).isEqualTo("c")
    }

    @Test
    fun `extensionOf returns empty for no extension`() {
        assertThat(CxxFileSupport.extensionOf(File("Makefile"))).isEmpty()
    }

    @Test
    fun `isClangdSupportedFile accepts sources headers and extensionless std headers`() {
        assertThat(CxxFileSupport.isClangdSupportedFile(File("main.cpp"))).isTrue()
        assertThat(CxxFileSupport.isClangdSupportedFile(File("foo.h"))).isTrue()
        assertThat(CxxFileSupport.isClangdSupportedFile(File("vector"))).isTrue()
        assertThat(CxxFileSupport.isClangdSupportedFile(File("README"))).isFalse()
        assertThat(CxxFileSupport.isClangdSupportedFile(File("Makefile"))).isFalse()
    }

    @Test
    fun `isExtensionlessCxxSystemHeader matches libcxx and libstdcxx paths`() {
        assertThat(
            CxxFileSupport.isExtensionlessCxxSystemHeader(
                File("/data/files/android-sysroots/builtin-ndk-r27c-arm64/usr/include/c++/v1/vector")
            )
        ).isTrue()
        assertThat(
            CxxFileSupport.isExtensionlessCxxSystemHeader(
                File("/ndk/sysroot/usr/include/c++/v1/__config")
            )
        ).isTrue()
        assertThat(
            CxxFileSupport.isExtensionlessCxxSystemHeader(
                File("/usr/include/c++/13/string")
            )
        ).isTrue()
        assertThat(CxxFileSupport.isExtensionlessCxxSystemHeader(File("include/vector"))).isTrue()
        assertThat(CxxFileSupport.isExtensionlessCxxSystemHeader(File("vector.hpp"))).isFalse()
        assertThat(CxxFileSupport.isExtensionlessCxxSystemHeader(File("README"))).isFalse()
    }
}
