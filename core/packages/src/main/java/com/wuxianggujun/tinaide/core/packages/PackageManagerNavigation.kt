package com.wuxianggujun.tinaide.core.packages

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.wuxianggujun.tinaide.core.common.registry.RegistryPackageId

object PackageManagerNavigation {
    const val ACTION_OPEN_PACKAGE_MANAGER = "com.wuxianggujun.tinaide.action.OPEN_PACKAGE_MANAGER"
    const val EXTRA_INITIAL_SEARCH_QUERY = "package_manager_initial_search_query"

    private const val SETTINGS_ACTIVITY_CLASS = "com.wuxianggujun.tinaide.settings.SettingsActivity"

    fun createIntent(context: Context, initialSearchQuery: String? = null): Intent =
        Intent(ACTION_OPEN_PACKAGE_MANAGER)
            .setClassName(context.packageName, SETTINGS_ACTIVITY_CLASS)
            .apply {
                initialSearchQuery
                    ?.takeIf(RegistryPackageId::isValid)
                    ?.let { putExtra(EXTRA_INITIAL_SEARCH_QUERY, it) }
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

    fun isPackageManagerIntent(intent: Intent): Boolean = intent.action == ACTION_OPEN_PACKAGE_MANAGER

    fun extractInitialSearchQuery(intent: Intent): String? =
        intent.getStringExtra(EXTRA_INITIAL_SEARCH_QUERY)?.takeIf(RegistryPackageId::isValid)
}
