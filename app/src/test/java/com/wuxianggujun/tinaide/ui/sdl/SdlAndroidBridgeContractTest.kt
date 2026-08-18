package com.wuxianggujun.tinaide.ui.sdl

import com.google.common.truth.Truth.assertThat
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Test

class SdlAndroidBridgeContractTest {

    @Test
    fun `routes SDL2 and SDL3 to different host activities`() {
        assertThat(externalSdlActivityClass(2)).isEqualTo(ExternalSdl2Activity::class.java)
        assertThat(externalSdlActivityClass(3)).isEqualTo(ExternalSdlActivity::class.java)
    }

    @Test
    fun `manifest isolates SDL2 and SDL3 native runtimes`() {
        val manifest = parseXml(projectFile("app/src/main/AndroidManifest.xml", "src/main/AndroidManifest.xml"))
        val activities = manifest.getElementsByTagName("activity")
        val processByActivity = buildMap {
            for (index in 0 until activities.length) {
                val activity = activities.item(index)
                val name = activity.attributes.getNamedItemNS(ANDROID_NAMESPACE, "name")?.nodeValue
                    ?: continue
                val process = activity.attributes.getNamedItemNS(ANDROID_NAMESPACE, "process")?.nodeValue
                    ?: continue
                put(name, process)
            }
        }

        assertThat(processByActivity[".ui.sdl.ExternalSdl2Activity"]).isEqualTo(":sdl2")
        assertThat(processByActivity[".ui.sdl.ExternalSdlActivity"]).isEqualTo(":sdl")
    }

    @Test
    fun `vendored SDL2 glue matches package builder contract`() {
        val activitySource = projectFile(
            "app/src/main/java/org/libsdl2/app/SDLActivity.java",
            "src/main/java/org/libsdl2/app/SDLActivity.java",
        ).readText(Charsets.UTF_8)
        val buildScript = projectFile(
            "docker/tinaide-pkg/libs/build-sdl2.sh",
            "../docker/tinaide-pkg/libs/build-sdl2.sh",
        ).readText(Charsets.UTF_8)
        val packageScript = projectFile(
            "docker/tinaide-pkg/package-sdl2.sh",
            "../docker/tinaide-pkg/package-sdl2.sh",
        ).readText(Charsets.UTF_8)

        assertThat(activitySource).contains("package org.libsdl2.app;")
        assertThat(activitySource).contains("SDL_MAJOR_VERSION = 2")
        assertThat(activitySource).contains("SDL_MINOR_VERSION = 32")
        assertThat(activitySource).contains("SDL_MICRO_VERSION = 10")
        assertThat(activitySource).contains("catch (UnsatisfiedLinkError error)")
        assertThat(activitySource).contains("continuing without HIDAPI")
        assertThat(buildScript).contains("SDL2_VERSION=\"2.32.10\"")
        assertThat(buildScript).contains("5d249570393f7a37e037abf22cd6012a4cc56a71")
        assertThat(buildScript).contains("org/libsdl2/app")
        assertThat(buildScript).contains("src/hidapi/android/hid.cpp")
        assertThat(buildScript).contains("Java_org_libsdl2_app_HIDDeviceManager_")
        assertThat(packageScript).contains("Java_org_libsdl2_app_HIDDeviceManager_")
        assertThat(packageScript).contains("SDL2_PACKAGE_VERSION=\"2.32.10.2\"")
        assertThat(packageScript).contains("SDL2_PACKAGE_REVISION=2")
        assertThat(packageScript).contains("\"packageRevision\": \${SDL2_PACKAGE_REVISION}")
        assertThat(packageScript).contains("Refusing to overwrite immutable SDL2 package")
    }

    private fun parseXml(file: File) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        setAttribute(ACCESS_EXTERNAL_DTD_PROPERTY, "")
        setAttribute(ACCESS_EXTERNAL_SCHEMA_PROPERTY, "")
    }.newDocumentBuilder().parse(file)

    private fun projectFile(vararg candidates: String): File = candidates
        .asSequence()
        .map(::File)
        .firstOrNull(File::isFile)
        ?: error("Unable to locate any of: ${candidates.joinToString()}")

    private companion object {
        private const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
        private const val ACCESS_EXTERNAL_DTD_PROPERTY =
            "http://javax.xml.XMLConstants/property/accessExternalDTD"
        private const val ACCESS_EXTERNAL_SCHEMA_PROPERTY =
            "http://javax.xml.XMLConstants/property/accessExternalSchema"
    }
}
