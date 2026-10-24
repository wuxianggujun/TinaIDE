package com.wuxianggujun.tinaide.core.debug

/** Retains only a possible prompt prefix, including prompts split across reads. */
internal class LldbPromptDecoder(var prompt: String = "(lldb) ") {
    private var carry = ""

    fun accept(chunk: String, text: (String) -> Unit, ready: () -> Unit) {
        var remaining = carry + chunk
        carry = ""
        while (true) {
            val index = remaining.indexOf(prompt)
            if (index < 0) break
            if (index > 0) text(remaining.substring(0, index))
            remaining = remaining.substring(index + prompt.length)
            ready()
        }
        val prefixLength = (minOf(prompt.length - 1, remaining.length) downTo 1)
            .firstOrNull { remaining.endsWith(prompt.take(it)) } ?: 0
        if (remaining.length > prefixLength) text(remaining.dropLast(prefixLength))
        carry = remaining.takeLast(prefixLength)
    }
}
