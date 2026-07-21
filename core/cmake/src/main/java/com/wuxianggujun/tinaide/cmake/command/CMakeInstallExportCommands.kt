package com.wuxianggujun.tinaide.cmake.command

import com.wuxianggujun.tinaide.cmake.parser.Token

/**
 * CMake add_subdirectory/install/export/try_compile command models.
 */

data class AddSubdirectoryCommand(
    val sourceDir: String,
    val binaryDir: String? = null,
    val isExcludeFromAll: Boolean = false,
    val isSystem: Boolean = false,
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "add_subdirectory"
    override val scope = CommandScope.PROJECT
}

/**
 * add_dependencies() - 添加依赖
 */
data class AddDependenciesCommand(
    val target: String,
    val dependencies: List<String>,
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "add_dependencies"
    override val scope = CommandScope.PROJECT
}

/**
 * add_custom_command() - 添加自定义命令
 */
data class AddCustomCommandCommand(
    val mode: Mode,
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "add_custom_command"
    override val scope = CommandScope.PROJECT

    sealed class Mode {
        data class Output(
            val outputs: List<String>,
            val commands: List<List<String>>,
            val depends: List<String> = emptyList(),
            val workingDirectory: String? = null,
            val comment: String? = null
        ) : Mode()

        data class Target(
            val target: String,
            val timing: Timing,
            val commands: List<List<String>>,
            val workingDirectory: String? = null,
            val comment: String? = null
        ) : Mode()

        enum class Timing { PRE_BUILD, PRE_LINK, POST_BUILD }
    }
}

/**
 * add_custom_target() - 添加自定义目标
 */
data class AddCustomTargetCommand(
    val targetName: String,
    val commands: List<List<String>> = emptyList(),
    val depends: List<String> = emptyList(),
    val isAll: Boolean = false,
    val workingDirectory: String? = null,
    val comment: String? = null,
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "add_custom_target"
    override val scope = CommandScope.PROJECT
}

/**
 * add_test() - 添加测试
 */
data class AddTestCommand(
    val testName: String,
    val command: List<String>,
    val workingDirectory: String? = null,
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "add_test"
    override val scope = CommandScope.PROJECT
}

/**
 * enable_testing() - 启用测试
 */
data class EnableTestingCommand(
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "enable_testing"
    override val scope = CommandScope.PROJECT
}

/**
 * install() - 安装规则
 */
data class InstallCommand(
    val installType: InstallType,
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "install"
    override val scope = CommandScope.PROJECT

    sealed class InstallType {
        data class Targets(
            val targets: List<String>,
            val destination: String? = null,
            val component: String? = null
        ) : InstallType()

        data class Files(
            val files: List<String>,
            val destination: String,
            val component: String? = null
        ) : InstallType()

        data class Directory(
            val directories: List<String>,
            val destination: String,
            val component: String? = null
        ) : InstallType()

        data class Script(val script: String) : InstallType()
        data class Code(val code: String) : InstallType()
        data class Export(val exportName: String, val destination: String) : InstallType()
    }
}

/**
 * include_directories() - 全局包含目录
 */
data class IncludeDirectoriesCommand(
    val directories: List<String>,
    val isAfter: Boolean = false,
    val isBefore: Boolean = false,
    val isSystem: Boolean = false,
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "include_directories"
    override val scope = CommandScope.PROJECT
}

/**
 * link_directories() - 全局链接目录
 */
data class LinkDirectoriesCommand(
    val directories: List<String>,
    val isAfter: Boolean = false,
    val isBefore: Boolean = false,
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "link_directories"
    override val scope = CommandScope.PROJECT
}

/**
 * link_libraries() - 全局链接库
 */
data class LinkLibrariesCommand(
    val libraries: List<String>,
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "link_libraries"
    override val scope = CommandScope.PROJECT
}

/**
 * add_compile_definitions() - 全局编译定义
 */
data class AddCompileDefinitionsCommand(
    val definitions: List<String>,
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "add_compile_definitions"
    override val scope = CommandScope.PROJECT
}

/**
 * add_compile_options() - 全局编译选项
 */
data class AddCompileOptionsCommand(
    val options: List<String>,
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "add_compile_options"
    override val scope = CommandScope.PROJECT
}

/**
 * add_link_options() - 全局链接选项
 */
data class AddLinkOptionsCommand(
    val options: List<String>,
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "add_link_options"
    override val scope = CommandScope.PROJECT
}

/**
 * set_property() - 设置属性
 */
data class SetPropertyCommand(
    val scopeType: PropertyScope,
    val scopeNames: List<String>,
    val property: String,
    val values: List<String>,
    val isAppend: Boolean = false,
    val isAppendString: Boolean = false,
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "set_property"
    override val scope = CommandScope.SCRIPTING

    enum class PropertyScope {
        GLOBAL,
        DIRECTORY,
        TARGET,
        SOURCE,
        INSTALL,
        TEST,
        CACHE
    }
}

/**
 * get_property() - 获取属性
 */
data class GetPropertyCommand(
    val variable: String,
    val scopeType: PropertyScope,
    val scopeName: String?,
    val property: String,
    val isDefined: Boolean = false,
    val isSet: Boolean = false,
    val isBrief: Boolean = false,
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "get_property"
    override val scope = CommandScope.SCRIPTING

    enum class PropertyScope {
        GLOBAL,
        DIRECTORY,
        TARGET,
        SOURCE,
        INSTALL,
        TEST,
        CACHE,
        VARIABLE
    }
}

/**
 * set_target_properties() - 设置目标属性
 */
data class SetTargetPropertiesCommand(
    val targets: List<String>,
    val properties: Map<String, String>,
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "set_target_properties"
    override val scope = CommandScope.PROJECT
}

/**
 * get_target_property() - 获取目标属性
 */
data class GetTargetPropertyCommand(
    val variable: String,
    val target: String,
    val property: String,
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "get_target_property"
    override val scope = CommandScope.PROJECT
}

/**
 * export() - 导出目标
 */
data class ExportCommand(
    val exportType: ExportType,
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "export"
    override val scope = CommandScope.PROJECT

    sealed class ExportType {
        data class Targets(
            val targets: List<String>,
            val file: String,
            val namespace: String? = null
        ) : ExportType()

        data class Export(
            val exportName: String,
            val file: String,
            val namespace: String? = null
        ) : ExportType()

        data class Package(val packageName: String) : ExportType()
    }
}

/**
 * try_compile() - 尝试编译
 *
 * 语法:
 * try_compile(<resultVar> <bindir> <srcdir>
 *             <projectName> [<targetName>] [CMAKE_FLAGS <flags>...]
 *             [OUTPUT_VARIABLE <var>])
 *
 * try_compile(<resultVar> <bindir> SOURCES <srcfile>...
 *             [CMAKE_FLAGS <flags>...]
 *             [COMPILE_DEFINITIONS <defs>...]
 *             [LINK_OPTIONS <options>...]
 *             [LINK_LIBRARIES <libs>...]
 *             [OUTPUT_VARIABLE <var>]
 *             [COPY_FILE <fileName> [COPY_FILE_ERROR <var>]]
 *             [<LANG>_STANDARD <std>]
 *             [<LANG>_STANDARD_REQUIRED <bool>]
 *             [<LANG>_EXTENSIONS <bool>])
 */
data class TryCompileCommand(
    val resultVariable: String,
    val binDir: String? = null,
    val srcDir: String? = null,
    val projectName: String? = null,
    val targetName: String? = null,
    val sources: List<String> = emptyList(),
    val cmakeFlags: List<String> = emptyList(),
    val compileDefinitions: List<String> = emptyList(),
    val linkOptions: List<String> = emptyList(),
    val linkLibraries: List<String> = emptyList(),
    val outputVariable: String? = null,
    val copyFile: String? = null,
    val copyFileError: String? = null,
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "try_compile"
    override val scope = CommandScope.PROJECT
}

/**
 * try_run() - 尝试编译并运行
 *
 * 语法:
 * try_run(<runResultVar> <compileResultVar>
 *         <bindir> <srcfile> [CMAKE_FLAGS <flags>...]
 *         [COMPILE_DEFINITIONS <defs>...]
 *         [LINK_OPTIONS <options>...]
 *         [LINK_LIBRARIES <libs>...]
 *         [COMPILE_OUTPUT_VARIABLE <var>]
 *         [RUN_OUTPUT_VARIABLE <var>]
 *         [OUTPUT_VARIABLE <var>]
 *         [ARGS <args>...])
 */
data class TryRunCommand(
    val runResultVariable: String,
    val compileResultVariable: String,
    val binDir: String? = null,
    val srcFile: String? = null,
    val sources: List<String> = emptyList(),
    val cmakeFlags: List<String> = emptyList(),
    val compileDefinitions: List<String> = emptyList(),
    val linkOptions: List<String> = emptyList(),
    val linkLibraries: List<String> = emptyList(),
    val compileOutputVariable: String? = null,
    val runOutputVariable: String? = null,
    val outputVariable: String? = null,
    val runArgs: List<String> = emptyList(),
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "try_run"
    override val scope = CommandScope.PROJECT
}

/**
 * mark_as_advanced() - 标记缓存变量为高级
 *
 * 语法:
 * mark_as_advanced([CLEAR|FORCE] <var1> ...)
 *
 * 将缓存变量标记为高级变量，这些变量在 CMake GUI 中默认不显示
 */
data class MarkAsAdvancedCommand(
    val variables: List<String>,
    val isClear: Boolean = false,
    val isForce: Boolean = false,
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "mark_as_advanced"
    override val scope = CommandScope.SCRIPTING
}

/**
 * get_filename_component() - 获取文件名组件
 *
 * 语法:
 * get_filename_component(<var> <FileName> <mode> [CACHE])
 * get_filename_component(<var> <FileName> PROGRAM [PROGRAM_ARGS <arg_var>] [CACHE])
 *
 * 模式:
 * - DIRECTORY: 目录路径（不含文件名）
 * - NAME: 文件名（不含目录）
 * - EXT: 文件扩展名（最长匹配，如 .tar.gz）
 * - NAME_WE: 文件名（不含扩展名）
 * - LAST_EXT: 最后一个扩展名（如 .gz）
 * - NAME_WLE: 文件名（不含最后一个扩展名）
 * - PATH: 目录路径（已废弃，使用 DIRECTORY）
 * - ABSOLUTE: 绝对路径
 * - REALPATH: 解析符号链接后的绝对路径
 * - PROGRAM: 程序路径
 */
data class GetFilenameComponentCommand(
    val variable: String,
    val fileName: String,
    val mode: FilenameMode,
    val isCache: Boolean = false,
    val programArgs: String? = null,
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "get_filename_component"
    override val scope = CommandScope.SCRIPTING

    enum class FilenameMode {
        DIRECTORY,
        NAME,
        EXT,
        NAME_WE,
        LAST_EXT,
        NAME_WLE,
        PATH,
        ABSOLUTE,
        REALPATH,
        PROGRAM
    }
}

/**
 * cmake_parse_arguments() - 解析函数/宏参数
 *
 * 语法:
 * cmake_parse_arguments(<prefix> <options> <one_value_keywords>
 *                       <multi_value_keywords> <args>...)
 *
 * cmake_parse_arguments(PARSE_ARGV <N> <prefix> <options>
 *                       <one_value_keywords> <multi_value_keywords>)
 *
 * 这是 CMake 函数库中最常用的命令之一，用于解析函数/宏的参数。
 *
 * 参数说明:
 * - prefix: 生成变量的前缀
 * - options: 布尔选项列表（存在则为 TRUE）
 * - one_value_keywords: 单值关键字列表
 * - multi_value_keywords: 多值关键字列表
 * - args: 要解析的参数
 *
 * 生成的变量:
 * - <prefix>_<option>: 每个选项的布尔值
 * - <prefix>_<keyword>: 每个关键字的值
 * - <prefix>_UNPARSED_ARGUMENTS: 未解析的参数
 * - <prefix>_KEYWORDS_MISSING_VALUES: 缺少值的关键字
 */
data class CMakeParseArgumentsCommand(
    val prefix: String,
    val options: List<String>,
    val oneValueKeywords: List<String>,
    val multiValueKeywords: List<String>,
    val args: List<String> = emptyList(),
    val isParseArgv: Boolean = false,
    val argvStart: Int? = null,
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "cmake_parse_arguments"
    override val scope = CommandScope.SCRIPTING
}

/**
 * 未知命令 - 用于不支持的命令
 */
data class UnknownCommand(
    override val name: String,
    override val rawArguments: List<Token>
) : CMakeCommand() {
    override val scope = CommandScope.SCRIPTING
}
