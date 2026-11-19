package com.wuxianggujun.tinaide.editor.symbol

data class GlobalSymbol(
    val name: String,
    val kind: SymbolKind,
    val detail: String,
    val insertText: String = name,
    val location: SymbolLocation? = null,
    val signature: String? = null,
    val documentation: String? = null,
    val containerName: String? = null,
)

data class SymbolLocation(
    val line: Int,
    val column: Int,
)

enum class SymbolKind {
    Class,
    Struct,
    Enum,
    Namespace,
    Function,
    Method,
    Field,
    Variable,
    Constant,
    Interface,
    Module,
    Property,
    Trait,
}
