package com.wuxianggujun.tinaide.core.packages.store

import android.app.Application
import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.packages.model.Platform
import com.wuxianggujun.tinaide.core.packages.model.PlatformInstallState
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
}
