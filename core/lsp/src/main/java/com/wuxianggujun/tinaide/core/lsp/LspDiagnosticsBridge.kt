package com.wuxianggujun.tinaide.core.lsp

import android.os.Handler
import android.os.Looper
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.DiagnosticTag
import org.eclipse.lsp4j.Range

/**
 * 将 LSP publishDiagnostics 数据桥接为 TinaIDE 可展示的诊断模型。
 *
 * 说明：该类只做"纯协议数据 -> UI 模型"映射，不依赖任意编辑器框架事件系统。
 */
class LspDiagnosticsBridge(
    private val onUpdate: (fileUri: String, diagnostics: List<Diagnostic>) -> Unit
) {

    private val mainHandler = Handler(Looper.getMainLooper())

    fun publish(
        fileUri: String,
        fileName: String,
        data: List<org.eclipse.lsp4j.Diagnostic>,
        commitIfCurrent: (commit: () -> Unit) -> Boolean = { commit ->
            commit()
            true
        },
        onAccepted: () -> Unit = {},
    ) {
        val mapped = data.mapNotNull { diag ->
            val normalizedRange = diag.range?.normalized() ?: return@mapNotNull null
            val message = diag.message ?: return@mapNotNull null

            val severity = when (diag.severity) {
                DiagnosticSeverity.Error -> Diagnostic.Severity.ERROR
                DiagnosticSeverity.Warning -> Diagnostic.Severity.WARNING
                DiagnosticSeverity.Information -> Diagnostic.Severity.INFO
                DiagnosticSeverity.Hint -> Diagnostic.Severity.HINT
                null -> Diagnostic.Severity.INFO
            }
            val code = diag.code?.let { rawCode ->
                when {
                    rawCode.isLeft -> rawCode.left
                    rawCode.isRight -> rawCode.right?.toString()
                    else -> null
                }
            }

            Diagnostic(
                fileUri = fileUri,
                fileName = fileName,
                line = normalizedRange.startLine,
                column = normalizedRange.startColumn,
                endLine = normalizedRange.endLine,
                endColumn = normalizedRange.endColumn,
                message = message,
                severity = severity,
                source = diag.source,
                code = code,
                codeDescriptionUri = diag.codeDescription?.href,
                tags = diag.tags.orEmpty().mapNotNull { tag ->
                    when (tag) {
                        DiagnosticTag.Unnecessary -> Diagnostic.Tag.UNNECESSARY
                        DiagnosticTag.Deprecated -> Diagnostic.Tag.DEPRECATED
                        else -> null
                    }
                },
                relatedInformation = diag.relatedInformation.orEmpty().mapNotNull { information ->
                    val location = information.location ?: return@mapNotNull null
                    val range = location.range?.normalized() ?: return@mapNotNull null
                    val relatedMessage = information.message ?: return@mapNotNull null
                    Diagnostic.RelatedInformation(
                        fileUri = location.uri ?: return@mapNotNull null,
                        line = range.startLine,
                        column = range.startColumn,
                        endLine = range.endLine,
                        endColumn = range.endColumn,
                        message = relatedMessage
                    )
                },
                data = diag.data
            )
        }

        mainHandler.post {
            val accepted = commitIfCurrent(onAccepted)
            if (accepted) onUpdate(fileUri, mapped)
        }
    }

    private fun Range.normalized(): NormalizedRange? {
        val start = start ?: return null
        val end = end ?: start
        val startLine = start.line.coerceAtLeast(0)
        val startColumn = start.character.coerceAtLeast(0)
        val rawEndLine = end.line.coerceAtLeast(0)
        val rawEndColumn = end.character.coerceAtLeast(0)
        val isReversed = rawEndLine < startLine ||
            (rawEndLine == startLine && rawEndColumn < startColumn)
        return NormalizedRange(
            startLine = startLine,
            startColumn = startColumn,
            endLine = if (isReversed) startLine else rawEndLine,
            endColumn = if (isReversed) startColumn else rawEndColumn
        )
    }

    private data class NormalizedRange(
        val startLine: Int,
        val startColumn: Int,
        val endLine: Int,
        val endColumn: Int
    )
}
