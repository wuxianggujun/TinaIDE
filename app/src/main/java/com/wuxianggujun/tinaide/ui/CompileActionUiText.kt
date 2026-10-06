package com.wuxianggujun.tinaide.ui

import android.content.Context
import com.wuxianggujun.tinaide.core.compile.CompileProjectUseCase
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr

internal data class CompileActionUiText(
    val menuLabel: String,
    val progressMessage: String,
    val successMessage: String,
    val failureMessage: String
)

internal fun CompileProjectUseCase.Action.resolveUiText(
    context: Context
): CompileActionUiText {
    require(isCMakeMaintenance()) {
        "Unsupported maintenance ui action: $this"
    }
    return when (this) {
        CompileProjectUseCase.Action.CMAKE_RECONFIGURE -> {
            CompileActionUiText(
                menuLabel = Strings.menu_cmake_reconfigure.strOr(context),
                progressMessage = Strings.toast_cmake_reconfiguring.strOr(context),
                successMessage = Strings.compile_cmake_reconfigure_finished.strOr(context),
                failureMessage = Strings.compile_cmake_reconfigure_failed.strOr(context)
            )
        }

        CompileProjectUseCase.Action.CMAKE_CLEAR_BUILD_DIRECTORY -> {
            CompileActionUiText(
                menuLabel = Strings.menu_cmake_clear_build_dir.strOr(context),
                progressMessage = Strings.toast_cmake_clearing_build_dir.strOr(context),
                successMessage = Strings.compile_cmake_clear_build_dir_finished.strOr(context),
                failureMessage = Strings.compile_cmake_clear_build_dir_failed.strOr(context)
            )
        }

        CompileProjectUseCase.Action.CMAKE_CLEAR_AND_RECONFIGURE -> {
            CompileActionUiText(
                menuLabel = Strings.menu_cmake_clean_and_reconfigure.strOr(context),
                progressMessage = Strings.toast_cmake_clean_and_reconfiguring.strOr(context),
                successMessage = Strings.compile_cmake_clean_and_reconfigure_finished.strOr(context),
                failureMessage = Strings.compile_cmake_clean_and_reconfigure_failed.strOr(context)
            )
        }

        CompileProjectUseCase.Action.CMAKE_CONFIGURE_ONLY -> {
            CompileActionUiText(
                menuLabel = Strings.cxx_context_reconfigure.strOr(context),
                progressMessage = Strings.toast_cmake_reconfiguring.strOr(context),
                successMessage = Strings.compile_cmake_configure_only_finished.strOr(context),
                failureMessage = Strings.compile_cmake_reconfigure_failed.strOr(context)
            )
        }

        else -> error("Unsupported maintenance ui action: $this")
    }
}
