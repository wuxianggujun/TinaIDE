package com.wuxianggujun.tinaide.ui.compose.state.editor

import android.content.Context
import com.wuxianggujun.tinaide.core.config.Prefs
import com.wuxianggujun.tinaide.core.editorview.EditorColorScheme
import com.wuxianggujun.tinaide.search.CodeSearchResult
import com.wuxianggujun.tinaide.search.SearchOptions
import timber.log.Timber

/**
 * 代码编辑器回调与搜索回调的注册表（按 tab + registrationId 管理）。
 */
internal class EditorCodeCallbackRegistry(
    private val context: Context,
    private val searchStateManager: SearchStateManager,
    private val codeRuntimeCache: EditorCodeRuntimeCache,
    private val resolveEditorColorScheme: (Context) -> EditorColorScheme,
) {
    private data class Registration(
        val searchCallback: SearchStateManager.CodeViewerCallback,
        val editorCallback: CodeEditorCallback,
    )

    private val callbacksByTabId = mutableMapOf<String, CodeEditorCallback>()
    private val registrationsByTabId =
        mutableMapOf<String, LinkedHashMap<Any, Registration>>()

    /** 供 FileMutationCoordinator 等直接 remap 的可变视图。 */
    val mutableCallbacks: MutableMap<String, CodeEditorCallback>
        get() = callbacksByTabId

    fun get(tabId: String): CodeEditorCallback? = callbacksByTabId[tabId]

    fun contains(tabId: String): Boolean = callbacksByTabId.containsKey(tabId)

    fun keys(): Set<String> = callbacksByTabId.keys.toSet()

    fun forEach(action: (tabId: String, callback: CodeEditorCallback) -> Unit) {
        callbacksByTabId.forEach { (tabId, callback) -> action(tabId, callback) }
    }

    fun bindCodeEditorCallbacks(
        tabId: String,
        registrationId: Any,
        search: (String, SearchOptions) -> List<CodeSearchResult>,
        goToMatch: (CodeSearchResult) -> Unit,
        editorCallback: CodeEditorCallback,
    ) {
        val registration = Registration(
            searchCallback = SearchStateManager.CodeViewerCallback(
                search = search,
                goToMatch = goToMatch,
            ),
            editorCallback = editorCallback,
        )
        registrationsByTabId.getOrPut(tabId) { LinkedHashMap() }[registrationId] = registration
        activate(tabId, registration)
    }

    fun unbindCodeEditorCallbacks(tabId: String, registrationId: Any) {
        val registrations = registrationsByTabId[tabId] ?: return
        val removed = registrations.remove(registrationId) ?: return
        if (registrations.isEmpty()) {
            registrationsByTabId.remove(tabId)
        }
        if (callbacksByTabId[tabId] === removed.editorCallback) {
            val replacement = registrations.values.lastOrNull()
            if (replacement == null) {
                callbacksByTabId.remove(tabId)
                searchStateManager.unregisterCodeViewerCallback(tabId)
            } else {
                activate(tabId, replacement)
            }
        }
        codeRuntimeCache.trim()
    }

    fun register(tabId: String, callback: CodeEditorCallback) {
        callbacksByTabId[tabId] = callback
        runCatching { callback.applyEditorSettings(Prefs.editorSettingsFlow.value) }
            .onFailure { t ->
                Timber.tag(TAG).w(t, "Failed to apply editor settings for tab=%s", tabId)
            }
        runCatching { callback.applyEditorColorScheme(resolveEditorColorScheme(context)) }
            .onFailure { t ->
                Timber.tag(TAG).w(t, "Failed to apply editor theme for tab=%s", tabId)
            }
    }

    fun unregister(tabId: String) {
        remove(tabId)
    }

    fun remove(tabId: String) {
        registrationsByTabId.remove(tabId)
        callbacksByTabId.remove(tabId)
        searchStateManager.unregisterCodeViewerCallback(tabId)
    }

    fun remapTabIds(idMap: Map<String, String>) {
        idMap.forEach { (oldId, newId) ->
            callbacksByTabId.remove(oldId)?.let { callback ->
                callbacksByTabId[newId] = callback
            }
            registrationsByTabId.remove(oldId)?.let { regs ->
                registrationsByTabId[newId] = regs
            }
        }
    }

    fun clear() {
        val tabIds = (callbacksByTabId.keys + registrationsByTabId.keys).toSet()
        tabIds.forEach { remove(it) }
    }

    private fun activate(tabId: String, registration: Registration) {
        searchStateManager.registerCodeViewerCallback(tabId, registration.searchCallback)
        register(tabId, registration.editorCallback)
    }

    companion object {
        private const val TAG = "EditorCodeCallbackReg"
    }
}
