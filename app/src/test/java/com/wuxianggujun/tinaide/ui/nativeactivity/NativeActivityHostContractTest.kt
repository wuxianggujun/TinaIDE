package com.wuxianggujun.tinaide.ui.nativeactivity

import com.google.common.truth.Truth.assertThat
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Test

class NativeActivityHostContractTest {

    @Test
    fun `manifest routes native activity through isolated host library`() {
        val manifest = parseXml(
            projectFile("app/src/main/AndroidManifest.xml", "src/main/AndroidManifest.xml")
        )
        val activities = manifest.getElementsByTagName("activity")
        var processName: String? = null
        var hostLibraryName: String? = null

        for (index in 0 until activities.length) {
            val activity = activities.item(index)
            val activityName = activity.attributes
                .getNamedItemNS(ANDROID_NAMESPACE, "name")
                ?.nodeValue
            if (activityName != ".ui.nativeactivity.ExternalNativeActivity") continue

            processName = activity.attributes
                .getNamedItemNS(ANDROID_NAMESPACE, "process")
                ?.nodeValue
            val children = activity.childNodes
            for (childIndex in 0 until children.length) {
                val child = children.item(childIndex)
                if (child.nodeName != "meta-data") continue
                val metadataName = child.attributes
                    ?.getNamedItemNS(ANDROID_NAMESPACE, "name")
                    ?.nodeValue
                if (metadataName == "android.app.lib_name") {
                    hostLibraryName = child.attributes
                        ?.getNamedItemNS(ANDROID_NAMESPACE, "value")
                        ?.nodeValue
                }
            }
        }

        assertThat(processName).isEqualTo(":gui")
        assertThat(hostLibraryName).isEqualTo("tina_native_activity_host")
    }

    @Test
    fun `native host preserves raylib shared entry contract`() {
        val hostSource = projectFile(
            "app/src/main/cpp/native_activity/native_activity_host.c",
            "src/main/cpp/native_activity/native_activity_host.c",
        ).readText(Charsets.UTF_8)
        val cmake = projectFile(
            "app/src/main/cpp/CMakeLists.txt",
            "src/main/cpp/CMakeLists.txt",
        ).readText(Charsets.UTF_8)
        val raylibBuild = projectFile(
            "docker/tinaide-pkg/libs/build-raylib.sh",
            "../docker/tinaide-pkg/libs/build-raylib.sh",
        ).readText(Charsets.UTF_8)

        assertThat(hostSource).contains("visibility(\"default\"))) int main")
        assertThat(hostSource).contains("resolve_owned_symbol")
        assertThat(hostSource).contains("RTLD_NOW | RTLD_GLOBAL")
        assertThat(cmake).contains("-Wl,-z,global")
        assertThat(raylibBuild).contains("ANativeActivity_onCreate")
        assertThat(raylibBuild).contains("undefined main contract")
        assertThat(raylibBuild).contains("unexpectedly depends on SDL")
    }

    @Test
    fun `all graphical activities install the shared floating return host`() {
        val activitySources = listOf(
            projectFile(
                "app/src/main/java/com/wuxianggujun/tinaide/ui/sdl/ExternalSdlActivity.kt",
                "src/main/java/com/wuxianggujun/tinaide/ui/sdl/ExternalSdlActivity.kt",
            ),
            projectFile(
                "app/src/main/java/com/wuxianggujun/tinaide/ui/sdl/ExternalSdl2Activity.kt",
                "src/main/java/com/wuxianggujun/tinaide/ui/sdl/ExternalSdl2Activity.kt",
            ),
            projectFile(
                "app/src/main/java/com/wuxianggujun/tinaide/ui/nativeactivity/ExternalNativeActivity.kt",
                "src/main/java/com/wuxianggujun/tinaide/ui/nativeactivity/ExternalNativeActivity.kt",
            ),
        ).map { it.readText(Charsets.UTF_8) }

        activitySources.forEach { source ->
            assertThat(source).contains("GraphicalRuntimeActivityHost")
            assertThat(source).contains("attachOverlay(")
            assertThat(source).contains("enableFloatingLog")

            val nativeDestroyIndex = source.indexOf("super.onDestroy()")
            val sharedHostDestroyIndex = source.indexOf("runtimeHost.onDestroy()")
            val processTerminationIndex = source.indexOf("Process.killProcess(Process.myPid())")
            assertThat(nativeDestroyIndex).isAtLeast(0)
            assertThat(sharedHostDestroyIndex).isGreaterThan(nativeDestroyIndex)
            assertThat(source).contains("isFinishing && !isChangingConfigurations")
            assertThat(processTerminationIndex).isGreaterThan(sharedHostDestroyIndex)
        }

        val nativeActivitySource = activitySources.last()
        assertThat(nativeActivitySource).doesNotContain("FLAG_ACTIVITY_CLEAR_TOP")
    }

    @Test
    fun `shared overlay keeps return and log actions independent`() {
        val overlaySource = projectFile(
            "app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/GraphicalRuntimeOverlay.kt",
            "src/main/java/com/wuxianggujun/tinaide/ui/compose/components/GraphicalRuntimeOverlay.kt",
        ).readText(Charsets.UTF_8)

        assertThat(overlaySource).contains("onTap = { showExitDialog = true }")
        assertThat(overlaySource).contains("onTap = { expanded = !expanded }")
    }

    @Test
    fun `graphical runtime host attaches saved state before restore`() {
        val hostSource = projectFile(
            "app/src/main/java/com/wuxianggujun/tinaide/ui/runtime/GraphicalRuntimeActivityHost.kt",
            "src/main/java/com/wuxianggujun/tinaide/ui/runtime/GraphicalRuntimeActivityHost.kt",
        ).readText(Charsets.UTF_8)

        val ownerSource = hostSource.substringAfter(
            "private class GraphicalRuntimeViewTreeOwner",
            missingDelimiterValue = "",
        )
        assertThat(ownerSource).isNotEmpty()

        val attachIndex = ownerSource.indexOf("savedStateController.performAttach()")
        val restoreMethodIndex = ownerSource.indexOf("fun performRestore(savedInstanceState: Bundle?)")
        assertThat(attachIndex).isAtLeast(0)
        assertThat(restoreMethodIndex).isGreaterThan(attachIndex)

        val preCreateSource = hostSource
            .substringAfter("fun onPreCreate(", missingDelimiterValue = "")
            .substringBefore("fun attachOverlay(")
        assertThat(preCreateSource).contains("viewTreeOwner.performRestore(savedInstanceState)")
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
