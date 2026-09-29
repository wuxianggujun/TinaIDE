package com.wuxianggujun.tinaide.core.treesitter

import com.itsaky.androidide.treesitter.TSInputEdit
import com.itsaky.androidide.treesitter.TSLanguage
import com.itsaky.androidide.treesitter.TSNode
import com.itsaky.androidide.treesitter.TSTree
import java.util.concurrent.locks.ReentrantLock

/**
 * 为非线程安全的 [TSTree] 提供串行访问能力。
 *
 * 渲染线程优先使用 [tryAccessTree]，避免因为后台解析阻塞 UI。
 */
internal class SafeTsTree(
    private val tree: TSTree
) : AutoCloseable {

    private val lock = ReentrantLock()

    override fun close() {
        accessTree { tree.close() }
    }

    fun <R> accessTree(block: (TreeAccessor) -> R): R {
        lock.lock()
        try {
            val accessor = TreeAccessor()
            val result = block(accessor)
            accessor.accessible = false
            return result
        } finally {
            lock.unlock()
        }
    }

    fun <R> tryAccessTree(block: (TreeAccessor) -> R): R? {
        if (!lock.tryLock()) {
            return null
        }
        try {
            val accessor = TreeAccessor()
            val result = block(accessor)
            accessor.accessible = false
            return result
        } finally {
            lock.unlock()
        }
    }

    fun accessTreeIfAvailable(block: (TreeAccessor) -> Unit): Boolean {
        if (!tree.canAccess()) return false
        return accessTree {
            if (it.closed) {
                false
            } else {
                block(it)
                true
            }
        }
    }

    inner class TreeAccessor(
        internal var accessible: Boolean = true
    ) {
        private fun checkAccess() {
            if (closed) {
                throw IllegalStateException("attempting to use an inaccessible tree accessor")
            }
        }

        val rootNode: TSNode
            get() {
                checkAccess()
                return tree.rootNode
            }

        val language: TSLanguage
            get() {
                checkAccess()
                return tree.language
            }

        val closed: Boolean
            get() = !accessible || !tree.canAccess()

        fun copy(): TSTree {
            checkAccess()
            return tree.copy()
        }

        fun edit(edit: TSInputEdit) {
            checkAccess()
            tree.edit(edit)
        }
    }
}
