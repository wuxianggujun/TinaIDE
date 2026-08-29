package com.wuxianggujun.tinaide.core.proot

import android.content.Context
import com.wuxianggujun.tinaide.core.config.ConfigKeys
import com.wuxianggujun.tinaide.core.config.IConfigManager
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Coordinates the Alpine mirror preference across rootfs download and the
 * installed guest's APK repository configuration.
 *
 * The two mechanisms remain separate: download ordering never edits the guest,
 * while repository switching edits `/etc/apk/repositories` and runs `apk update`.
 */
class AlpineMirrorManager(
    context: Context,
    private val configManager: IConfigManager,
) {
    private val appContext = context.applicationContext
    private val selectedMirrorState = MutableStateFlow(readSelectedMirror())

    val selectedMirrorFlow: StateFlow<Mirror> = selectedMirrorState.asStateFlow()

    val selectedMirror: Mirror
        get() = selectedMirrorState.value

    /** Persists the mirror preference without requiring an installed rootfs. */
    fun selectMirror(mirror: Mirror) {
        configManager.set(ConfigKeys.AlpineRepositoryMirror, mirror.id)
        selectedMirrorState.value = mirror
    }

    fun detectMirror(rootfsPath: String): Mirror? {
        val repositories = File(rootfsPath, REPOSITORIES_PATH)
        if (!repositories.isFile) return null
        val urls = runCatching {
            repositories.readLines(Charsets.UTF_8)
                .map(String::trim)
                .filter { it.isNotEmpty() && !it.startsWith('#') }
        }.getOrDefault(emptyList())
        return Mirror.entries.firstOrNull { mirror ->
            urls.isNotEmpty() && urls.all { url -> url == mirror.baseUrl || url.startsWith("${mirror.baseUrl}/") }
        }
    }

    /** Makes the user's Alpine mirror the first rootfs download candidate. */
    fun orderRootfsDownloadCandidates(canonicalUrl: String, mirrorUrls: List<String>): List<String> {
        if (!canonicalUrl.startsWith("${Mirror.OFFICIAL.baseUrl}/")) {
            return (listOf(canonicalUrl) + mirrorUrls).distinct()
        }
        val preferred = selectedMirror.takeUnless { it == Mirror.OFFICIAL }?.let { mirror ->
            mirror.baseUrl + canonicalUrl.removePrefix(Mirror.OFFICIAL.baseUrl)
        }
        return buildList {
            preferred?.let(::add)
            add(canonicalUrl)
            mirrorUrls.forEach(::add)
        }.distinct()
    }

    /** Applies the selected repository file during a new rootfs installation. */
    fun applySelectedMirror(rootfsDir: File) {
        writeRepositories(rootfsDir, selectedMirror)
    }

    /**
     * Switches an installed Alpine rootfs and verifies it with `apk update`.
     * The old repository file is restored if validation fails.
     */
    suspend fun switchMirror(rootfsPath: String, mirror: Mirror): Result<Mirror> = withContext(Dispatchers.IO) {
        val rootfsDir = File(rootfsPath)
        val repositoriesFile = File(rootfsDir, REPOSITORIES_PATH)
        var previous: ByteArray? = null
        var hadPrevious = false
        var snapshotCaptured = false

        try {
            hadPrevious = repositoriesFile.isFile
            previous = repositoriesFile.takeIf(File::isFile)?.readBytes()
            snapshotCaptured = true
            writeRepositories(rootfsDir, mirror)
            val environment = PRootRootfsLinuxEnvironment(appContext, rootfsDir.absolutePath)
            val update = GuestSystemPackageManager.updateIndex(
                linuxEnvironment = environment,
                packageManager = RootfsPackageManager.APK,
                timeoutMs = UPDATE_TIMEOUT_MS,
            )
            check(update.isSuccess) {
                update.combinedOutput.ifBlank { "apk update failed" }
            }
            selectMirror(mirror)
            Timber.tag(TAG).i("Switched Alpine APK repository mirror to %s", mirror.id)
            Result.success(mirror)
        } catch (cancellation: CancellationException) {
            if (snapshotCaptured) restoreRepositories(repositoriesFile, hadPrevious, previous)
            throw cancellation
        } catch (error: Throwable) {
            if (snapshotCaptured) restoreRepositories(repositoriesFile, hadPrevious, previous)
            Timber.tag(TAG).w(error, "Failed to switch Alpine APK repository mirror")
            Result.failure(error)
        }
    }

    private fun readSelectedMirror(): Mirror = Mirror.fromId(
        configManager.get(ConfigKeys.AlpineRepositoryMirror)
    )

    private fun writeRepositories(rootfsDir: File, mirror: Mirror) {
        require(rootfsDir.isDirectory) { "Linux rootfs directory does not exist: ${rootfsDir.absolutePath}" }
        val version = detectAlpineVersion(rootfsDir)
        val target = File(rootfsDir, REPOSITORIES_PATH)
        val parent = requireNotNull(target.parentFile)
        check(parent.mkdirs() || parent.isDirectory) { "Failed to create APK repository directory" }
        val pending = Files.createTempFile(parent.toPath(), ".repositories-", ".tmp")
        try {
            pending.toFile().writeText(mirror.repositoriesContent(version), Charsets.UTF_8)
            try {
                Files.move(
                    pending,
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: java.io.IOException) {
                Files.move(pending, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(pending)
        }
    }

    private fun restoreRepositories(target: File, hadPrevious: Boolean, previous: ByteArray?) {
        runCatching {
            if (!hadPrevious) {
                target.delete()
            } else {
                val backup = checkNotNull(previous) { "Missing APK repositories backup" }
                val parent = requireNotNull(target.parentFile)
                parent.mkdirs()
                val pending = Files.createTempFile(parent.toPath(), ".repositories-restore-", ".tmp")
                try {
                    pending.toFile().writeBytes(backup)
                    try {
                        Files.move(
                            pending,
                            target.toPath(),
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING,
                        )
                    } catch (_: java.io.IOException) {
                        Files.move(pending, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    }
                } finally {
                    Files.deleteIfExists(pending)
                }
            }
        }.onFailure { error ->
            Timber.tag(TAG).e(error, "Failed to restore Alpine APK repositories")
        }
    }

    private fun detectAlpineVersion(rootfsDir: File): String {
        val release = File(rootfsDir, "etc/alpine-release").takeIf(File::isFile)
            ?.readText(Charsets.UTF_8)
            ?.lineSequence()
            ?.map(String::trim)
            ?.firstOrNull { it.isNotEmpty() }
        val match = release?.let(ALPINE_RELEASE_PATTERN::matchEntire)
        match?.groupValues?.getOrNull(1)?.let { return "v$it" }

        val repositories = File(rootfsDir, REPOSITORIES_PATH).takeIf(File::isFile)
            ?.readLines(Charsets.UTF_8)
            .orEmpty()
        repositories.firstNotNullOfOrNull { line ->
            ALPINE_REPOSITORY_VERSION_PATTERN.find(line)?.groupValues?.getOrNull(1)
        }?.let { return it }

        error("Cannot determine Alpine repository version")
    }

    enum class Mirror(
        val id: String,
        val baseUrl: String,
    ) {
        OFFICIAL("official", "https://dl-cdn.alpinelinux.org/alpine"),
        TSINGHUA("tsinghua", "https://mirrors.tuna.tsinghua.edu.cn/alpine"),
        ALIYUN("aliyun", "https://mirrors.aliyun.com/alpine"),
        USTC("ustc", "https://mirrors.ustc.edu.cn/alpine"),
        HUAWEI("huawei", "https://repo.huaweicloud.com/alpine"),
        TENCENT("tencent", "https://mirrors.cloud.tencent.com/alpine"),
        ;

        fun repositoriesContent(version: String): String =
            "$baseUrl/$version/main\n$baseUrl/$version/community\n"

        companion object {
            fun fromId(id: String): Mirror = entries.firstOrNull { it.id == id } ?: TSINGHUA
        }
    }

    private companion object {
        const val TAG = "AlpineMirrorManager"
        const val REPOSITORIES_PATH = "etc/apk/repositories"
        const val UPDATE_TIMEOUT_MS = 120_000L
        val ALPINE_RELEASE_PATTERN = Regex("^([0-9]+\\.[0-9]+)(?:\\.[0-9]+)?(?:[-+].*)?$")
        val ALPINE_REPOSITORY_VERSION_PATTERN = Regex("/alpine/(v[0-9]+\\.[0-9]+)/")
    }
}
