package com.wuxianggujun.tinaide.core.textengine

internal object NativeLineIndexKernel {
    fun isAvailable(): Boolean = TextEngineNativeBridge.isAvailable()

    external fun nativeCreate(): Long
    external fun nativeDestroy(handle: Long)
    external fun nativeClear(handle: Long)
    external fun nativeRebuild(handle: Long, text: String)
    external fun nativeAppendChunk(handle: Long, chunk: String)
    external fun nativeGetLineCount(handle: Long): Int
    external fun nativeGetLineStart(handle: Long, line: Int): Int
    external fun nativeGetLineEnd(handle: Long, line: Int, textLength: Int): Int
    external fun nativeOffsetToLine(handle: Long, offset: Int): Int
    external fun nativePositionToOffset(handle: Long, line: Int, column: Int, textLength: Int): Int
    external fun nativeApplyChange(handle: Long, startOffset: Int, oldText: String, newText: String)
}
