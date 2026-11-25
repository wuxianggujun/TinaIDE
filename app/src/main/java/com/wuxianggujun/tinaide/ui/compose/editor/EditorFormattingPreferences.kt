package com.wuxianggujun.tinaide.ui.compose.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.wuxianggujun.tinaide.core.config.Prefs
import com.wuxianggujun.tinaide.core.editorview.EditorState
import com.wuxianggujun.tinaide.core.format.FormatterIndentation
import com.wuxianggujun.tinaide.core.format.FormatterIndentationResult
import com.wuxianggujun.tinaide.file.FileChangeListener
import com.wuxianggujun.tinaide.ui.compose.state.editor.EditorContainerState
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import timber.log.Timber

private const val INDENTATION_REFRESH_DEBOUNCE_MS = 100L

/** File-open, focus, settings and configuration changes share one cancellable resolution path. */
@OptIn(FlowPreview::class)
@Composable
internal fun rememberEditorProjectIndentation(
    container: EditorContainerState,
    sourceFile: File,
    editorState: EditorState,
): State<FormatterIndentation?> {
    val context = LocalContext.current.applicationContext
    val lifecycleOwner = LocalLifecycleOwner.current
    val indentation = remember(sourceFile, editorState) { mutableStateOf<FormatterIndentation?>(null) }
    val refreshes = remember(sourceFile, editorState) {
        MutableSharedFlow<Unit>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
            .apply { tryEmit(Unit) }
    }

    DisposableEffect(container, sourceFile, lifecycleOwner, refreshes) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshes.tryEmit(Unit)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        val registration = runCatching {
            container.watchFormattingConfiguration(object : FileChangeListener {
                private fun refresh(file: File) {
                    if (isEditorFormattingConfig(file, sourceFile)) refreshes.tryEmit(Unit)
                }

                override fun onFileCreated(file: File) = refresh(file)
                override fun onFileModified(file: File) = refresh(file)
                override fun onFileDeleted(file: File) = refresh(file)
                override fun onFileRenamed(oldFile: File, newFile: File) {
                    refresh(oldFile)
                    refresh(newFile)
                }
            })
        }.onFailure { Timber.w(it, "Failed to watch editor formatting configuration") }.getOrNull()
        onDispose {
            registration?.dispose()
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(container, refreshes) {
        snapshotFlow { container.activeTabIndex }.distinctUntilChanged().collectLatest {
            refreshes.tryEmit(Unit)
        }
    }
    LaunchedEffect(container, sourceFile, editorState, refreshes) {
        combine(
            Prefs.editorSettingsFlow.map { FormatterIndentation.fromEditorSettings(it) }.distinctUntilChanged(),
            refreshes,
        ) { _, _ -> Unit }
            .debounce(INDENTATION_REFRESH_DEBOUNCE_MS)
            .collectLatest {
                try {
                    when (val result = container.resolveFormattingIndentation(context, sourceFile)) {
                        is FormatterIndentationResult.Success -> {
                            indentation.value = result.indentation.takeIf { result.usesProjectConfig }
                            editorState.config = editorConfigFromPrefs(indentation.value)
                        }
                        is FormatterIndentationResult.Error ->
                            Timber.w("Could not resolve project indentation: %s", result.message)
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Exception) {
                    Timber.w(error, "Failed to resolve editor indentation")
                }
            }
    }
    return indentation
}

internal fun isEditorFormattingConfig(file: File, sourceFile: File): Boolean {
    if (file.name != ".clang-format" && file.name != "_clang-format") return false
    val directory = file.absoluteFile.normalize().parentFile ?: return false
    return generateSequence(sourceFile.absoluteFile.normalize().parentFile) { it.parentFile }
        .any { it == directory }
}
