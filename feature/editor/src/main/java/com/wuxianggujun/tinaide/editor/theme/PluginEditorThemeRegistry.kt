package com.wuxianggujun.tinaide.editor.theme

import android.content.Context
import com.wuxianggujun.tinaide.core.ServiceLifecycle
import com.wuxianggujun.tinaide.core.serialization.JsonSerializer
import com.wuxianggujun.tinaide.plugin.EditorThemeIndex
import com.wuxianggujun.tinaide.plugin.InstalledPlugin
import com.wuxianggujun.tinaide.plugin.PluginManager
import com.wuxianggujun.tinaide.plugin.ThemeConfig
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import timber.log.Timber

class PluginEditorThemeRegistry(
    private val context: Context,
    private val pluginManager: PluginManager
) : ServiceLifecycle,
    EditorThemeIndex {

    companion object {
        private const val TAG = "PluginEditorThemeRegistry"
        const val THEME_ID_PREFIX: String = "plugin:"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _themesFlow = MutableStateFlow<Map<String, ThemeConfig>>(emptyMap())
    override val themesFlow: StateFlow<Map<String, ThemeConfig>> = _themesFlow.asStateFlow()

    override fun onCreate() {
        Timber.tag(TAG).i(
            "PluginEditorThemeRegistry binding PluginManager instance=%s",
            pluginManager.instanceId
        )
        scope.launch {
            pluginManager.enabledPluginsFlow.collect { enabledPlugins ->
                _themesFlow.value = buildThemesIndex(enabledPlugins)
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
    }

    private fun buildThemesIndex(enabledPlugins: List<InstalledPlugin>): Map<String, ThemeConfig> {
        val result = LinkedHashMap<String, ThemeConfig>()

        enabledPlugins.asSequence()
            .forEach { plugin ->
                val themePaths = plugin.manifest.contributions?.themes.orEmpty()
                themePaths.forEach { relativePath ->
                    val themeId = toThemeId(plugin.manifest.id, relativePath)
                    val themeFile = resolveFileSafely(plugin.directory, relativePath) ?: return@forEach
                    if (!themeFile.exists()) return@forEach

                    runCatching {
                        JsonSerializer.decodeFromFile<ThemeConfig>(themeFile)
                    }.onSuccess { config ->
                        val normalizedConfig = config.copy(
                            colors = PluginThemeColorResolver.resolve(config)
                        )
                        // 额外兜底：配置可能为空字符串或仅含空字段
                        if (isValidThemeConfig(normalizedConfig)) {
                            result[themeId] = normalizedConfig
                        } else {
                            Timber.tag(TAG).w("Invalid theme config (null or missing required fields): $themeId")
                        }
                    }.onFailure { t ->
                        Timber.tag(TAG).w(t, "Failed to parse theme: $themeId")
                    }
                }
            }

        return result
    }

    private fun toThemeId(pluginId: String, themePath: String): String {
        val normalized = themePath.replace('\\', '/')
        return "$THEME_ID_PREFIX$pluginId/$normalized"
    }

    /**
     * 验证主题配置是否有效
     *
     * 解析阶段已做结构化校验，这里仅做业务必填项检查
     */
    private fun isValidThemeConfig(config: ThemeConfig): Boolean = runCatching {
        config.name.isNotEmpty() && config.colors.isNotEmpty()
    }.getOrDefault(false)

    private fun resolveFileSafely(pluginDir: File, relativePath: String): File? {
        if (relativePath.isBlank()) return null
        val normalized = relativePath.replace('\\', '/')
        if (normalized.startsWith("/")) return null
        if (normalized.contains("../")) return null

        return runCatching {
            val base = pluginDir.canonicalFile
            val file = File(pluginDir, normalized).canonicalFile
            if (file.path.startsWith(base.path + File.separator)) file else null
        }.getOrNull()
    }
}
