package com.wuxianggujun.tinaide.core.network.registry

import android.app.Application
import android.content.Context
import com.google.common.truth.Truth.assertThat
import java.net.InetSocketAddress
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], manifest = Config.NONE)
class GitHubRegistryProxyConfigTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before
    fun resetPreferences() {
        check(context.getSharedPreferences("tinaide_github_registry_proxy", Context.MODE_PRIVATE).edit().clear().commit())
    }

    @Test
    fun saveNormalizesTheConfigurationSharedByMarketAndSettings() {
        GitHubRegistryProxyConfig.save(context, GitHubRegistryProxySettings(
            enabled = true, host = " proxy.example ", port = 7890,
            customMirrorUrl = " HTTPS://Mirror.Example/proxy/ ",
        ))

        assertThat(GitHubRegistryProxyConfig.load(context)).isEqualTo(GitHubRegistryProxySettings(
            enabled = true, host = "proxy.example", port = 7890,
            customMirrorUrl = "https://mirror.example/proxy/",
        ))
    }

    @Test
    fun observePublishesInitialAndChangedSettingsWithoutDuplicates() = runTest {
        val observed = mutableListOf<GitHubRegistryProxySettings>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            GitHubRegistryProxyConfig.observe(context).collect { observed += it }
        }
        try {
            runCurrent()
            val changed = GitHubRegistryProxySettings(customMirrorUrl = "https://mirror.example/")
            GitHubRegistryProxyConfig.save(context, changed)
            ShadowLooper.idleMainLooper()
            runCurrent()
            GitHubRegistryProxyConfig.save(context, changed)
            ShadowLooper.idleMainLooper()
            runCurrent()

            assertThat(observed).containsExactly(GitHubRegistryProxySettings(), changed).inOrder()
        } finally {
            collector.cancelAndJoin()
        }
    }

    @Test
    fun newHttpClientsUseCurrentProxyWithoutDnsOrChangingAnActiveClient() {
        GitHubRegistryProxyConfig.save(context, GitHubRegistryProxySettings(true, "127.0.0.1", 7890))
        val active = GitHubRegistryHttpClientFactory.download(context)
        val originalEndpoint = checkNotNull(active.proxy).address() as InetSocketAddress
        assertThat(originalEndpoint.isUnresolved).isTrue()
        assertThat(originalEndpoint.hostString).isEqualTo("127.0.0.1")

        GitHubRegistryProxyConfig.save(context, GitHubRegistryProxySettings(true, "127.0.0.2", 7891))
        val next = GitHubRegistryHttpClientFactory.download(context)
        val nextEndpoint = checkNotNull(next.proxy).address() as InetSocketAddress
        assertThat(nextEndpoint.isUnresolved).isTrue()
        assertThat(nextEndpoint.hostString).isEqualTo("127.0.0.2")
        assertThat(nextEndpoint.port).isEqualTo(7891)
        assertThat(originalEndpoint.port).isEqualTo(7890)
        assertThat(active.proxy?.address()).isEqualTo(originalEndpoint)

        GitHubRegistryProxyConfig.save(context, GitHubRegistryProxySettings())
        assertThat(GitHubRegistryHttpClientFactory.download(context).proxy).isNull()
    }
}
