# TinaEditor 高亮链路审查报告（2026-03-28）

## 1. 背景与范围

本报告用于回答“当前整套高亮逻辑是否有问题”。审查范围覆盖：

- 语法高亮（Tree-sitter）
- 语义高亮（LSP Semantic Tokens）
- 选择区高亮 / 当前行高亮
- 诊断波浪线高亮
- 多层叠加顺序、缓存与并发策略

本报告基于当前代码实现做静态审查，不包含运行时基准测试数据。

---

## 2. 结论先行

当前实现整体架构是清晰且可维护的，核心路径可工作，且有明显的性能意识（可见区计算、缓存、异步任务、版本校验）。

基于对 `android-tree-sitter` 依赖源码的复核，以及本轮修复后的代码状态，当前**未发现可确认的高亮正确性 bug**。此前将 Tree-sitter 的 `<<1 / >>1` 视为“代理对错位问题”的判断，已被证伪：当前 binding 明确以 UTF-16 字符串作为输入，`byte offset` 与 Kotlin `String` 的 UTF-16 code unit 是对齐的。

各高亮层正确性对比：

| 高亮层 | offset 单位 | 与编辑器字符索引对齐？ | 问题级别 |
|---|---|---|---|
| Tree-sitter 语法高亮 | UTF-16 byte offset（`charIndex * 2`） | 与 Kotlin UTF-16 code unit 对齐 | 无 |
| LSP 语义高亮（解码层） | UTF-16 code unit（LSP 规范） | 完全一致，无问题 | 无 |
| LSP 语义高亮（缓存层） | `semanticTokensVersion` | 已修复引用变化导致的 cache miss | 无 |
| 选择区 / 当前行高亮 | 直接用 EditorState offset | 正确 | 无 |
| 诊断波浪线 | 直接用 diagnosticsByLine | 正确 | 无 |

本轮已修复的问题：

- **已修复**：语法高亮异步结果增加“运行中请求 / 排队请求 / 窗口覆盖”门禁，旧窗口结果不再回写覆盖新窗口。
- **已修复**：移除语法高亮 cache miss 时的同步回退路径，避免主线程额外高亮计算压力。
- **已修复**：语义高亮缓存从对象引用判断改为显式 `semanticTokensVersion`，解决 range 合并后每次 cache miss 的问题。
- **待补强（P2）**：缺少针对“快速窗口切换”和“UTF-16 列索引”边界的定向测试。

> 结论：整体设计成立，关键缓存与异步门禁问题已修复；后续重点应放在回归测试补强，而不是继续推翻现有高亮架构。

---

## 3. 端到端调用链（从装配到上屏）

### 3.1 装配层（页面）

在 `TinaCodeEditorPage` 中创建并注入 `TreeSitterHighlighter`：

1. `TreeSitterHighlighter.create(context, tab.file)` 创建语法高亮器
2. `editorState.highlighter = syntaxHighlighter` 注入状态
3. 页面销毁时 `highlighter.dispose()` 释放 native 资源

关键位置：
- `app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/editor/TinaCodeEditorPage.kt`

### 3.2 渲染入口

`EditorRenderer.render()` 统一编排绘制顺序：

1. 当前行背景
2. 词高亮/括号辅助层
3. 选区背景
4. 文本（含语法/语义/彩虹括号颜色合成）
5. 空白字符可视化
6. 匹配括号高亮
7. 诊断波浪线
8. 选区手柄

关键位置：
- `core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorRenderer.kt`

### 3.3 文本高亮核心（TextRenderer）

`TextRenderer.drawText()` 负责可见区颜色分段和实际文字绘制：

- 先解析语法高亮 segment（Tree-sitter）
- 再解析语义高亮 segment（LSP）
- 再与彩虹括号 overlay 合并
- 用 `TextRenderPlanner.buildRuns()` 输出最终颜色 runs 并逐段绘制

关键位置：
- `core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/TextRenderer.kt`

### 3.4 语义高亮数据来源

LSP 侧请求 full/range semantic tokens 并写入 `editorState.semanticTokensByLine`，供 `TextRenderer` 消费。

关键位置：
- `core/editor-lsp/src/main/java/com/wuxianggujun/tinaide/core/editorlsp/LspEditorManager.kt`
- `app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/editor/TinaCodeEditorPage.kt`

---

## 4. 分层实现要点

## 4.1 语法高亮（Tree-sitter）

- 维护 parser/query/queryCursor
- 文本变化时重建 parse tree（`ensureParsedTree`）
- 仅查询可见范围（`setByteRange`）
- 输出 `HighlightSpan(start, end, type)`
- 当前 binding 的 `TSParser.parseString()` 明确将输入转换为 `UTF-16 string`，因此 `startByte/endByte` 与 Kotlin UTF-16 code unit 可通过 `<<1 / >>1` 对齐，不构成代理对错位问题

关键位置：
- `core/tree-sitter/src/main/java/com/wuxianggujun/tinaide/core/treesitter/TreeSitterHighlighter.kt`
- `external/tina-android-tree-sitter/android-tree-sitter/src/main/java/com/itsaky/androidide/treesitter/TSParser.java`

## 4.2 语义高亮（Semantic Tokens）

**解码层（`LspSemanticTokenDecoder`）**：
- LSP 原始数据为 5 元组 `[deltaLine, deltaStart, length, tokenTypeIndex, modifierBits]`
- `startColumn` 和 `length` 均为 UTF-16 code unit，与 Kotlin String 索引天然对齐，**无 offset 换算问题**
- fallback 逻辑：tokenTypeIndex 越界时使用 `"variable"` 兜底
- 测试覆盖：`LspSemanticTokenDecoderTest` 已覆盖 delta 解析、modifier bits、越界兜底、尾部不完整 tuple

**请求策略（`LspEditorManager`）**：
- 首次打开：优先请求全量（`full`），填满缓存
- 滚动到新区域：请求可见区间（`range`）
- range 失败：fallback 全量
- 缓存命中条件：`documentVersion` 相同且 `cachedLines` 覆盖当前可见区

**写入与合并（`TinaCodeEditorPage.applySemanticTokens`）**：
- 全量结果直接替换 `semanticTokensByLine`
- range 结果按行合并，不覆盖已有区域（防止 LSP 暂时返回空时清空可见区颜色）

**写入与合并（当前实现）**：
- 全量结果走 `EditorState.replaceSemanticTokens(...)`
- range 结果走 `EditorState.mergeSemanticTokens(...)`
- `EditorState` 维护显式 `semanticTokensVersion`，供渲染缓存使用

**渲染侧（`TextRenderer.resolveVisibleSemanticSegments`）**：
- 缓存 key：`version + windowLines + semanticTokensVersion`
- 不再依赖 `semanticTokensByLine` 的对象引用，避免 range 合并后重复 miss

关键位置：
- `core/editor-lsp/src/main/java/com/wuxianggujun/tinaide/core/editorlsp/LspSemanticTokenDecoder.kt`
- `LspEditorManager.requestSemanticTokens(...)`
- `TinaCodeEditorPage.applySemanticTokens(...)`
- `TextRenderer.resolveVisibleSemanticSegments(...)`

## 4.3 选择/当前行高亮

- `drawCurrentLineHighlight()`：光标当前行背景
- `drawSelection()`：按可见视觉行计算矩形覆盖
- `drawSelectionHandles()`：绘制拖拽手柄

关键位置：
- `core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/SelectionRenderer.kt`

## 4.4 诊断高亮

- 逐行构建诊断片段
- 重叠区按 severity 取优先级（error > warning > info > hint）
- 以波浪线路径绘制到文本底部区域

关键位置：
- `core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/DiagnosticRenderer.kt`

---

## 5. 叠加优先级与覆盖关系

### 5.1 文本颜色优先级

`TextRenderPlanner` 的最终规则为：

- `semanticColor ?: syntaxColor ?: defaultColor`

即：**语义色 > 语法色 > 默认文本色**。

### 5.2 图层覆盖顺序（视觉）

- 选择区背景先画，文本后画
- 诊断波浪线在文本之后，保证可见
- 手柄最后画，保证可交互可见

整体顺序合理，避免了“文本被选择底色覆盖”的常见问题。

---

## 6. 缓存与并发策略审查

### 6.1 现有策略（优点）

- 行文本缓存：`lineCache`
- 文档快照缓存：`documentSnapshotText + version`
- 可见窗口缓存：`visibleHighlightCacheKey / visibleSemanticCacheKey`
- 异步高亮：单线程 `HighlightWorker`
- 异步请求门禁：`runningHighlightRequest / queuedHighlightRequest`
- 文本改动后局部缓存失效：`applyTextChange(...)`
- 语义 token 版本：`semanticTokensVersion`

### 6.2 关键风险点

#### 已修复：异步窗口回写与当前窗口不一致

当前实现已不再只依赖 `textBuffer.version` 回写，而是通过：

- `runningHighlightRequest`
- `queuedHighlightRequest`
- `request.covers(...)`

共同约束异步结果回写。旧窗口结果若已被更新窗口请求覆盖，将不会写回当前缓存。

#### 已修复：同步回退路径主线程压力

当前 `TextRenderer.drawText()` 在语法高亮 cache miss 时不再走同步高亮计算，而是直接返回当前缓存并调度异步 worker 计算。

#### 已修复：语义高亮缓存每次 miss（性能）

当前 `resolveVisibleSemanticSegments()` 已改为以 `semanticTokensVersion` 作为缓存 key 之一，不再依赖 `semanticTokensByLine` 的对象引用。

#### P2：测试覆盖缺口

当前 tree-sitter 测试主要覆盖 capture 分类，不覆盖：

- 快速滚动与异步任务回写一致性

`LspSemanticTokenDecoderTest` 已覆盖解码主路径，并已补充含代理对字符的 UTF-16 列偏移验证；但渲染侧仍缺少快速窗口切换的一致性测试。

---

## 7. 最小改动建议（不做过度设计）

### 建议 A（优先）：补窗口切换一致性测试

目标：验证异步高亮在快速滚动、多次视口切换时不会回写旧窗口结果。

### 建议 B（可选）：补测试

1. 含代理对字符样本（emoji）高亮边界测试（Tree-sitter 层）
2. 快速窗口切换下缓存与回写一致性测试
3. LSP 语义解码层：含代理对字符时列偏移验证

### 建议 C（可选）：增加滚动压测回归

目标：在大文件、高频滚动、semantic tokens 开启场景下观察高亮刷新延迟与闪动情况。

---

## 8. 回归验证建议

1. 普通 C/C++/Kotlin 文件：语法色、语义色、折叠、诊断显示正常。
2. 含 emoji/代理对字符的样本文件：确认 Tree-sitter 高亮边界正常，LSP 语义列偏移正常。
3. 快速滚动 + 连续输入：观察是否有可见闪烁、旧窗口回写或错色。
4. 打开/关闭 semantic tokens：确认优先级稳定（语义色覆盖语法色），且滚动时无明显重复分段开销。
5. 多诊断重叠区域：确认 severity 优先级和波浪线连续性。

---

## 9. 附：关键代码定位

- 装配：`TinaCodeEditorPage.kt`
- 渲染编排：`EditorRenderer.kt`
- 文本与高亮合成：`TextRenderer.kt`
- 语法高亮：`TreeSitterHighlighter.kt`
- 语义 token 拉取：`LspEditorManager.kt`
- 选区与当前行：`SelectionRenderer.kt`
- 诊断波浪线：`DiagnosticRenderer.kt`

（以上文件均位于当前仓库对应模块路径）
