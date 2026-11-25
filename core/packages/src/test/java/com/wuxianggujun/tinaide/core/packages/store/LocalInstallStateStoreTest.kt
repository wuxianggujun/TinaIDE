package com.wuxianggujun.tinaide.core.packages.store

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.packages.model.Platform
import com.wuxianggujun.tinaide.core.packages.model.PlatformInstallState
import java.io.IOException
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [34])
class LocalInstallStateStoreTest {
    private val context = RuntimeEnvironment.getApplication()
    private val store = LocalInstallStateStore(context)

    @Before fun clear() { store.clear() }

    @Test fun failedDiskWriteCannotReportSuccessfulStateMutation() {
        val backing = context.getSharedPreferences("package_install_state", Context.MODE_PRIVATE)
        val preferences = object : SharedPreferences by backing {
            override fun edit(): SharedPreferences.Editor {
                val editor = backing.edit()
                return object : SharedPreferences.Editor by editor {
                    override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply {
                        editor.putString(key, value)
                    }
                    override fun clear(): SharedPreferences.Editor = apply { editor.clear() }
                    override fun commit() = false
                }
            }
        }
        val failingContext = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) = preferences
        }
        val failingStore = LocalInstallStateStore(failingContext)

        assertThat(runCatching { failingStore.setInstalled("fmt", Platform.ANDROID, "1.0") }.exceptionOrNull())
            .isInstanceOf(IOException::class.java)
        assertThat(runCatching { failingStore.setUninstalled("fmt", Platform.ANDROID) }.exceptionOrNull())
            .isInstanceOf(IOException::class.java)
        assertThat(runCatching { failingStore.clear() }.exceptionOrNull()).isInstanceOf(IOException::class.java)
    }

    @Test fun uninstallClearsOnlyThatPlatformsUpdateAndLateResponseCannotRestoreIt() {
        for (platform in Platform.entries) {
            store.setInstalled("fmt", platform, "1.0")
            store.setUpdateAvailable("fmt", platform, "1.0", "2.0")
        }
        store.setUninstalled("fmt", Platform.ANDROID)
        store.setUpdateAvailable("fmt", Platform.ANDROID, "1.0", "2.0")
        val reloaded = LocalInstallStateStore(context).getInstallState("fmt")
        assertThat(reloaded.android).isEqualTo(PlatformInstallState.NotInstalled)
        assertThat(reloaded.linux).isInstanceOf(PlatformInstallState.UpdateAvailable::class.java)
        store.setUninstalled("fmt", Platform.LINUX)
        assertThat(store.getAllInstalledPackages()).isEmpty()
        assertThat(store.getInstallState("fmt").linux.isInstalled).isFalse()
    }

    @Test fun legacyOrphanUpdateDoesNotMakePackageInstalled() {
        context.getSharedPreferences("package_install_state", Context.MODE_PRIVATE).edit()
            .putString("update_available", """[{"packageId":"fmt","platform":"android","currentVersion":"1.0","newVersion":"2.0"}]""")
            .commit()
        assertThat(store.getInstallState("fmt").android.isInstalled).isFalse()
    }

    @Test fun reinstallClearsStaleUpdateAndIgnoresOlderResponse() {
        store.setInstalled("fmt", Platform.ANDROID, "1.0")
        store.setUpdateAvailable("fmt", Platform.ANDROID, "1.0", "2.0")
        store.setInstalled("fmt", Platform.ANDROID, "2.0")
        store.setUpdateAvailable("fmt", Platform.ANDROID, "1.0", "2.0")
        assertThat(store.getInstallState("fmt").android).isInstanceOf(PlatformInstallState.Installed::class.java)
        assertThat(store.getInstalledVersion("fmt", Platform.ANDROID)).isEqualTo("2.0")
    }

    @Test fun corruptedRecordsArePreservedInsteadOfOverwrittenAsEmpty() {
        val prefs = context.getSharedPreferences("package_install_state", Context.MODE_PRIVATE)
        prefs.edit().putString("installed_packages", "{broken").commit()
        assertThat(runCatching { store.setInstalled("fmt", Platform.ANDROID, "1.0") }.isFailure).isTrue()
        assertThat(prefs.getString("installed_packages", null)).isEqualTo("{broken")
    }

    @Test fun unknownRecordValuesDoNotCrashOrBecomeUsableInstallations() {
        val prefs = context.getSharedPreferences("package_install_state", Context.MODE_PRIVATE)
        prefs.edit().putString("installed_packages", """[
            {"packageId":"fmt","packageName":"fmt","platform":"android","version":"1.0","installedAt":1,"installType":"download"},
            {"packageId":"future-platform","packageName":"future","platform":"future","version":"1.0","installedAt":1,"installType":"download"},
            {"packageId":"future-installer","packageName":"future","platform":"android","version":"1.0","installedAt":1,"installType":"future"},
            {"packageId":"../invalid","packageName":"invalid","platform":"android","version":"1.0","installedAt":1,"installType":"download"}
        ]""").commit()

        assertThat(store.getAllInstalledPackages().map { it.packageId }).containsExactly("fmt")
        assertThat(store.getInstallState("future-installer").android.isInstalled).isFalse()
        assertThat(store.isInstalled("future-installer", Platform.ANDROID)).isFalse()
        assertThat(store.getInstalledVersion("../invalid", Platform.ANDROID)).isNull()
        store.setInstalled("glm", Platform.ANDROID, "1.0")
        assertThat(prefs.getString("installed_packages", null)).contains("future-installer")
        assertThat(prefs.getString("installed_packages", null)).contains("future-platform")
        assertThat(store.getAllInstalledPackages().map { it.packageId }).containsExactly("fmt", "glm")
    }
}
