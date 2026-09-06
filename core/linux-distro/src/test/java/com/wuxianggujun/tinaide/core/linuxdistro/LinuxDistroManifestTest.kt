package com.wuxianggujun.tinaide.core.linuxdistro

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Assert.assertThrows
import org.junit.Test

class LinuxDistroManifestTest {

    @Test
    fun manifest_shouldRejectUnsupportedSchemaVersion() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            LinuxDistroManifest(
                schemaVersion = 2,
                distros = listOf(distro("alpine"))
            )
        }

        assertThat(error).hasMessageThat()
            .contains("Unsupported linux distro manifest schema")
    }

    @Test
    fun manifest_shouldRejectDuplicateDistroIds() {
        assertThrows(IllegalArgumentException::class.java) {
            LinuxDistroManifest(
                schemaVersion = LinuxDistroManifest.CURRENT_SCHEMA_VERSION,
                distros = listOf(distro("alpine"), distro("alpine"))
            )
        }
    }

    @Test
    fun manifestCatalog_shouldResolveManifestDistros() {
        val manifest = LinuxDistroManifest(
            schemaVersion = LinuxDistroManifest.CURRENT_SCHEMA_VERSION,
            generatedAt = "2026-05-03T00:00:00Z",
            distros = listOf(distro("alpine"))
        )
        val catalog = ManifestLinuxDistroCatalog(manifest)

        assertThat(catalog.resolveDistro("alpine")?.displayName)
            .isEqualTo("Alpine")
    }

    @Test
    fun parser_shouldIgnoreUnknownKeysAndDecodeValidManifest() {
        val manifest = LinuxDistroManifestParser.decode(
            """
            {
              "schemaVersion": 1,
              "unknown": "ignored",
              "distros": [
                {
                  "id": "alpine",
                  "family": "ALPINE",
                  "displayName": "Alpine",
                  "packageManager": "APK",
                  "defaultReleaseId": "3.20",
                  "releases": [
                    {
                      "id": "3.20",
                      "version": "3.20",
                      "displayName": "Alpine 3.20",
                      "artifacts": [
                        {
                          "architecture": "AARCH64",
                          "url": "https://example.test/rootfs.tar.gz",
                          "format": "TAR_GZ",
                          "checksum": {
                            "algorithm": "SHA256",
                            "value": "${"AB".repeat(32)}"
                          }
                        }
                      ]
                    }
                  ]
                }
              ]
            }
            """.trimIndent()
        )

        assertThat(manifest.distros.single().id).isEqualTo("alpine")
        assertThat(manifest.distros.single().defaultRelease()?.artifactFor(DistroArchitecture.AARCH64))
            .isNotNull()
    }

    @Test
    fun parser_shouldDecodeMirrorRulesAndExposeThemViaCatalog() {
        val manifest = LinuxDistroManifestParser.decode(
            """
            {
              "schemaVersion": 1,
              "mirrors": [
                {
                  "matchPrefix": "https://dl-cdn.alpinelinux.org/",
                  "replaceWith": "https://mirrors.tuna.tsinghua.edu.cn/"
                }
              ],
              "distros": [
                {
                  "id": "alpine",
                  "family": "ALPINE",
                  "displayName": "Alpine",
                  "packageManager": "APK",
                  "defaultReleaseId": "3.20",
                  "releases": [
                    {
                      "id": "3.20",
                      "version": "3.20",
                      "displayName": "Alpine 3.20",
                      "artifacts": [
                        {
                          "architecture": "AARCH64",
                          "url": "https://dl-cdn.alpinelinux.org/alpine/v3.23/rootfs.tar.gz",
                          "format": "TAR_GZ",
                          "checksum": {
                            "algorithm": "SHA256",
                            "value": "${"AB".repeat(32)}"
                          }
                        }
                      ]
                    }
                  ]
                }
              ]
            }
            """.trimIndent()
        )
        val catalog = ManifestLinuxDistroCatalog(manifest)

        val rule = catalog.mirrorRules().single()
        assertThat(rule.deriveOrNull("https://dl-cdn.alpinelinux.org/alpine/v3.23/rootfs.tar.gz"))
            .isEqualTo("https://mirrors.tuna.tsinghua.edu.cn/alpine/v3.23/rootfs.tar.gz")
        assertThat(rule.deriveOrNull("https://other.example/rootfs.tar.gz")).isNull()
    }

    @Test
    fun manifest_shouldDefaultToEmptyMirrorsWhenAbsent() {
        val manifest = LinuxDistroManifest(
            schemaVersion = LinuxDistroManifest.CURRENT_SCHEMA_VERSION,
            distros = listOf(distro("alpine"))
        )

        assertThat(manifest.mirrors).isEmpty()
        assertThat(ManifestLinuxDistroCatalog(manifest).mirrorRules()).isEmpty()
    }

    @Test
    fun bundledManifest_shouldContainUbuntuOnlyAndUbuntuMirrorRules() {
        val workingDirectory = File(requireNotNull(System.getProperty("user.dir")))
        val manifestFile = listOf(
            File(workingDirectory, "src/main/assets/linux-distro/manifest.json"),
            File(workingDirectory, "core/linux-distro/src/main/assets/linux-distro/manifest.json"),
        ).firstOrNull { it.isFile }
        requireNotNull(manifestFile) { "Bundled linux distro manifest not found from ${workingDirectory.absolutePath}" }

        val manifest = LinuxDistroManifestParser.decode(manifestFile.readText(Charsets.UTF_8))
        assertThat(manifest.distros.map { it.id }).containsExactly("ubuntu")
        assertThat(manifest.distros.single().packageManager).isEqualTo(DistroPackageManager.APT)

        assertThat(manifest.mirrors.map { it.replaceWith }).containsAtLeast(
            "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage/",
            "https://mirrors.ustc.edu.cn/ubuntu-cdimage/",
        )
    }

    private fun distro(id: String): DistroDefinition = DistroDefinition(
        id = id,
        family = DistroFamily.ALPINE,
        displayName = "Alpine",
        packageManager = DistroPackageManager.APK,
        defaultReleaseId = "3.20",
        releases = listOf(
            DistroRelease(
                id = "3.20",
                version = "3.20",
                displayName = "Alpine 3.20",
                artifacts = listOf(
                    DistroArtifact(
                        architecture = DistroArchitecture.AARCH64,
                        url = "https://example.test/rootfs.tar.gz",
                        format = DistroArchiveFormat.TAR_GZ,
                        checksum = DistroChecksum(DistroChecksumAlgorithm.SHA256, "ab".repeat(32))
                    )
                )
            )
        )
    )
}
