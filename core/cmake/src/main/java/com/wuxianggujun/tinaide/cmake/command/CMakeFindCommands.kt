package com.wuxianggujun.tinaide.cmake.command

import com.wuxianggujun.tinaide.cmake.parser.Token

/**
 * CMake include/find command models.
 */

data class IncludeCommand(
    val file: String,
    val isOptional: Boolean = false,
    val resultVariable: String? = null,
    val noPolicy: Boolean = false,
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "include"
    override val scope = CommandScope.SCRIPTING
}

/**
 * cmake_minimum_required() - 设置最低 CMake 版本
 */
data class CMakeMinimumRequiredCommand(
    val version: String,
    val fatalError: Boolean = false,
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "cmake_minimum_required"
    override val scope = CommandScope.SCRIPTING
}

/**
 * cmake_policy() - 管理 CMake 策略
 */
data class CMakePolicyCommand(
    val operation: PolicyOperation,
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "cmake_policy"
    override val scope = CommandScope.SCRIPTING

    sealed class PolicyOperation {
        data class Set(val policy: String, val value: String) : PolicyOperation()
        data class Get(val policy: String, val variable: String) : PolicyOperation()
        data class Version(val version: String) : PolicyOperation()
        object Push : PolicyOperation()
        object Pop : PolicyOperation()
    }
}

/**
 * option() - 定义选项
 */
data class OptionCommand(
    val variable: String,
    val helpString: String,
    val initialValue: String? = null,
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "option"
    override val scope = CommandScope.SCRIPTING
}

/**
 * find_package() - 查找包
 */
data class FindPackageCommand(
    val packageName: String,
    val version: String? = null,
    val isExact: Boolean = false,
    val isQuiet: Boolean = false,
    val isRequired: Boolean = false,
    val components: List<String> = emptyList(),
    val optionalComponents: List<String> = emptyList(),
    val isConfig: Boolean = false,
    val isModule: Boolean = false,
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "find_package"
    override val scope = CommandScope.SCRIPTING
}

/**
 * find_library/find_path/find_file/find_program - 查找命令
 */
data class FindLibraryCommand(
    val variable: String,
    val names: List<String>,
    val hints: List<String> = emptyList(),
    val paths: List<String> = emptyList(),
    val isRequired: Boolean = false,
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "find_library"
    override val scope = CommandScope.SCRIPTING
}

data class FindPathCommand(
    val variable: String,
    val names: List<String>,
    val hints: List<String> = emptyList(),
    val paths: List<String> = emptyList(),
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "find_path"
    override val scope = CommandScope.SCRIPTING
}

data class FindFileCommand(
    val variable: String,
    val names: List<String>,
    val hints: List<String> = emptyList(),
    val paths: List<String> = emptyList(),
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "find_file"
    override val scope = CommandScope.SCRIPTING
}

data class FindProgramCommand(
    val variable: String,
    val names: List<String>,
    val hints: List<String> = emptyList(),
    val paths: List<String> = emptyList(),
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "find_program"
    override val scope = CommandScope.SCRIPTING
}

/**
 * list() - 列表操作
 */
