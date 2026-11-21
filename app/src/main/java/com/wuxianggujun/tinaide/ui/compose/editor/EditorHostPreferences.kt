package com.wuxianggujun.tinaide.ui.compose.editor

import com.wuxianggujun.tinaide.core.config.Prefs
import com.wuxianggujun.tinaide.core.editorview.EditorConfig
import com.wuxianggujun.tinaide.core.editorview.EditorRuntimeOptions
import com.wuxianggujun.tinaide.core.editorview.EditorTouchDiagnosticsFlags
import com.wuxianggujun.tinaide.core.editorview.WhitespaceRenderMode
import com.wuxianggujun.tinaide.core.format.FormatterIndentation

internal fun editorConfigFromPrefs(projectIndentation: FormatterIndentation? = null): EditorConfig = runCatching {
    val rainbow = Prefs.editorRainbowBrackets
    val indentation = projectIndentation ?: FormatterIndentation.fromPreferences()
    EditorConfig(
        showLineNumbers = Prefs.editorShowLineNumbers,
        fontSizeSp = Prefs.editorFontSize,
        tabSize = indentation.tabWidth,
        indentSize = indentation.indentWidth,
        continuationIndentSize = indentation.continuationIndentWidth,
        completionCaseSensitive = Prefs.completionCaseSensitive,
        wordWrap = Prefs.editorWordWrap,
        autoIndent = Prefs.editorAutoIndent,
        scrollFlingEnabled = Prefs.editorScrollFlingEnabled,
        singleDirectionDragging = Prefs.editorSingleDirectionDragging,
        singleDirectionFling = Prefs.editorSingleDirectionFling,
        codeFolding = Prefs.editorCodeFolding,
        rainbowBrackets = rainbow,
        rainbowBracketsMaxLines = Prefs.editorRainbowBracketsMaxLines,
        bracketPairGuides = rainbow,
        renderWhitespace = when (Prefs.editorRenderWhitespace) {
            "boundary" -> WhitespaceRenderMode.BOUNDARY
            "all" -> WhitespaceRenderMode.ALL
            else -> WhitespaceRenderMode.NONE
        },
        insertSpacesForTabs = indentation.insertSpacesForTabs,
        showMinimap = Prefs.editorShowMinimap,
        showGitGutter = Prefs.editorShowGitGutter
    )
}.getOrDefault(EditorConfig())

internal fun editorRuntimeOptionsFromPrefs(): EditorRuntimeOptions = EditorRuntimeOptions(
    onFontSizeChanged = Prefs::setEditorFontSize,
    imeDiagnosticsEnabled = {
        runCatching { Prefs.devDiagnosticsEnabled }.getOrDefault(false)
    },
    layoutDiagnosticsEnabled = {
        runCatching { Prefs.developerOptionsEnabled && Prefs.devDiagnosticsEnabled }.getOrDefault(false)
    },
    touchDiagnostics = {
        runCatching {
            EditorTouchDiagnosticsFlags(
                enabled = Prefs.developerOptionsEnabled &&
                    Prefs.devDiagnosticsEnabled &&
                    Prefs.editorTouchDiagnosticsEnabled,
                verbose = Prefs.devEditorTouchInternalLogEnabled,
                scale = Prefs.devEditorTouchScaleLogEnabled,
                focus = Prefs.devEditorTouchFocusLogEnabled,
                scroll = Prefs.devEditorTouchScrollLogEnabled,
                fling = Prefs.devEditorTouchFlingLogEnabled
            )
        }.getOrDefault(EditorTouchDiagnosticsFlags())
    }
)
