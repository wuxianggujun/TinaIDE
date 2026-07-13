package com.wuxianggujun.tinaide.core.packages

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.packages.api.PackageApiClient
import com.wuxianggujun.tinaide.core.packages.cache.PackageCacheManager
import com.wuxianggujun.tinaide.core.packages.model.PackageInstallState
import com.wuxianggujun.tinaide.core.packages.model.Platform
import com.wuxianggujun.tinaide.core.packages.model.UninstallResult
import com.wuxianggujun.tinaide.core.packages.store.LocalInstallStateStore
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

class PackageManagerIoDispatcherTest {
    @Test
    fun uninstall_entersConfiguredIoDispatcherBeforeReadingInstallState() = runTest {
        val callerThread = Thread.currentThread()
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "package-manager-io-test")
        }
        val dispatcher = executor.asCoroutineDispatcher()
        val installStateStore = mockk<LocalInstallStateStore>()
        var operationThread: Thread? = null
        every { installStateStore.getInstallState("demo") } answers {
            operationThread = Thread.currentThread()
            PackageInstallState()
        }

        val packageManager = PackageManagerImpl(
            context = mockk<Context>(relaxed = true),
            apiClient = mockk<PackageApiClient>(relaxed = true),
            installStateStore = installStateStore,
            cacheManager = mockk<PackageCacheManager>(relaxed = true),
            ioDispatcher = dispatcher,
        )

        try {
            val result = packageManager.uninstall("demo", Platform.ANDROID)

            assertThat(result).isInstanceOf(UninstallResult.Failure::class.java)
            assertThat(operationThread).isNotEqualTo(callerThread)
            assertThat(operationThread?.name).isEqualTo("package-manager-io-test")
        } finally {
            dispatcher.close()
            executor.shutdownNow()
        }
    }
}
