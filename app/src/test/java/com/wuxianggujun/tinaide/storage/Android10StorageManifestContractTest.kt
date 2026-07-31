package com.wuxianggujun.tinaide.storage

import com.google.common.truth.Truth.assertThat
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Test

class Android10StorageManifestContractTest {

    @Test
    fun applicationOptsOutOfScopedStorageOnAndroid10() {
        val manifest = locateManifest()
        val documentBuilderFactory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setAttribute(ACCESS_EXTERNAL_DTD_PROPERTY, "")
            setAttribute(ACCESS_EXTERNAL_SCHEMA_PROPERTY, "")
        }
        val document = documentBuilderFactory.newDocumentBuilder().parse(manifest)
        val application = document.getElementsByTagName("application").item(0)

        assertThat(application).isNotNull()
        assertThat(
            application.attributes
                .getNamedItemNS(ANDROID_NAMESPACE, "requestLegacyExternalStorage")
                ?.nodeValue
        ).isEqualTo("true")
    }

    private fun locateManifest(): File {
        val candidates = listOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml"),
        )
        return candidates.firstOrNull(File::isFile)
            ?: error("Unable to locate app/src/main/AndroidManifest.xml")
    }

    private companion object {
        private const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
        private const val ACCESS_EXTERNAL_DTD_PROPERTY =
            "http://javax.xml.XMLConstants/property/accessExternalDTD"
        private const val ACCESS_EXTERNAL_SCHEMA_PROPERTY =
            "http://javax.xml.XMLConstants/property/accessExternalSchema"
    }
}
