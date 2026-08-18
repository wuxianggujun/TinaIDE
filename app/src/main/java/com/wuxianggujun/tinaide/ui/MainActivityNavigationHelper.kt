package com.wuxianggujun.tinaide.ui

import androidx.core.net.toUri
import com.wuxianggujun.tinaide.core.lsp.Diagnostic
import com.wuxianggujun.tinaide.core.lsp.LocationItem
import com.wuxianggujun.tinaide.ui.compose.state.editor.EditorContainerState
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * MainActivity 导航辅助类
 *
 * 职责：
 * - 导航到诊断位置
 * - 导航到搜索结果
 * - 处理文件跳转逻辑
 */
object MainActivityNavigationHelper {

    /**
     * 导航到诊断位置
     *
     * @param diagnostic 诊断信息
     * @param editorContainerState 编辑器容器状态
     * @param bottomPanelController 底部面板宿主控制器
     * @param scope 协程作用域
     */
    fun navigateToDiagnostic(
        diagnostic: Diagnostic,
        editorContainerState: EditorContainerState,
        bottomPanelController: BottomPanelController?,
        scope: CoroutineScope
    ) {
        val file = resolveDiagnosticFile(diagnostic)
        editorContainerState.openFileAndGoToPosition(file, diagnostic.line, diagnostic.column)

        // 收起底部面板
        scope.launch {
            bottomPanelController?.collapseImmediate()
        }
    }

    internal fun resolveDiagnosticFile(diagnostic: Diagnostic): File {
        val filePath = try {
            diagnostic.fileUri.toUri().path ?: diagnostic.fileUri
        } catch (e: Exception) {
            if (diagnostic.fileUri.startsWith("file://")) {
                diagnostic.fileUri.substring(7)
            } else {
                diagnostic.fileUri
            }
        }
        return File(filePath)
    }

    /**
     * 导航到搜索结果
     *
     * @param filePath 文件路径
     * @param lineNumber 行号（从 1 开始）
     * @param editorContainerState 编辑器容器状态
     * @param onFileNotExist 文件不存在时的回调
     */
    fun navigateToSearchResult(
        filePath: String,
        lineNumber: Int,
        editorContainerState: EditorContainerState,
        onFileNotExist: () -> Unit
    ) {
        val file = File(filePath)
        if (!file.exists() || file.isDirectory) {
            onFileNotExist()
            return
        }

        editorContainerState.openFileAndGoToPosition(file, lineNumber - 1, 0)
    }

    /**
     * 导航到 LSP LocationItem 位置
     */
    fun navigateToLocation(
        location: LocationItem,
        editorContainerState: EditorContainerState
    ) {
        val file = File(location.filePath)
        editorContainerState.openFileAndGoToPosition(file, location.line, location.column)
    }

    /**
     * 导航到指定文件位置（0-based line/column）
     */
    fun navigateToFilePosition(
        file: File,
        line: Int,
        column: Int,
        editorContainerState: EditorContainerState,
    ) {
        editorContainerState.openFileAndGoToPosition(file, line, column)
    }
}
