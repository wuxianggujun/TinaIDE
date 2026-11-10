package com.wuxianggujun.tinaide.core.packages

import android.content.Context
import android.util.Xml
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wuxianggujun.tinaide.core.packages.api.PackageApiClient
import com.wuxianggujun.tinaide.core.packages.model.InstallProgressEvent
import com.wuxianggujun.tinaide.core.packages.model.InstallResult
import com.wuxianggujun.tinaide.core.packages.model.Platform
import com.wuxianggujun.tinaide.core.packages.model.UninstallResult
import com.wuxianggujun.tinaide.core.packages.store.LocalInstallStateStore
import com.wuxianggujun.tinaide.ui.regressionEvidenceDirectory
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser

/** Real registry HTTPS and production manager. Use only on a dedicated test device. */
@RunWith(AndroidJUnit4::class)
class RegistryInstallDeviceTest {
    @Test fun downloadCancelRetryAndUninstall_keepFilesAndRecordsConsistent() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val packageId = InstrumentationRegistry.getArguments().getString("packageId", "fmt")
        val phase = InstrumentationRegistry.getArguments().getString("packagePhase", "install")
        val store = LocalInstallStateStore(context)
        val manager = PackageManagerImpl(context, PackageApiClient.getInstance(context), store)
        val directory = File(context.filesDir, "installed-packages/$packageId")
        val evidence = regressionEvidenceDirectory(context)
        if (phase == "verify-uninstalled") {
            assertFalse(directory.exists())
            assertFalse(store.isInstalled(packageId, Platform.ANDROID))
            assertFalse(persistedAndroidPackage(context, packageId))
            File(evidence, "registry-$phase.json").writeText(JSONObject().put("packageId", packageId)
                .put("passed", true).toString(2), Charsets.UTF_8)
            return@runBlocking
        }
        if (phase == "uninstall") {
            assertTrue("Install phase must complete first", store.isInstalled(packageId, Platform.ANDROID))
            val result = withTimeout(40_000) { manager.uninstall(packageId, Platform.ANDROID) }
            assertTrue(result.toString(), result is UninstallResult.Success)
            assertFalse(directory.exists())
            assertFalse(persistedAndroidPackage(context, packageId))
            assertFalse(manager.getInstalledPackages().any { it.packageId == packageId && it.platform == Platform.ANDROID })
            File(evidence, "registry-uninstall.json").writeText(JSONObject().put("packageId", packageId)
                .put("passed", true).toString(2), Charsets.UTF_8)
            return@runBlocking
        }
        assertTrue("Unknown test phase", phase in listOf("cancel", "install"))
        assertFalse("Fixture must not overwrite an existing installation", store.isInstalled(packageId, Platform.ANDROID))
        assertFalse("Fixture must not overwrite existing files", directory.exists())
        val events = StringBuilder()
        var completed = 0
        var cancelled = false
        var installed = false
        try {
            try {
                val result = withTimeout(40_000) {
                    manager.install(packageId, Platform.ANDROID) { event ->
                        if (event !is InstallProgressEvent.Downloading && events.length < 64 * 1024) events.appendLine(event)
                        if (phase == "cancel" && event is InstallProgressEvent.Verifying) {
                            throw CancellationException("Cancel verified archive")
                        }
                        if (event is InstallProgressEvent.Completed) {
                            completed++
                            assertTrue(store.isInstalled(packageId, Platform.ANDROID))
                            assertTrue("Completion requires a durable record, not only the process cache",
                                persistedAndroidPackage(context, packageId))
                        }
                    }
                }
                assertTrue(result.toString() + "\n" + events, result is InstallResult.Success)
                assertEquals(1, completed)
                assertNotNull(InstalledPackageMetadataReader.read(context, packageId))
                assertTrue(manager.getInstalledPackages().any { it.packageId == packageId })
                installed = true
            } catch (cancel: CancellationException) {
                if (cancel.message == "Cancel verified archive") cancelled = true else throw cancel
            }
            if (phase == "cancel") {
                assertTrue("Download never reached the cancellation point: $events", cancelled)
                assertFalse(directory.exists())
                assertFalse(store.isInstalled(packageId, Platform.ANDROID))
            }
            File(evidence, "registry-$phase.json").writeText(
                JSONObject().put("packageId", packageId).put("cancelledAfterVerifiedDownload", cancelled)
                    .put("completedEvents", completed).put("installed", installed).put("passed", true)
                    .toString(2), Charsets.UTF_8,
            )
        } finally {
            File(evidence, "registry-$phase.log").writeText(events.toString(), Charsets.UTF_8)
        }
    }

    private fun persistedAndroidPackage(context: Context, packageId: String): Boolean {
        val file = File(context.dataDir, "shared_prefs/package_install_state.xml")
        if (!file.isFile) return false
        return file.inputStream().use { input ->
            val parser = Xml.newPullParser().apply { setInput(input, "UTF-8") }
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "string" &&
                    parser.getAttributeValue(null, "name") == "installed_packages") {
                    val records = JSONArray(parser.nextText())
                    return@use (0 until records.length()).any { index ->
                        val record = records.getJSONObject(index)
                        record.optString("packageId") == packageId && record.optString("platform") == "android"
                    }
                }
            }
            false
        }
    }
}
