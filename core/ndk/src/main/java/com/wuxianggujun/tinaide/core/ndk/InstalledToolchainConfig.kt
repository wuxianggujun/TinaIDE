package com.wuxianggujun.tinaide.core.ndk

import android.content.Context
import com.wuxianggujun.tinaide.core.serialization.JsonSerializer
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import timber.log.Timber

/**
 * 工具链配置信息
 */
@Serializable
data class ToolchainInfo(
    val id: String,
    val name: String,
    val version: String?,
    val type: ToolchainType,
    val path: String,
    val installedAt: Long
)

@Serializable
enum class ToolchainType {
    BUILTIN, // 内置工具链（从 assets）
    CUSTOM // 用户导入的工具链
}

/**
 * 工具链配置
 */
@Serializable
data class InstalledToolchainConfig(
    val activeToolchain: String?,
    val toolchains: List<ToolchainInfo>
)

/**
 * 工具链配置管理器
 */
class ToolchainConfigManager(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    companion object {
        private const val TAG = "ToolchainConfigManager"
        private const val CONFIG_FILE = "toolchain-config.json"
        private const val TOOLCHAINS_DIR = "toolchains"
    }

    private val json = JsonSerializer.pretty

    private val configFile: File
        get() = File(context.filesDir, CONFIG_FILE)

    private val toolchainsDir: File
        get() = File(context.filesDir, TOOLCHAINS_DIR)

    /**
     * 读取配置
     */
    fun readConfig(): InstalledToolchainConfig = try {
        if (configFile.exists()) {
            val text = configFile.readText(Charsets.UTF_8)
            json.decodeFromString<InstalledToolchainConfig>(text)
        } else {
            InstalledToolchainConfig(activeToolchain = null, toolchains = emptyList())
        }
    } catch (e: Exception) {
        Timber.tag(TAG).e(e, "Failed to read toolchain config")
        InstalledToolchainConfig(activeToolchain = null, toolchains = emptyList())
    }

    /**
     * 保存配置
     */
    fun saveConfig(config: InstalledToolchainConfig) {
        try {
            val text = json.encodeToString(config)
            configFile.writeText(text, Charsets.UTF_8)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to save toolchain config")
        }
    }

    /**
     * 获取指定工具链的目录
     */
    fun getToolchainDir(id: String): File {
        toolchainsDir.mkdirs()
        return File(toolchainsDir, id)
    }

    /**
     * 获取当前激活的工具链目录
     */
    fun getActiveToolchainDir(): File? {
        val config = readConfig()
        val activeId = config.activeToolchain ?: return null
        val toolchainInfo = config.toolchains.find { it.id == activeId } ?: return null
        return File(context.filesDir, toolchainInfo.path)
    }

    /**
     * 获取当前激活的工具链 ID。
     */
    fun getActiveToolchainId(): String? {
        val config = readConfig()
        return config.activeToolchain?.trim()?.takeIf { it.isNotBlank() }
    }

    /**
     * 切换激活的工具链
     */
    fun switchToolchain(id: String): Result<Unit> {
        return try {
            val config = readConfig()
            val toolchainInfo = config.toolchains.find { it.id == id }
                ?: return Result.failure(IllegalArgumentException("Toolchain not found: $id"))

            val toolchainDir = File(context.filesDir, toolchainInfo.path)
            if (!toolchainDir.exists() || !toolchainDir.isDirectory) {
                return Result.failure(IllegalStateException("Toolchain directory not found: ${toolchainDir.absolutePath}"))
            }

            // 更新配置
            val newConfig = config.copy(activeToolchain = id)
            saveConfig(newConfig)

            Timber.tag(TAG).i("Switched to toolchain: $id")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to switch toolchain")
            Result.failure(e)
        }
    }

    /**
     * 注册新工具链
     */
    fun registerToolchain(info: ToolchainInfo): Result<Unit> {
        return try {
            val config = readConfig()

            // 检查 ID 是否已存在
            if (config.toolchains.any { it.id == info.id }) {
                return Result.failure(IllegalArgumentException("Toolchain ID already exists: ${info.id}"))
            }

            // 添加到列表
            val newToolchains = config.toolchains + info
            val newConfig = config.copy(toolchains = newToolchains)
            saveConfig(newConfig)

            Timber.tag(TAG).i("Registered toolchain: ${info.id}")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to register toolchain")
            Result.failure(e)
        }
    }

    /**
     * 删除工具链
     */
    suspend fun removeToolchain(id: String, deleteFiles: Boolean = true): Result<Unit> =
        withContext(ioDispatcher) {
            removeToolchainOnIo(id, deleteFiles)
        }

    private fun removeToolchainOnIo(id: String, deleteFiles: Boolean): Result<Unit> {
        return try {
            val config = readConfig()
            val toolchainInfo = config.toolchains.find { it.id == id }
                ?: return Result.failure(IllegalArgumentException("Toolchain not found: $id"))

            // 不允许删除当前激活的工具链
            if (config.activeToolchain == id) {
                return Result.failure(IllegalStateException("Cannot remove active toolchain"))
            }

            // 删除文件
            if (deleteFiles) {
                val toolchainDir = File(context.filesDir, toolchainInfo.path)
                if (toolchainDir.exists() && !toolchainDir.deleteRecursively()) {
                    throw IOException("Failed to delete toolchain directory: ${toolchainDir.absolutePath}")
                }
            }

            // 从配置中移除
            val newToolchains = config.toolchains.filter { it.id != id }
            val newConfig = config.copy(toolchains = newToolchains)
            saveConfig(newConfig)

            Timber.tag(TAG).i("Removed toolchain: $id")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to remove toolchain")
            Result.failure(e)
        }
    }
}
