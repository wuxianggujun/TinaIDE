package com.wuxianggujun.tinaide.plugin.script

import android.content.Context
import android.content.SharedPreferences
import com.wuxianggujun.tinaide.core.serialization.JsonSerializer
import java.util.Collections
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import timber.log.Timber

class PluginPermissionManager private constructor(context: Context) {
    companion object {
        private const val TAG = "PluginPermissionMgr"
        private const val PREFS_NAME = "plugin_permissions"
        private const val KEY_GRANTS = "permission_grants"

        @Volatile
        private var instance: PluginPermissionManager? = null

        fun getInstance(context: Context): PluginPermissionManager = instance ?: synchronized(this) {
            instance ?: PluginPermissionManager(context.applicationContext).also {
                instance = it
            }
        }
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = JsonSerializer.default

    private val _grantsFlow = MutableStateFlow<Map<String, Set<PluginPermission>>>(emptyMap())
    val grantsFlow: StateFlow<Map<String, Set<PluginPermission>>> = _grantsFlow.asStateFlow()

    init {
        loadGrants()
    }

    private fun loadGrants() {
        try {
            val jsonText = prefs.getString(KEY_GRANTS, null) ?: return
            val raw: Map<String, List<String>> = json.decodeFromString(jsonText)
            _grantsFlow.value = immutableSnapshot(raw.mapValues { (_, perms) ->
                perms.mapNotNull { PluginPermission.fromId(it) }.toSet()
            })
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to load permission grants")
        }
    }

    private fun persistGrants(grants: Map<String, Set<PluginPermission>>) {
        val snapshot = immutableSnapshot(grants)
        val raw = snapshot.mapValues { (_, permissions) -> permissions.map { it.id } }
        check(prefs.edit().putString(KEY_GRANTS, json.encodeToString(raw)).commit()) {
            "Failed to persist plugin permission grants"
        }
        _grantsFlow.value = snapshot
    }

    private fun immutableSnapshot(
        grants: Map<String, Set<PluginPermission>>,
    ): Map<String, Set<PluginPermission>> = Collections.unmodifiableMap(
        grants.mapValuesTo(LinkedHashMap()) { (_, permissions) ->
            Collections.unmodifiableSet(LinkedHashSet(permissions))
        },
    )

    fun getGrantedPermissions(pluginId: String): Set<PluginPermission> =
        _grantsFlow.value[pluginId] ?: emptySet()

    fun hasPermission(pluginId: String, permission: PluginPermission): Boolean {
        if (permission.level == PermissionLevel.L0_NO_RISK) return true
        return getGrantedPermissions(pluginId).contains(permission)
    }

    @Synchronized
    fun grantPermission(pluginId: String, permission: PluginPermission) {
        val current = _grantsFlow.value.toMutableMap()
        val pluginGrants = current[pluginId]?.toMutableSet() ?: mutableSetOf()
        pluginGrants.add(permission)
        current[pluginId] = pluginGrants
        persistGrants(current)
        Timber.tag(TAG).d("Granted ${permission.id} to $pluginId")
    }

    @Synchronized
    fun grantPermissions(pluginId: String, permissions: Set<PluginPermission>) {
        val current = _grantsFlow.value.toMutableMap()
        val pluginGrants = current[pluginId]?.toMutableSet() ?: mutableSetOf()
        pluginGrants.addAll(permissions)
        current[pluginId] = pluginGrants
        persistGrants(current)
        Timber.tag(TAG).d("Granted ${permissions.size} permissions to $pluginId")
    }

    @Synchronized
    fun revokePermission(pluginId: String, permission: PluginPermission) {
        val current = _grantsFlow.value.toMutableMap()
        val pluginGrants = current[pluginId]?.toMutableSet() ?: return
        pluginGrants.remove(permission)
        if (pluginGrants.isEmpty()) {
            current.remove(pluginId)
        } else {
            current[pluginId] = pluginGrants
        }
        persistGrants(current)
        Timber.tag(TAG).d("Revoked ${permission.id} from $pluginId")
    }

    @Synchronized
    fun revokeAllPermissions(pluginId: String) {
        val current = _grantsFlow.value.toMutableMap()
        current.remove(pluginId)
        persistGrants(current)
        Timber.tag(TAG).d("Revoked all permissions from $pluginId")
    }

    @Synchronized
    fun replacePermissions(pluginId: String, permissions: Set<PluginPermission>) {
        val current = _grantsFlow.value.toMutableMap()
        if (permissions.isEmpty()) {
            current.remove(pluginId)
        } else {
            current[pluginId] = permissions
        }
        persistGrants(current)
        Timber.tag(TAG).d("Replaced permission grants for $pluginId count=${permissions.size}")
    }

    fun getRequiredPermissionsForInstall(
        requestedPermissions: Set<PluginPermission>
    ): PermissionInstallResult {
        val autoGranted = mutableSetOf<PluginPermission>()
        val requiresPrompt = mutableSetOf<PluginPermission>()
        val requiresWarning = mutableSetOf<PluginPermission>()

        for (perm in requestedPermissions) {
            when (perm.level) {
                PermissionLevel.L0_NO_RISK -> autoGranted.add(perm)
                PermissionLevel.L1_LOW_RISK,
                PermissionLevel.L2_MEDIUM_RISK -> requiresPrompt.add(perm)
                PermissionLevel.L3_HIGH_RISK -> requiresWarning.add(perm)
            }
        }

        return PermissionInstallResult(
            autoGranted = autoGranted,
            requiresPrompt = requiresPrompt,
            requiresWarning = requiresWarning
        )
    }
}

data class PermissionInstallResult(
    val autoGranted: Set<PluginPermission>,
    val requiresPrompt: Set<PluginPermission>,
    val requiresWarning: Set<PluginPermission>
) {
    val needsUserConfirmation: Boolean
        get() = requiresPrompt.isNotEmpty() || requiresWarning.isNotEmpty()

    val allPermissions: Set<PluginPermission>
        get() = autoGranted + requiresPrompt + requiresWarning
}
