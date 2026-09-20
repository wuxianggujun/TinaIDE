package com.wuxianggujun.tinaide.core.textengine

internal object NativeTextScanKernel {
    fun isAvailable(): Boolean = TextEngineNativeBridge.isAvailable()

    external fun nativeHasActiveSignatureHelpContext(textBeforeCursor: String): Boolean
    external fun nativeAdvanceBracketDepth(startDepth: Int, lineText: String): Int
    external fun nativeAdvanceBracketDepthPrefix(startDepth: Int, lineText: String, endColumn: Int): Int
    external fun nativeComputeLineBoundaryBracketDepths(startDepth: Int, text: String): IntArray
    external fun nativeFindMatchingBracket(text: String, cursorOffset: Int): IntArray
    external fun nativeFindBracketPairs(text: String): IntArray
    external fun nativeFindBracketGuideSpans(
        text: String,
        visibleStartLine: Int,
        visibleEndLine: Int,
        includeOpenSpansAtEnd: Boolean
    ): IntArray
    external fun nativeScanBracketSnapshot(
        startDepth: Int,
        text: String,
        visibleStartLine: Int,
        visibleEndLine: Int,
        includeOpenSpansAtEnd: Boolean
    ): IntArray
    external fun nativeFindWordBounds(lineText: String, column: Int): IntArray
    external fun nativeFindWordPrefixStart(lineText: String, column: Int): Int
    external fun nativeFindWholeWordMatches(lineText: String, word: String): IntArray
    external fun nativeFindWhitespaceMarkers(lineText: String, boundaryOnly: Boolean): IntArray
    external fun nativeFindTabColumns(lineText: String): IntArray
    external fun nativeMeasureVisualColumns(lineText: String, tabSize: Int): Int
    external fun nativeMeasureVisualColumnsPrefix(lineText: String, tabSize: Int, endColumn: Int): Int
    external fun nativeFindWrapSegmentStarts(lineText: String, wrapColumns: Int, tabSize: Int): IntArray
    external fun nativeBuildVisualColumnPrefix(lineText: String, tabSize: Int): IntArray
    external fun nativeScanLineWhitespace(lineText: String, tabSize: Int): IntArray
}
