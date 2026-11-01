package com.wuxianggujun.tinaide.core.apkbuilder

import android.Manifest
import android.content.pm.PermissionInfo
import com.reandroid.app.AndroidManifest
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import com.reandroid.arsc.chunk.xml.ResXmlElement
import com.reandroid.arsc.value.ValueType
import java.io.ByteArrayInputStream
import timber.log.Timber

/**
 * Patches the template AndroidManifest.xml as binary AXML.
 *
 * Rebase package-owned identifiers without renaming the template's bytecode.
 * Device permissions come from the export selection; template-private signature
 * permissions stay paired with their declarations for AndroidX runtime receivers.
 */
object ManifestPatcher {

    private const val TAG = "ManifestPatcher"

    private val permissionAttributeOverrides = mapOf(
        Manifest.permission.WRITE_EXTERNAL_STORAGE to PermissionAttributeOverride(maxSdkVersion = 29)
    )

    private val permissionReferenceAttributes = intArrayOf(
        android.R.attr.permission,
        android.R.attr.readPermission,
        android.R.attr.writePermission,
        android.R.attr.permissionGroup
    )

    private val permissionDeclarationTags = setOf("permission", "permission-tree", "permission-group")

    fun patch(axml: ByteArray, config: ApkBuildConfig): ByteArray {
        val manifest = AndroidManifestBlock.load(ByteArrayInputStream(axml))
        val requestedPermissions = normalizeRequestedPermissions(config.requestedPermissions)
        val privatePermissions = patchPackageScopedIdentifiers(manifest, config.packageName)

        manifest.setPackageName(config.packageName)
        manifest.setVersionCode(config.versionCode)
        manifest.setVersionName(config.versionName)
        patchApplicationLabel(manifest, config.appName)
        patchUsesPermissions(manifest, (requestedPermissions + privatePermissions).distinct())
        manifest.refreshFull()

        val patched = manifest.getBytes()
        Timber.tag(TAG).d(
            "Manifest patched: package=%s, appName=%s, permissions=%s",
            config.packageName,
            config.appName,
            requestedPermissions.joinToString(",")
        )
        return patched
    }

    @Suppress("DEPRECATION") // Raw AXML encodes the base protection and flags in one integer.
    private fun patchPackageScopedIdentifiers(manifest: AndroidManifestBlock, newPackage: String): List<String> {
        val originalPackage = manifest.getPackageName()?.takeIf { it.isNotBlank() } ?: return emptyList()
        val root = manifest.getManifestElement() ?: return emptyList()
        val signaturePermissions = root.getElements("permission").asSequence()
            .filter { element ->
                val protection = element.searchAttributeByResourceId(android.R.attr.protectionLevel)?.data ?: 0
                (protection and PermissionInfo.PROTECTION_MASK_BASE) == PermissionInfo.PROTECTION_SIGNATURE
            }
            .mapNotNull { it.searchAttributeByResourceId(android.R.attr.name)?.getValueAsString() }
            .filter { it.startsWith("$originalPackage.") }
            .toSet()
        val privatePermissions = manifest.getUsesPermissions()
            .filter { it in signaturePermissions }
            .map { rebaseIdentifier(it, originalPackage, newPackage) }

        patchElementIdentifiers(root, originalPackage, newPackage)
        return privatePermissions
    }

    private fun patchElementIdentifiers(element: ResXmlElement, originalPackage: String, newPackage: String) {
        if (element.equalsName("provider")) {
            patchIdentifierAttribute(element, android.R.attr.authorities, originalPackage, newPackage)
        }
        if (element.getName() in permissionDeclarationTags) {
            patchIdentifierAttribute(element, android.R.attr.name, originalPackage, newPackage)
        }
        permissionReferenceAttributes.forEach { attributeId ->
            patchIdentifierAttribute(element, attributeId, originalPackage, newPackage)
        }
        element.getElements().forEach { child -> patchElementIdentifiers(child, originalPackage, newPackage) }
    }

    private fun patchIdentifierAttribute(
        element: ResXmlElement,
        attributeId: Int,
        originalPackage: String,
        newPackage: String
    ) {
        val attribute = element.searchAttributeByResourceId(attributeId) ?: return
        if (attribute.getValueType() != ValueType.STRING) return
        val original = attribute.getValueAsString() ?: return
        // A provider can declare several authorities; never replace a partial package prefix.
        val rewritten = original.split(';').joinToString(";") { rebaseIdentifier(it, originalPackage, newPackage) }
        if (rewritten != original) attribute.setValueAsString(rewritten)
    }

    private fun rebaseIdentifier(value: String, originalPackage: String, newPackage: String): String =
        if (value == originalPackage || value.startsWith("$originalPackage.")) {
            newPackage + value.removePrefix(originalPackage)
        } else {
            value
        }

    private fun patchApplicationLabel(manifest: AndroidManifestBlock, appName: String) {
        val applicationElement = manifest.getOrCreateApplicationElement()
        applicationElement
            .getOrCreateAndroidAttribute(AndroidManifest.NAME_label, AndroidManifest.ID_label)
            .setValueAsString(appName)
    }

    private fun patchUsesPermissions(
        manifest: AndroidManifestBlock,
        requestedPermissions: List<String>
    ) {
        val manifestElement = manifest.getManifestElement() ?: return
        manifestElement.removeElementsIf { element -> element.equalsName(AndroidManifest.TAG_uses_permission) }

        requestedPermissions.forEach { permissionName ->
            val permissionElement = manifest.addUsesPermission(permissionName) ?: return@forEach
            applyPermissionAttributes(permissionElement, permissionName)
        }
    }

    private fun applyPermissionAttributes(permissionElement: ResXmlElement, permissionName: String) {
        val override = permissionAttributeOverrides[permissionName] ?: return
        override.maxSdkVersion?.let { maxSdkVersion ->
            permissionElement
                .getOrCreateAndroidAttribute(AndroidManifest.NAME_maxSdkVersion, AndroidManifest.ID_maxSdkVersion)
                .setTypeAndData(ValueType.DEC, maxSdkVersion)
        }
    }

    private fun normalizeRequestedPermissions(permissions: List<String>): List<String> = permissions
        .asSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .toList()

    private data class PermissionAttributeOverride(
        val maxSdkVersion: Int? = null
    )
}
