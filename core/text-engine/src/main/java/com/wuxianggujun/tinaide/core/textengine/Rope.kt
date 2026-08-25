package com.wuxianggujun.tinaide.core.textengine

import kotlin.math.abs
import kotlin.math.max

/**
 * 轻量 Rope 实现，针对大文本编辑场景。
 */
internal class Rope {

    private sealed class Node {
        abstract val length: Int
        abstract val height: Int
    }

    private data class Leaf(
        val text: String
    ) : Node() {
        override val length: Int = text.length
        override val height: Int = 1
    }

    private data class Branch(
        val left: Node,
        val right: Node
    ) : Node() {
        override val length: Int = left.length + right.length
        override val height: Int = max(left.height, right.height) + 1
    }

    companion object {
        private const val CHUNK_SIZE = 4096
        private const val MAX_LEAF_SIZE = CHUNK_SIZE * 2
    }

    private var root: Node? = null

    val length: Int
        get() = root?.length ?: 0

    fun clear() {
        root = null
    }

    fun setText(text: String) {
        root = if (text.isEmpty()) null else buildTreeFromText(text)
    }

    fun append(text: String) {
        if (text.isEmpty()) return
        val appendNode = buildTreeFromText(text)
        root = concat(root, appendNode)
    }

    fun insert(offset: Int, text: String) {
        require(offset in 0..length) { "Invalid offset: $offset, length=$length" }
        if (text.isEmpty()) return

        val (left, right) = split(root, offset)
        val insertNode = buildTreeFromText(text)
        root = concat(concat(left, insertNode), right)
    }

    fun delete(start: Int, end: Int) {
        require(start in 0..length && end in start..length) {
            "Invalid range: [$start, $end), length=$length"
        }
        if (start == end) return

        val (left, tail) = split(root, start)
        val (_, right) = split(tail, end - start)
        root = concat(left, right)
    }

    fun substring(start: Int, end: Int): String {
        require(start in 0..length && end in start..length) {
            "Invalid range: [$start, $end), length=$length"
        }
        if (start == end) return ""

        return buildString(end - start) {
            appendSubstring(root, start, end, this)
        }
    }

    /** Compares without flattening the rope into a temporary String. */
    fun contentEquals(other: CharSequence): Boolean {
        if (length != other.length) return false

        var otherOffset = 0
        var matches = true
        forEachChunk { chunk ->
            if (!matches) return@forEachChunk
            for (index in chunk.indices) {
                if (chunk[index] != other[otherOffset + index]) {
                    matches = false
                    break
                }
            }
            otherOffset += chunk.length
        }
        return matches
    }

    fun charAt(offset: Int): Char {
        require(offset in 0 until length) { "Invalid offset: $offset, length=$length" }
        return charAt(root, offset)
    }

    /**
     * 按内部分片顺序回调每个叶子的文本。用于大文件流式保存 / 哈希计算：
     * 不需要把整份 rope 物化成一个 String（对 50MB 文件可省 100MB 堆分配）。
     * 回调次数等于当前叶子节点数；顺序严格按 [0, length)。
     */
    fun forEachChunk(block: (CharSequence) -> Unit) {
        forEachChunk(root, block)
    }

    private fun forEachChunk(node: Node?, block: (CharSequence) -> Unit) {
        if (node == null) return
        when (node) {
            is Leaf -> if (node.text.isNotEmpty()) block(node.text)
            is Branch -> {
                forEachChunk(node.left, block)
                forEachChunk(node.right, block)
            }
        }
    }

    override fun toString(): String = substring(0, length)

    private fun buildTreeFromText(text: String): Node {
        val leaves = text.chunked(CHUNK_SIZE).map(::Leaf)
        return buildBalanced(leaves, 0, leaves.size)
    }

    private fun buildBalanced(leaves: List<Leaf>, start: Int, end: Int): Node {
        if (end - start == 1) return leaves[start]
        val mid = (start + end) ushr 1
        val left = buildBalanced(leaves, start, mid)
        val right = buildBalanced(leaves, mid, end)
        return Branch(left, right)
    }

    private fun concat(left: Node?, right: Node?): Node? {
        if (left == null) return right
        if (right == null) return left

        if (left is Leaf && right is Leaf && left.length + right.length <= MAX_LEAF_SIZE) {
            return Leaf(left.text + right.text)
        }

        return rebalance(Branch(left, right))
    }

    private fun rebalance(node: Node): Node {
        if (node !is Branch) return node
        val balance = node.left.height - node.right.height
        if (abs(balance) <= 1) return node

        val leaves = mutableListOf<Leaf>()
        collectLeaves(node, leaves)
        return buildBalanced(leaves, 0, leaves.size)
    }

    private fun collectLeaves(node: Node, out: MutableList<Leaf>) {
        when (node) {
            is Leaf -> out.add(node)
            is Branch -> {
                collectLeaves(node.left, out)
                collectLeaves(node.right, out)
            }
        }
    }

    private fun split(node: Node?, offset: Int): Pair<Node?, Node?> {
        if (node == null) return null to null
        if (offset <= 0) return null to node
        if (offset >= node.length) return node to null

        return when (node) {
            is Leaf -> {
                val leftText = node.text.substring(0, offset)
                val rightText = node.text.substring(offset)
                (if (leftText.isEmpty()) null else Leaf(leftText)) to
                    (if (rightText.isEmpty()) null else Leaf(rightText))
            }
            is Branch -> {
                val leftLen = node.left.length
                when {
                    offset < leftLen -> {
                        val (leftA, leftB) = split(node.left, offset)
                        leftA to concat(leftB, node.right)
                    }
                    offset > leftLen -> {
                        val (rightA, rightB) = split(node.right, offset - leftLen)
                        concat(node.left, rightA) to rightB
                    }
                    else -> node.left to node.right
                }
            }
        }
    }

    private fun appendSubstring(
        node: Node?,
        start: Int,
        end: Int,
        out: StringBuilder
    ) {
        if (node == null || start >= end) return

        when (node) {
            is Leaf -> out.append(node.text, start, end)
            is Branch -> {
                val leftLen = node.left.length
                if (start < leftLen) {
                    appendSubstring(node.left, start, minOf(end, leftLen), out)
                }
                if (end > leftLen) {
                    appendSubstring(
                        node.right,
                        maxOf(0, start - leftLen),
                        end - leftLen,
                        out
                    )
                }
            }
        }
    }

    private fun charAt(node: Node?, offset: Int): Char {
        requireNotNull(node) { "Rope is empty" }
        return when (node) {
            is Leaf -> node.text[offset]
            is Branch -> {
                val leftLen = node.left.length
                if (offset < leftLen) {
                    charAt(node.left, offset)
                } else {
                    charAt(node.right, offset - leftLen)
                }
            }
        }
    }
}
