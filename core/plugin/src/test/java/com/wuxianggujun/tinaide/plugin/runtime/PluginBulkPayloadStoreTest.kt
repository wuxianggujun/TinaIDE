package com.wuxianggujun.tinaide.plugin.runtime

import android.app.Application
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    manifest = Config.NONE,
    application = Application::class,
)
class PluginBulkPayloadStoreTest {

    @Test
    fun `pending payload count should be bounded and released by plugin cleanup`() {
        val context = RuntimeEnvironment.getApplication<Application>()
        val store = PluginBulkPayloadStore(context)
        store.clear()

        repeat(8) {
            store.put("plugin", byteArrayOf(it.toByte()), PluginBulkPayloadEncoding.STRING)
        }
        assertThrows(IllegalStateException::class.java) {
            store.put("plugin", byteArrayOf(9), PluginBulkPayloadEncoding.STRING)
        }

        store.clearPlugin("plugin")
        val payload = store.put("plugin", byteArrayOf(10), PluginBulkPayloadEncoding.STRING)
        assertThat(payload.sizeBytes).isEqualTo(1L)
        store.clear()
    }

    @Test
    fun `pending payload count should also be bounded across plugins`() {
        val context = RuntimeEnvironment.getApplication<Application>()
        val store = PluginBulkPayloadStore(context)
        store.clear()

        repeat(32) { index ->
            store.put("plugin-$index", byteArrayOf(index.toByte()), PluginBulkPayloadEncoding.STRING)
        }

        assertThrows(IllegalStateException::class.java) {
            store.put("plugin-overflow", byteArrayOf(1), PluginBulkPayloadEncoding.STRING)
        }
        store.clear()
    }
}
