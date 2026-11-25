package com.wuxianggujun.tinaide.ui.compose.components

import android.content.ClipData
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.lang.CxxFileSupport
import com.wuxianggujun.tinaide.editor.analysis.CxxFunctionFlowAnalyzer
import com.wuxianggujun.tinaide.editor.analysis.FunctionFlow
import com.wuxianggujun.tinaide.ui.MainActivityNavigationHelper
import com.wuxianggujun.tinaide.ui.compose.state.editor.ActiveEditableEditorSnapshotResult
import com.wuxianggujun.tinaide.ui.compose.state.editor.EditorContainerState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import timber.log.Timber

@Composable
internal fun FunctionFlowDialog(editor: EditorContainerState, onDismiss: () -> Unit) {
    var revision by remember { mutableIntStateOf(0) }
    val snapshot = remember(revision) { (editor.snapshotActiveEditableEditorContent() as? ActiveEditableEditorSnapshotResult.Success)?.snapshot }
    val line = remember(revision) { editor.getCursorPositionInActiveTab()?.line ?: 0 }
    var flow by remember(revision) { mutableStateOf<FunctionFlow?>(null) }
    var failed by remember(revision) { mutableStateOf(false) }
    var stale by remember { mutableStateOf(false) }
    var zoom by remember { mutableStateOf(1f) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(revision) {
        stale = false
        failed = false
        try {
            require(snapshot != null && snapshot.file.extension.lowercase() in CxxFileSupport.editorRelatedExtensions)
            flow = CxxFunctionFlowAnalyzer().analyze(snapshot.text, line)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { Timber.w(error, "Function flow analysis failed"); failed = true }
        catch (error: LinkageError) { Timber.w(error, "Native parser unavailable"); failed = true }
    }
    fun current(): Boolean {
        val latest = (editor.snapshotActiveEditableEditorContent() as? ActiveEditableEditorSnapshotResult.Success)?.snapshot
        return (latest?.file == snapshot?.file && latest?.text == snapshot?.text).also { stale = !it }
    }
    TinaCustomDialog(onDismissRequest = onDismiss, contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
        Text(stringResource(Strings.function_flow_title), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(Strings.function_flow_scope), style = MaterialTheme.typography.bodySmall)
        if (failed) Text(stringResource(Strings.function_flow_unavailable), color = MaterialTheme.colorScheme.error)
        if (stale) Text(stringResource(Strings.function_flow_stale), color = MaterialTheme.colorScheme.error)
        val graph = flow
        if (graph == null && !failed) CircularProgressIndicator()
        if (graph != null) {
            Row {
                TextButton(onClick = { zoom = (zoom - 0.2f).coerceAtLeast(0.6f) }) { Text("−") }
                TextButton(onClick = { zoom = (zoom + 0.2f).coerceAtMost(1.8f) }) { Text("+") }
                TextButton(onClick = { if (current()) scope.launch {
                    try { clipboard.setClipEntry(ClipData.newPlainText("Mermaid", graph.mermaid()).toClipEntry()) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { Timber.w(error, "Flow copy failed"); failed = true }
                } }) { Text(stringResource(Strings.function_flow_copy)) }
            }
            FlowGraphView(graph, zoom) { node ->
                val source = node.source
                if (source != null && current() && snapshot != null) {
                    MainActivityNavigationHelper.navigateToFilePosition(snapshot.file, source.line, source.column, editor)
                    onDismiss()
                }
            }
        }
        Row {
            TextButton(onClick = { revision++ }) { Text(stringResource(Strings.menu_refresh)) }
            TextButton(onClick = onDismiss) { Text(stringResource(Strings.action_close)) }
        }
    }
}

@Composable
private fun FlowGraphView(graph: FunctionFlow, zoom: Float, onNavigate: (com.wuxianggujun.tinaide.editor.analysis.FlowNode) -> Unit) {
    // Source order keeps the function readable; side lanes preserve loops and branch destinations.
    val ordered = remember(graph) { graph.nodes.sortedWith(compareBy({ it.source?.line ?: if (it.id == 0) -1 else Int.MAX_VALUE }, { it.source?.column ?: 0 }, { it.id })) }
    val positions = remember(ordered) { ordered.mapIndexed { index, node -> node.id to index }.toMap() }
    val color = MaterialTheme.colorScheme.primary
    val cell = 112 * zoom
    val width = 420 * zoom
    Box(Modifier.fillMaxWidth().height(380.dp).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())) {
        Box(Modifier.size(width.dp, (ordered.size * cell + 20).dp)) {
            Canvas(Modifier.matchParentSize()) {
                val right = (260 * zoom).dp.toPx()
                graph.edges.forEachIndexed { index, edge ->
                    val fromY = (positions.getValue(edge.from) * cell + 42 * zoom).dp.toPx()
                    val toY = (positions.getValue(edge.to) * cell + 42 * zoom).dp.toPx()
                    val lane = (280 * zoom + (index % 8) * 14 * zoom).dp.toPx()
                    drawPath(Path().apply { moveTo(right, fromY); lineTo(lane, fromY); lineTo(lane, toY); lineTo(right, toY) }, color, style = Stroke(1.5.dp.toPx()))
                    drawLine(color, Offset(right, toY), Offset(right + 7.dp.toPx(), toY - 5.dp.toPx()), 1.5.dp.toPx())
                    drawLine(color, Offset(right, toY), Offset(right + 7.dp.toPx(), toY + 5.dp.toPx()), 1.5.dp.toPx())
                }
            }
            ordered.forEachIndexed { index, node ->
                Column(
                    Modifier.offset(8.dp, (index * cell).dp).size((252 * zoom).dp, (96 * zoom).dp)
                        .background(if (node.decision) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                        .clickable(enabled = node.source != null) { onNavigate(node) }.padding(6.dp),
                ) {
                    Text("#${node.id} ${node.text}", maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                    Text(graph.edges.filter { it.from == node.id }.joinToString { "${it.label} → #${it.to}" }, maxLines = 2, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
