package com.wuxianggujun.tinaide.plugin.lsp

internal object LspToolchainPackagePolicy {
    private const val MAX_PACKAGE_SPEC_LENGTH = 256
    private val systemPackage = Regex("^[A-Za-z0-9][A-Za-z0-9+._:@~-]*(?:=[A-Za-z0-9][A-Za-z0-9+._:~*-]*)?$")
    private val pipPackage = Regex(
        "^[A-Za-z0-9][A-Za-z0-9._-]*" +
            "(?:\\[[A-Za-z0-9_,.-]+])?" +
            "(?:(?:===|==|~=|!=|<=|>=|<|>)[A-Za-z0-9][A-Za-z0-9.*+!_,:<>=~-]*)?$",
    )
    private val npmPackage = Regex(
        "^(?:@[A-Za-z0-9][A-Za-z0-9._-]*/)?" +
            "[A-Za-z0-9][A-Za-z0-9._-]*" +
            "(?:@[A-Za-z0-9^~*<>=|+._-]+)?$",
    )
    private val fallbackVersion = Regex("^[A-Za-z0-9][A-Za-z0-9+._:~-]{0,63}$")

    fun areValid(type: String, packages: List<String>): Boolean = packages.isNotEmpty() &&
        packages.all { packageSpec -> isValid(type, packageSpec) }

    fun areFallbackVersionsValid(versions: List<String>?): Boolean = versions.orEmpty().all { version ->
        version == version.trim() && fallbackVersion.matches(version)
    }

    private fun isValid(type: String, packageSpec: String): Boolean {
        if (
            packageSpec != packageSpec.trim() ||
            packageSpec.isEmpty() ||
            packageSpec.length > MAX_PACKAGE_SPEC_LENGTH ||
            packageSpec.startsWith('-') ||
            packageSpec.any(Char::isWhitespace) ||
            packageSpec.contains("://")
        ) {
            return false
        }
        return when (type.trim().lowercase()) {
            "system" -> systemPackage.matches(packageSpec)
            "pip" -> pipPackage.matches(packageSpec)
            "npm" -> npmPackage.matches(packageSpec)
            else -> false
        }
    }
}
