package com.wuxianggujun.tinaide.core.compile

/**
 * 将运行配置中的参数文本解析为 argv。
 *
 * 支持空白分隔、单双引号、反斜杠转义和空字符串参数。未闭合引号按已输入内容处理，
 * 避免历史配置因为一次输入错误而完全无法运行。
 */
internal object CommandLineArguments {
    fun parse(commandLine: String): List<String> {
        val arguments = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var escaping = false
        var tokenStarted = false

        fun finishToken() {
            if (tokenStarted) {
                arguments += current.toString()
                current.setLength(0)
                tokenStarted = false
            }
        }

        commandLine.forEach { character ->
            if (escaping) {
                current.append(character)
                tokenStarted = true
                escaping = false
                return@forEach
            }

            when (quote) {
                '\'' -> if (character == '\'') {
                    quote = null
                } else {
                    current.append(character)
                }

                '"' -> when (character) {
                    '"' -> quote = null
                    '\\' -> escaping = true
                    else -> current.append(character)
                }

                else -> when {
                    character.isWhitespace() -> finishToken()
                    character == '\'' || character == '"' -> {
                        quote = character
                        tokenStarted = true
                    }
                    character == '\\' -> {
                        escaping = true
                        tokenStarted = true
                    }
                    else -> {
                        current.append(character)
                        tokenStarted = true
                    }
                }
            }
        }

        // 末尾孤立反斜杠按字面量保留，避免静默吞掉用户输入。
        if (escaping) current.append('\\')
        finishToken()
        return arguments
    }
}
