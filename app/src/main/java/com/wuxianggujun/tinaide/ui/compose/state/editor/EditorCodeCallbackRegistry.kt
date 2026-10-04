package com.wuxianggujun.tinaide.ui.compose.state.editor

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import com.wuxianggujun.tinaide.core.config.Prefs
import com.wuxianggujun.tinaide.core.editorview.EditorColorScheme
import timber.log.Timber

/**
 * 代码编辑器回调注册表（按 tab + registrationId 管理）；查找由 editor-kit 管理。
 */
internal class EditorCodeCallbackRegistry(
    private val context: Context,
    private val codeRuntimeCache: EditorCodeRuntimeCache,
    private val resolveEditorColorScheme: (Context) -> EditorColorScheme,
) {

    // BottomPanel 会在 composition 中读取注册状态；使用 snapshot map 让首次 attach/detach
    // 能直接驱动符号栏可见性更新，而不依赖其它无关状态碰巧触发重组。
    private val callbacksByTabId = mutableStateMapOf<String, CodeEditorCallback>()
    private val registrationsByTabId =
        mutableMapOf<String, LinkedHashMap<Any, CodeEditorCallback>>()

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
        editorCallback: CodeEditorCallback,
    ) {
        registrationsByTabId.getOrPut(tabId) { LinkedHashMap() }[registrationId] = editorCallback
        register(tabId, editorCallback)
    }

    fun unbindCodeEditorCallbacks(tabId: String, registrationId: Any) {
        val registrations = registrationsByTabId[tabId] ?: return
        val removed = registrations.remove(registrationId) ?: return
        if (registrations.isEmpty()) {
            registrationsByTabId.remove(tabId)
        }
        if (callbacksByTabId[tabId] === removed) {
            val replacement = registrations.values.lastOrNull()
            if (replacement == null) {
                callbacksByTabId.remove(tabId)
            } else {
                register(tabId, replacement)
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

    companion object {
        private const val TAG = "EditorCodeCallbackReg"
    }
}
