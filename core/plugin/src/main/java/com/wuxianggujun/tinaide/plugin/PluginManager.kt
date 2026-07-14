package com.wuxianggujun.tinaide.plugin

import android.content.Context
import com.wuxianggujun.tinaide.core.ServiceLifecycle
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import com.wuxianggujun.tinaide.core.serialization.JsonSerializer
import com.wuxianggujun.tinaide.project.ProjectApkExportType
import com.wuxianggujun.tinaide.project.ProjectLanguage
import com.wuxianggujun.tinaide.project.ProjectTemplateOption
import com.wuxianggujun.tinaide.project.ProjectTemplateSpec
import com.wuxianggujun.tinaide.plugin.runtime.PluginRuntimeUnavailableException
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import timber.log.Timber

@Serializable
internal data class PluginInstallTransactionRecord(
    val transactionId: String,
    val pluginId: String,
    val previousVersion: String? = null,
    val backupDirectoryName: String? = null,
    val hadDesiredEnabled: Boolean,
    val oldDesiredEnabled: Boolean,
    val hadLegacyEnabled: Boolean,
    val oldLegacyEnabled: Boolean,
    val previousFault: PluginFaultRecord? = null,
)

class PluginManager(
    private val context: Context
) : ServiceLifecycle {

    companion object {
        private const val TAG = "PluginManager"

        private const val PLUGINS_DIR_NAME = "plugins"
        const val MANIFEST_FILE_NAME: String = "manifest.json"

        private const val PREFS_NAME = "tinaide_plugins"
        private const val PREF_ENABLED_PREFIX = "enabled_"
        private const val PREF_DESIRED_ENABLED_PREFIX = "desired_enabled_"

        private val PLUGIN_ID_PATTERN = Regex("^[a-zA-Z0-9][a-zA-Z0-9._-]*$")

        @Volatile
        private var instance: PluginManager? = null

        fun getInstance(context: Context): PluginManager {
            instance?.let { manager ->
                Timber.tag(TAG).d(
                    "Reusing PluginManager singleton instance=%s",
                    manager.instanceId
                )
                manager.logHostDebug("Reusing singleton instance=${manager.instanceId}")
                return manager
            }
            return synchronized(this) {
                instance ?: PluginManager(context.applicationContext).also {
                    it.onCreate()
                    Timber.tag(TAG).i(
                        "Created PluginManager singleton instance=%s",
                        it.instanceId
                    )
                    it.logHostInfo("Created singleton instance=${it.instanceId}")
                    instance = it
                }
            }
        }
    }

    private val json = JsonSerializer.default
    private val pluginsDir = File(context.filesDir, PLUGINS_DIR_NAME)
    private val stagingRoot = File(pluginsDir, ".staging")
    private val backupRoot = File(pluginsDir, ".backup")
    private val transactionRoot = File(pluginsDir, ".transactions")
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val pluginLogManager = PluginLogManager.getInstance(context)
    private val faultStore = PluginFaultStore.getInstance(context)
    val instanceId: String = Integer.toHexString(System.identityHashCode(this))

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutationMutex = Mutex()
    private val initializationStarted = AtomicBoolean(false)
    private val initializationCompleted = CompletableDeferred<Unit>()

    // 插件状态的单一来源：安装态、启用态、版本映射与 capability 都从这里派生。
    private val _pluginStateFlow = MutableStateFlow(PluginStateSnapshot())
    val pluginStateFlow: StateFlow<PluginStateSnapshot> = _pluginStateFlow.asStateFlow()

    // 兼容现有调用方的安装态视图。
    private val _pluginsFlow = MutableStateFlow<List<InstalledPlugin>>(emptyList())
    val pluginsFlow: StateFlow<List<InstalledPlugin>> = _pluginsFlow.asStateFlow()

    // 所有会影响宿主行为的模块都应优先消费启用态。
    private val _enabledPluginsFlow = MutableStateFlow<List<InstalledPlugin>>(emptyList())
    val enabledPluginsFlow: StateFlow<List<InstalledPlugin>> = _enabledPluginsFlow.asStateFlow()

    private val _enabledCapabilitiesFlow = MutableStateFlow<Set<String>>(emptySet())
    val enabledCapabilitiesFlow: StateFlow<Set<String>> = _enabledCapabilitiesFlow.asStateFlow()
    private val _loadIssuesFlow = MutableStateFlow<List<PluginLoadIssue>>(emptyList())
    val loadIssuesFlow: StateFlow<List<PluginLoadIssue>> = _loadIssuesFlow.asStateFlow()
    private val _pluginHealthReportsFlow = MutableStateFlow<Map<String, PluginHealthReport>>(emptyMap())
    val pluginHealthReportsFlow: StateFlow<Map<String, PluginHealthReport>> =
        _pluginHealthReportsFlow.asStateFlow()

    override fun onCreate() {
        if (!initializationStarted.compareAndSet(false, true)) return
        Timber.tag(TAG).i(
            "PluginManager.onCreate instance=%s",
            instanceId,
        )
        logHostInfo("onCreate instance=$instanceId")
        pluginsDir.mkdirs()
        stagingRoot.mkdirs()
        backupRoot.mkdirs()
        transactionRoot.mkdirs()
        scope.launch {
            try {
                mutationMutex.withLock {
                    recoverInterruptedInstallTransactions()
                    refreshInstalledPlugins()
                }
                initializationCompleted.complete(Unit)
            } catch (error: Throwable) {
                initializationCompleted.completeExceptionally(error)
                Timber.tag(TAG).e(error, "Plugin transaction recovery failed; plugin loading remains disabled")
                logHostError("Plugin transaction recovery failed", error)
                return@launch
            }
            runCatching {
                BundledPluginsInstaller(context, this@PluginManager).installOrUpdateBundledPlugins()
            }.onFailure { error ->
                Timber.tag(TAG).w(error, "Bundled plugin installation failed")
                logHostError("Bundled plugin installation failed", error)
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
    }

    internal suspend fun awaitInitialization() {
        if (initializationStarted.get()) initializationCompleted.await()
    }

    suspend fun refreshInstalledPlugins() = withContext(Dispatchers.IO) {
        val loadIssues = mutableListOf<PluginLoadIssue>()
        val installed = pluginsDir.listFiles()
            ?.asSequence()
            ?.filter { it.isDirectory && !it.name.startsWith('.') }
            ?.mapNotNull { dir ->
                val manifestFile = File(dir, MANIFEST_FILE_NAME)
                if (!manifestFile.exists()) {
                    loadIssues += PluginLoadIssue(
                        directoryName = dir.name,
                        pluginName = dir.name,
                        type = PluginLoadIssueType.MISSING_MANIFEST,
                        message = Strings.plugin_error_missing_manifest.strOr(context, MANIFEST_FILE_NAME)
                    )
                    return@mapNotNull null
                }

                var manifestForIssue: PluginManifest? = null

                runCatching {
                    val manifest = JsonSerializer.decodeFromFile<PluginManifest>(manifestFile)
                    manifestForIssue = manifest
                    validateManifest(manifest, dir)
                    val localizedManifest = PluginLocalizationResolver.localize(manifest, dir, context)
                    InstalledPlugin(
                        manifest = localizedManifest,
                        directory = dir,
                        enabled = resolvePluginEnabled(manifest)
                    )
                }.onFailure { t ->
                    manifestForIssue?.let { invalidManifest ->
                        quarantineInvalidContribution(invalidManifest, t)
                    }
                    Timber.tag(TAG).w(t, "Failed to load plugin manifest: ${manifestFile.path}")
                    pluginLogManager.warn(
                        PluginHostLogSources.PluginManager,
                        "Invalid plugin skipped dir=${dir.name} reason=${t.message.orEmpty()}"
                    )
                    loadIssues += PluginLoadIssue(
                        directoryName = dir.name,
                        pluginId = manifestForIssue?.id,
                        pluginName = manifestForIssue?.name ?: dir.name,
                        type = PluginLoadIssueType.INVALID_MANIFEST,
                        message = t.message ?: Strings.plugin_error_install_failed.strOr(context)
                    )
                }.getOrNull()
            }
            ?.sortedBy { it.manifest.name }
            ?.toList()
            ?: emptyList()

        val snapshot = PluginStateSnapshotFactory.create(installed)
        _pluginStateFlow.value = snapshot
        _pluginsFlow.value = snapshot.installedPlugins
        _enabledPluginsFlow.value = snapshot.enabledPlugins
        _enabledCapabilitiesFlow.value = snapshot.enabledCapabilities
        _loadIssuesFlow.value = loadIssues.toList()
        _pluginHealthReportsFlow.value = installed.associate { plugin ->
            plugin.manifest.id to PluginHealthInspector.inspect(context, plugin)
        }
        installed.forEach { plugin ->
            val status = when {
                faultStore.isQuarantined(plugin.manifest.id) -> PluginEffectiveStatus.QUARANTINED
                !plugin.enabled -> PluginEffectiveStatus.DISABLED
                plugin.manifest.type.equals(PluginTypes.SCRIPT, ignoreCase = true) ||
                    plugin.manifest.type.equals(PluginTypes.HYBRID, ignoreCase = true) -> null
                else -> PluginEffectiveStatus.ACTIVE
            }
            if (status != null) faultStore.setEffectiveStatus(plugin.manifest.id, status)
        }
        Timber.tag(TAG).i(
            "Refreshed plugins instance=%s installed=%s enabled=%s capabilities=%s",
            instanceId,
            snapshot.installedPluginIds.joinToString(","),
            snapshot.enabledPluginIds.joinToString(","),
            snapshot.enabledCapabilities.joinToString(",")
        )
        logHostInfo(
            "refreshInstalledPlugins instance=$instanceId installed=${snapshot.installedPluginIds.joinToString(",")} enabled=${snapshot.enabledPluginIds.joinToString(",")} capabilities=${snapshot.enabledCapabilities.joinToString(",")}"
        )
    }

    suspend fun installPlugin(zipFile: File): Result<PluginManifest> = withContext(Dispatchers.IO) {
        runCatching {
            require(zipFile.exists()) { Strings.plugin_error_file_not_exist.strOr(context, zipFile.path) }
            require(zipFile.length() <= ZipUtils.MAX_PACKAGE_BYTES) {
                Strings.plugin_error_package_too_large.strOr(context)
            }

            val tempDir = createStagingDirectory()
            try {
                try {
                    ZipUtils.unzipToDirectory(zipFile, tempDir)
                } catch (error: PluginArchiveException) {
                    throw IllegalArgumentException(localizeArchiveFailure(error), error)
                }
                val installed = installPluginFromDirectory(tempDir, allowSkipIfSameVersion = false)
                requireNotNull(installed) { Strings.plugin_error_install_failed.strOr(context) }
                installed
            } finally {
                if (tempDir.exists()) tempDir.deleteRecursively()
            }
        }
    }

    suspend fun uninstallPlugin(pluginId: String): Result<Unit> = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            runCatching {
                validatePluginId(pluginId)
                Timber.tag(TAG).i(
                    "Uninstall plugin requested instance=%s pluginId=%s",
                    instanceId,
                    pluginId
                )
                logHostInfo("uninstall requested pluginId=$pluginId instance=$instanceId")

                val pluginDir = File(pluginsDir, pluginId)
                PluginRuntimeLifecycle.stop(pluginId)
                if (pluginDir.exists()) {
                    check(pluginDir.deleteRecursively()) { "Failed to remove plugin directory" }
                }
                check(
                    prefs.edit()
                        .remove(PREF_ENABLED_PREFIX + pluginId)
                        .remove(PREF_DESIRED_ENABLED_PREFIX + pluginId)
                        .commit(),
                ) { "Failed to clear plugin enabled state" }
                check(faultStore.clearAllForUninstall(pluginId)) { "Failed to clear plugin fault state" }
                PluginConfigurationStore.getInstance(context).clearPlugin(pluginId)

                refreshInstalledPlugins()
            }
        }
    }

    suspend fun setPluginEnabled(pluginId: String, enabled: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            runCatching {
                validatePluginId(pluginId)
                Timber.tag(TAG).i(
                    "Set plugin enabled requested instance=%s pluginId=%s enabled=%s",
                    instanceId,
                    pluginId,
                    enabled
                )
                logHostInfo("setPluginEnabled requested pluginId=$pluginId enabled=$enabled instance=$instanceId")
                if (!enabled) PluginRuntimeLifecycle.stop(pluginId)
                setPluginEnabledInternal(pluginId, enabled, userRequested = true)
                refreshInstalledPlugins()
                if (enabled) PluginRuntimeLifecycle.activate(pluginId).getOrThrow()
            }
        }
    }

    fun isPluginEnabled(pluginId: String): Boolean {
        val manifest = getInstalledManifestOrNull(pluginId)
        return if (manifest != null) {
            resolvePluginEnabled(manifest)
        } else {
            getStoredPluginEnabledOrNull(pluginId) ?: true
        }
    }

    private fun setPluginEnabledInternal(
        pluginId: String,
        enabled: Boolean,
        userRequested: Boolean = false,
    ) {
        val editor = prefs.edit().putBoolean(PREF_ENABLED_PREFIX + pluginId, enabled)
        if (userRequested) editor.putBoolean(PREF_DESIRED_ENABLED_PREFIX + pluginId, enabled)
        check(editor.commit()) { "Failed to persist plugin enabled state" }
        if (enabled && userRequested) {
            check(faultStore.clearFault(pluginId)) { "Failed to clear plugin quarantine" }
        }
    }

    private fun resolvePluginEnabled(manifest: PluginManifest): Boolean {
        val desired = getDesiredPluginEnabledOrNull(manifest.id)
            ?: getStoredPluginEnabledOrNull(manifest.id)
            ?: getDefaultEnabledValue(manifest)
        migrateDesiredEnabled(manifest.id, desired)
        return desired && !faultStore.isQuarantined(manifest.id)
    }

    private fun getDesiredPluginEnabledOrNull(pluginId: String): Boolean? {
        val key = PREF_DESIRED_ENABLED_PREFIX + pluginId
        if (!prefs.contains(key)) return null
        return prefs.getBoolean(key, true)
    }

    private fun migrateDesiredEnabled(pluginId: String, desired: Boolean) {
        val key = PREF_DESIRED_ENABLED_PREFIX + pluginId
        if (!prefs.contains(key)) prefs.edit().putBoolean(key, desired).commit()
    }

    private fun getStoredPluginEnabledOrNull(pluginId: String): Boolean? {
        val key = PREF_ENABLED_PREFIX + pluginId
        if (!prefs.contains(key)) return null
        return prefs.getBoolean(key, true)
    }

    private fun getDefaultEnabledValue(manifest: PluginManifest): Boolean = !manifest.type.equals(PluginTypes.SYSTEM, ignoreCase = true)

    suspend fun quarantinePlugin(
        record: PluginFaultRecord,
        runtimeAlreadyStopped: Boolean = false,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val quarantine = suspend {
            runCatching {
                validatePluginId(record.pluginId)
                if (!runtimeAlreadyStopped) PluginRuntimeLifecycle.stop(record.pluginId)
                check(faultStore.recordFault(record)) { "Failed to persist plugin quarantine" }
                // Keep desiredEnabled untouched, but disable the legacy key for downgrade safety.
                check(
                    prefs.edit().putBoolean(PREF_ENABLED_PREFIX + record.pluginId, false).commit(),
                ) { "Failed to persist legacy disabled state" }
                refreshInstalledPlugins()
            }
        }
        // Script startup/callback quarantine can be called from an activation that already owns this mutex.
        if (runtimeAlreadyStopped) quarantine() else mutationMutex.withLock { quarantine() }
    }

    fun getPluginFault(pluginId: String): PluginFaultRecord? = faultStore.getFault(pluginId)

    fun pluginFaultsFlow(): StateFlow<Map<String, PluginFaultRecord>> = faultStore.faults

    private fun validateManifest(manifest: PluginManifest, pluginDir: File) {
        PluginManifestValidator.validate(
            context = context,
            manifest = manifest,
            pluginDir = pluginDir,
        )
    }

    private fun quarantineInvalidContribution(manifest: PluginManifest, error: Throwable) {
        if (faultStore.isQuarantined(manifest.id)) return
        val desired = getDesiredPluginEnabledOrNull(manifest.id)
            ?: getStoredPluginEnabledOrNull(manifest.id)
            ?: getDefaultEnabledValue(manifest)
        if (!desired) return
        val record = PluginFaultRecord(
            pluginId = manifest.id,
            pluginVersion = manifest.version,
            phase = PluginFaultPhase.CONTRIBUTION,
            kind = PluginFaultKind.INVALID_CONTRIBUTION,
            message = error.message ?: "Invalid plugin contribution",
            timestampMillis = System.currentTimeMillis(),
            executionId = UUID.randomUUID().toString(),
        )
        if (faultStore.recordFault(record)) {
            prefs.edit().putBoolean(PREF_ENABLED_PREFIX + manifest.id, false).commit()
        }
    }

    private fun validatePluginId(id: String) {
        require(id.isNotBlank()) { Strings.plugin_error_id_empty.strOr(context) }
        require(PLUGIN_ID_PATTERN.matches(id)) { Strings.plugin_error_id_invalid.strOr(context, id) }
        require(!id.contains("..")) { Strings.plugin_error_id_contains_dotdot.strOr(context, id) }
        require(!id.contains(File.separatorChar)) { Strings.plugin_error_id_contains_separator.strOr(context, id) }
    }

    private fun createStagingDirectory(): File {
        check(stagingRoot.mkdirs() || stagingRoot.isDirectory) { "Unable to create plugin staging root" }
        return File(stagingRoot, UUID.randomUUID().toString()).also { staging ->
            check(staging.mkdir()) { "Unable to create plugin staging directory" }
        }
    }

    private fun prepareStagingDirectory(source: File): File {
        require(source.isDirectory) { "Plugin source directory does not exist" }
        val sourceParent = source.canonicalFile.parentFile
        if (sourceParent == stagingRoot.canonicalFile) return source

        val staging = createStagingDirectory()
        try {
            source.walkTopDown().forEach { file ->
                require(!java.nio.file.Files.isSymbolicLink(file.toPath())) { "Symbolic links are not allowed in plugins" }
            }
            check(source.copyRecursively(staging, overwrite = true)) { "Unable to copy plugin into staging" }
            return staging
        } catch (error: Throwable) {
            staging.deleteRecursively()
            throw error
        }
    }

    private fun validatePluginDirectoryLimits(directory: File) {
        val root = directory.canonicalFile
        var entryCount = 0
        var totalBytes = 0L
        var luaBytes = 0L
        directory.walkTopDown().drop(1).forEach { entry ->
            entryCount += 1
            require(entryCount <= ZipUtils.MAX_ENTRY_COUNT) {
                Strings.plugin_error_archive_too_many_entries.strOr(context)
            }
            require(!java.nio.file.Files.isSymbolicLink(entry.toPath())) { "Symbolic links are not allowed in plugins" }
            require(entry.canonicalPath.startsWith(root.path + File.separator)) {
                Strings.plugin_error_archive_invalid_entry.strOr(context, entry.name.take(160))
            }
            if (entry.isFile) {
                val size = entry.length()
                require(size <= ZipUtils.MAX_ENTRY_BYTES) {
                    Strings.plugin_error_archive_entry_too_large.strOr(context, entry.name.take(160))
                }
                totalBytes += size
                require(totalBytes <= ZipUtils.MAX_EXPANDED_BYTES) {
                    Strings.plugin_error_archive_expanded_too_large.strOr(context)
                }
                if (entry.extension.equals("lua", ignoreCase = true)) {
                    require(size <= ZipUtils.MAX_LUA_FILE_BYTES) {
                        Strings.plugin_error_lua_file_too_large.strOr(context, entry.name.take(160))
                    }
                    luaBytes += size
                    require(luaBytes <= ZipUtils.MAX_LUA_SOURCE_BYTES) {
                        Strings.plugin_error_lua_sources_too_large.strOr(context)
                    }
                }
            }
        }
    }

    private fun atomicRename(from: File, to: File) {
        require(!to.exists()) { "Plugin transaction target already exists" }
        check(to.parentFile?.let { it.mkdirs() || it.isDirectory } != false) {
            "Unable to create plugin transaction directory"
        }
        check(from.renameTo(to)) { "Atomic plugin directory rename failed" }
    }

    private fun localizeArchiveFailure(error: PluginArchiveException): String {
        val entry = error.entryName.orEmpty()
        return when (error.failure) {
            PluginArchiveFailure.PACKAGE_TOO_LARGE -> Strings.plugin_error_package_too_large.strOr(context)
            PluginArchiveFailure.TOO_MANY_ENTRIES -> Strings.plugin_error_archive_too_many_entries.strOr(context)
            PluginArchiveFailure.ENTRY_TOO_LARGE -> Strings.plugin_error_archive_entry_too_large.strOr(context, entry)
            PluginArchiveFailure.EXPANDED_TOO_LARGE -> Strings.plugin_error_archive_expanded_too_large.strOr(context)
            PluginArchiveFailure.COMPRESSION_RATIO_TOO_HIGH -> Strings.plugin_error_archive_ratio_too_high.strOr(context, entry)
            PluginArchiveFailure.LUA_FILE_TOO_LARGE -> Strings.plugin_error_lua_file_too_large.strOr(context, entry)
            PluginArchiveFailure.LUA_SOURCES_TOO_LARGE -> Strings.plugin_error_lua_sources_too_large.strOr(context)
            PluginArchiveFailure.INVALID_ENTRY -> Strings.plugin_error_archive_invalid_entry.strOr(context, entry)
            PluginArchiveFailure.DUPLICATE_ENTRY -> Strings.plugin_error_archive_duplicate_entry.strOr(context, entry)
        }
    }

    private fun logHostDebug(message: String) {
        pluginLogManager.debug(PluginHostLogSources.PluginManager, message)
    }

    private fun logHostInfo(message: String) {
        pluginLogManager.info(PluginHostLogSources.PluginManager, message)
    }

    private fun logHostError(message: String, throwable: Throwable) {
        pluginLogManager.error(
            source = PluginHostLogSources.PluginManager,
            message = message,
            stackTrace = throwable.stackTraceToString()
        )
    }

    /**
     * 从“已解包目录”安装插件（适用于 zip 解压后的目录，或 assets 复制出的目录）。
     *
     * @return 安装/更新成功返回 manifest；如果 allowSkipIfSameVersion=true 且已安装同版本则返回 null
     */
    internal suspend fun installPluginFromDirectory(
        extractedDir: File,
        allowSkipIfSameVersion: Boolean,
        markAsBundled: Boolean = false
    ): PluginManifest? = mutationMutex.withLock {
        val stagingDir = prepareStagingDirectory(extractedDir)
        var backupDir: File? = null
        var transactionFile: File? = null
        var oldDirectoryMoved = false
        var newDirectoryInstalled = false
        try {
            validatePluginDirectoryLimits(stagingDir)
            val manifestFile = File(stagingDir, MANIFEST_FILE_NAME)
            require(manifestFile.exists()) { Strings.plugin_error_missing_manifest.strOr(context, MANIFEST_FILE_NAME) }

            val decodedManifest = JsonSerializer.decodeFromFile<PluginManifest>(manifestFile)
            val manifest = if (markAsBundled && !decodedManifest.isBundled) {
                decodedManifest.copy(isBundled = true).also { updated ->
                    JsonSerializer.encodeToFile(manifestFile, PluginManifest.serializer(), updated)
                }
            } else {
                decodedManifest
            }
            validateManifest(manifest, stagingDir)

            val previousManifest = getInstalledManifestOrNull(manifest.id)
            if (allowSkipIfSameVersion && previousManifest?.version == manifest.version) return null

            val pluginDir = File(pluginsDir, manifest.id)
            val previousDesiredEnabled = getDesiredPluginEnabledOrNull(manifest.id)
                ?: previousManifest?.let(::resolvePluginEnabled)
                ?: if (markAsBundled) getDefaultEnabledValue(manifest) else false
            val previousFault = faultStore.getFault(manifest.id)
            val rollbackFailedUpgrade = previousManifest != null && previousDesiredEnabled && previousFault == null
            val isNewerVersion = previousManifest != null &&
                (PluginVersionComparator.compare(manifest.version, previousManifest.version) ?: 0) > 0
            val legacyEnabledAfterInstall = previousDesiredEnabled && (previousFault == null || isNewerVersion)
            val desiredKey = PREF_DESIRED_ENABLED_PREFIX + manifest.id
            val enabledKey = PREF_ENABLED_PREFIX + manifest.id
            val hadDesired = prefs.contains(desiredKey)
            val oldDesired = prefs.getBoolean(desiredKey, false)
            val hadEnabled = prefs.contains(enabledKey)
            val oldEnabled = prefs.getBoolean(enabledKey, false)
            val targetDir = pluginDir
            val transaction = PluginInstallTransactionRecord(
                transactionId = UUID.randomUUID().toString(),
                pluginId = manifest.id,
                previousVersion = previousManifest?.version,
                backupDirectoryName = previousManifest?.let { "${manifest.id}-${UUID.randomUUID()}" },
                hadDesiredEnabled = hadDesired,
                oldDesiredEnabled = oldDesired,
                hadLegacyEnabled = hadEnabled,
                oldLegacyEnabled = oldEnabled,
                previousFault = previousFault,
            )
            transactionFile = writeInstallTransaction(transaction)

            try {
                PluginRuntimeLifecycle.stop(manifest.id)
            } catch (error: Throwable) {
                runCatching { PluginRuntimeLifecycle.activate(manifest.id) }
                if (transactionFile?.delete() == true) transactionFile = null
                throw error
            }
            try {
                if (targetDir.exists()) {
                    backupDir = File(backupRoot, checkNotNull(transaction.backupDirectoryName))
                    atomicRename(targetDir, backupDir)
                    oldDirectoryMoved = true
                }
                atomicRename(stagingDir, targetDir)
                newDirectoryInstalled = true
                check(
                    prefs.edit()
                        .putBoolean(desiredKey, previousDesiredEnabled)
                        .putBoolean(enabledKey, legacyEnabledAfterInstall)
                        .commit(),
                ) { "Failed to persist plugin state" }

                if (isNewerVersion) check(faultStore.clearFault(manifest.id)) { "Failed to clear plugin quarantine" }

                val activationError = PluginRuntimeLifecycle.activate(manifest.id).exceptionOrNull()
                if (activationError != null && rollbackFailedUpgrade && activationError !is PluginRuntimeUnavailableException) {
                    throw IllegalStateException("Updated plugin failed its startup health check", activationError)
                }

                check(transactionFile?.delete() != false) { "Failed to commit plugin install transaction" }
                transactionFile = null
            } catch (error: Throwable) {
                runCatching { PluginRuntimeLifecycle.stop(manifest.id) }
                if (newDirectoryInstalled) targetDir.takeIf { it.exists() }?.deleteRecursively()
                if (oldDirectoryMoved) {
                    backupDir?.takeIf { it.exists() }?.let { atomicRename(it, targetDir) }
                }
                restoreInstallState(transaction)
                runCatching { PluginRuntimeLifecycle.activate(manifest.id) }
                transactionFile?.delete()
                throw error
            }

            backupDir?.let { backup ->
                runCatching { backup.deleteRecursively() }
                    .onFailure { error -> Timber.tag(TAG).w(error, "Failed to remove committed plugin backup") }
            }
            return@withLock manifest
        } finally {
            if (stagingDir.exists()) stagingDir.deleteRecursively()
        }
    }

    private fun writeInstallTransaction(record: PluginInstallTransactionRecord): File {
        check(transactionRoot.mkdirs() || transactionRoot.isDirectory) { "Unable to create plugin transaction root" }
        val pending = File(transactionRoot, ".${record.transactionId}.tmp")
        val committed = File(transactionRoot, "${record.transactionId}.json")
        JsonSerializer.encodeToFile(pending, PluginInstallTransactionRecord.serializer(), record)
        check(pending.renameTo(committed)) { "Unable to persist plugin install transaction" }
        return committed
    }

    internal fun recoverInterruptedInstallTransactions() {
        check(pluginsDir.mkdirs() || pluginsDir.isDirectory) { "Unable to create plugins directory" }
        stagingRoot.mkdirs()
        backupRoot.mkdirs()
        transactionRoot.mkdirs()

        transactionRoot.listFiles { file -> file.isFile && file.name.endsWith(".tmp") }
            .orEmpty()
            .forEach(File::delete)

        val referencedBackups = mutableSetOf<String>()
        transactionRoot.listFiles { file -> file.isFile && file.extension == "json" }
            .orEmpty()
            .sortedBy(File::lastModified)
            .forEach { transactionFile ->
                val transaction = JsonSerializer.decodeFromFileOrNull<PluginInstallTransactionRecord>(transactionFile)
                    ?: throw IllegalStateException("Invalid plugin install transaction journal: ${transactionFile.name}")
                validatePluginId(transaction.pluginId)
                val backupName = transaction.backupDirectoryName
                require(
                    backupName == null ||
                        (File(backupName).name == backupName && backupName.startsWith("${transaction.pluginId}-")),
                ) { "Invalid plugin transaction backup name" }
                require((transaction.previousVersion == null) == (backupName == null)) {
                    "Inconsistent plugin transaction journal"
                }
                val target = File(pluginsDir, transaction.pluginId)
                val backup = backupName?.let { validBackupName ->
                    referencedBackups += validBackupName
                    File(backupRoot, validBackupName)
                }
                when {
                    backup?.isDirectory == true -> {
                        target.takeIf { it.exists() }?.deleteRecursively()
                        atomicRename(backup, target)
                    }
                    transaction.previousVersion == null -> {
                        target.takeIf { it.exists() }?.deleteRecursively()
                    }
                    getInstalledManifestOrNull(transaction.pluginId)?.version != transaction.previousVersion -> {
                        throw IllegalStateException("Plugin transaction backup is missing")
                    }
                }
                restoreInstallState(transaction)
                check(transactionFile.delete()) { "Unable to clear recovered plugin transaction" }
                Timber.tag(TAG).w("Recovered interrupted plugin install transaction: %s", transaction.pluginId)
            }

        stagingRoot.listFiles().orEmpty().forEach { entry -> entry.deleteRecursively() }
        backupRoot.listFiles().orEmpty()
            .filterNot { backup -> backup.name in referencedBackups }
            .forEach { backup -> backup.deleteRecursively() }
    }

    private fun restoreInstallState(transaction: PluginInstallTransactionRecord) {
        val desiredKey = PREF_DESIRED_ENABLED_PREFIX + transaction.pluginId
        val enabledKey = PREF_ENABLED_PREFIX + transaction.pluginId
        val editor = prefs.edit()
        if (transaction.hadDesiredEnabled) {
            editor.putBoolean(desiredKey, transaction.oldDesiredEnabled)
        } else {
            editor.remove(desiredKey)
        }
        if (transaction.hadLegacyEnabled) {
            editor.putBoolean(enabledKey, transaction.oldLegacyEnabled)
        } else {
            editor.remove(enabledKey)
        }
        check(editor.commit()) { "Unable to restore plugin enabled state" }
        if (transaction.previousFault != null) {
            check(faultStore.recordFault(transaction.previousFault)) { "Unable to restore plugin fault state" }
        } else {
            check(faultStore.clearFault(transaction.pluginId)) { "Unable to restore plugin fault state" }
        }
    }

    private fun getInstalledManifestOrNull(pluginId: String): PluginManifest? {
        val pluginDir = File(pluginsDir, pluginId)
        val manifestFile = File(pluginDir, MANIFEST_FILE_NAME)
        if (!manifestFile.exists()) return null
        return JsonSerializer.decodeFromFileOrNull<PluginManifest>(manifestFile)
    }

    fun listInstalledPlugins(): List<InstalledPlugin> = _pluginStateFlow.value.installedPlugins

    fun listEnabledPlugins(): List<InstalledPlugin> = _pluginStateFlow.value.enabledPlugins

    fun getInstalledPlugin(pluginId: String): InstalledPlugin? = _pluginStateFlow.value.installedPlugins.find { it.manifest.id == pluginId }

    fun getEnabledPlugin(pluginId: String): InstalledPlugin? = _pluginStateFlow.value.enabledPlugins.find { it.manifest.id == pluginId }

    fun isPluginInstalled(pluginId: String): Boolean = _pluginStateFlow.value.isInstalled(pluginId)

    fun getInstalledVersion(pluginId: String): String? = _pluginStateFlow.value.getInstalledVersion(pluginId)

    fun listProjectTemplateOptions(): List<ProjectTemplateOption> = _pluginStateFlow.value.enabledPlugins.asSequence()
        .flatMap { plugin ->
            plugin.manifest.contributions?.projectTemplates.orEmpty()
                .asSequence()
                .mapNotNull { template -> resolveProjectTemplateOption(plugin, template) }
        }
        .sortedBy { it.displayName.lowercase() }
        .toList()

    fun listApkExportOptions(projectType: ProjectApkExportType): List<ResolvedPluginApkExport> {
        val options = _pluginStateFlow.value.enabledPlugins.asSequence()
            .flatMap { plugin ->
                plugin.manifest.contributions?.apkExports.orEmpty()
                    .asSequence()
                    .mapNotNull { export -> resolveApkExportOption(plugin, export, projectType) }
            }
            .sortedBy { it.displayName.lowercase() }
            .toList()
        Timber.tag(TAG).d(
            "Resolved apk export options instance=%s projectType=%s options=%s",
            instanceId,
            projectType,
            options.joinToString(",") { "${it.pluginId}:${it.exportId}" }
        )
        logHostDebug(
            "listApkExportOptions instance=$instanceId projectType=$projectType options=${
                options.joinToString(",") { "${it.pluginId}:${it.exportId}" }
            }"
        )
        return options
    }

    fun hasEnabledCapability(capability: String): Boolean {
        if (capability.isBlank()) return false
        return _pluginStateFlow.value.enabledCapabilities.contains(capability)
    }

    suspend fun install(zipFile: File): Result<InstalledPlugin> = withContext(Dispatchers.IO) {
        installPlugin(zipFile).map { manifest ->
            refreshInstalledPlugins()
            logHostInfo("install completed pluginId=${manifest.id} version=${manifest.version} instance=$instanceId")
            getInstalledPlugin(manifest.id)
                ?: throw IllegalStateException("Plugin installed but not found: ${manifest.id}")
        }
    }

    fun resolveFileTreeContextMenuItems(
        installedPlugins: List<InstalledPlugin>,
        file: File,
        isDirectory: Boolean
    ): List<ResolvedHostMenuItem> = PluginMenuResolver.resolveFileTreeContextMenuItems(
        context = context,
        installedPlugins = installedPlugins,
        file = file,
        isDirectory = isDirectory
    )

    fun resolveFileTreeContextCommands(
        installedPlugins: List<InstalledPlugin>,
        file: File,
        isDirectory: Boolean
    ): List<ResolvedPluginCommand> = PluginMenuResolver.resolveFileTreeContextCommands(
        context = context,
        installedPlugins = installedPlugins,
        file = file,
        isDirectory = isDirectory
    )

    fun resolveFileTreeIcons(
        installedPlugins: List<InstalledPlugin>
    ): List<ResolvedPluginFileIcon> = PluginFileIconResolver.resolve(installedPlugins)

    fun resolveEditorContextMenuItems(
        installedPlugins: List<InstalledPlugin>,
        file: File,
        isDirty: Boolean
    ): List<ResolvedHostMenuItem> = PluginMenuResolver.resolveEditorContextMenuItems(
        context = context,
        installedPlugins = installedPlugins,
        file = file,
        isDirty = isDirty
    )

    fun resolveEditorContextCommands(
        installedPlugins: List<InstalledPlugin>,
        file: File,
        isDirty: Boolean
    ): List<ResolvedPluginCommand> = PluginMenuResolver.resolveEditorContextCommands(
        context = context,
        installedPlugins = installedPlugins,
        file = file,
        isDirty = isDirty
    )

    fun resolveEditorToolbarMenuItems(
        installedPlugins: List<InstalledPlugin>,
        file: File,
        isDirty: Boolean
    ): List<ResolvedHostMenuItem> = PluginMenuResolver.resolveEditorToolbarMenuItems(
        context = context,
        installedPlugins = installedPlugins,
        file = file,
        isDirty = isDirty
    )

    fun resolveEditorToolbarCommands(
        installedPlugins: List<InstalledPlugin>,
        file: File,
        isDirty: Boolean
    ): List<ResolvedPluginCommand> = PluginMenuResolver.resolveEditorToolbarCommands(
        context = context,
        installedPlugins = installedPlugins,
        file = file,
        isDirty = isDirty
    )

    fun resolveKeyBindings(
        installedPlugins: List<InstalledPlugin>
    ): List<ResolvedPluginKeyBinding> = PluginKeyBindingResolver.resolve(installedPlugins)

    private fun resolveProjectTemplateOption(
        plugin: InstalledPlugin,
        template: PluginProjectTemplate
    ): ProjectTemplateOption? {
        val buildSystem = PluginManifestValidator.parseProjectBuildSystem(template.buildSystem)
        if (buildSystem == null) {
            Timber.tag(TAG).w(
                "Skip plugin template %s/%s: unsupported build system %s",
                plugin.manifest.id,
                template.id,
                template.buildSystem
            )
            return null
        }

        val zipFile = File(plugin.directory, template.templatePath)
        if (!zipFile.isFile) {
            Timber.tag(TAG).w(
                "Skip plugin template %s/%s: missing zip %s",
                plugin.manifest.id,
                template.id,
                zipFile.absolutePath
            )
            return null
        }

        val optionId = "plugin:${plugin.manifest.id}:${template.id}"
        return ProjectTemplateOption(
            id = optionId,
            displayName = template.name,
            description = template.description,
            spec = ProjectTemplateSpec.Zip(
                id = optionId,
                zipFile = zipFile,
                buildSystem = buildSystem,
                primaryLanguage = parseProjectLanguage(template.primaryLanguage),
                isNdkTemplate = template.isNdkTemplate,
                defaultRunTargetName = template.defaultRunTargetName,
                defaultSdlTargetName = template.defaultSdlTargetName,
            )
        )
    }

    private fun resolveApkExportOption(
        plugin: InstalledPlugin,
        export: PluginApkExport,
        projectType: ProjectApkExportType
    ): ResolvedPluginApkExport? {
        val supportedProjectTypes = export.projectTypes
            .mapNotNull(::parseProjectApkExportType)
            .toSet()
        if (projectType !in supportedProjectTypes) return null

        val templateType = normalizePluginApkTemplateType(export.templateType)
        if (templateType == null) {
            Timber.tag(TAG).w(
                "Skip plugin apk export %s/%s: unsupported template type %s",
                plugin.manifest.id,
                export.id,
                export.templateType
            )
            return null
        }

        val templateFile = File(plugin.directory, export.templatePath)
        if (!templateFile.isFile) {
            Timber.tag(TAG).w(
                "Skip plugin apk export %s/%s: missing template %s",
                plugin.manifest.id,
                export.id,
                templateFile.absolutePath
            )
            return null
        }

        val optionId = "plugin:${plugin.manifest.id}:${export.id}"
        return ResolvedPluginApkExport(
            optionId = optionId,
            pluginId = plugin.manifest.id,
            exportId = export.id,
            displayName = export.name,
            description = export.description,
            projectTypes = supportedProjectTypes,
            templateType = templateType,
            templateFile = templateFile
        )
    }

    private fun parseProjectApkExportType(value: String): ProjectApkExportType? = ProjectApkExportType.entries.firstOrNull { entry ->
        entry.name.equals(value.trim(), ignoreCase = true)
    }

    private fun normalizePluginApkTemplateType(value: String): String? = when (value.trim().lowercase()) {
        "native_activity", "native-activity", "nativeactivity" -> "native_activity"
        "sdl3" -> "sdl3"
        "terminal" -> "terminal"
        else -> null
    }

    private fun parseProjectLanguage(value: String?): ProjectLanguage {
        val language = ProjectLanguage.fromString(value)
        return if (language == ProjectLanguage.UNKNOWN) ProjectLanguage.CPP else language
    }
}
