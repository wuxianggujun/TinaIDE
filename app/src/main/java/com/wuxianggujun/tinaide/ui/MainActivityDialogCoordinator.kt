package com.wuxianggujun.tinaide.ui

import android.app.Activity
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.LifecycleCoroutineScope
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import com.wuxianggujun.tinaide.editor.IEditorManager
import com.wuxianggujun.tinaide.editor.session.SaveReason
import com.wuxianggujun.tinaide.editor.session.SaveResult
import com.wuxianggujun.tinaide.file.IProjectContext
import com.wuxianggujun.tinaide.file.IProjectSession
import com.wuxianggujun.tinaide.storage.ProjectDirStructure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * MainActivity 的对话框宿主协调器。
 *
 * 先承接关闭项目与未保存退出确认相关的宿主流程，避免 Activity 持续堆叠对话框编排细节。
 */
class MainActivityDialogCoordinator(
    private val activity: Activity,
    private val lifecycleScope: LifecycleCoroutineScope,
    private val editorManager: IEditorManager,
    private val projectSession: IProjectSession,
    private val projectContext: IProjectContext,
    private val onToastSuccess: (String) -> Unit,
    private val onToastError: (String) -> Unit,
) {
    var showUnsavedExitDialog by mutableStateOf(false)
        private set

    fun requestUnsavedExitConfirm() {
        showUnsavedExitDialog = true
    }

    fun onShowUnsavedExitDialogChange(show: Boolean) {
        showUnsavedExitDialog = show
    }

    fun closeProject(forgetSession: Boolean) {
        lifecycleScope.launch {
            closeProjectAndReturn(forgetSession)
        }
    }

    private suspend fun ensureAllEditorsSaved(actionName: String): Boolean {
        val results = editorManager.saveAll(SaveReason.MANUAL)
        if (results.isEmpty()) return true
        val failures = results.filterIsInstance<SaveResult.Failure>()
        if (failures.isNotEmpty()) {
            onToastError(
                Strings.toast_save_failed_cancelled.strOr(
                    activity,
                    actionName,
                    failures.first().message
                )
            )
            return false
        }
        onToastSuccess(Strings.toast_auto_saved.strOr(activity, results.size))
        return true
    }

    private suspend fun closeProjectAndReturn(forgetSession: Boolean) {
        val action = if (forgetSession) {
            Strings.action_close_and_forget.strOr(activity)
        } else {
            Strings.action_close_project.strOr(activity)
        }
        if (!ensureAllEditorsSaved(action)) return

        if (!forgetSession) {
            editorManager.persistStateSnapshot()
        }
        editorManager.closeAll(clearPersistentState = forgetSession)
        withContext(NonCancellable + Dispatchers.IO) {
            if (forgetSession) {
                clearCurrentProjectState()
            }
            projectSession.closeProject()
        }
        withContext(Dispatchers.Main) {
            activity.startActivity(
                Intent(activity, MainPortalActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
            activity.finish()
        }
    }

    private fun clearCurrentProjectState() {
        val project = projectContext.getCurrentProject() ?: return
        val stateDir = ProjectDirStructure.getStateDir(project.rootPath)
        if (stateDir.exists()) stateDir.deleteRecursively()
        val tinaideDir = ProjectDirStructure.getTinaideDir(project.rootPath)
        if (tinaideDir.exists() && tinaideDir.list()?.isEmpty() == true) {
            tinaideDir.delete()
        }
    }
}
