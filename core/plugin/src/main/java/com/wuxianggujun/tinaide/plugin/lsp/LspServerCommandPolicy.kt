package com.wuxianggujun.tinaide.plugin.lsp

internal object LspServerCommandPolicy {
    private const val MAX_COMMAND_LENGTH = 1_024
    private const val MAX_ARGUMENT_COUNT = 128
    private const val MAX_ARGUMENT_LENGTH = 4_096
    private const val MAX_ENVIRONMENT_COUNT = 64
    private const val MAX_ENVIRONMENT_VALUE_LENGTH = 8_192
    private val environmentNamePattern = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

    fun isValid(command: String?, args: List<String>, environment: Map<String, String>): Boolean {
        if (command.isNullOrBlank() || command.length > MAX_COMMAND_LENGTH || command.hasControlCharacters()) {
            return false
        }
        if (args.size > MAX_ARGUMENT_COUNT || args.any { argument ->
                argument.length > MAX_ARGUMENT_LENGTH || argument.hasControlCharacters()
            }
        ) {
            return false
        }
        return environment.size <= MAX_ENVIRONMENT_COUNT && environment.all { (name, value) ->
            environmentNamePattern.matches(name) &&
                value.length <= MAX_ENVIRONMENT_VALUE_LENGTH &&
                !value.contains('\u0000')
        }
    }

    private fun String.hasControlCharacters(): Boolean =
        contains('\u0000') || contains('\n') || contains('\r')
}
