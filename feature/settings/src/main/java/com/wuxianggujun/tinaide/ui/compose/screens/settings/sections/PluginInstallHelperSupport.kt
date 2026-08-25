package com.wuxianggujun.tinaide.ui.compose.screens.settings.sections

import com.wuxianggujun.tinaide.core.serialization.JsonSerializer
import com.wuxianggujun.tinaide.plugin.PluginDiagnosticSeverity
import com.wuxianggujun.tinaide.plugin.PluginDiagnosticsReport
import com.wuxianggujun.tinaide.plugin.PluginManifest
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import java.util.zip.ZipFile
import kotlinx.serialization.decodeFromString

internal object PluginInstallHelperSupport {

    private const val COPY_BUFFER_BYTES = 8 * 1024

    fun buildPreviewTempFile(
        cacheDir: File,
        lastPathSegment: String?,
        timestampMillis: Long,
    ): File {
        val fileName = PluginsSettingsSectionSupport.resolveInstallSourceFileName(lastPathSegment)
        return File(
            cacheDir,
            PluginsSettingsSectionSupport.buildTempInstallFileName(
                timestampMillis = timestampMillis,
                fileName = fileName,
            )
        )
    }

    fun readManifestFromZip(zipFile: File): PluginManifest? {
        return try {
            val json = JsonSerializer.default
            ZipFile(zipFile).use { zip ->
                val entry = zip.getEntry("manifest.json") ?: return null
                zip.getInputStream(entry).bufferedReader().use { reader ->
                    json.decodeFromString<PluginManifest>(reader.readText())
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    fun copyAtMost(
        input: InputStream,
        output: OutputStream,
        maxBytes: Long,
    ): Boolean {
        require(maxBytes >= 0L) { "Maximum byte count must not be negative" }
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        var copied = 0L
        while (true) {
            val bytesRead = input.read(buffer)
            if (bytesRead < 0) return true
            if (bytesRead > maxBytes - copied) return false
            output.write(buffer, 0, bytesRead)
            copied += bytesRead
        }
    }

    fun buildInstallOutcome(
        result: Result<PluginManifest>,
        installedTemplate: String,
        failedTemplate: String,
        locale: Locale = Locale.getDefault(),
    ): PluginInstallOutcome {
        val manifest = result.getOrNull()
        val message = if (manifest != null) {
            String.format(locale, installedTemplate, manifest.name)
        } else {
            String.format(locale, failedTemplate, result.exceptionOrNull()?.message ?: "")
        }
        return PluginInstallOutcome(
            message = message,
            manifest = manifest,
        )
    }

    fun shouldBlockPreflightInstall(report: PluginDiagnosticsReport): Boolean = report.hasSeverity(PluginDiagnosticSeverity.ERROR)

    fun hasPreflightWarnings(report: PluginDiagnosticsReport): Boolean = report.hasSeverity(PluginDiagnosticSeverity.WARNING)

    private fun PluginDiagnosticsReport.hasSeverity(severity: PluginDiagnosticSeverity): Boolean = entries.any { entry -> entry.issue.severity == severity }
}
