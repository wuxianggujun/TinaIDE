package com.wuxianggujun.tinaide.core.textengine

/**
 * 文本内容指纹。长度 + FNV-1a 64 位哈希，用于脏标记比较，避免物化整份文档。
 */
data class TextContentFingerprint(
    val length: Int,
    val hash: Long
)

/**
 * 指纹与其对应的文档版本号。两者必须在同一次加锁读取中取出，
 * 否则可能出现"指纹来自版本 N、版本号已是 N+1"的错配。
 */
data class TextFingerprintSnapshot(
    val fingerprint: TextContentFingerprint,
    val documentVersion: Long
)
