package com.wuxianggujun.tinaide.core.common.registry

object RegistryPackageId {
    private const val MAX_LENGTH = 128
    private val pattern = Regex("^[A-Za-z0-9][A-Za-z0-9._-]*$")

    fun isValid(value: String): Boolean =
        value.length in 1..MAX_LENGTH && pattern.matches(value)

    fun requireValid(value: String, fieldName: String = "package id"): String {
        require(isValid(value)) { "Invalid $fieldName: $value" }
        return value
    }
}
