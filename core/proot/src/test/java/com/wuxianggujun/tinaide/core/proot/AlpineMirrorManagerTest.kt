package com.wuxianggujun.tinaide.core.proot

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.config.ConfigManager
import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AlpineMirrorManagerTest {
    private lateinit var context: Context
    private lateinit var testDir: File
    private lateinit var configManager: ConfigManager
    private lateinit var manager: AlpineMirrorManager

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication().applicationContext
        context.getSharedPreferences(CONFIG_PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        testDir = createTempDirectory("alpine-mirror-manager").toFile()
        configManager = ConfigManager(context, File(testDir, "config.json"))
        manager = AlpineMirrorManager(context, configManager)
    }

    @After
    fun tearDown() {
        context.getSharedPreferences(CONFIG_PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        testDir.deleteRecursively()
    }

    @Test
    fun selectMirror_shouldPersistPreferenceForFutureInstallations() {
        assertThat(manager.selectedMirror).isEqualTo(AlpineMirrorManager.Mirror.TSINGHUA)

        manager.selectMirror(AlpineMirrorManager.Mirror.HUAWEI)

        assertThat(AlpineMirrorManager(context, configManager).selectedMirror)
            .isEqualTo(AlpineMirrorManager.Mirror.HUAWEI)
    }

    @Test
    fun applySelectedMirror_shouldUseMajorMinorVersionFromAlpineRelease() {
        val rootfsDir = createRootfs(alpineRelease = "3.23.4\n")
        manager.selectMirror(AlpineMirrorManager.Mirror.ALIYUN)

        manager.applySelectedMirror(rootfsDir)

        assertThat(File(rootfsDir, "etc/apk/repositories").readText(Charsets.UTF_8)).isEqualTo(
            "https://mirrors.aliyun.com/alpine/v3.23/main\n" +
                "https://mirrors.aliyun.com/alpine/v3.23/community\n"
        )
    }

    @Test
    fun applySelectedMirror_shouldFallBackToVersionInExistingRepositories() {
        val rootfsDir = createRootfs(alpineRelease = null)
        File(rootfsDir, "etc/apk/repositories").apply {
            parentFile?.mkdirs()
            writeText("https://dl-cdn.alpinelinux.org/alpine/v3.22/main\n", Charsets.UTF_8)
        }
        manager.selectMirror(AlpineMirrorManager.Mirror.USTC)

        manager.applySelectedMirror(rootfsDir)

        assertThat(File(rootfsDir, "etc/apk/repositories").readLines(Charsets.UTF_8)).containsExactly(
            "https://mirrors.ustc.edu.cn/alpine/v3.22/main",
            "https://mirrors.ustc.edu.cn/alpine/v3.22/community",
        ).inOrder()
    }

    @Test
    fun detectMirror_shouldRecognizeKnownMirrorAndRejectMixedRepositories() {
        val rootfsDir = createRootfs(alpineRelease = "3.23.4")
        val repositories = File(rootfsDir, "etc/apk/repositories").apply { parentFile?.mkdirs() }
        repositories.writeText(
            "https://mirrors.cloud.tencent.com/alpine/v3.23/main\n" +
                "https://mirrors.cloud.tencent.com/alpine/v3.23/community\n",
            Charsets.UTF_8,
        )

        assertThat(manager.detectMirror(rootfsDir.absolutePath))
            .isEqualTo(AlpineMirrorManager.Mirror.TENCENT)

        repositories.appendText("https://custom.example/alpine/v3.23/testing\n", Charsets.UTF_8)
        assertThat(manager.detectMirror(rootfsDir.absolutePath)).isNull()
    }

    @Test
    fun orderRootfsDownloadCandidates_shouldPutSelectedMirrorFirstAndKeepFallbacks() {
        manager.selectMirror(AlpineMirrorManager.Mirror.HUAWEI)
        val canonical = "https://dl-cdn.alpinelinux.org/alpine/v3.23/releases/aarch64/rootfs.tar.gz"
        val tsinghua = "https://mirrors.tuna.tsinghua.edu.cn/alpine/v3.23/releases/aarch64/rootfs.tar.gz"

        val candidates = manager.orderRootfsDownloadCandidates(
            canonicalUrl = canonical,
            mirrorUrls = listOf(tsinghua, canonical),
        )

        assertThat(candidates).containsExactly(
            "https://repo.huaweicloud.com/alpine/v3.23/releases/aarch64/rootfs.tar.gz",
            canonical,
            tsinghua,
        ).inOrder()
    }

    private fun createRootfs(alpineRelease: String?): File = File(testDir, "rootfs-${System.nanoTime()}").apply {
        mkdirs()
        if (alpineRelease != null) {
            File(this, "etc/alpine-release").apply {
                parentFile?.mkdirs()
                writeText(alpineRelease, Charsets.UTF_8)
            }
        }
    }

    private companion object {
        const val CONFIG_PREFS_NAME = "tinaide_config"
    }
}
