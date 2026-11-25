package com.wuxianggujun.tinaide.core.apkbuilder

import com.google.common.truth.Truth.assertThat
import com.reandroid.apk.ApkModule
import java.io.File
import java.util.jar.JarFile
import org.junit.Test

class ArscLibAndroidRuntimeTest {

    @Test
    fun runtimeArtifact_doesNotRedefineAndroidFrameworkInterfaces() {
        val location = checkNotNull(ApkModule::class.java.protectionDomain?.codeSource?.location) {
            "ARSCLib runtime artifact location is unavailable"
        }
        val artifact = File(location.toURI())

        JarFile(artifact).use { jar ->
            val platformClasses = jar.entries().asSequence()
                .map { it.name }
                .filter { it.endsWith(".class") }
                .filter { it.startsWith("android/") || it.startsWith("org/xmlpull/") }
                .toList()

            // These must come from android.jar/the device, not an optimizable program JAR.
            assertThat(platformClasses).isEmpty()
            assertThat(jar.getJarEntry("com/reandroid/apk/ApkModule.class")).isNotNull()
            assertThat(jar.getJarEntry("com/reandroid/arsc/chunk/xml/AndroidManifestBlock.class"))
                .isNotNull()
        }
    }
}
