# TinaEditor 高亮链路审查报告（2026-03-28）

> 最后人工核验：2026-09-09
>
> 本文原稿是 2026-03-28 的一次性审查。2026-08 之后语法高亮改成了 `IncrementalTreeSitterHighlightState`
> 增量解析 + 逐行 segment 缓存 + 视口优先 bulk prewarm，渲染侧新增 `EditorLineRenderPlanCache` 与
> `EditorVisualLineIndex`（Fenwick）。本次核验已按当前源码更新第 3、4、6 节的类名与机制描述，
> 结论章（第 2 节）保留原审查口径作为历史记录。

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

语法高亮器由 tab 级运行时缓存创建并注入，不再在 `TinaCodeEditorPage` 里直接 new：

1. `EditorCodeRuntimeCache.getOrCreateSyntaxHighlighter(tab)` 调用 `TreeSitterHighlighter.create(context, tab.file)`
2. `CodeEditorRuntime.installSyntaxHighlighter(...)` 写入 `editorState.highlighter`
3. runtime 移除或释放时 `highlighter.dispose()` 释放 native 资源

关键位置：
- `app/src/main/java/com/wuxianggujun/tinaide/ui/compose/state/editor/EditorCodeRuntimeCache.kt`
- `app/src/main/java/com/wuxianggujun/tinaide/ui/compose/state/editor/CodeEditorRuntime.kt`
- `app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/editor/TinaCodeEditorPage.kt`

### 3.2 渲染入口

`EditorRenderer.render()`（实现 `EditorRenderEngine`）统一编排绘制顺序：

1. 当前行背景
2. 词出现高亮（`WordOccurrenceHighlightRenderer`）
3. 括号对参考线（`BracketPairGuideRenderer`）
4. 选区背景
5. 文本（含语法/语义/彩虹括号颜色合成）
6. Inlay hint
7. 空白字符可视化
8. 匹配括号高亮
9. 诊断波浪线
10. 选区手柄

关键位置：
- `core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorRenderer.kt`
- `core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorRenderEngine.kt`

### 3.3 文本高亮核心（TextRenderer）

`TextRenderer.drawText()` 负责可见区颜色分段和实际文字绘制：

- 先解析语法高亮 segment（Tree-sitter，`resolveDrawHighlightSegmentsForVisibleWindow`）
- 再解析语义高亮 segment（LSP，`resolveVisibleSemanticSegments`）
- 再与彩虹括号 overlay 合并
- 用 `TextRenderPlanner.Workspace.buildRuns()` 输出最终颜色 runs，并经 `EditorLineRenderPlanCache` 缓存逐行 render plan 后绘制

关键位置：
- `core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/TextRenderer.kt`
- `core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorLineRenderPlanCache.kt`

### 3.4 语义高亮数据来源

LSP 侧请求 full/range semantic tokens 并写入 `editorState.semanticTokensByLine`，供 `TextRenderer` 消费。

关键位置：
- `core/editor-lsp/src/main/java/com/wuxianggujun/tinaide/core/editorlsp/LspEditorManager.kt`
- `app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/editor/TinaCodeEditorPage.kt`

---

## 4. 分层实现要点

## 4.1 语法高亮（Tree-sitter）

`TreeSitterHighlighter` 只做生命周期与 dispose 门禁，真正的解析与缓存在 `IncrementalTreeSitterHighlightState`：

- 维护 parser/query/queryCursor，以及 worker 侧 `workerTree` 与渲染侧 `RenderSnapshot`
- 文本变化走增量编辑：`applyTextChange` 计算脏行范围，在名为 `TreeSitterHighlightWorker` 的守护线程（单线程）上增量重解析，不整份重建
- 逐行缓存 `HighlightLineSegment`（`RenderSnapshot.lineCache`），渲染侧 `getLineSegments(line)` 只读缓存；miss 时排队后台补算并通过 `setOnStateUpdated` 回调通知重绘
- `setViewportHint(firstVisibleLine)` 让 bulk prewarm（`TreeSitterPrewarmPlan.ranges`）以视口为中心螺旋扩展，保证可见区最先着色
- `openDocumentBlocking` 用于首帧：返回时高亮快照与 prewarm 已就绪，避免首帧闪默认色
- 查询仍限定字节范围（`cursor.setByteRange(start shl 1, end shl 1)`）
- 输出 `HighlightSpan(start, end, type, priority)`
- 当前 binding 的 `TSParser.parseString()` 明确将输入转换为 `UTF-16 string`，因此 `startByte/endByte` 与 Kotlin UTF-16 code unit 可通过 `<<1 / >>1` 对齐，不构成代理对错位问题

关键位置：
- `core/tree-sitter/src/main/java/com/wuxianggujun/tinaide/core/treesitter/TreeSitterHighlighter.kt`
- `core/tree-sitter/src/main/java/com/wuxianggujun/tinaide/core/treesitter/IncrementalTreeSitterHighlightState.kt`
- `core/tree-sitter/src/main/java/com/wuxianggujun/tinaide/core/treesitter/TreeSitterPrewarmPlan.kt`
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

**写入与合并（当前实现，`TinaCodeEditorPageSupport.applySemanticTokens`）**：
- 未指定可见区间（全量结果）走 `EditorState.replaceSemanticTokens(...)`，整份替换
- 指定 `requestedVisibleLines`（range 结果）走 `EditorState.replaceSemanticTokensInLines(...)`，只覆盖请求过的行，区间外已有颜色保留（防止 LSP 暂时返回空时清空可见区颜色）
- `EditorState.mergeSemanticTokens(...)` 仍保留在 API 上，但当前生产路径不再使用，只有单元测试覆盖
- `EditorState` 维护显式 `semanticTokensVersion`，供渲染缓存使用

**渲染侧（`TextRenderer.resolveVisibleSemanticSegments`）**：
- 缓存 key：`version + windowLines + semanticTokensVersion`
- 不再依赖 `semanticTokensByLine` 的对象引用，避免 range 合并后重复 miss

关键位置：
- `core/editor-lsp/src/main/java/com/wuxianggujun/tinaide/core/editorlsp/LspSemanticTokenDecoder.kt`
- `LspEditorManager.requestSemanticTokens(...)`
- `app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/editor/TinaCodeEditorPageSupport.kt` 的 `applySemanticTokens(...)`
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

渲染侧（`TextRenderer`）：

- 行文本缓存：`lineCache`
- 可见窗口缓存：`visibleHighlightCacheKey / visibleSemanticCacheKey`，窗口两侧各留 `HIGHLIGHT_CACHE_MARGIN_LINES = 32` 行余量
- 语法高亮缓存 key 含 `state.highlightVersion`，语义缓存 key 含 `state.semanticTokensVersion`
- 复用同一个结果 HashMap，失效时只清 key 不动 map 内容，避免非主线程失效与主线程 paint 迭代冲突
- 逐行 render plan 缓存：`EditorLineRenderPlanCache`

高亮器侧（`IncrementalTreeSitterHighlightState`）：

- 单线程 worker：线程名 `TreeSitterHighlightWorker`
- 逐行 segment 缓存 `RenderSnapshot.lineCache`（按行数上限裁剪）
- 增量解析门禁：`revision` + `sessionId` + `PendingParseRequest / ParseRequest`，过期请求直接丢弃
- 视口优先 bulk prewarm：`viewportHintLine` + `TreeSitterPrewarmPlan`
- 文本改动后局部缓存失效：`applyTextChange(...)` 计算 `DirtyLineRange` 并只失效受影响行

### 6.2 关键风险点

#### 已修复：异步窗口回写与当前窗口不一致

结果回写不再只依赖 `textBuffer.version`。当前由 `IncrementalTreeSitterHighlightState` 用
`revision` 与 `sessionId` 双重校验：`parseRequest` / `applyResult` / prewarm 回调都会先比对期望的
revision 与 session，过期结果直接丢弃，不写回 `lineCache`。渲染侧另有 `highlightVersion` 作为缓存 key，
高亮状态更新后可见窗口缓存自然失效。

> 原稿描述的 `runningHighlightRequest` / `queuedHighlightRequest` / `request.covers(...)`
> 已随本次重构删除，不再存在于代码里。

#### 已修复：同步回退路径主线程压力

当前 `TextRenderer` 的 `resolveDrawHighlightSegmentsForVisibleWindow()` 只调用 `highlighter.getLineSegments(line)`
读缓存，cache miss 由高亮器自己排队到 worker 补算，主线程不做同步解析。

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

- 装配：`EditorCodeRuntimeCache.kt` / `CodeEditorRuntime.kt` / `TinaCodeEditorPage.kt`
- 渲染编排：`EditorRenderer.kt`（接口 `EditorRenderEngine.kt`）
- 文本与高亮合成：`TextRenderer.kt`、`EditorLineRenderPlanCache.kt`
- 语法高亮：`TreeSitterHighlighter.kt`、`IncrementalTreeSitterHighlightState.kt`
- 语义 token 拉取：`LspEditorManager.kt`；写入：`TinaCodeEditorPageSupport.kt`
- 视觉行索引：`EditorVisualLineIndex.kt`（Fenwick）、`EditorVisualLineMapper.kt`
- 选区与当前行：`SelectionRenderer.kt`
- 诊断波浪线：`DiagnosticRenderer.kt`

（以上文件均位于当前仓库对应模块路径）
