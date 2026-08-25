package com.wuxianggujun.tinaide.core.compile

import android.content.Context
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import com.wuxianggujun.tinaide.core.ndk.AndroidSysrootManager
import java.io.File
import timber.log.Timber

internal object NativeSysrootPreparer {
    private const val TAG = "NativeSysrootPreparer"

    suspend fun ensureInstalled(
        context: Context,
        profileId: String?,
        apiLevel: Int? = null,
    ): String? {
        val appContext = context.applicationContext
        val manager = AndroidSysrootManager(appContext)
        val arch = AndroidSysrootManager.Companion.Arch.current()
        val resolvedProfileId = manager.resolveProfileId(profileId, arch)
        val sysrootDir = manager.getSysrootDir(arch, resolvedProfileId)

        if (resolvedProfileId.isNullOrBlank()) {
            return if (manager.isInstalled(arch)) {
                null
            } else {
                missingStaticRuntimeMessage(appContext, manager, arch, resolvedProfileId, apiLevel)
                    ?: Strings.compile_sysroot_missing.strOr(appContext, sysrootDir.absolutePath)
            }
        }

        if (manager.isInstalled(arch, resolvedProfileId)) {
            return null
        }

        Timber.tag(TAG).i(
            "Installing or repairing sysroot before native build: arch=%s profile=%s dir=%s",
            arch.name,
            resolvedProfileId,
            sysrootDir.absolutePath
        )
        val result = manager.ensureProfileInstalled(resolvedProfileId, arch)
        return if (result.isSuccess) {
            null
        } else {
            missingStaticRuntimeMessage(appContext, manager, arch, resolvedProfileId, apiLevel)
                ?: result.exceptionOrNull()?.message
                ?: Strings.compile_sysroot_missing.strOr(appContext, sysrootDir.absolutePath)
        }
    }

    private fun missingStaticRuntimeMessage(
        context: Context,
        manager: AndroidSysrootManager,
        arch: AndroidSysrootManager.Companion.Arch,
        profileId: String?,
        apiLevel: Int?,
    ): String? {
        val missingStaticRuntime: File = apiLevel?.let {
            manager.findMissingStaticCppRuntimeFile(it, arch, profileId)
        } ?: return null
        return Strings.compile_sysroot_missing_static_libcpp.strOr(context, missingStaticRuntime.absolutePath)
    }
}
