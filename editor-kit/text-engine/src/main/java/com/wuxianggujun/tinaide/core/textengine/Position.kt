package com.wuxianggujun.tinaide.core.textengine

/**
 * 文本位置（0-based 行列）。
 */
data class Position(
    val line: Int,
    val column: Int
) : Comparable<Position> {
    override fun compareTo(other: Position): Int = when {
        line != other.line -> line.compareTo(other.line)
        else -> column.compareTo(other.column)
    }
}
