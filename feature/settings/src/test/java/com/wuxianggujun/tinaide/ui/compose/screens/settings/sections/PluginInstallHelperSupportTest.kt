package com.wuxianggujun.tinaide.ui.compose.screens.settings.sections

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.plugin.PluginDiagnosticCategory
import com.wuxianggujun.tinaide.plugin.PluginDiagnosticEntry
import com.wuxianggujun.tinaide.plugin.PluginDiagnosticIssue
import com.wuxianggujun.tinaide.plugin.PluginDiagnosticSeverity
import com.wuxianggujun.tinaide.plugin.PluginDiagnosticSource
import com.wuxianggujun.tinaide.plugin.PluginDiagnosticsReport
import com.wuxianggujun.tinaide.plugin.PluginManifest
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Test

class PluginInstallHelperSupportTest {

    @Test
    fun buildPreviewTempFile_shouldReuseNormalizedInstallFileName() {
        val cacheDir = Files.createTempDirectory("plugin-install-helper").toFile()

        val tempFile = PluginInstallHelperSupport.buildPreviewTempFile(
            cacheDir = cacheDir,
            lastPathSegment = "content://plugins/demo/test-plugin.tinaplug",
            timestampMillis = 123L,
        )

        assertThat(tempFile.parentFile).isEqualTo(cacheDir)
        assertThat(tempFile.name).isEqualTo("install_123_test-plugin.tinaplug")
    }

    @Test
    fun readManifestFromZip_shouldParseValidManifestAndIgnoreInvalidArchives() {
        val validZip = createZipWithManifest(
            """
            {
              "id": "demo.plugin",
              "name": "Demo Plugin",
              "version": "1.0.0"
            }
            """.trimIndent()
        )
        val missingManifestZip = createZipWithoutManifest()
        val invalidManifestZip = createZipWithManifest("not json")

        assertThat(
            PluginInstallHelperSupport.readManifestFromZip(validZip)
        ).isEqualTo(
            PluginManifest(
                id = "demo.plugin",
                name = "Demo Plugin",
                version = "1.0.0",
            )
        )
        assertThat(
            PluginInstallHelperSupport.readManifestFromZip(missingManifestZip)
        ).isNull()
        assertThat(
            PluginInstallHelperSupport.readManifestFromZip(invalidManifestZip)
        ).isNull()
    }

    @Test
    fun buildInstallOutcome_shouldFormatSuccessAndFailureMessages() {
        val manifest = PluginManifest(
            id = "demo.plugin",
            name = "Demo Plugin",
            version = "1.0.0",
        )

        assertThat(
            PluginInstallHelperSupport.buildInstallOutcome(
                result = Result.success(manifest),
                installedTemplate = "Installed %s",
                failedTemplate = "Failed %s",
                locale = Locale.US,
            )
        ).isEqualTo(
            PluginInstallOutcome(
                message = "Installed Demo Plugin",
                manifest = manifest,
            )
        )
        assertThat(
            PluginInstallHelperSupport.buildInstallOutcome(
                result = Result.failure(IllegalStateException("broken archive")),
                installedTemplate = "Installed %s",
                failedTemplate = "Failed %s",
                locale = Locale.US,
            )
        ).isEqualTo(
            PluginInstallOutcome(
                message = "Failed broken archive",
                manifest = null,
            )
        )
    }

    @Test
    fun copyAtMost_shouldStopBeforeWritingPastLimit() {
        val output = ByteArrayOutputStream()

        val completed = PluginInstallHelperSupport.copyAtMost(
            input = ByteArrayInputStream(ByteArray(12 * 1024)),
            output = output,
            maxBytes = 8L * 1024L,
        )

        assertThat(completed).isFalse()
        assertThat(output.size()).isEqualTo(8 * 1024)
    }

    @Test
    fun preflightSeverityHelpers_shouldBlockErrorsAndAllowWarnings() {
        val errorReport = createDiagnosticsReport(PluginDiagnosticSeverity.ERROR)
        val warningReport = createDiagnosticsReport(PluginDiagnosticSeverity.WARNING)
        val cleanReport = createDiagnosticsReport()

        assertThat(PluginInstallHelperSupport.shouldBlockPreflightInstall(errorReport)).isTrue()
        assertThat(PluginInstallHelperSupport.hasPreflightWarnings(errorReport)).isFalse()
        assertThat(PluginInstallHelperSupport.shouldBlockPreflightInstall(warningReport)).isFalse()
        assertThat(PluginInstallHelperSupport.hasPreflightWarnings(warningReport)).isTrue()
        assertThat(PluginInstallHelperSupport.shouldBlockPreflightInstall(cleanReport)).isFalse()
        assertThat(PluginInstallHelperSupport.hasPreflightWarnings(cleanReport)).isFalse()
    }

    private fun createZipWithManifest(manifestJson: String): File {
        val zipFile = Files.createTempFile("plugin-manifest", ".zip").toFile()
        ZipOutputStream(zipFile.outputStream()).use { output ->
            output.putNextEntry(ZipEntry("manifest.json"))
            output.write(manifestJson.toByteArray())
            output.closeEntry()
        }
        return zipFile
    }

    private fun createZipWithoutManifest(): File {
        val zipFile = Files.createTempFile("plugin-no-manifest", ".zip").toFile()
        ZipOutputStream(zipFile.outputStream()).use { output ->
            output.putNextEntry(ZipEntry("README.txt"))
            output.write("hello".toByteArray())
            output.closeEntry()
        }
        return zipFile
    }

    private fun createDiagnosticsReport(
        vararg severities: PluginDiagnosticSeverity,
    ): PluginDiagnosticsReport = PluginDiagnosticsReport(
        pluginId = "demo.plugin",
        pluginName = "Demo Plugin",
        isInstalled = false,
        entries = severities.map { severity ->
            PluginDiagnosticEntry(
                source = PluginDiagnosticSource.HEALTH,
                issue = PluginDiagnosticIssue(
                    severity = severity,
                    category = PluginDiagnosticCategory.MANIFEST,
                    message = severity.name,
                ),
            )
        },
    )
}
