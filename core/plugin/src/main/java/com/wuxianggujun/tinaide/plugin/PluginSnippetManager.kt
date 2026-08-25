package com.wuxianggujun.tinaide.plugin

import com.wuxianggujun.tinaide.core.ServiceLifecycle
import com.wuxianggujun.tinaide.core.common.snippet.expandSnippetToPlainText
import com.wuxianggujun.tinaide.core.serialization.JsonSerializer
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * 插件代码片段管理器（阶段 1.5：配置插件增强）
 *
 * 约束：仅加载 JSON 数据，不执行插件代码。
 */
class PluginSnippetManager(
    private val pluginManager: PluginManager
) : ServiceLifecycle {

    companion object {
        private const val TAG = "PluginSnippetManager"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val snippetsByLanguage = MutableStateFlow<Map<String, List<SnippetEntry>>>(emptyMap())

    override fun onCreate() {
        Timber.tag(TAG).i(
            "PluginSnippetManager binding PluginManager instance=%s",
            pluginManager.instanceId
        )
        scope.launch {
            pluginManager.enabledPluginsFlow.collect { plugins ->
                try {
                    reloadFromInstalledPlugins(plugins)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Timber.tag(TAG).e(error, "Failed to refresh plugin snippet contributions")
                }
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
    }

    fun findSnippetCompletions(
        languageId: String,
        prefix: String,
        maxItems: Int = 64
    ): List<SnippetCompletionCandidate> {
        if (prefix.isBlank()) return emptyList()

        val prefixLower = prefix.lowercase()
        val snippets = snippetsByLanguage.value[normalizeLanguageId(languageId)].orEmpty()

        return buildList(minOf(maxItems, snippets.size)) {
            for (snippet in snippets) {
                val trigger = snippet.prefix
                if (!trigger.lowercase().startsWith(prefixLower)) continue

                add(
                    SnippetCompletionCandidate(
                        trigger = trigger,
                        description = snippet.description ?: snippet.name,
                        snippetText = snippet.bodyText,
                        plainInsertText = snippet.plainInsertText
                    )
                )
                if (size >= maxItems) break
            }
        }
    }

    private fun reloadFromInstalledPlugins(enabledPlugins: List<InstalledPlugin>) {
        val byLanguage = LinkedHashMap<String, MutableList<SnippetEntry>>()

        enabledPlugins.forEach { plugin ->
            val snippetPaths = plugin.manifest.contributions?.snippets.orEmpty()
            if (snippetPaths.isEmpty()) return@forEach

            snippetPaths.forEach { relativePath ->
                val snippetFile = File(plugin.directory, relativePath)
                if (!snippetFile.exists()) {
                    Timber.tag(TAG).w("Snippet file not found: ${snippetFile.path} (plugin=${plugin.manifest.id})")
                    return@forEach
                }

                runCatching {
                    val parsed: SnippetFile = JsonSerializer.decodeFromFile(snippetFile)
                    val lang = normalizeLanguageId(parsed.language)
                    val list = byLanguage.getOrPut(lang) { mutableListOf() }

                    parsed.snippets.forEach { s ->
                        val bodyText = s.body.joinToString("\n")
                        if (bodyText.isBlank()) return@forEach

                        list.add(
                            SnippetEntry(
                                pluginId = plugin.manifest.id,
                                language = lang,
                                prefix = s.prefix,
                                name = s.name,
                                description = s.description,
                                bodyText = bodyText,
                                plainInsertText = expandSnippetToPlainText(bodyText)
                            )
                        )
                    }
                }.onFailure { t ->
                    Timber.tag(TAG).w(t, "Failed to load snippet file: ${snippetFile.path}")
                }
            }
        }

        snippetsByLanguage.value = byLanguage.mapValues { (_, v) ->
            v.distinctBy { "${it.pluginId}#${it.language}#${it.prefix}#${it.name}" }
                .sortedWith(compareBy<SnippetEntry> { it.prefix }.thenBy { it.name }.thenBy { it.pluginId })
        }
    }

    private fun normalizeLanguageId(id: String): String = id.trim().lowercase()
}

internal data class SnippetEntry(
    val pluginId: String,
    val language: String,
    val prefix: String,
    val name: String,
    val description: String?,
    val bodyText: String,
    val plainInsertText: String
)

data class SnippetCompletionCandidate(
    val trigger: String,
    val description: String?,
    val snippetText: String,
    val plainInsertText: String
)
