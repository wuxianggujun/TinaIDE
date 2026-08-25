package com.wuxianggujun.tinaide.core.packages.model

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test

class PackageModelsSerializationTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun guiPackage_shouldDeserializeSnakeCaseProtocolFields() {
        val pkg = json.decodeFromString<GUIPackage>(
            """
            {
              "id": "sdl3",
              "name": "SDL3",
              "icon_url": "https://example.test/sdl.svg",
              "is_bundled": true,
              "android": {
                "version": "3.2.0",
                "artifact_type": "shared",
                "install_type": "download",
                "is_latest": true,
                "release_notes": "stable"
              }
            }
            """.trimIndent()
        )

        assertThat(pkg.id).isEqualTo("sdl3")
        assertThat(pkg.iconUrl).isEqualTo("https://example.test/sdl.svg")
        assertThat(pkg.isBundled).isTrue()
        assertThat(pkg.android?.artifactType).isEqualTo(PackageArtifactType.SHARED)
        assertThat(pkg.android?.installType).isEqualTo(InstallType.DOWNLOAD)
        assertThat(pkg.android?.isLatest).isTrue()
        assertThat(pkg.android?.releaseNotes).isEqualTo("stable")
    }

    @Test
    fun downloadInfo_shouldDeserializeSourceRangeSupportDefault() {
        val info = json.decodeFromString<DownloadInfo>(
            """
            {
              "package_id": "clang",
              "version": "18",
              "platform": "android",
              "install_type": "download",
              "sources": [
                { "id": 1, "name": "mirror", "url": "https://example.test/pkg.tar", "priority": 10 }
              ]
            }
            """.trimIndent()
        )

        assertThat(info.packageId).isEqualTo("clang")
        assertThat(info.platform).isEqualTo(Platform.ANDROID)
        assertThat(info.sources.single().supportsRange).isTrue()
    }

    @Test
    fun downloadInfo_shouldDeserializeAbiSpecificSourceIntegrityMetadata() {
        val info = json.decodeFromString<DownloadInfo>(
            """
            {
              "package_id": "raylib",
              "version": "6.0",
              "platform": "android",
              "install_type": "download",
              "sources": [
                {
                  "id": 1,
                  "name": "arm64",
                  "url": "packages/raylib/6.0/raylib-arm64-v8a.tar.xz",
                  "priority": 100,
                  "abi": "arm64-v8a",
                  "size": 512,
                  "checksum": "sha256:abc"
                }
              ]
            }
            """.trimIndent()
        )

        val source = info.sources.single()
        assertThat(source.abi).isEqualTo("arm64-v8a")
        assertThat(source.size).isEqualTo(512)
        assertThat(source.checksum).isEqualTo("sha256:abc")
    }
}
