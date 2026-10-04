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
                distros = listOf(distro("debian"))
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
                distros = listOf(distro("debian"), distro("debian"))
            )
        }
    }

    @Test
    fun manifestCatalog_shouldResolveManifestDistros() {
        val manifest = LinuxDistroManifest(
            schemaVersion = LinuxDistroManifest.CURRENT_SCHEMA_VERSION,
            generatedAt = "2026-05-03T00:00:00Z",
            distros = listOf(distro("debian"))
        )
        val catalog = ManifestLinuxDistroCatalog(manifest)

        assertThat(catalog.resolveDistro("debian")?.displayName)
            .isEqualTo("Debian")
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
                  "id": "debian",
                  "family": "DEBIAN",
                  "displayName": "Debian",
                  "packageManager": "APT",
                  "defaultReleaseId": "12",
                  "releases": [
                    {
                      "id": "12",
                      "version": "12",
                      "displayName": "Debian 12",
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

        assertThat(manifest.distros.single().id).isEqualTo("debian")
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
                  "matchPrefix": "https://cdimage.debian.org/",
                  "replaceWith": "https://mirrors.tuna.tsinghua.edu.cn/"
                }
              ],
              "distros": [
                {
                  "id": "debian",
                  "family": "DEBIAN",
                  "displayName": "Debian",
                  "packageManager": "APT",
                  "defaultReleaseId": "12",
                  "releases": [
                    {
                      "id": "12",
                      "version": "12",
                      "displayName": "Debian 12",
                      "artifacts": [
                        {
                          "architecture": "AARCH64",
                          "url": "https://cdimage.debian.org/debian-cd/12/rootfs.tar.gz",
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
        assertThat(rule.deriveOrNull("https://cdimage.debian.org/debian-cd/12/rootfs.tar.gz"))
            .isEqualTo("https://mirrors.tuna.tsinghua.edu.cn/debian-cd/12/rootfs.tar.gz")
        assertThat(rule.deriveOrNull("https://other.example/rootfs.tar.gz")).isNull()
    }

    @Test
    fun manifest_shouldDefaultToEmptyMirrorsWhenAbsent() {
        val manifest = LinuxDistroManifest(
            schemaVersion = LinuxDistroManifest.CURRENT_SCHEMA_VERSION,
            distros = listOf(distro("debian"))
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
        family = DistroFamily.DEBIAN,
        displayName = "Debian",
        packageManager = DistroPackageManager.APT,
        defaultReleaseId = "12",
        releases = listOf(
            DistroRelease(
                id = "12",
                version = "12",
                displayName = "Debian 12",
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
