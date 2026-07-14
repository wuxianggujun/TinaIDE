package com.wuxianggujun.tinaide.core.linuxdistro

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import org.junit.Test

class LinuxDistroInstallationRegistryConcurrencyTest {

    @Test
    fun concurrentRegistryInstances_shouldPreserveEveryUpsert() {
        val tempDir = createTempDirectory("linux-distro-registry-concurrency").toFile()
        val registryFile = File(tempDir, "registry.json")
        val workerCount = 12
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(workerCount)

        try {
            val futures = (0 until workerCount).map { index ->
                executor.submit<InstalledLinuxDistro> {
                    start.await()
                    FileLinuxDistroInstallationRegistry(registryFile).upsert(installation(tempDir, index))
                }
            }

            start.countDown()
            futures.forEach { future -> future.get(10, TimeUnit.SECONDS) }

            assertThat(FileLinuxDistroInstallationRegistry(registryFile).list().map { it.distroId })
                .containsExactlyElementsIn((0 until workerCount).map { index -> "distro-$index" })
        } finally {
            executor.shutdownNow()
            tempDir.deleteRecursively()
        }
    }

    private fun installation(
        tempDir: File,
        index: Int,
    ): InstalledLinuxDistro = InstalledLinuxDistro(
        distroId = "distro-$index",
        displayName = "Distro $index",
        packageManager = DistroPackageManager.APK,
        rootfsPath = File(tempDir, "rootfs-$index").absolutePath,
        installedAtEpochMillis = index + 1L,
    )
}
