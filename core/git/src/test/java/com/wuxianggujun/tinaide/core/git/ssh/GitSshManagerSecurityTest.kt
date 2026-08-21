package com.wuxianggujun.tinaide.core.git.ssh

import android.app.Application
import android.content.Context
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File
import java.util.Base64
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = Application::class)
class GitSshManagerSecurityTest {
    private lateinit var context: Application

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        File(context.filesDir, "ssh").deleteRecursively()
        File(context.filesDir, "git-ssh.json").delete()
        File(context.filesDir, "git-ssh.json.bak").delete()
        context.getSharedPreferences("git_ssh_known_hosts", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun generateEd25519Key_shouldWriteOpenSshWireFormat() = runBlocking {
        val manager = GitSshManager(context)

        manager.generateEd25519Key("test_ed25519", "test\ncomment").getOrThrow()

        val publicKey = checkNotNull(manager.readPublicKey("test_ed25519"))
        val parts = publicKey.split(' ', limit = 3)
        assertThat(parts[0]).isEqualTo("ssh-ed25519")
        assertThat(parts[2]).isEqualTo("testcomment")

        DataInputStream(ByteArrayInputStream(Base64.getDecoder().decode(parts[1]))).use { input ->
            assertThat(input.readSshString().toString(Charsets.US_ASCII)).isEqualTo("ssh-ed25519")
            assertThat(input.readSshString()).hasLength(32)
            assertThat(input.available()).isEqualTo(0)
        }
    }

    @Test
    fun generateEd25519Key_shouldRejectUnsafeOrOversizedNames() = runBlocking {
        val manager = GitSshManager(context)

        listOf("", ".hidden", "../key", "key name", "key\nname", "x".repeat(65)).forEach { name ->
            assertThat(manager.generateEd25519Key(name).isFailure).isTrue()
        }
    }

    @Test
    fun fileBackedOperations_shouldRejectUnsafeNamesAndOversizedImports() = runBlocking {
        val manager = GitSshManager(context)

        assertThat(manager.deleteKey("../outside").isFailure).isTrue()
        assertThat(runCatching { manager.readPublicKey("../outside") }.isFailure).isTrue()
        assertThat(
            manager.importPrivateKey(
                "large_key",
                "x".repeat(GitSshManager.MAX_PRIVATE_KEY_BYTES + 1),
            ).isFailure
        ).isTrue()
        assertThat(File(context.filesDir, "outside").exists()).isFalse()
    }

    @Test
    fun hostValidation_shouldRejectPathsCredentialsAndInvalidPorts() = runBlocking {
        assertThat(GitSshManager.isValidHost("github.com")).isTrue()
        assertThat(GitSshManager.isValidHost("[2001:db8::1]")).isTrue()
        assertThat(GitSshManager.isValidHost("host/path")).isFalse()
        assertThat(GitSshManager.isValidHost("user@host")).isFalse()

        val manager = GitSshManager(context)
        manager.generateEd25519Key("default_key").getOrThrow()
        assertThat(
            runCatching {
                manager.upsertHostBinding(GitSshHostBinding("host/path", "default_key", 22))
            }.isFailure
        ).isTrue()
        assertThat(
            runCatching {
                manager.upsertHostBinding(GitSshHostBinding("github.com", "default_key", 65536))
            }.isFailure
        ).isTrue()
    }

    @Test
    fun hostBindings_shouldUseHostAndPortIdentity() = runBlocking {
        val manager = GitSshManager(context)
        manager.generateEd25519Key("default_key").getOrThrow()
        manager.upsertHostBinding(GitSshHostBinding("GitHub.com", "default_key", 22))
        manager.upsertHostBinding(GitSshHostBinding("github.com", "default_key", 2222))

        assertThat(manager.listHostBindings()).containsExactly(
            GitSshHostBinding("github.com", "default_key", 22),
            GitSshHostBinding("github.com", "default_key", 2222),
        )

        manager.deleteHostBinding("GITHUB.COM", 2222)

        assertThat(manager.listHostBindings()).containsExactly(
            GitSshHostBinding("github.com", "default_key", 22),
        )
    }

    @Test
    fun parseSshTarget_shouldRejectExplicitPortsOutsideValidRange() {
        val manager = GitSshManager(context)

        assertThat(manager.parseSshTarget("ssh://git@example.com:22/repo.git"))
            .isEqualTo(ParsedSshTarget("example.com", 22))
        assertThat(manager.parseSshTarget("ssh://git@example.com:0/repo.git")).isNull()
        assertThat(manager.parseSshTarget("ssh://git@example.com:65536/repo.git")).isNull()
    }

    @Test
    fun concurrentImports_shouldPreserveBothStoreUpdates() = runBlocking {
        val firstManager = GitSshManager(context)
        val secondManager = GitSshManager(context)

        coroutineScope {
            val first = async { firstManager.importPrivateKey("first_key", "first") }
            val second = async { secondManager.importPrivateKey("second_key", "second") }
            first.await().getOrThrow()
            second.await().getOrThrow()
        }

        assertThat(firstManager.listKeys().map { it.name })
            .containsExactly("first_key", "second_key")
    }

    @Test
    fun importPrivateKey_shouldRemoveStalePublicKeyForSameName() = runBlocking {
        val manager = GitSshManager(context)
        manager.generateEd25519Key("reused_key").getOrThrow()
        assertThat(manager.readPublicKey("reused_key")).isNotNull()

        manager.importPrivateKey("reused_key", "replacement").getOrThrow()

        assertThat(manager.readPublicKey("reused_key")).isNull()
    }

    @Test
    fun storeRead_shouldRecoverAtomicBackupAfterInterruptedWrite() = runBlocking {
        val manager = GitSshManager(context)
        manager.importPrivateKey("recoverable_key", "private").getOrThrow()
        val stateFile = File(context.filesDir, "git-ssh.json")
        val backupFile = File(context.filesDir, "git-ssh.json.bak")
        assertThat(stateFile.renameTo(backupFile)).isTrue()
        stateFile.writeText("{incomplete", Charsets.UTF_8)

        assertThat(GitSshManager(context).listKeys().map { it.name })
            .containsExactly("recoverable_key")
    }

    @Test
    fun passphraseMarker_shouldResolveTheActualKeyName() {
        assertThat(
            parseGitSshPassphraseRequired(
                "$TINA_GIT_SSH_PASSPHRASE_MARKER keyName=my_key"
            )
        ).isEqualTo("my_key" to null)
        assertThat(parseGitSshPassphraseRequired("generic passphrase error")).isNull()
        assertThat(
            parseGitSshPassphraseRequired("$TINA_GIT_SSH_PASSPHRASE_MARKER keyName=../unsafe")
        ).isNull()
    }

    private fun DataInputStream.readSshString(): ByteArray {
        val length = readInt()
        require(length in 0..4096)
        return ByteArray(length).also { bytes -> readFully(bytes) }
    }
}
