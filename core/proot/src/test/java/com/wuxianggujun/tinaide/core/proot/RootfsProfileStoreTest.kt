package com.wuxianggujun.tinaide.core.proot

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.config.ConfigManager
import com.wuxianggujun.tinaide.storage.ProjectPaths
import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class RootfsProfileStoreTest {
    private lateinit var context: Context
    private lateinit var testDir: File
    private lateinit var store: RootfsProfileStore

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication().applicationContext
        File(ProjectPaths.getPRootRoot(context), "rootfs_profiles.json").delete()
        testDir = createTempDirectory("rootfs-profile-store").toFile()
        store = RootfsProfileStore(
            context = context,
            configManager = ConfigManager(context, File(testDir, "config.json")),
        )
    }

    @After
    fun tearDown() {
        File(ProjectPaths.getPRootRoot(context), "rootfs_profiles.json").delete()
        testDir.deleteRecursively()
    }

    @Test
    fun activeProfileForDistro_shouldResolveUbuntuWithoutUsingAnotherDistro() {
        val alpine = profile(
            id = "linux-distro:alpine",
            distroId = "alpine",
            packageManager = RootfsPackageManager.APK,
        )
        val ubuntu = profile(
            id = "linux-distro:ubuntu",
            distroId = "ubuntu",
            packageManager = RootfsPackageManager.APT,
        )

        store.upsertProfile(alpine, makeActive = true)
        store.upsertProfile(ubuntu, makeActive = false)

        assertThat(store.getActiveProfile().id).isEqualTo(alpine.id)
        assertThat(store.getActiveProfileForDistro("ubuntu")?.id).isEqualTo(ubuntu.id)
        assertThat(store.listProfilesForDistro("ubuntu").map { profile -> profile.id })
            .containsExactly(ubuntu.id)
    }

    @Test
    fun activeProfileForDistro_shouldPreferTheExplicitlySelectedUbuntuProfile() {
        val first = profile(
            id = "linux-distro:ubuntu",
            distroId = "ubuntu",
            packageManager = RootfsPackageManager.APT,
        )
        val second = profile(
            id = "linux-distro:ubuntu-secondary",
            distroId = "ubuntu",
            packageManager = RootfsPackageManager.APT,
        )

        store.upsertProfile(first, makeActive = true)
        store.upsertProfile(second, makeActive = false)
        store.setActiveProfileForDistro(profileId = second.id, distroId = "ubuntu")

        assertThat(store.getActiveProfileForDistro("ubuntu")?.id).isEqualTo(second.id)
    }

    @Test
    fun setActiveProfileForDistro_shouldRejectProfilesFromAnotherDistro() {
        val alpine = profile(
            id = "linux-distro:alpine",
            distroId = "alpine",
            packageManager = RootfsPackageManager.APK,
        )
        store.upsertProfile(alpine, makeActive = true)

        assertThrows(IllegalArgumentException::class.java) {
            store.setActiveProfileForDistro(profileId = alpine.id, distroId = "ubuntu")
        }
    }

    private fun profile(
        id: String,
        distroId: String,
        packageManager: RootfsPackageManager,
    ): RootfsProfile = RootfsProfile(
        id = id,
        displayName = distroId.replaceFirstChar(Char::uppercase),
        distroId = distroId,
        distroName = distroId,
        rootfsPath = File(testDir, distroId).absolutePath,
        sourceType = RootfsSourceType.LINUX_DISTRO,
        packageManager = packageManager,
        createdAt = 1L,
        updatedAt = 1L,
    )
}
