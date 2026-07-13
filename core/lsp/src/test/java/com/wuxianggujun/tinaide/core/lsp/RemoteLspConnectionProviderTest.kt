package com.wuxianggujun.tinaide.core.lsp

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.i18n.AppStrings
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class RemoteLspConnectionProviderTest {
    @Before
    fun setUp() {
        AppStrings.initialize(RuntimeEnvironment.getApplication())
    }

    @Test
    fun close_shouldBeIdempotentAndPreventRestartOrReconnect() = runTest {
        val provider = RemoteLspConnectionProvider(
            host = "127.0.0.1",
            port = 1,
            autoReconnect = true,
        )

        provider.close()
        provider.close()
        provider.reconnect()

        assertThat(provider.isConnected()).isFalse()
        assertThat(provider.connectionState.value).isEqualTo(ConnectionState.DISCONNECTED)
        assertThat(provider.startAsync().isFailure).isTrue()
    }

    @Test
    fun closeFromConnectingListener_shouldKeepTerminalDisconnectedState() = runTest {
        val provider = RemoteLspConnectionProvider(
            host = "127.0.0.1",
            port = 1,
            autoReconnect = true,
        )
        provider.addStateListener(
            object : ConnectionStateListener {
                override fun onStateChanged(state: ConnectionState) {
                    if (state == ConnectionState.CONNECTING) provider.close()
                }

                override fun onEvent(event: ConnectionEvent) = Unit
            },
        )

        assertThat(provider.startAsync().isFailure).isTrue()
        assertThat(provider.connectionState.value).isEqualTo(ConnectionState.DISCONNECTED)
        assertThat(provider.isConnected()).isFalse()
    }
}
