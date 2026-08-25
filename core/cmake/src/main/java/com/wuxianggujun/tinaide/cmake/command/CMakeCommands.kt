/*
 * CMake Command Definitions for TinaIDE
 * Copyright (C) 2024 wuxianggujun
 *
 * CMake 命令定义 - 支持的所有 CMake 命令枚举
 * CMake 版本: v3.26
 * 参考: https://cmake.org/cmake/help/v3.26/manual/cmake-commands.7.html
 */

package com.wuxianggujun.tinaide.cmake.command

import com.wuxianggujun.tinaide.cmake.parser.Token

/**
 * 命令作用域
 */
enum class CommandScope {
    SCRIPTING, // 脚本命令 - 始终可用
    PROJECT, // 项目命令 - 仅在 CMake 项目中可用
    CTEST, // CTest 命令
    DEPRECATED // 已弃用命令
}

/**
 * CMake 命令基类
 */
sealed class CMakeCommand {
    abstract val name: String
    abstract val scope: CommandScope
    abstract val rawArguments: List<Token>
}

// =====================================================
// 脚本命令 (Scripting Commands)
// =====================================================

/**
 * set() - 设置变量
 */
data class SetCommand(
    val variable: String,
    val values: List<String>,
    val isParentScope: Boolean = false,
    val cacheType: CacheType? = null,
    val docstring: String? = null,
    val isForce: Boolean = false,
    val isEnv: Boolean = false,
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "set"
    override val scope = CommandScope.SCRIPTING

    enum class CacheType { BOOL, FILEPATH, PATH, STRING, INTERNAL }
}

/**
 * unset() - 取消设置变量
 */
data class UnsetCommand(
    val variable: String,
    val isCache: Boolean = false,
    val isParentScope: Boolean = false,
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "unset"
    override val scope = CommandScope.SCRIPTING
}

/**
 * message() - 输出消息
 */
data class MessageCommand(
    val mode: MessageMode?,
    val messages: List<String>,
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "message"
    override val scope = CommandScope.SCRIPTING

    enum class MessageMode {
        FATAL_ERROR,
        SEND_ERROR,
        WARNING,
        AUTHOR_WARNING,
        DEPRECATION,
        NOTICE,
        STATUS,
        VERBOSE,
        DEBUG,
        TRACE,
        CHECK_START,
        CHECK_PASS,
        CHECK_FAIL
    }
}

/**
 * if/elseif/else/endif - 条件语句
 */
data class IfCommand(
    val condition: List<Token>,
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "if"
    override val scope = CommandScope.SCRIPTING
}

data class ElseIfCommand(
    val condition: List<Token>,
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "elseif"
    override val scope = CommandScope.SCRIPTING
}

data class ElseCommand(
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "else"
    override val scope = CommandScope.SCRIPTING
}

data class EndIfCommand(
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "endif"
    override val scope = CommandScope.SCRIPTING
}

/**
 * foreach/endforeach - 循环
 */
data class ForEachCommand(
    val loopVar: String,
    val items: List<Token>,
    val mode: ForEachMode = ForEachMode.ITEMS,
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "foreach"
    override val scope = CommandScope.SCRIPTING

    enum class ForEachMode { ITEMS, RANGE, IN_LISTS, IN_ITEMS, ZIP_LISTS }
}

data class EndForEachCommand(
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "endforeach"
    override val scope = CommandScope.SCRIPTING
}

/**
 * while/endwhile - 循环
 */
data class WhileCommand(
    val condition: List<Token>,
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "while"
    override val scope = CommandScope.SCRIPTING
}

data class EndWhileCommand(
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "endwhile"
    override val scope = CommandScope.SCRIPTING
}

/**
 * function/endfunction - 函数定义
 */
data class FunctionCommand(
    val functionName: String,
    val arguments: List<String>,
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "function"
    override val scope = CommandScope.SCRIPTING
}

data class EndFunctionCommand(
    val functionName: String? = null,
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "endfunction"
    override val scope = CommandScope.SCRIPTING
}

/**
 * macro/endmacro - 宏定义
 */
data class MacroCommand(
    val macroName: String,
    val arguments: List<String>,
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "macro"
    override val scope = CommandScope.SCRIPTING
}

data class EndMacroCommand(
    val macroName: String? = null,
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "endmacro"
    override val scope = CommandScope.SCRIPTING
}

/**
 * block/endblock - 块作用域 (CMake 3.25+)
 */
data class BlockCommand(
    val scopeFor: List<String> = emptyList(),
    val propagate: List<String> = emptyList(),
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "block"
    override val scope = CommandScope.SCRIPTING
}

data class EndBlockCommand(
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "endblock"
    override val scope = CommandScope.SCRIPTING
}

/**
 * break/continue/return - 流程控制
 */
data class BreakCommand(
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "break"
    override val scope = CommandScope.SCRIPTING
}

data class ContinueCommand(
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "continue"
    override val scope = CommandScope.SCRIPTING
}

data class ReturnCommand(
    val propagate: List<String> = emptyList(),
    override val rawArguments: List<Token> = emptyList()
) : CMakeCommand() {
    override val name = "return"
    override val scope = CommandScope.SCRIPTING
}

/**
 * include() - 包含其他 CMake 文件
 */
