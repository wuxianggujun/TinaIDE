package com.wuxianggujun.tinaide.cmake.command

import com.wuxianggujun.tinaide.cmake.parser.Token

/**
 * CMake project/target command models.
 */

data class ProjectCommand(
    val projectName: String,
    val version: String? = null,
    val description: String? = null,
    val homepage: String? = null,
    val languages: List<String> = emptyList(),
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "project"
    override val scope = CommandScope.PROJECT
}

/**
 * add_executable() - 添加可执行目标
 */
data class AddExecutableCommand(
    val targetName: String,
    val sources: List<String> = emptyList(),
    val isWin32: Boolean = false,
    val isMacOSXBundle: Boolean = false,
    val isExcludeFromAll: Boolean = false,
    val isImported: Boolean = false,
    val isAlias: Boolean = false,
    val aliasTarget: String? = null,
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "add_executable"
    override val scope = CommandScope.PROJECT
}

/**
 * add_library() - 添加库目标
 */
data class AddLibraryCommand(
    val targetName: String,
    val libraryType: LibraryType? = null,
    val sources: List<String> = emptyList(),
    val isExcludeFromAll: Boolean = false,
    val isImported: Boolean = false,
    val isAlias: Boolean = false,
    val aliasTarget: String? = null,
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "add_library"
    override val scope = CommandScope.PROJECT

    enum class LibraryType { STATIC, SHARED, MODULE, OBJECT, INTERFACE }
}

/**
 * target_link_libraries() - 链接库
 */
data class TargetLinkLibrariesCommand(
    val target: String,
    val libraries: List<LibraryLink>,
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "target_link_libraries"
    override val scope = CommandScope.PROJECT

    data class LibraryLink(val name: String, val visibility: Visibility = Visibility.PUBLIC)
    enum class Visibility { PUBLIC, PRIVATE, INTERFACE }
}

/**
 * target_include_directories() - 添加包含目录
 */
data class TargetIncludeDirectoriesCommand(
    val target: String,
    val directories: List<DirectoryEntry>,
    val isSystem: Boolean = false,
    val isBefore: Boolean = false,
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "target_include_directories"
    override val scope = CommandScope.PROJECT

    data class DirectoryEntry(val path: String, val visibility: Visibility = Visibility.PUBLIC)
    enum class Visibility { PUBLIC, PRIVATE, INTERFACE }
}

/**
 * target_compile_definitions() - 添加编译定义
 */
data class TargetCompileDefinitionsCommand(
    val target: String,
    val definitions: List<DefinitionEntry>,
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "target_compile_definitions"
    override val scope = CommandScope.PROJECT

    data class DefinitionEntry(val definition: String, val visibility: Visibility = Visibility.PUBLIC)
    enum class Visibility { PUBLIC, PRIVATE, INTERFACE }
}

/**
 * target_compile_options() - 添加编译选项
 */
data class TargetCompileOptionsCommand(
    val target: String,
    val options: List<OptionEntry>,
    val isBefore: Boolean = false,
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "target_compile_options"
    override val scope = CommandScope.PROJECT

    data class OptionEntry(val option: String, val visibility: Visibility = Visibility.PUBLIC)
    enum class Visibility { PUBLIC, PRIVATE, INTERFACE }
}

/**
 * target_compile_features() - 添加编译特性
 */
data class TargetCompileFeaturesCommand(
    val target: String,
    val features: List<FeatureEntry>,
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "target_compile_features"
    override val scope = CommandScope.PROJECT

    data class FeatureEntry(val feature: String, val visibility: Visibility = Visibility.PUBLIC)
    enum class Visibility { PUBLIC, PRIVATE, INTERFACE }
}

/**
 * target_sources() - 添加源文件
 */
data class TargetSourcesCommand(
    val target: String,
    val sources: List<SourceEntry>,
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "target_sources"
    override val scope = CommandScope.PROJECT

    data class SourceEntry(val source: String, val visibility: Visibility = Visibility.PUBLIC)
    enum class Visibility { PUBLIC, PRIVATE, INTERFACE }
}

/**
 * target_link_directories() - 添加链接目录
 */
data class TargetLinkDirectoriesCommand(
    val target: String,
    val directories: List<DirectoryEntry>,
    val isBefore: Boolean = false,
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "target_link_directories"
    override val scope = CommandScope.PROJECT

    data class DirectoryEntry(val path: String, val visibility: Visibility = Visibility.PUBLIC)
    enum class Visibility { PUBLIC, PRIVATE, INTERFACE }
}

/**
 * target_link_options() - 添加链接选项
 */
data class TargetLinkOptionsCommand(
    val target: String,
    val options: List<OptionEntry>,
    val isBefore: Boolean = false,
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "target_link_options"
    override val scope = CommandScope.PROJECT

    data class OptionEntry(val option: String, val visibility: Visibility = Visibility.PUBLIC)
    enum class Visibility { PUBLIC, PRIVATE, INTERFACE }
}

/**
 * add_subdirectory() - 添加子目录
 */
