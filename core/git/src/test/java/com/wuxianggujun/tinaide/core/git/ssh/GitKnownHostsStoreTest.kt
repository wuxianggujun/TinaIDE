package com.wuxianggujun.tinaide.core.git.ssh

import android.app.Application
import android.content.Context
import com.google.common.truth.Truth.assertThat
import java.security.PublicKey
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = Application::class)
class GitKnownHostsStoreTest {
    private lateinit var context: Application

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("git_ssh_known_hosts", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun accept_shouldTrustFirstKeyAndRejectChangedKey() {
        val store = GitKnownHostsStore(context)
        val firstKey = FakePublicKey(byteArrayOf(1, 2, 3))
        val changedKey = FakePublicKey(byteArrayOf(4, 5, 6))

        assertThat(store.accept("GitHub.com", 22, firstKey)).isTrue()
        assertThat(store.accept("github.com", 22, firstKey)).isTrue()
        assertThat(store.accept("github.com", 22, changedKey)).isFalse()
    }

    @Test
    fun accept_shouldTreatNonPositivePortAsDefaultSshPort() {
        val store = GitKnownHostsStore(context)
        val key = FakePublicKey(byteArrayOf(7, 8, 9))

        assertThat(store.accept("example.com", 0, key)).isTrue()
        assertThat(store.accept("example.com", 22, key)).isTrue()
    }

    @Test
    fun removeAndClear_shouldAllowExplicitTrustReset() {
        val store = GitKnownHostsStore(context)
        val firstKey = FakePublicKey(byteArrayOf(1, 2, 3))
        val changedKey = FakePublicKey(byteArrayOf(4, 5, 6))

        assertThat(store.accept("example.com", 22, firstKey)).isTrue()
        assertThat(store.remove("EXAMPLE.com", 22)).isTrue()
        assertThat(store.accept("example.com", 22, changedKey)).isTrue()

        assertThat(store.accept("git.example.com", 2222, firstKey)).isTrue()
        assertThat(store.clear()).isTrue()
        assertThat(store.accept("git.example.com", 2222, changedKey)).isTrue()
    }

    private class FakePublicKey(
        private val bytes: ByteArray,
    ) : PublicKey {
        override fun getAlgorithm(): String = "test"

        override fun getFormat(): String = "raw"

        override fun getEncoded(): ByteArray = bytes.copyOf()
    }
}
