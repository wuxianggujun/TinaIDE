package com.wuxianggujun.tinaide.ui.compose.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import java.io.File

/**
 * 对话框状态管理
 *
 * 统一管理 MainActivity 中的各种对话框状态，简化 MainScreen 函数
 *
 * 包含：
 * - 新建文件对话框
 * - 新建文件夹对话框
 * - 重命名对话框
 * - 删除确认对话框
 * - 关闭项目对话框
 */
class DialogState {

    // ============ 新建文件对话框 ============

    var showNewFileDialog by mutableStateOf(false)
        private set

    var newFileTargetDir by mutableStateOf<File?>(null)
        private set

    fun openNewFileDialog(targetDir: File) {
        newFileTargetDir = targetDir
        showNewFileDialog = true
    }

    fun closeNewFileDialog() {
        showNewFileDialog = false
        newFileTargetDir = null
    }

    // ============ 新建文件夹对话框 ============

    var showCreateFolderDialog by mutableStateOf(false)
        private set

    var createFolderParentDir by mutableStateOf<File?>(null)
        private set

    fun openCreateFolderDialog(parentDir: File) {
        createFolderParentDir = parentDir
        showCreateFolderDialog = true
    }

    fun closeCreateFolderDialog() {
        showCreateFolderDialog = false
        createFolderParentDir = null
    }

    // ============ 重命名对话框 ============

    var showRenameDialog by mutableStateOf(false)
        private set

    var renameFile by mutableStateOf<File?>(null)
        private set

    fun openRenameDialog(file: File) {
        renameFile = file
        showRenameDialog = true
    }

    fun closeRenameDialog() {
        showRenameDialog = false
        renameFile = null
    }

    // ============ 删除确认对话框 ============

    var showDeleteDialog by mutableStateOf(false)
        private set

    var deleteFile by mutableStateOf<File?>(null)
        private set

    fun openDeleteDialog(file: File) {
        deleteFile = file
        showDeleteDialog = true
    }

    fun closeDeleteDialog() {
        showDeleteDialog = false
        deleteFile = null
    }

    // ============ 关闭项目对话框 ============

    var showCloseProjectDialog by mutableStateOf(false)
        private set

    fun openCloseProjectDialog() {
        showCloseProjectDialog = true
    }

    fun closeCloseProjectDialog() {
        showCloseProjectDialog = false
    }

    // ============ 编辑器对话框（Goto/Replace） ============

    var showGotoLineDialog by mutableStateOf(false)
        private set

    fun openGotoLineDialog() {
        showGotoLineDialog = true
    }

    fun closeGotoLineDialog() {
        showGotoLineDialog = false
    }

}

/**
 * 创建并记住 DialogState 实例
 */
@Composable
fun rememberDialogState(): DialogState = remember { DialogState() }
