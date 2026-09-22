package com.wuxianggujun.tinaide.core.editorview

/** Fenwick index over visible document lines; point edits never rewrite the following rows. */
internal class EditorVisualLineIndex(private val counts: IntArray) {
    private val sums = IntArray(counts.size + 1)
    private var totalCount: Int = 0
    val total: Int
        @Synchronized get() = totalCount

    init {
        for (index in counts.indices) {
            require(counts[index] > 0)
            val node = index + 1
            sums[node] += counts[index]
            val parent = node + (node and -node)
            if (parent < sums.size) sums[parent] += sums[node]
            totalCount += counts[index]
        }
    }

    @Synchronized
    fun countAt(index: Int): Int = counts[index]

    @Synchronized
    fun firstVisualLineAt(index: Int): Int {
        var node = index.coerceIn(0, counts.size)
        var sum = 0
        while (node > 0) {
            sum += sums[node]
            node -= node and -node
        }
        return sum
    }

    @Synchronized
    fun update(index: Int, count: Int) {
        require(count > 0)
        val delta = count - counts[index]
        if (delta == 0) return
        counts[index] = count
        totalCount += delta
        var node = index + 1
        while (node < sums.size) {
            sums[node] += delta
            node += node and -node
        }
    }

    @Synchronized
    fun visibleIndexForVisualLine(visualLine: Int): Int {
        if (counts.isEmpty()) return 0
        val target = visualLine.coerceIn(0, totalCount - 1)
        var node = 0
        var sum = 0
        var step = Integer.highestOneBit(counts.size)
        while (step != 0) {
            val next = node + step
            if (next < sums.size && sum + sums[next] <= target) {
                sum += sums[next]
                node = next
            }
            step = step ushr 1
        }
        return node.coerceAtMost(counts.lastIndex)
    }
}
