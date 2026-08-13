# LSP Snippet 占位符处理机制

> 文档版本：2.2
> 创建日期：2026-03-03
> 最后更新：2026-08-13

## 概述

本文档说明 TinaIDE 如何处理 LSP 返回的 snippet 格式补全文本，以及已实现的 snippet 引擎功能。

## 背景

### 什么是 LSP Snippet

LSP (Language Server Protocol) 的补全项可以返回 snippet 格式的文本，包含占位符和跳转点：

```
${1:name}           // 第1个占位符，默认值为 name
${2:type}           // 第2个占位符，默认值为 type
${3}                // 第3个空占位符
${1|one,two,three|} // choice snippet，提供选项列表
$0                  // 最终光标位置
```

示例：
```cpp
// LSP 返回的 snippet 文本
void ${1:functionName}(${2:int} ${3:param}) {
    $0
}
```

## 当前实现（Phase 1 + Phase 2）

### 核心模块

| 文件 | 职责 |
|------|------|
| [`SnippetParser.kt`](../../core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/SnippetParser.kt) | Snippet 语法解析，生成 AST 和展开文本 |
| [`SnippetSession.kt`](../../core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/SnippetSession.kt) | 会话状态管理，Tab/Shift+Tab 跳转，偏移与长度同步 |
| [`EditorState.kt`](../../core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorState.kt) | 编辑器集成，占位符焦点/选区管理 |
| [`EditorKeyboardShortcuts.kt`](../../core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorKeyboardShortcuts.kt) | Tab/Shift+Tab/Escape 快捷键处理 |
| [`EditorStateEditOperations.kt`](../../core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorStateEditOperations.kt) | 编辑操作时同步 snippet 偏移 |
| [`LspEditorManager.kt`](../../core/editor-lsp/src/main/java/com/wuxianggujun/tinaide/core/editorlsp/LspEditorManager.kt) | LSP 补全请求与结果装配 |
| [`LspCompletionMapping.kt`](../../core/editor-lsp/src/main/java/com/wuxianggujun/tinaide/core/editorlsp/LspCompletionMapping.kt) | LSP 补全项转换，snippet 文本透传给引擎 |
| [`PluginSnippetManager.kt`](../../core/plugin/src/main/java/com/wuxianggujun/tinaide/plugin/PluginSnippetManager.kt) | 插件 snippet 展开为纯文本 |

### SnippetParser 支持的语法

| Snippet 格式 | 解析结果 | 展开文本 |
|-------------|---------|----------|
| `${1:name}` | `Placeholder(1, "name")` | `name` |
| `${2:int}` | `Placeholder(2, "int")` | `int` |
| `${3}` | `Placeholder(3, "")` | `` |
| `$1` | `Placeholder(1, "")` | `` |
| `$0` | `Placeholder(0, "")` 排最后 | `` |
| `${1\|one,two,three\|}` | `ChoicePlaceholder(1, ["one","two","three"])` | `one` |
| `\$`、`\}`、`\\` | 转义字符 | `$`、`}`、`\` |

### SnippetSession 功能

- **Tab 正向跳转**：`advance()` → 跳到下一个不同 tabstopIndex 的占位符
- **Shift+Tab 反向跳转**：`retreat()` → 退回上一个 tabstopIndex 组
- **$0 终止**：到达 `$0` 后结束会话，光标定位于 `$0` 位置
- **Escape 取消**：随时取消 snippet 会话，光标停留在当前位置
- **偏移与长度同步**：`adjustOffsets(editOffset, delta)` 在 snippet 内编辑时动态更新占位符偏移和当前占位符长度
- **多处同 index**：同一 tabstopIndex 出现多次时，`currentGroup()` 返回整个分组
- **破坏性操作保护**：undo/redo/replaceAll/toggleLineComment 会自动取消 snippet 会话

### LSP 补全流转

位置：[`LspEditorManager.kt`](../../core/editor-lsp/src/main/java/com/wuxianggujun/tinaide/core/editorlsp/LspEditorManager.kt) 与 [`LspCompletionMapping.kt`](../../core/editor-lsp/src/main/java/com/wuxianggujun/tinaide/core/editorlsp/LspCompletionMapping.kt)

当 LSP 返回 `InsertTextFormat.Snippet` 格式的补全项时：

1. `extractSnippetText(item)` 检测到 snippet 格式且文本含 `$` → 返回原始 snippet 文本
2. 补全项同时携带 `insertText`（原始文本）和 `snippetText`（原始 snippet 文本）
3. 用户选择补全项后，`editorApplyCompletion()` 检测到 `snippetText != null`，走 snippet 路径
4. `applySnippetCompletion()` 解析 snippet，插入 `expandedText`（纯文本），启动 `SnippetSession`

```kotlin
private fun normalizeCompletionPayloadText(
    text: String,
    insertTextFormat: InsertTextFormat?
): String {
    if (insertTextFormat != InsertTextFormat.Snippet) return text
    // snippet 文本直接返回原文，由编辑器 snippet 引擎处理
    return text
}
```

是否生成 snippet 占位符由 clangd 服务端配置 `clangdFunctionArgPlaceholders`（boolean）控制：
- **开启**（默认）：clangd 返回含 `${1:type} ${2:name}` 的 snippet，编辑器引擎处理跳转
- **关闭**：clangd 返回普通文本，无 snippet 处理

### 编辑操作的 snippet 偏移同步

所有编辑操作均会通知 snippet session 更新偏移：

| 操作 | 偏移通知方式 |
|------|-------------|
| `editorInsert` | `adjustSnippetOffsets(offset, text.length)` |
| `editorBackspace`（普通删除） | `adjustSnippetOffsets(deleteStart, -deleteCount)` |
| `editorBackspace`（配对删除） | `adjustSnippetOffsets(offset - 1, -2)` |
| `editorBackspace`（选区删除） | `adjustSnippetOffsets(selStart, -selLen)` |
| `editorDeleteForward` | `adjustSnippetOffsets(offset, -deleteCount)` |
| `editorReplaceSelection` | `adjustSnippetOffsets(safeStart, delta)` |
| `editorReplaceRange` | `adjustSnippetOffsets(safeStart, delta)` |
| `editorUndo` / `editorRedo` | `cancelSnippet()`（无法可靠追踪偏移） |
| `editorReplaceAll` | `cancelSnippet()`（全量替换，偏移不可恢复） |
| `editorToggleLineComment` | `cancelSnippet()`（多行变更，偏移不可恢复） |

### 快捷键

| 快捷键 | 行为 |
|--------|------|
| Tab（snippet 活跃时） | 跳转到下一个 tabstop |
| Shift+Tab（snippet 活跃时） | 退回上一个 tabstop |
| Escape（snippet 活跃时） | 取消 snippet 会话 |

## 实现阶段

| 阶段 | 功能 | 优先级 | 状态 |
|------|------|--------|------|
| 1 | 基础解析器 + 单占位符跳转 + Escape 取消 | P0 | ✅ 已完成（2026-03-07） |
| 2 | Choice snippet 解析 + Shift+Tab 后退 + 偏移与长度同步 | P0 | ✅ 已完成（2026-03-08） |
| 3 | 多占位符同步编辑（同 index 多处同步输入） | P0 | ❌ 待实现 |
| 4 | 嵌套 snippet 支持 | P2 | ❌ 待实现 |
| 5 | 变量替换（如 `$TM_FILENAME`） | P2 | ❌ 待实现 |
| 6 | Choice snippet 下拉选择 UI | P1 | ❌ 待实现 |

## 已知限制

- **嵌套占位符**：`${1:hello ${2:world}}` 不支持，内层 `${2:world}` 不会被识别
- **`${...}` 内转义**：`${1:text\}more}` 中 `\}` 不会被识别为转义，解析器会在第一个 `}` 截断
- **变量替换**：`$TM_FILENAME`、`$CLIPBOARD` 等 TextMate 变量暂不支持

## 相关文件

- [`SnippetParser.kt`](../../core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/SnippetParser.kt)：解析器
- [`SnippetSession.kt`](../../core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/SnippetSession.kt)：状态管理
- [`EditorState.kt`](../../core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorState.kt)：编辑器集成
- [`EditorKeyboardShortcuts.kt`](../../core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorKeyboardShortcuts.kt)：快捷键
- [`EditorStateEditOperations.kt`](../../core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorStateEditOperations.kt)：编辑操作偏移同步
- [`LspEditorManager.kt`](../../core/editor-lsp/src/main/java/com/wuxianggujun/tinaide/core/editorlsp/LspEditorManager.kt)：LSP 补全请求与结果装配
- [`LspCompletionMapping.kt`](../../core/editor-lsp/src/main/java/com/wuxianggujun/tinaide/core/editorlsp/LspCompletionMapping.kt)：LSP 补全项转换
- [`PluginSnippetManager.kt`](../../core/plugin/src/main/java/com/wuxianggujun/tinaide/plugin/PluginSnippetManager.kt)：插件 snippet 展开
- [`Prefs.kt`](../../core/config/src/main/java/com/wuxianggujun/tinaide/core/config/Prefs.kt)：`clangdFunctionArgPlaceholders` 配置项

## 参考资料

- [LSP Specification - InsertTextFormat](https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#insertTextFormat)
- [VSCode Snippet Syntax](https://code.visualstudio.com/docs/editor/userdefinedsnippets#_snippet-syntax)
- [TextMate Snippet Syntax](https://macromates.com/manual/en/snippets)

## 更新日志

| 日期 | 版本 | 变更 |
|------|------|------|
| 2026-03-03 | 1.0 | 初始版本，记录当前清洗机制和未来规划 |
| 2026-03-07 | 1.1 | Phase 1 MVP 完成：基础解析器 + Tab 跳转 + Escape 退出 |
| 2026-03-08 | 2.0 | Phase 2 完成：Choice snippet、Shift+Tab 后退、偏移同步 |
| 2026-03-08 | 2.1 | Bug 修复：补全偏移同步覆盖所有编辑路径、占位符长度追踪、破坏性操作取消会话、转义处理修正；文档与实际代码对齐（移除过时的模式 0/1/2 描述） |
| 2026-08-13 | 2.2 | 更新 `LspEditorManager` 下沉到 `core:editor-lsp` 后的源码位置，并补充已拆出的 `LspCompletionMapping` 职责 |
