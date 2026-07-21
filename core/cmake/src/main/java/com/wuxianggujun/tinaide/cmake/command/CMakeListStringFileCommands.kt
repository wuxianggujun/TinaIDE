package com.wuxianggujun.tinaide.cmake.command

import com.wuxianggujun.tinaide.cmake.parser.Token

/**
 * CMake list/string/file/math/process command models.
 */

data class ListCommand(
    val operation: ListOperation,
    val listName: String,
    val operationArgs: List<Token>,
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "list"
    override val scope = CommandScope.SCRIPTING

    enum class ListOperation {
        LENGTH,
        GET,
        JOIN,
        SUBLIST,
        FIND,
        APPEND,
        FILTER,
        INSERT,
        POP_BACK,
        POP_FRONT,
        PREPEND,
        REMOVE_ITEM,
        REMOVE_AT,
        REMOVE_DUPLICATES,
        REVERSE,
        SORT,
        TRANSFORM
    }
}

/**
 * string() - 字符串操作
 */
data class StringCommand(
    val operation: StringOperation,
    val operationArgs: List<Token>,
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "string"
    override val scope = CommandScope.SCRIPTING

    enum class StringOperation {
        FIND,
        REPLACE,
        REGEX,
        APPEND,
        PREPEND,
        CONCAT,
        JOIN,
        TOLOWER,
        TOUPPER,
        LENGTH,
        SUBSTRING,
        STRIP,
        GENEX_STRIP,
        REPEAT,
        COMPARE,
        MD5,
        SHA1,
        SHA224,
        SHA256,
        SHA384,
        SHA512,
        ASCII,
        HEX,
        CONFIGURE,
        MAKE_C_IDENTIFIER,
        RANDOM,
        TIMESTAMP,
        UUID,
        JSON
    }
}

/**
 * file() - 文件操作
 */
data class FileCommand(
    val operation: FileOperation,
    val operationArgs: List<Token>,
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "file"
    override val scope = CommandScope.SCRIPTING

    enum class FileOperation {
        READ,
        STRINGS,
        MD5,
        SHA1,
        SHA224,
        SHA256,
        SHA384,
        SHA512,
        WRITE,
        APPEND,
        TOUCH,
        TOUCH_NOCREATE,
        GENERATE,
        CONFIGURE,
        GLOB,
        GLOB_RECURSE,
        MAKE_DIRECTORY,
        REMOVE,
        REMOVE_RECURSE,
        RENAME,
        COPY_FILE,
        COPY,
        SIZE,
        READ_SYMLINK,
        CREATE_LINK,
        CHMOD,
        CHMOD_RECURSE,
        REAL_PATH,
        RELATIVE_PATH,
        TO_CMAKE_PATH,
        TO_NATIVE_PATH,
        DOWNLOAD,
        UPLOAD,
        LOCK,
        ARCHIVE_CREATE,
        ARCHIVE_EXTRACT,
        GET_RUNTIME_DEPENDENCIES
    }
}

/**
 * math() - 数学表达式
 */
data class MathCommand(
    val expr: String,
    val outputVariable: String,
    val outputFormat: OutputFormat? = null,
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "math"
    override val scope = CommandScope.SCRIPTING

    enum class OutputFormat { DECIMAL, HEXADECIMAL }
}

/**
 * execute_process() - 执行进程
 */
data class ExecuteProcessCommand(
    val commands: List<List<String>>,
    val workingDirectory: String? = null,
    val timeout: Int? = null,
    val resultVariable: String? = null,
    val resultsVariable: String? = null,
    val outputVariable: String? = null,
    val errorVariable: String? = null,
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "execute_process"
    override val scope = CommandScope.SCRIPTING
}

/**
 * configure_file() - 配置文件
 */
data class ConfigureFileCommand(
    val input: String,
    val output: String,
    val isCopyOnly: Boolean = false,
    val isEscapeQuotes: Boolean = false,
    val isAtOnly: Boolean = false,
    val newlineStyle: NewlineStyle? = null,
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "configure_file"
    override val scope = CommandScope.SCRIPTING

    enum class NewlineStyle { UNIX, DOS, WIN32, LF, CRLF }
}

// =====================================================
// 项目命令 (Project Commands)
// =====================================================

/**
 * project() - 定义项目
 */
