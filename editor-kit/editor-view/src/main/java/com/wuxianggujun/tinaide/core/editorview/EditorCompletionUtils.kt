package com.wuxianggujun.tinaide.core.editorview

import com.wuxianggujun.tinaide.core.lang.MakeFileSupport

internal const val COMPLETION_TRIGGER_DEBOUNCE_MS = 70L
private const val COMPLETION_TRIGGER_MAX_INSERT_CHARS = 8

internal fun shouldRequestCompletionAfterInsert(
    insertedText: String,
    trigger: Char?,
    charBeforeTrigger: Char? = null,
    fileName: String? = null
): Boolean {
    if (trigger == null) return false
    if (insertedText.isEmpty()) return false
    if (insertedText.length > COMPLETION_TRIGGER_MAX_INSERT_CHARS) return false
    if (insertedText.any { it == '\n' || it == '\r' || it == '\t' }) return false

    if (trigger == '.' || trigger == '_' || trigger.isLetterOrDigit()) return true
    if (trigger == '>' && charBeforeTrigger == '-') return true
    if (trigger == ':' && charBeforeTrigger == ':') return true

    if (fileName != null) {
        val nameLower = fileName.lowercase()
        val isCMake = nameLower == "cmakelists.txt" || nameLower.endsWith(".cmake")
        val isMakefile = MakeFileSupport.isMakeLikeFileName(fileName)
        if (isCMake || isMakefile) {
            if (trigger == '$' || trigger == '{' || trigger == '(') return true
        }
    }

    return false
}

internal fun isTriggerCharacter(ch: Char?, charBefore: Char?): Boolean {
    if (ch == null) return false
    if (ch == '.') return true
    if (ch == '>' && charBefore == '-') return true
    if (ch == ':' && charBefore == ':') return true
    return false
}

internal fun isCompletionPrefixExtension(oldPrefix: String, newPrefix: String): Boolean = newPrefix.length > oldPrefix.length &&
    newPrefix.startsWith(oldPrefix, ignoreCase = true)
