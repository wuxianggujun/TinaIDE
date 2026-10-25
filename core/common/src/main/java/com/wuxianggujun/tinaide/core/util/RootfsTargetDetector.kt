package com.wuxianggujun.tinaide.core.util

import java.io.File

/**
 * 检测 rootfs 类型，自动选择正确的 target 三元组。
 *
 * 支持的 rootfs 类型：
 * - GNU/Linux (Debian/Ubuntu): aarch64-linux-gnu, x86_64-linux-gnu
 * - Android NDK sysroot: aarch64-linux-android, x86_64-linux-android
 */
object RootfsTargetDetector {

    /**
     * rootfs 类型枚举
     */
    enum class RootfsType {
        GNU_LINUX, // Debian/Ubuntu 等 GNU/Linux 发行版
        ANDROID_NDK, // Android NDK sysroot
        UNKNOWN
    }

    /**
     * 架构类型枚举
     */
    enum class Architecture(val gnuTriple: String, val androidTriple: String) {
        AARCH64("aarch64-linux-gnu", "aarch64-linux-android"),
        X86_64("x86_64-linux-gnu", "x86_64-linux-android"),
        ARMV7("arm-linux-gnueabihf", "armv7a-linux-androideabi"),
        X86("i686-linux-gnu", "i686-linux-android");

        companion object {
            fun detect(rootfsDir: File): Architecture {
                val includeDir = File(rootfsDir, "usr/include")
                if (!includeDir.isDirectory) return AARCH64

                return when {
                    File(includeDir, "aarch64-linux-gnu").isDirectory -> AARCH64
                    File(includeDir, "x86_64-linux-gnu").isDirectory -> X86_64
                    File(includeDir, "arm-linux-gnueabihf").isDirectory -> ARMV7
                    File(includeDir, "i686-linux-gnu").isDirectory -> X86
                    // Android NDK sysroot 的 per-arch UAPI 目录是 *-linux-android[-eabi]，
                    // 必须与 GNU 检测并列；否则 x86_64 设备上的 NDK sysroot 会全部
                    // 落到默认 AARCH64，兜底 compile_commands 的 target 与实际
                    // sysroot 不匹配，clangd 解析 asm/types.h 等内核头全线失败。
                    File(includeDir, "aarch64-linux-android").isDirectory -> AARCH64
                    File(includeDir, "x86_64-linux-android").isDirectory -> X86_64
                    File(includeDir, "armv7a-linux-androideabi").isDirectory -> ARMV7
                    File(includeDir, "arm-linux-androideabi").isDirectory -> ARMV7
                    File(includeDir, "i686-linux-android").isDirectory -> X86
                    // 默认假设 aarch64（移动设备最常见）
                    else -> AARCH64
                }
            }
        }
    }

    /**
     * 检测结果
     */
    data class DetectionResult(
        val type: RootfsType,
        val architecture: Architecture,
        val target: String,
        val tripleBase: String,
        val apiLevel: Int = 28
    )

    /**
     * 检测 rootfs 类型并返回适合的 target 三元组
     *
     * 检测逻辑：
     * 1. 检查 /usr/include/{arch}-linux-gnu 是否存在 → GNU/Linux
     * 2. 检查是否有 Android 特征文件 → Android NDK
     * 3. 根据检测结果返回对应的 target 三元组
     */
    fun detect(rootfsDir: File, defaultApiLevel: Int = 28): DetectionResult {
        val type = detectRootfsType(rootfsDir)
        val arch = Architecture.detect(rootfsDir)

        return when (type) {
            RootfsType.GNU_LINUX -> DetectionResult(
                type = type,
                architecture = arch,
                target = arch.gnuTriple,
                tripleBase = arch.gnuTriple
            )
            RootfsType.ANDROID_NDK -> DetectionResult(
                type = type,
                architecture = arch,
                target = "${arch.androidTriple}$defaultApiLevel",
                tripleBase = arch.androidTriple,
                apiLevel = defaultApiLevel
            )
            RootfsType.UNKNOWN -> {
                // 默认使用 GNU/Linux 配置（更通用）
                DetectionResult(
                    type = type,
                    architecture = arch,
                    target = arch.gnuTriple,
                    tripleBase = arch.gnuTriple
                )
            }
        }
    }

    /**
     * 检测 rootfs 类型
     */
    private fun detectRootfsType(rootfsDir: File): RootfsType {
        if (!rootfsDir.isDirectory) return RootfsType.UNKNOWN

        // 检查 GNU/Linux 特征：/usr/include/{arch}-linux-gnu/bits 存在
        val includeDir = File(rootfsDir, "usr/include")
        if (includeDir.isDirectory) {
            val gnuArchDirs = listOf(
                "aarch64-linux-gnu",
                "x86_64-linux-gnu",
                "arm-linux-gnueabihf",
                "i686-linux-gnu"
            )
            for (archDir in gnuArchDirs) {
                val bitsDir = File(includeDir, "$archDir/bits")
                if (bitsDir.isDirectory) {
                    return RootfsType.GNU_LINUX
                }
            }
        }

        // 检查 Android NDK 特征
        // 1. 检查 sysroot 下的 Android 特有头文件
        val androidHeader = File(rootfsDir, "usr/include/android/log.h")
        if (androidHeader.exists()) {
            return RootfsType.ANDROID_NDK
        }

        // 2. 检查 NDK 的 api-level.h
        val apiLevelHeader = File(rootfsDir, "usr/include/android/api-level.h")
        if (apiLevelHeader.exists()) {
            return RootfsType.ANDROID_NDK
        }

        // 3. 检查是否有 bionic libc 特征
        val bionicHeader = File(rootfsDir, "usr/include/sys/_system_properties.h")
        if (bionicHeader.exists()) {
            return RootfsType.ANDROID_NDK
        }

        return RootfsType.UNKNOWN
    }

    /**
     * 获取架构特定的 include 路径
     * 例如：/usr/include/aarch64-linux-gnu
     */
    fun getArchSpecificIncludePath(rootfsDir: File, result: DetectionResult): String? {
        val path = File(rootfsDir, "usr/include/${result.tripleBase}")
        return if (path.isDirectory) path.absolutePath else null
    }
}
