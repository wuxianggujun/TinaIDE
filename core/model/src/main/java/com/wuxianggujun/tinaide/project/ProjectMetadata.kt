package com.wuxianggujun.tinaide.project

import kotlinx.serialization.Serializable

/**
 * 项目构建系统类型
 *
 * 注意：这个枚举的名称会被序列化到 JSON 中，不要随意修改
 */
@Serializable
enum class ProjectBuildSystem {
    /** 单文件编译（无构建系统） */
    SINGLE_FILE,

    /** CMake 项目 */
    CMAKE,

    /** Makefile 项目 */
    MAKE,

    /** TinaIDE 插件项目 */
    PLUGIN,

    /** 未知类型（需要自动检测） */
    UNKNOWN
}

/**
 * 项目 APK 导出能力类型。
 *
 * 注意：枚举名称会写入项目元数据 JSON，不要随意修改。
 */
@Serializable
enum class ProjectApkExportType {
    /** 适配 NativeActivity 模板的原生项目 */
    NATIVE_ACTIVITY,

    /** 适配 SDL3 模板的项目 */
    SDL3,

    /** 适配终端模板的可执行程序项目 */
    TERMINAL,

    /** 显式关闭 APK 导出功能 */
    DISABLED
}

/**
 * 项目使用的 SDL 主版本。
 *
 * 该字段只描述 SDL 图形运行能力，不代表项目一定支持 APK 导出。
 */
@Serializable
enum class ProjectSdlVersion(val major: Int) {
    SDL2(2),
    SDL3(3),
    ;

    companion object {
        fun fromMajor(major: Int?): ProjectSdlVersion? = entries.firstOrNull { it.major == major }
    }
}

/**
 * 项目元数据
 *
 * @property id 项目唯一标识符（UUID）
 * @property displayName 项目显示名称
 * @property createdAt 项目创建时间戳
 * @property createdByIdeVersion 创建此项目的 IDE 版本（如 "1.0.50"）
 * @property buildSystem 构建系统类型
 * @property cppStandard C++ 标准版本（存储 CppStandard.name，如 "CPP_17"）
 * @property primaryLanguage 项目主要编程语言（存储 ProjectLanguage.name，如 "CPP"）
 * @property apkExportType 项目支持的 APK 导出类型，null 表示不显示导出 APK 功能
 * @property lastOpenedIdeVersion 最后打开此项目的 IDE 版本
 * @property lastOpenedAt 最后打开时间戳
 * @property nativeApiLevel 原生构建默认 API Level（21-35，null 表示使用编译策略默认值）
 * @property nativeIncludeDirs 原生依赖头文件搜索路径（项目级）
 * @property nativeLibraryDirs 原生依赖库搜索路径（项目级）
 * @property nativeRuntimeDirs 原生运行库搜索路径（项目级）
 * @property nativeCFlags 原生 C 编译参数（项目级）
 * @property nativeCppFlags 原生 C++ 编译参数（项目级）
 * @property nativeLdFlags 原生链接参数（项目级）
 * @property nativeLdLibs 原生链接库参数（项目级）
 * @property nativeCMakeArgs CMake 额外参数（项目级）
 */
@Serializable
data class ProjectMetadata(
    /**
     * project.json schema 版本。
     *
     * - 新写入始终使用当前 schema
     * - 读取阶段由 ProjectMetadataStore 做当前 schema 的字段归一化
     */
    val schemaVersion: Int = 5,
    val id: String,
    val displayName: String,
    val createdAt: Long,
    /** 创建此项目的 IDE 版本（如 "1.0.50"） */
    val createdByIdeVersion: String? = null,
    /** 构建系统类型，null 表示需要自动检测 */
    val buildSystem: ProjectBuildSystem? = null,
    /** C++ 标准版本，null 表示使用默认值 C++17 */
    val cppStandard: String? = null,
    /** 项目主要编程语言，null 表示需要自动检测 */
    val primaryLanguage: String? = null,
    /** 项目支持的 APK 导出类型，null 表示当前项目不支持导出 APK */
    val apkExportType: ProjectApkExportType? = null,
    /** SDL 图形运行主版本；null 表示不是 SDL 项目或需要从产物自动检测 */
    val sdlVersion: ProjectSdlVersion? = null,
    /** 最后打开此项目的 IDE 版本（如 "1.0.50"） */
    val lastOpenedIdeVersion: String? = null,
    /** 最后打开时间戳 */
    val lastOpenedAt: Long? = null,
    /** 原生构建默认 API Level（21-35），用于 sysroot/target 自动联动 */
    val nativeApiLevel: Int? = null,
    /** 原生依赖头文件搜索路径（项目级） */
    val nativeIncludeDirs: List<String> = emptyList(),
    /** 原生依赖库搜索路径（项目级） */
    val nativeLibraryDirs: List<String> = emptyList(),
    /** 原生运行库搜索路径（项目级） */
    val nativeRuntimeDirs: List<String> = emptyList(),
    /** 原生 C 编译参数（项目级） */
    val nativeCFlags: String = "",
    /** 原生 C++ 编译参数（项目级） */
    val nativeCppFlags: String = "",
    /** 原生链接参数（项目级） */
    val nativeLdFlags: String = "",
    /** 原生链接库参数（项目级） */
    val nativeLdLibs: String = "",
    /** CMake 额外参数（项目级） */
    val nativeCMakeArgs: List<String> = emptyList(),
    /** Default target for normal build/run flows, usually an executable. */
    val defaultRunTargetName: String? = null,
    /** Default target for SDL graphical run flows, usually a shared library. */
    val defaultSdlTargetName: String? = null
) {
    /**
     * 获取 C++ 标准枚举值（默认 C++17）
     */
    fun getCppStandard(): CppStandard = CppStandard.fromString(cppStandard)

    /**
     * 归一化 C++ 标准配置（优先存储 CppStandard.name）。
     *
     * - 空白值 -> null
     * - 可识别值（CPP_20 / 20 / c++20）-> CPP_20
     * - 不可识别值 -> 去首尾空白后保留
     */
    fun normalizedCppStandardValue(): String? {
        val normalized = cppStandard?.trim().orEmpty()
        if (normalized.isBlank()) return null
        val known = CppStandard.entries.firstOrNull {
            it.name.equals(normalized, ignoreCase = true) ||
                it.cmakeValue == normalized ||
                it.flag.equals(normalized, ignoreCase = true)
        }
        return known?.name ?: normalized
    }

    /**
     * 获取项目主要编程语言（默认 UNKNOWN）
     */
    fun getPrimaryLanguage(): ProjectLanguage = ProjectLanguage.fromString(primaryLanguage)

    /**
     * 获取项目支持的 APK 导出类型。
     */
    fun getApkExportTypeOrNull(): ProjectApkExportType? = apkExportType

    /**
     * 获取项目 SDL 主版本，并兼容 schema 3 及更早版本的 SDL3 元数据。
     */
    fun getSdlVersionOrNull(): ProjectSdlVersion? = sdlVersion
        ?: ProjectSdlVersion.SDL3.takeIf { apkExportType == ProjectApkExportType.SDL3 }

    /**
     * 获取项目元数据中的原生 API Level（合法范围 21-35）。
     */
    fun getNativeApiLevelOrNull(): Int? = nativeApiLevel?.takeIf { it in 21..35 }

    /**
     * 获取项目级 include 路径（去空、去重）。
     */
    fun normalizedNativeIncludeDirs(): List<String> = normalizePathEntries(nativeIncludeDirs)

    /**
     * 获取项目级 library 路径（去空、去重）。
     */
    fun normalizedNativeLibraryDirs(): List<String> = normalizePathEntries(nativeLibraryDirs)

    /**
     * 获取项目级 runtime 路径（去空、去重）。
     */
    fun normalizedNativeRuntimeDirs(): List<String> = normalizePathEntries(nativeRuntimeDirs)

    /**
     * 获取项目级 C 编译参数（去空白）。
     */
    fun normalizedNativeCFlags(): String = normalizeFlagValue(nativeCFlags)

    /**
     * 获取项目级 C++ 编译参数（去空白）。
     */
    fun normalizedNativeCppFlags(): String = normalizeFlagValue(nativeCppFlags)

    /**
     * 获取项目级链接参数（去空白）。
     */
    fun normalizedNativeLdFlags(): String = normalizeFlagValue(nativeLdFlags)

    /**
     * 获取项目级链接库参数（去空白）。
     */
    fun normalizedNativeLdLibs(): String = normalizeFlagValue(nativeLdLibs)

    /**
     * 获取项目级 CMake 参数（去空、去重）。
     */
    fun normalizedNativeCMakeArgs(): List<String> = normalizeStringEntries(nativeCMakeArgs)

    fun normalizedDefaultRunTargetName(): String? = normalizeTargetName(defaultRunTargetName)

    fun normalizedDefaultSdlTargetName(): String? = normalizeTargetName(defaultSdlTargetName)

    private fun normalizePathEntries(entries: List<String>): List<String> = normalizeStringEntries(entries)

    private fun normalizeStringEntries(entries: List<String>): List<String> {
        if (entries.isEmpty()) return emptyList()
        return entries.asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
    }

    private fun normalizeFlagValue(value: String): String {
        if (value.isBlank()) return ""
        return value.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .trim()
    }

    private fun normalizeTargetName(value: String?): String? = value
        ?.trim()
        ?.takeIf { it.isNotBlank() }
}
