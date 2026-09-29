package com.wuxianggujun.tinaide.core.editorview

/**
 * 基于 charOffset（UTF-16 code unit）的选区范围。
 *
 * - [anchor] = 选区固定端（长按/拖拽起始点）
 * - [caret]  = 选区活动端（= cursorOffset）
 *
 * charOffset 单位 = UTF-16 code unit = Kotlin/Java String index。
 */
data class OffsetRange(
    val anchor: Int,
    val caret: Int
) {
    val start: Int get() = minOf(anchor, caret)
    val end: Int get() = maxOf(anchor, caret)
    val isEmpty: Boolean get() = anchor == caret
    val length: Int get() = end - start
}
