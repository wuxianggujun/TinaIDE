package com.wuxianggujun.tinaide.core.apkbuilder

import android.Manifest
import android.content.pm.PermissionInfo
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
    fun patch_removesAllPermissionsWhenTemplateHasNoPrivateDeclarations() {
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

    @Test
    fun patch_scopesProviderAuthoritiesAcrossBothBundledGraphicalTemplates() {
        listOf(ApkTemplateType.NATIVE_ACTIVITY, ApkTemplateType.SDL3).forEach { templateType ->
            val bytes = loadTemplateManifestBytes(templateType)
            val original = AndroidManifestBlock.load(bytes.inputStream())
            val originalPackage = original.getPackageName()
            val authorities = providerAuthorities(original)
            assertThat(authorities).isNotEmpty()

            val first = patchManifest(bytes, "com.example.first", templateType)
            val second = patchManifest(bytes, "com.example.second", templateType)

            assertThat(providerAuthorities(first)).containsExactlyElementsIn(
                authorities.map { it.replaceFirst("$originalPackage.", "com.example.first.") }
            )
            assertThat(providerAuthorities(second)).containsExactlyElementsIn(
                authorities.map { it.replaceFirst("$originalPackage.", "com.example.second.") }
            )
            assertThat(providerAuthorities(first).intersect(providerAuthorities(second).toSet())).isEmpty()
        }
    }

    @Test
    fun patch_rebasesOnlyOwnedAuthorityTokensAndNeverRenamesComponentClasses() {
        val original = AndroidManifestBlock.load(loadTemplateManifestBytes().inputStream())
        val originalPackage = original.getPackageName()
        val provider = original.getOrCreateApplicationElement().getElements("provider").next()
        provider.getOrCreateAndroidAttribute("authorities", android.R.attr.authorities)
            .setValueAsString("$originalPackage.files;$originalPackage;$originalPackage.other.files;third.party.files")
        provider.getOrCreateAndroidAttribute("name", android.R.attr.name)
            .setValueAsString("$originalPackage.ProviderImplementation")
        original.refreshFull()

        val patched = patchManifest(original.getBytes(), "com.example.exported")
        val patchedProvider = patched.getOrCreateApplicationElement().getElements("provider").next()
        assertThat(providerAuthorities(patched)).containsExactly(
            "com.example.exported.files;com.example.exported;com.example.exported.other.files;third.party.files"
        )
        assertThat(patchedProvider.searchAttributeByResourceId(android.R.attr.name).getValueAsString())
            .isEqualTo("$originalPackage.ProviderImplementation")

        // Similar textual prefixes are not owned by the template package.
        provider.getOrCreateAndroidAttribute("authorities", android.R.attr.authorities)
            .setValueAsString("${originalPackage}Else.files")
        original.refreshFull()
        assertThat(providerAuthorities(patchManifest(original.getBytes(), "com.example.exported")))
            .containsExactly("${originalPackage}Else.files")
    }

    @Test
    fun patch_rebasesPrivatePermissionDefinitionsAndReferencesWithoutGrantingDevicePermissions() {
        val original = AndroidManifestBlock.load(loadTemplateManifestBytes(ApkTemplateType.SDL3).inputStream())
        val originalPackage = original.getPackageName()
        val permission = original.getManifestElement().newElement("permission")
        permission.getOrCreateAndroidAttribute("name", android.R.attr.name)
            .setValueAsString("$originalPackage.PRIVATE")
        permission.getOrCreateAndroidAttribute("protectionLevel", android.R.attr.protectionLevel)
            .setTypeAndData(ValueType.HEX, PermissionInfo.PROTECTION_SIGNATURE)
        original.addUsesPermission("$originalPackage.PRIVATE")
        original.addUsesPermission(Manifest.permission.INTERNET)
        val provider = original.getOrCreateApplicationElement().getElements("provider").next()
        provider.getOrCreateAndroidAttribute("readPermission", android.R.attr.readPermission)
            .setValueAsString("$originalPackage.PRIVATE")
        provider.getOrCreateAndroidAttribute("writePermission", android.R.attr.writePermission)
            .setValueAsString("third.party.WRITE")
        original.refreshFull()

        val patched = patchManifest(original.getBytes(), "com.example.permissions", ApkTemplateType.SDL3)
        val patchedProvider = patched.getOrCreateApplicationElement().getElements("provider").next()
        val definitions = patched.getManifestElement().getElements("permission").asSequence()
            .map { it.searchAttributeByResourceId(android.R.attr.name).getValueAsString() }.toList()
        assertThat(definitions).containsExactly(
            "com.example.permissions.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
            "com.example.permissions.PRIVATE"
        )
        assertThat(patched.getUsesPermissions()).containsExactlyElementsIn(definitions)
        assertThat(patchedProvider.searchAttributeByResourceId(android.R.attr.readPermission).getValueAsString())
            .isEqualTo("com.example.permissions.PRIVATE")
        assertThat(patchedProvider.searchAttributeByResourceId(android.R.attr.writePermission).getValueAsString())
            .isEqualTo("third.party.WRITE")
    }

    @Test
    fun patch_canRebaseAnAlreadyPatchedManifestWithoutStaleIdentifiers() {
        val first = patchManifest(loadTemplateManifestBytes(ApkTemplateType.SDL3), "com.example.first", ApkTemplateType.SDL3)
        val second = patchManifest(first.getBytes(), "com.example.second", ApkTemplateType.SDL3)

        assertThat(providerAuthorities(second)).containsExactly("com.example.second.androidx-startup")
        assertThat(second.getUsesPermissions()).containsExactly(
            "com.example.second.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
        )
    }

    @Test
    fun patch_doesNotPreserveUnselectedNonSignatureOrThirdPartyPermissions() {
        val original = AndroidManifestBlock.load(loadTemplateManifestBytes(ApkTemplateType.SDL3).inputStream())
        val originalPackage = original.getPackageName()
        val permission = original.getManifestElement().newElement("permission")
        permission.getOrCreateAndroidAttribute("name", android.R.attr.name)
            .setValueAsString("$originalPackage.UNSELECTED")
        permission.getOrCreateAndroidAttribute("protectionLevel", android.R.attr.protectionLevel)
            .setTypeAndData(ValueType.HEX, PermissionInfo.PROTECTION_NORMAL)
        original.addUsesPermission("$originalPackage.UNSELECTED")
        original.addUsesPermission("third.party.UNSELECTED")
        original.refreshFull()

        val patched = patchManifest(original.getBytes(), "com.example.selected", ApkTemplateType.SDL3)
        assertThat(patched.getUsesPermissions()).containsExactly(
            "com.example.selected.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
        )
    }

    private fun providerAuthorities(manifest: AndroidManifestBlock): List<String> =
        manifest.getOrCreateApplicationElement().getElements("provider").asSequence()
            .map { it.searchAttributeByResourceId(android.R.attr.authorities).getValueAsString() }.toList()

    private fun patchManifest(
        bytes: ByteArray,
        packageName: String,
        templateType: ApkTemplateType = ApkTemplateType.NATIVE_ACTIVITY
    ): AndroidManifestBlock = AndroidManifestBlock.load(
        ManifestPatcher.patch(
            bytes,
            ApkBuildConfig(
                soFiles = emptyList(),
                targetAbis = listOf("x86_64"),
                packageName = packageName,
                appName = "Exported Test",
                templateType = templateType
            )
        ).inputStream()
    )

    private fun loadTemplateManifestBytes(
        templateType: ApkTemplateType = ApkTemplateType.NATIVE_ACTIVITY
    ): ByteArray {
        val templateApk = findProjectFile("app/src/main/assets/apk_templates/${templateType.templateFileName}")
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
