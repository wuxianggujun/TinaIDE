package com.wuxianggujun.tinaide.core.apkbuilder

import android.Manifest
import com.google.common.truth.Truth.assertThat
import com.reandroid.app.AndroidManifest
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import com.reandroid.arsc.value.ValueType
import java.io.File
import java.util.zip.ZipFile
import org.junit.Test

class ManifestPatcherTest {

    @Test
    fun patch_updatesManifestMetadataAndPermissions() {
        val patchedBytes = ManifestPatcher.patch(
            loadTemplateManifestBytes(),
            ApkBuildConfig(
                soFiles = emptyList<File>(),
                targetAbis = listOf("arm64-v8a"),
                packageName = "com.example.dynamic",
                appName = "Dynamic Export",
                versionCode = 7,
                versionName = "2.5.1",
                requestedPermissions = listOf(
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.RECORD_AUDIO
                ),
                templateType = ApkTemplateType.NATIVE_ACTIVITY
            )
        )

        val patchedManifest = AndroidManifestBlock.load(patchedBytes.inputStream())

        assertThat(patchedManifest.getPackageName()).isEqualTo("com.example.dynamic")
        assertThat(patchedManifest.getVersionCode()).isEqualTo(7)
        assertThat(patchedManifest.getVersionName()).isEqualTo("2.5.1")
        assertThat(patchedManifest.getApplicationLabelString()).isEqualTo("Dynamic Export")
        assertThat(patchedManifest.getUsesPermissions()).containsExactly(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        ).inOrder()

        val writePermission = patchedManifest.getUsesPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        val maxSdkAttribute = writePermission?.searchAttributeByResourceId(AndroidManifest.ID_maxSdkVersion)
        assertThat(maxSdkAttribute?.getValueType()).isEqualTo(ValueType.DEC)
        assertThat(maxSdkAttribute?.data).isEqualTo(29)
    }

    @Test
    fun patch_removesAllPermissionsWhenExportConfigIsEmpty() {
        val patchedBytes = ManifestPatcher.patch(
            loadTemplateManifestBytes(),
            ApkBuildConfig(
                soFiles = emptyList<File>(),
                targetAbis = listOf("arm64-v8a"),
                packageName = "com.example.empty",
                appName = "Empty",
                requestedPermissions = emptyList(),
                templateType = ApkTemplateType.NATIVE_ACTIVITY
            )
        )

        val patchedManifest = AndroidManifestBlock.load(patchedBytes.inputStream())
        assertThat(patchedManifest.getUsesPermissions()).isEmpty()
    }

    private fun loadTemplateManifestBytes(): ByteArray {
        val templateApk = findProjectFile("app/src/main/assets/apk_templates/template-native-activity.apk")
        check(templateApk.exists()) { "Template APK not found: ${templateApk.absolutePath}" }

        ZipFile(templateApk).use { zip ->
            val entry = zip.getEntry("AndroidManifest.xml")
            checkNotNull(entry) { "AndroidManifest.xml not found in template APK" }
            return zip.getInputStream(entry).use { it.readBytes() }
        }
    }

    private fun findProjectFile(relativePath: String): File {
        val workingDir = File(System.getProperty("user.dir") ?: error("user.dir is unavailable"))
        return generateSequence(workingDir) { current -> current.parentFile }
            .map { candidateRoot -> File(candidateRoot, relativePath) }
            .firstOrNull(File::exists)
            ?: File(workingDir, relativePath)
    }
}
