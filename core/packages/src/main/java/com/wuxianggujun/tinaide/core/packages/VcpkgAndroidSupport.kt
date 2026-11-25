package com.wuxianggujun.tinaide.core.packages

enum class VcpkgPlatformSupport { SUPPORTED, UNSUPPORTED, UNKNOWN }

/** Evaluates upstream supports against TinaIDE's native, static Android triplet. */
object VcpkgAndroidSupport {
    fun evaluate(expression: String, androidAbi: String): VcpkgPlatformSupport {
        if (androidAbi !in setOf("arm64-v8a", "x86_64") || expression.length > MAX_EXPRESSION_LENGTH) {
            return VcpkgPlatformSupport.UNKNOWN
        }
        if (expression.isBlank()) return VcpkgPlatformSupport.SUPPORTED
        return try {
            if (Parser(expression, androidAbi).parse()) VcpkgPlatformSupport.SUPPORTED
            else VcpkgPlatformSupport.UNSUPPORTED
        } catch (_: InvalidExpression) {
            // Do not turn an unknown condition into a positive match through negation.
            VcpkgPlatformSupport.UNKNOWN
        }
    }

    private class InvalidExpression : IllegalArgumentException()

    private class Parser(private val source: String, private val abi: String) {
        private var position = 0

        fun parse(): Boolean {
            val result = expression(0)
            whitespace()
            if (position != source.length) throw InvalidExpression()
            return result
        }

        // A comma is OR with lower precedence. Upstream forbids mixing & and | without parentheses.
        private fun expression(depth: Int): Boolean {
            if (depth > MAX_DEPTH) throw InvalidExpression()
            var result = booleanChain(depth)
            while (consume(',')) {
                val next = booleanChain(depth)
                result = result or next
            }
            return result
        }

        private fun booleanChain(depth: Int): Boolean {
            var result = operand(depth)
            var chainOperator: Char? = null
            while (true) {
                whitespace()
                val operator = when {
                    consume('&') -> '&'
                    consume('|') -> '|'
                    word("and") -> '&'
                    else -> return result
                }
                if (chainOperator != null && chainOperator != operator) throw InvalidExpression()
                chainOperator = operator
                val next = operand(depth) // Always parse both sides, including unknown/invalid conditions.
                result = if (operator == '&') result and next else result or next
            }
        }

        private fun operand(depth: Int): Boolean {
            val negated = consume('!') || word("not")
            val value = if (consume('(')) {
                val nested = expression(depth + 1)
                if (!consume(')')) throw InvalidExpression()
                nested
            } else {
                whitespace()
                val start = position
                while (position < source.length && identifierChar(source[position])) position++
                when (source.substring(start, position)) {
                    "android", "static", "staticcrt", "native" -> true
                    "arm", "arm64" -> abi == "arm64-v8a"
                    "x64" -> abi == "x86_64"
                    "x86", "arm32", "arm64ec", "wasm32", "mips64",
                    "windows", "mingw", "linux", "freebsd", "openbsd", "netbsd", "bsd",
                    "solaris", "osx", "uwp", "xbox", "emscripten", "ios", "qnx", "vxworks",
                    "tvos", "watchos", "visionos", "apple", "ohos" -> false
                    else -> throw InvalidExpression()
                }
            }
            return if (negated) !value else value
        }

        private fun word(value: String): Boolean {
            whitespace()
            val end = position + value.length
            if (!source.startsWith(value, position) || end < source.length && identifierChar(source[end])) return false
            position = end
            return true
        }

        private fun consume(character: Char): Boolean {
            whitespace()
            if (position >= source.length || source[position] != character) return false
            position++
            return true
        }

        private fun whitespace() {
            while (position < source.length && source[position] in " \t\n\r") position++
        }

        private fun identifierChar(character: Char) = character in 'a'..'z' || character in '0'..'9'
    }

    private const val MAX_EXPRESSION_LENGTH = 4096
    private const val MAX_DEPTH = 64
}
