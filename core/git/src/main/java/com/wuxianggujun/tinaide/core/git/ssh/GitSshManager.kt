package com.wuxianggujun.tinaide.core.git.ssh

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.net.InetSocketAddress
import java.net.URI
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.util.Base64
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.sshd.common.keyprovider.FileKeyPairProvider
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.sshd.JGitKeyCache
import org.eclipse.jgit.transport.sshd.ServerKeyDatabase
import org.eclipse.jgit.transport.sshd.SshdSessionFactory
import timber.log.Timber

/** SSH passphrase 错误标记，用于 ViewModel 层识别 */
const val TINA_GIT_SSH_PASSPHRASE_MARKER = "[TINA_SSH_PASSPHRASE]"
private const val DEFAULT_GIT_SSH_PORT = 22
private val GIT_SSH_KEY_NAME_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
private val GIT_SSH_HOST_LABEL_PATTERN = Regex("(?i)^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?$")
private val GIT_SSH_IPV6_PATTERN = Regex("(?i)^[0-9a-f:.]+$")

/**
 * SSH 密钥管理器 — 纯 JGit 实现
 *
 * 职责：
 * 1. 管理 SSH 密钥的生成、导入、删除
 * 2. 管理 Host → 密钥绑定
 * 3. 为 Git 远程操作构建 JGit SshdSessionFactory
 *
 * 不依赖 PRoot、外部 ssh CLI 或 ssh-agent。
 * 所有 SSH 认证通过 JGit 内置的 Apache MINA SSHD 完成。
 */
class GitSshManager(context: Context) {

    private val appContext = context.applicationContext
    private val sshDir = File(appContext.filesDir, "ssh")
    private val store = GitSshStore(appContext)
    private val knownHostsStore = GitKnownHostsStore(appContext)
    private val keyCache = JGitKeyCache()
    private val mutationMutex = Mutex()

    /** 内存中的 passphrase 缓存（keyName → passphrase），不持久化 */
    private val passphraseCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    companion object {
        private const val TAG = "GitSshManager"
        private const val DEFAULT_KEY_NAME = "id_ed25519"
        const val MAX_PRIVATE_KEY_BYTES = 1024 * 1024

        fun isValidKeyName(name: String): Boolean = GIT_SSH_KEY_NAME_PATTERN.matches(name)

        fun isValidHost(host: String): Boolean {
            val normalized = normalizeGitSshHost(host)
            if (normalized.isBlank() || normalized.length > 253 ||
                normalized.any { it.isWhitespace() || it.isISOControl() } ||
                normalized.any { it in "/@?#" }
            ) {
                return false
            }
            return if (':' in normalized) {
                GIT_SSH_IPV6_PATTERN.matches(normalized) &&
                    runCatching { URI("ssh://[$normalized]:$DEFAULT_GIT_SSH_PORT").host != null }
                        .getOrDefault(false)
            } else {
                normalized.split('.').all { label ->
                    label.length in 1..63 && GIT_SSH_HOST_LABEL_PATTERN.matches(label)
                }
            }
        }
    }

    init {
        sshDir.mkdirs()
    }

    // ── 密钥查询 ──

    suspend fun listKeys(): List<GitSshKeyMeta> = store.read().keys

    suspend fun listHostBindings(): List<GitSshHostBinding> = store.read().hostBindings

    suspend fun getDefaultKeyName(): String? = store.read().defaultKeyName

    suspend fun setDefaultKeyName(name: String?) {
        mutationMutex.withLock {
            if (name != null) {
                validateGitSshKeyName(name)
            }
            store.update { state ->
                require(name == null || state.keys.any { it.name == name }) { "SSH key does not exist: $name" }
                state.copy(defaultKeyName = name)
            }
        }
    }

    // ── 密钥生成 ──

    /**
     * 生成 Ed25519 密钥对，保存到 sshDir，并更新元数据
     */
    suspend fun generateEd25519Key(
        keyName: String = DEFAULT_KEY_NAME,
        comment: String? = null,
    ): Result<GitSshKeyMeta> = withContext(Dispatchers.IO) {
        runCatching {
            mutationMutex.withLock {
                validateGitSshKeyName(keyName)

                // Android 9-12 do not consistently expose Ed25519 through the platform JCA provider.
                val kpg = KeyPairGenerator.getInstance("Ed25519", BouncyCastleProvider())
                val keyPair = kpg.generateKeyPair()
                val normalizedComment = normalizeSshPublicKeyComment(comment)
                writeKeyPairToFiles(keyName, keyPair, normalizedComment.orEmpty())

                val meta = GitSshKeyMeta(
                    name = keyName,
                    type = "ssh-ed25519",
                    comment = normalizedComment,
                )
                store.update { state ->
                    val updatedKeys = state.keys.filter { it.name != keyName } + meta
                    state.copy(keys = updatedKeys, defaultKeyName = state.defaultKeyName ?: keyName)
                }
                meta
            }
        }
    }

    // ── 密钥导入 ──

    /**
     * 导入已有私钥（PEM 格式文本）
     */
    suspend fun importPrivateKey(
        keyName: String,
        privateKeyContent: String,
        comment: String? = null,
    ): Result<GitSshKeyMeta> = withContext(Dispatchers.IO) {
        runCatching {
            mutationMutex.withLock {
                validateGitSshKeyName(keyName)
                require(privateKeyContent.toByteArray(Charsets.UTF_8).size <= MAX_PRIVATE_KEY_BYTES) {
                    "SSH private key exceeds the size limit"
                }

                val privateFile = File(sshDir, keyName)
                val publicFile = File(sshDir, "$keyName.pub")
                check(!publicFile.exists() || publicFile.delete()) {
                    "Failed to remove the stale SSH public key"
                }
                writeUtf8Atomically(privateFile, privateKeyContent.trimEnd() + "\n")
                setFilePermissions(privateFile)

                val meta = GitSshKeyMeta(
                    name = keyName,
                    type = "imported",
                    comment = normalizeSshPublicKeyComment(comment)
                )
                store.update { state ->
                    val updatedKeys = state.keys.filter { it.name != keyName } + meta
                    state.copy(keys = updatedKeys, defaultKeyName = state.defaultKeyName ?: keyName)
                }
                meta
            }
        }
    }

    // ── 密钥删除 ──

    suspend fun deleteKey(name: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            mutationMutex.withLock {
                validateGitSshKeyName(name)
                val publicFile = File(sshDir, "$name.pub")
                check(!publicFile.exists() || publicFile.delete()) {
                    "Failed to delete the SSH public key"
                }
                val privateFile = File(sshDir, name)
                check(!privateFile.exists() || privateFile.delete()) {
                    "Failed to delete the SSH private key"
                }

                store.update { state ->
                    val updatedKeys = state.keys.filter { it.name != name }
                    val updatedBindings = state.hostBindings.filter { it.keyName != name }
                    val newDefault = if (state.defaultKeyName == name) {
                        updatedKeys.firstOrNull()?.name
                    } else {
                        state.defaultKeyName
                    }
                    state.copy(
                        keys = updatedKeys,
                        hostBindings = updatedBindings,
                        defaultKeyName = newDefault
                    )
                }
                passphraseCache.remove(name)
            }
        }
    }

    // ── 公钥读取 ──

    suspend fun readPublicKey(name: String): String? = withContext(Dispatchers.IO) {
        validateGitSshKeyName(name)
        val pubFile = File(sshDir, "$name.pub")
        if (pubFile.exists()) {
            runCatching { pubFile.readText(Charsets.UTF_8).trim() }.getOrNull()
        } else {
            null
        }
    }

    // ── Host 绑定 ──

    suspend fun upsertHostBinding(binding: GitSshHostBinding) {
        mutationMutex.withLock {
            val normalized = binding.copy(host = normalizeGitSshHost(binding.host))
            require(isValidHost(normalized.host)) { "SSH host is invalid" }
            require(normalized.port == null || normalized.port in 1..65535) { "Port must be between 1 and 65535" }
            validateGitSshKeyName(normalized.keyName)
            store.update { state ->
                require(state.keys.any { it.name == normalized.keyName }) {
                    "SSH key does not exist: ${normalized.keyName}"
                }
                val updated = state.hostBindings
                    .filterNot { it.matchesEndpoint(normalized.host, normalized.port) } + normalized
                state.copy(hostBindings = updated)
            }
        }
    }

    suspend fun deleteHostBinding(host: String, port: Int? = null) {
        val normalizedHost = normalizeGitSshHost(host)
        require(isValidHost(normalizedHost)) { "SSH host is invalid" }
        require(port == null || port in 1..65535) { "Port must be between 1 and 65535" }
        mutationMutex.withLock {
            check(knownHostsStore.remove(normalizedHost, port ?: DEFAULT_GIT_SSH_PORT)) {
                "Failed to remove trusted SSH host key"
            }
            store.update { state ->
                state.copy(
                    hostBindings = state.hostBindings.filterNot { it.matchesEndpoint(normalizedHost, port) },
                )
            }
        }
    }

    suspend fun clearTrustedHosts() = withContext(Dispatchers.IO) {
        check(knownHostsStore.clear()) { "Failed to clear trusted SSH host keys" }
    }

    // ── SSH URL 解析 ──

    /**
     * 解析 SSH URL 中的 host 和 port
     * 支持格式：
     * - ssh://git@github.com/user/repo.git
     * - git@github.com:user/repo.git
     */
    fun parseSshTarget(url: String): ParsedSshTarget? {
        val trimmed = url.trim()
        if (trimmed.isBlank()) return null
        return try {
            if (trimmed.startsWith("ssh://") || trimmed.startsWith("git+ssh://")) {
                val uri = URI(trimmed)
                val host = normalizeGitSshHost(uri.host ?: return null)
                if (!isValidHost(host)) return null
                val explicitPort = uri.port
                if (explicitPort != -1 && explicitPort !in 1..65535) return null
                ParsedSshTarget(host, explicitPort.takeIf { it != -1 })
            } else {
                // git@github.com:user/repo.git 格式
                val atIdx = trimmed.indexOf('@')
                if (atIdx < 0) return null
                val rest = trimmed.substring(atIdx + 1)
                val colonIdx = rest.indexOf(':')
                if (colonIdx < 0) return null
                val host = normalizeGitSshHost(rest.substring(0, colonIdx))
                if (!isValidHost(host)) return null
                ParsedSshTarget(host, null)
            }
        } catch (e: Exception) {
            Timber.tag(TAG).d(e, "parseSshTarget failed for SSH URL")
            null
        }
    }

    // ── Passphrase 缓存 ──

    /**
     * 缓存密钥的 passphrase（仅内存，不持久化）
     */
    fun cachePassphrase(keyName: String, passphrase: String) {
        validateGitSshKeyName(keyName)
        passphraseCache[keyName] = passphrase
    }

    /**
     * 清除指定密钥的 passphrase 缓存
     */
    fun clearPassphrase(keyName: String) {
        validateGitSshKeyName(keyName)
        passphraseCache.remove(keyName)
    }

    /**
     * 清除所有 passphrase 缓存
     */
    fun clearAllPassphrases() {
        passphraseCache.clear()
    }

    // ── JGit SshdSessionFactory ──

    /**
     * 为远程操作构建 JGit SshdSessionFactory。
     * 根据 URL 中的 host 查找绑定的密钥，或使用默认密钥。
     * 如果密钥有 passphrase 保护，会从内存缓存中获取。
     */
    suspend fun buildSshSessionFactory(url: String): SshdSessionFactory = withContext(Dispatchers.IO) {
        val target = parseSshTarget(url)
        val state = store.read()

        val keyName = if (target != null) {
            state.hostBindings
                .firstOrNull { it.matchesEndpoint(target.host, target.port) }
                ?.keyName
        } else {
            null
        }
        val resolvedKeyName = keyName ?: state.defaultKeyName ?: DEFAULT_KEY_NAME
        validateGitSshKeyName(resolvedKeyName)

        val keyFile = File(sshDir, resolvedKeyName)
        val cachedPassphrase = passphraseCache[resolvedKeyName]
        Timber.tag(TAG).d(
            "buildSshSessionFactory: parsedTarget=%s, customPort=%s, keyExists=%s, hasPassphrase=%s",
            target != null,
            target?.port != null,
            keyFile.exists(),
            cachedPassphrase != null,
        )

        object : SshdSessionFactory(keyCache, null) {
            override fun getHomeDirectory(): File = sshDir
            override fun getSshDirectory(): File = sshDir

            override fun getDefaultKeys(sshDir: File): Iterable<KeyPair> {
                if (!keyFile.exists()) return emptyList()
                return try {
                    val provider = FileKeyPairProvider(keyFile.toPath())
                    if (cachedPassphrase != null) {
                        provider.setPasswordFinder { _, _, _ -> cachedPassphrase }
                    }
                    provider.loadKeys(null).toList()
                } catch (e: Exception) {
                    Timber.tag(TAG).w("Failed to load SSH key: %s", e.javaClass.simpleName)
                    // 如果加载失败且可能是 passphrase 问题，抛出带标记的异常
                    if (isPassphraseError(e)) {
                        throw RuntimeException(
                            "$TINA_GIT_SSH_PASSPHRASE_MARKER keyName=$resolvedKeyName",
                            e
                        )
                    }
                    emptyList()
                }
            }

            override fun getServerKeyDatabase(
                homeDir: File,
                sshDir: File
            ): ServerKeyDatabase = object : ServerKeyDatabase {
                override fun lookup(
                    connectAddress: String,
                    remoteAddress: InetSocketAddress,
                    config: ServerKeyDatabase.Configuration
                ): List<PublicKey> = emptyList()

                override fun accept(
                    connectAddress: String,
                    remoteAddress: InetSocketAddress,
                    serverKey: PublicKey,
                    config: ServerKeyDatabase.Configuration,
                    provider: CredentialsProvider?
                ): Boolean {
                    val trustedHost = target?.host ?: remoteAddress.hostString
                    val trustedPort = target?.port ?: remoteAddress.port
                    val accepted = knownHostsStore.accept(trustedHost, trustedPort, serverKey)
                    if (!accepted) {
                        Timber.tag(TAG).e("SSH host key was rejected or could not be persisted")
                    }
                    return accepted
                }
            }
        }
    }

    private fun isPassphraseError(e: Exception): Boolean {
        val msg = (e.message ?: "").lowercase(Locale.ROOT)
        return "passphrase" in msg ||
            "encrypted" in msg ||
            "password" in msg ||
            "failed to decrypt" in msg ||
            "cannot read" in msg
    }

    fun getSshDir(): File = sshDir

    // ── 内部工具方法 ──

    private fun writeKeyPairToFiles(keyName: String, keyPair: KeyPair, comment: String) {
        val privateFile = File(sshDir, keyName)
        val publicFile = File(sshDir, "$keyName.pub")

        // 写私钥（PKCS8 PEM 格式）
        val privateEncoded = keyPair.private.encoded
        val privatePem = buildString {
            appendLine("-----BEGIN PRIVATE KEY-----")
            Base64.getMimeEncoder(76, "\n".toByteArray())
                .encodeToString(privateEncoded)
                .lines()
                .forEach { appendLine(it) }
            appendLine("-----END PRIVATE KEY-----")
        }
        writeUtf8Atomically(privateFile, privatePem)
        setFilePermissions(privateFile)

        // 写公钥（OpenSSH wire format，而不是 X.509 SubjectPublicKeyInfo）
        val pubLine = formatOpenSshEd25519PublicKey(keyPair.public, comment)
        writeUtf8Atomically(publicFile, pubLine + "\n")
    }

    private fun writeUtf8Atomically(file: File, content: String) {
        val atomicFile = AtomicFile(file)
        val output = atomicFile.startWrite()
        try {
            val writer = output.bufferedWriter(Charsets.UTF_8)
            writer.write(content)
            writer.flush()
            atomicFile.finishWrite(output)
        } catch (error: Throwable) {
            atomicFile.failWrite(output)
            throw error
        }
    }

    private fun setFilePermissions(file: File) {
        try {
            file.setReadable(false, false)
            file.setReadable(true, true)
            file.setWritable(false, false)
            file.setWritable(true, true)
            file.setExecutable(false, false)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "setFilePermissions failed for ${file.name}")
        }
    }

}

private fun GitSshHostBinding.matchesEndpoint(host: String, port: Int?): Boolean =
    normalizeGitSshHost(this.host) == normalizeGitSshHost(host) &&
        (this.port ?: DEFAULT_GIT_SSH_PORT) == (port ?: DEFAULT_GIT_SSH_PORT)

private fun normalizeGitSshHost(host: String): String = host
    .trim()
    .removeSurrounding("[", "]")
    .lowercase(Locale.ROOT)

internal fun validateGitSshKeyName(name: String) {
    require(GitSshManager.isValidKeyName(name)) {
        "Key name must be 1-64 characters and contain only letters, digits, '.', '_' or '-'"
    }
}

/**
 * 从错误信息中解析 SSH passphrase 需求
 */
fun parseGitSshPassphraseRequired(message: String): Pair<String, String?>? {
    val markerLine = message.lineSequence()
        .firstOrNull { TINA_GIT_SSH_PASSPHRASE_MARKER in it }
        ?: return null
    val keyName = Regex("\\bkeyName=([A-Za-z0-9][A-Za-z0-9._-]{0,63})\\b")
        .find(markerLine)
        ?.groupValues
        ?.getOrNull(1)
        ?: return null
    return keyName to null
}
