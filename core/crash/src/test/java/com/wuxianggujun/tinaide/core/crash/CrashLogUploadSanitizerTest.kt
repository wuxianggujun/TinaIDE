package com.wuxianggujun.tinaide.core.crash

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CrashLogUploadSanitizerTest {
    private val packageName = "com.example.tinaide"

    @Test
    fun sanitize_redactsCredentialsUrisAndUserPaths() {
        val crashText = """
            >>> $packageName <<<
            GET https://downloads.example.org/archive.zip?token=query-secret&mirror=private
            remote=https://user:password@private.example.org/archive.zip
            Authorization: Bearer header-secret
            {"api_key":"json-secret","password":"json-password"}
            tree=content://com.android.externalstorage.documents/tree/primary%3ADocuments%2FPrivate
            source=/storage/emulated/0/Documents/TinaIDE/secret-project/main.cpp:42
            cache=/data/user/0/$packageName/files/workspace/secret-project/CMakeLists.txt
        """.trimIndent()

        val sanitized = CrashLogUploadSanitizer.sanitize(crashText, packageName)

        assertThat(sanitized).doesNotContain("query-secret")
        assertThat(sanitized).doesNotContain("user:password")
        assertThat(sanitized).doesNotContain("header-secret")
        assertThat(sanitized).doesNotContain("json-secret")
        assertThat(sanitized).doesNotContain("json-password")
        assertThat(sanitized).doesNotContain("externalstorage.documents")
        assertThat(sanitized).doesNotContain("secret-project")
        assertThat(sanitized).contains("https://downloads.example.org/archive.zip?<redacted>")
        assertThat(sanitized).contains("<private-uri>")
        assertThat(sanitized).contains("<private-path>")
    }

    @Test
    fun sanitize_preservesHostCrashMetadataAndSystemLibraryPath() {
        val crashText = """
            >>> $packageName <<<
            signal 11 (SIGSEGV), fault addr 0x0
            #00 pc 0000000000021c90 /data/app/~~abc/$packageName/lib/arm64/libtinaide.so
        """.trimIndent()

        val sanitized = CrashLogUploadSanitizer.sanitize(crashText, packageName)

        assertThat(sanitized).contains(">>> $packageName <<<")
        assertThat(sanitized).contains("signal 11 (SIGSEGV)")
        assertThat(sanitized).contains("/data/app/~~abc/$packageName/lib/arm64/libtinaide.so")
    }
}
