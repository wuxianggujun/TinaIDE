package com.wuxianggujun.tinaide.ui.runtime

import android.app.Activity
import android.app.AlertDialog
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import timber.log.Timber

/**
 * 图形运行宿主启动失败时的提示对话框。
 *
 * 宿主进程会在退出时被 kill，Toast 往往来不及被用户看到；
 * 因此这里用不可取消的对话框把原因和程序输出留在屏幕上，直到用户确认。
 */
internal object NativeRuntimeFailureDialog {
    private const val TAG = "NativeRuntimeFailureDialog"

    /**
     * @param onDismiss 用户确认后回调；调用方通常在这里放行宿主退出流程。
     */
    fun show(
        activity: Activity,
        message: String,
        onDismiss: () -> Unit,
    ) {
        if (activity.isFinishing || activity.isDestroyed) {
            onDismiss()
            return
        }

        runCatching {
            AlertDialog.Builder(activity)
                .setTitle(Strings.sdl_runtime_title_start_failed.strOr(activity))
                .setMessage(message)
                .setCancelable(false)
                .setPositiveButton(Strings.floating_overlay_exit.strOr(activity)) { dialog, _ ->
                    dialog.dismiss()
                    onDismiss()
                }
                .show()
        }.onFailure { error ->
            Timber.tag(TAG).e(error, "Failed to show runtime failure dialog")
            onDismiss()
        }
    }

    /** 把程序输出拼进提示正文；没有输出时保持原样。 */
    fun appendOutput(activity: Activity, message: String, output: String): String {
        if (output.isBlank()) return message
        val header = Strings.sdl_runtime_error_output_header.strOr(activity)
        return "$message\n\n$header\n$output"
    }
}
