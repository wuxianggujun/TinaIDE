package com.wuxianggujun.tinaide.core.compile

import com.wuxianggujun.tinaide.core.ndk.AndroidSysrootManager

internal object AndroidLinkerCompatibilityFlags {
    internal const val DISABLE_RELATIVE_RELOCATION_PACKING = "-Wl,-z,nopack-relative-relocs"

    private const val ANDROID_RELR_MIN_API_LEVEL = 28

    fun forTarget(
        arch: AndroidSysrootManager.Companion.Arch,
        apiLevel: Int,
    ): List<String> {
        return if (arch == AndroidSysrootManager.Companion.Arch.ARM64 &&
            apiLevel >= ANDROID_RELR_MIN_API_LEVEL
        ) {
            listOf(DISABLE_RELATIVE_RELOCATION_PACKING)
        } else {
            emptyList()
        }
    }

    fun forCurrentTarget(apiLevel: Int): List<String> =
        forTarget(AndroidSysrootManager.Companion.Arch.current(), apiLevel)

    fun mergeWithUserLdFlags(
        arch: AndroidSysrootManager.Companion.Arch,
        apiLevel: Int,
        userLdFlags: String,
    ): String {
        return buildList {
            val compatibilityFlags = forTarget(arch, apiLevel).joinToString(" ")
            if (compatibilityFlags.isNotBlank()) add(compatibilityFlags)
            userLdFlags.trim().takeIf { it.isNotBlank() }?.let(::add)
        }.joinToString(" ")
    }
}
