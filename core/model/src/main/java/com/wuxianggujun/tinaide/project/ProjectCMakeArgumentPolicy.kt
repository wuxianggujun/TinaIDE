package com.wuxianggujun.tinaide.project

/** Keeps IDE-managed CMake cache variables out of project-wide extra arguments. */
object ProjectCMakeArgumentPolicy {
    const val BUILD_TYPE_VARIABLE = "CMAKE_BUILD_TYPE"

    private val cacheDefinitionPattern = Regex(
        pattern = """(?:^|\s)-D\s*([A-Za-z_][A-Za-z0-9_]*)(?::[^=\s]+)?\s*="""
    )

    fun containsManagedBuildType(arguments: List<String>): Boolean =
        arguments.any(::definesManagedBuildType)

    fun sanitize(arguments: List<String>): List<String> = arguments.asSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .filterNot(::definesManagedBuildType)
        .distinct()
        .toList()

    private fun definesManagedBuildType(argument: String): Boolean {
        val normalized = argument.trim().trim('"', '\'')
        return cacheDefinitionPattern.findAll(normalized).any { match ->
            match.groupValues[1].equals(BUILD_TYPE_VARIABLE, ignoreCase = true)
        }
    }
}
