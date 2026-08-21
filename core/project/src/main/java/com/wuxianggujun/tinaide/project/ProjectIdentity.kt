package com.wuxianggujun.tinaide.project

import java.util.UUID

object ProjectIdentity {
    private const val MAX_ID_LENGTH = 128
    private val safeIdRegex = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,${MAX_ID_LENGTH - 1}}$")

    fun isValid(id: String): Boolean = safeIdRegex.matches(id)

    fun requireValid(id: String): String {
        require(isValid(id)) { "Invalid project identity" }
        return id
    }

    fun create(): String = UUID.randomUUID().toString()
}
