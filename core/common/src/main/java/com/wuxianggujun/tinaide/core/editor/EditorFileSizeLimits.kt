package com.wuxianggujun.tinaide.core.editor

import java.io.File

/**
 * 代码编辑器可承受的文本文件大小上限。
 *
 * 超过该阈值的文件整份读进 RopeTextBuffer 会带来两个问题：
 * 一次性堆占用约为文件字节数的 4 倍（UTF-16 String + rope 分片拷贝），
 * 且 rope 建树与行索引重建会阻塞调用线程数百毫秒。
 * 因此默认改用只读分页查看器，仅在用户显式确认后才走编辑器。
 */
object EditorFileSizeLimits {

    const val LARGE_TEXT_THRESHOLD_BYTES: Long = 10L * 1024 * 1024

    /**
     * 超过该大小不再创建 tree-sitter 高亮器与折叠提供者。
     *
     * tree-sitter 会自己长期持有一份完整 `StringBuilder`，并且每次 parse 都要再物化一份快照；
     * 对 30MB 文档这两笔各约 63MB，是除 rope 本身之外最大的堆开销。
     * 与此同时它对这个量级的文档也几乎不可用——每次键入都要 replace 整个 StringBuilder。
     * 所以超过阈值直接放弃高亮与折叠，换取可编辑性。
     */
    const val SYNTAX_HIGHLIGHT_THRESHOLD_BYTES: Long = 4L * 1024 * 1024

    fun isLargeTextFile(file: File): Boolean = fileLength(file) >= LARGE_TEXT_THRESHOLD_BYTES

    fun exceedsSyntaxHighlightLimit(file: File): Boolean =
        fileLength(file) >= SYNTAX_HIGHLIGHT_THRESHOLD_BYTES

    fun fileLength(file: File): Long = runCatching { file.length() }.getOrDefault(0L)
}
