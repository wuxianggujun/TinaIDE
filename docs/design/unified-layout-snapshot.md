# 统一布局：字符偏移统一模型（charOffset Unified Layout）

> 版本：v2.1 | 日期：2026-03-06
> 状态：历史参考（设计提案，仅部分落地）
> 最后人工核验：2026-09-09

**落地情况（2026-09-09 回源码核对）**

已落地的部分：

- charOffset 统一坐标：`EditorState.cursorOffset` / `selectionRange: OffsetRange?` 已是内部唯一坐标（第 3 节）。
- `OffsetRange` 已存在（`core/editor-view/.../OffsetRange.kt`），旧 `Selection` 数据类已删除。
- `wrapSegmentCount()` 已删除，分段统一走 `EditorWordWrapLayoutCache`（第 6 节）。
- `Position` 仅保留在 LSP 边界与状态栏派生显示：`EditorState.cursorPosition` 是由 offset 派生的只读属性。

**未落地**的部分（第 4、5、8.2 节）：

- `EditorFrameLayout`、`VisualLineLayout`、`HitZone`、`OffsetViewport` 这几个类**不存在**。
- `EditorRenderer.render()` 仍是原文的 "Before" 签名 `render(drawScope, state, textPaint, lineNumberPaint)`。
- 每帧共享状态由可复用的 `EditorRenderFrameContext` 承载（不是不可变快照）；命中区域由
  `EditorRenderer.hitZones()` 返回 `EditorHitZones`；视觉行映射走 `EditorVisualLineMapper` +
  `EditorVisualLineIndex`（Fenwick 索引）；逐行 render plan 走 `EditorLineRenderPlanCache`。

阅读第 4、5、8.2 节时请当作未采纳的方案，不要据此判断当前渲染架构。

## 0. 设计宣言

**charOffset 是编辑器的唯一坐标语言。**

所有位置表达——光标、选区、命中测试、缩放锚点、诊断标注、补全触发点——
统一使用 `charOffset: Int`（文档中从 0 开始的偏移量）。

`Position(line, column)` **不再出现在编辑器内部**。
仅在 LSP 协议边界处做一次转换（`textBuffer.offsetToPosition` / `positionToOffset`）。

### charOffset 单位定义（不可变约定）

> **charOffset 的单位是 UTF-16 code unit（= Kotlin/Java `String` 的 index）。**

| 决策 | 说明 |
|---|---|
| 单位 | UTF-16 code unit（`Char`），不是 Unicode scalar，不是 grapheme cluster |
| 等价于 | `String.length`、`String[i]`、`String.substring(start, end)` 的 index |
| 理由 1 | `TextBuffer.insert(offset, text)` / `delete(start, end)` / `substring(start, end)` 已经全部使用 `String` index 语义——charOffset 与 TextBuffer 零转换 |
| 理由 2 | LSP 3.17 的 `positionEncoding` 默认 `utf-16`——大多数 language server（clangd、rust-analyzer、typescript-language-server）的 `character` 字段就是 UTF-16 code unit offset。charOffset 与 LSP 边界转换几乎零成本（仅需 `lineStartOffset + character`） |
| 理由 3 | Rope（`RopeTextBuffer`）内部使用 `CharArray` 存储——offset 直接对应 chunk 内的数组下标，无需额外编码转换 |

**Surrogate pair 处理规则**（emoji、CJK 扩展 B 等 BMP 外字符）：

- 一个 BMP 外字符（如 `😀`）占 **2 个 charOffset**（high surrogate + low surrogate）
- 光标移动（`moveLeft` / `moveRight`）、删除（`backspace` / `deleteForward`）必须检测 surrogate pair，跳过半个 surrogate：
  ```kotlin
  fun moveRight() {
      val step = if (Character.isHighSurrogate(textBuffer.charAt(cursorOffset))) 2 else 1
      moveCursorTo(cursorOffset + step)
  }
  ```
- 命中测试（`hitTest`）返回的 offset 可能落在 surrogate pair 中间——调用方在做光标设置前应对齐到 pair 起始位置
- 这是 **局部处理**（仅 4-5 个方法），不需要全局换坐标单位

**Grapheme cluster 暂不处理**：

- 组合字符序列（如 `👨‍👩‍👧‍👦` = 7 个 UTF-16 code unit）在当前编辑器中按 code unit 粒度移动光标（与 VS Code 行为一致）
- 未来如果需要 grapheme-level 光标移动，可在 `moveLeft/Right` 中引入 `java.text.BreakIterator`，不影响 charOffset 的定义

---

## 1. 现状问题

### 1.1 核心矛盾

```
TextBuffer 的原语是 offset：
  insert(offset, text)
  delete(start, end)
  substring(start, end)

但 EditorState 的 API 全用 Position(line, column)：
  cursorPosition: Position
  selection: Selection(Position, Position)
  moveCursorTo(Position)
  selectRange(Position, Position)
  selectWord(line, column)
```

导致每次操作都在做无意义的 `Position ↔ offset` 转换：

| 操作 | 当前路径 | 转换次数 |
|---|---|---|
| `moveLeft()` | Position → offset → offset-1 → Position | 2 次 |
| `moveRight()` | Position → offset → offset+1 → Position | 2 次 |
| `selectedText()` | 2×Position → 2×offset → substring | 2 次 |
| `onTap(viewportXY)` | viewport → visualLine → docLine → column → Position | 4 层映射 |
| `replaceSelection()` | 2×Position → 2×offset → delete+insert | 2 次 |
| Tree-sitter highlight | 返回 offset range → 转 Position → 切列渲染 | 2 次 |

### 1.2 三套独立布局缓存

| 缓存 | 坐标语言 | 失效机制 |
|---|---|---|
| `VisualLineMap`（EditorState 内） | visualLine → docLine | textVersion + foldDataVersion + wrapColumns + tabSize |
| `EditorWordWrapLayoutCache` | docLine → `WrapLayout(starts[])` | signature(wrapColumns, tabSize) + textVersion |
| `EditorLineLayoutCache` | docLine → `PrefixLayout(prefix[])` | paintSignature(textSize, typeface) + textVersion |

问题：
1. **分段规则分裂（P0）**：`EditorState.wrapSegmentCount()`（1632行）和 `EditorWordWrapLayoutCache.buildStarts()`（136行）是两份独立实现
2. **跨缓存失效不同步（P1）**：三套版本号各自维护
3. **消费者重复查询（P1）**：同一帧 6 个消费者各自查 HashMap，~200-400 次/帧

---

## 2. 设计目标

1. **charOffset 全面替代 Position**：cursorOffset、selectionRange、hitTest 结果全用 `Int`
2. **单一布局快照**：`EditorFrameLayout` 每帧构建一次，所有消费者共享
3. **消除分段规则分裂**：删除 `wrapSegmentCount()`，统一走 `EditorWordWrapLayoutCache`
4. **Position 仅存在于 LSP 边界**：`LspEditorManager` 等外部模块在请求/响应入口处做一次 `offset ↔ Position` 转换

---

## 3. charOffset 全量设计

### 3.1 EditorState 坐标全量替换

```kotlin
@Stable
class EditorState(
    override val textBuffer: TextBuffer,
    ...
) : EditorStateSnapshot, EditorEditOperations {

    // ===== 核心状态：全部 charOffset =====

    /** 光标位置（charOffset） */
    var cursorOffset by mutableStateOf(0)

    /** 选区（charOffset 范围） */
    var selectionRange by mutableStateOf<OffsetRange?>(null)

    // ===== 派生：仅用于状态栏显示 =====

    /** 光标所在行号（0-based） */
    val cursorLine: Int get() = textBuffer.offsetToLine(cursorOffset)

    /** 光标所在列号（0-based） */
    val cursorColumn: Int get() = textBuffer.offsetToPosition(cursorOffset).column

    // ===== 操作 API =====

    fun moveCursorTo(offset: Int, clearSelection: Boolean = true) { ... }

    fun moveLeft() {
        if (cursorOffset > 0) moveCursorTo(cursorOffset - 1)
    }

    fun moveRight() {
        if (cursorOffset < textBuffer.length) moveCursorTo(cursorOffset + 1)
    }

    fun moveUp() {
        val pos = textBuffer.offsetToPosition(cursorOffset)
        val targetLine = (pos.line - 1).coerceAtLeast(0)
        val targetCol = pos.column.coerceAtMost(textBuffer.getLine(targetLine).length)
        moveCursorTo(textBuffer.positionToOffset(targetLine, targetCol))
    }

    fun moveDown() {
        val pos = textBuffer.offsetToPosition(cursorOffset)
        val targetLine = (pos.line + 1).coerceAtMost(textBuffer.lineCount - 1)
        val targetCol = pos.column.coerceAtMost(textBuffer.getLine(targetLine).length)
        moveCursorTo(textBuffer.positionToOffset(targetLine, targetCol))
    }

    fun selectRange(startOffset: Int, endOffset: Int) { ... }

    fun selectWordAt(offset: Int): Boolean { ... }

    fun selectedText(): String? {
        val range = selectionRange ?: return null
        if (range.isEmpty) return null
        return textBuffer.substring(range.start, range.end)
    }

    fun gotoLine(line: Int, column: Int = 0) {
        moveCursorTo(textBuffer.positionToOffset(line, column))
    }
}
```

### 3.2 OffsetRange 替代 Selection

```kotlin
/**
 * 基于 charOffset 的选区范围。
 *
 * anchor = 选区固定端（长按/拖拽起始点）
 * caret  = 选区活动端（= cursorOffset）
 */
data class OffsetRange(
    val anchor: Int,
    val caret: Int
) {
    val start: Int get() = minOf(anchor, caret)
    val end: Int get() = maxOf(anchor, caret)
    val isEmpty: Boolean get() = anchor == caret
}
```

### 3.3 EditorStateSnapshot / EditorEditOperations 接口

```kotlin
interface EditorStateSnapshot {
    val textBuffer: TextBuffer
    var cursorOffset: Int
    var selectionRange: OffsetRange?
    fun moveCursorTo(offset: Int, clearSelection: Boolean = true)
    fun selectRange(startOffset: Int, endOffset: Int)
}

interface EditorEditOperations {
    fun insert(text: String)
    fun backspace()
    fun deleteForward()
    fun replaceSelection(replacement: String): Boolean
    fun replaceRange(startOffset: Int, endOffset: Int, replacement: String): Boolean
    fun undo(): Boolean
    fun redo(): Boolean
}
```

### 3.4 EditorEvent

```kotlin
sealed class EditorEvent {
    data class CursorMoved(val oldOffset: Int, val newOffset: Int) : EditorEvent()
    data class SelectionChanged(val range: OffsetRange?) : EditorEvent()
    data class ScrollChanged(val scrollX: Float, val scrollY: Float) : EditorEvent()
    data class TextChanged(val change: TextChange) : EditorEvent()
    data class FocusChanged(val focused: Boolean) : EditorEvent()
}
```

---

## 4. EditorFrameLayout（统一布局快照）

### 4.1 核心结构

```kotlin
/**
 * 一帧的完整布局快照。
 *
 * 在 Canvas draw block 开头构建，整帧内不可变。
 * 渲染、命中测试、选区、光标、缩放锚点全部从此快照查询。
 */
internal class EditorFrameLayout(
    val textVersion: Long,
    val documentLength: Int,
    val docLineCount: Int,
    val visualLineCount: Int,
    val lineHeightPx: Float,
    val textStartX: Float,
    val scrollY: Float,
    val scrollX: Float,
    val visibleRange: IntRange,
    /** 可见行布局（索引 = visualLine - visibleRange.first） */
    val lines: Array<VisualLineLayout>
) {
    fun layoutFor(visualLine: Int): VisualLineLayout? {
        val i = visualLine - visibleRange.first
        return lines.getOrNull(i)
    }

    // ========== 统一命中测试 ==========

    /** viewport 坐标 → charOffset */
    fun hitTest(viewportX: Float, viewportY: Float): Int {
        val visualLine = ((viewportY + scrollY) / lineHeightPx).toInt()
            .coerceIn(0, (visualLineCount - 1).coerceAtLeast(0))
        val layout = layoutFor(visualLine) ?: return 0
        val contentX = (viewportX - textStartX + scrollX).coerceAtLeast(0f)
        val column = layout.xToColumn(contentX)
        return layout.lineStartOffset + column
    }

    /** viewport 坐标 → 命中区域 */
    fun hitZone(viewportX: Float, viewportY: Float, pinLineNumber: Boolean): HitZone {
        val visualLine = ((viewportY + scrollY) / lineHeightPx).toInt()
            .coerceIn(0, (visualLineCount - 1).coerceAtLeast(0))
        val layout = layoutFor(visualLine)
        val docLine = layout?.docLine ?: 0
        val gutterX = if (pinLineNumber) viewportX else viewportX + scrollX
        // hitZone 边界由构建时的 lineNumberEndX / gutterEndX / textStartX 决定
        return when {
            gutterX < lineNumberEndX -> HitZone.LineNumber(docLine)
            gutterX < gutterEndX -> HitZone.Gutter(docLine)
            else -> {
                val offset = hitTest(viewportX, viewportY)
                HitZone.Text(offset)
            }
        }
    }

    /** charOffset → 视口坐标 (x, y)，null = 不在可见范围 */
    fun offsetToViewport(charOffset: Int): OffsetViewport? {
        for (layout in lines) {
            val col = charOffset - layout.lineStartOffset
            if (col < layout.startColumn || col > layout.endColumn) continue
            return OffsetViewport(
                x = layout.columnToViewportX(col, textStartX),
                yTop = layout.yTopInViewport,
                lineHeightPx = lineHeightPx
            )
        }
        return null
    }

    // 行号/gutter 区域宽度（构建时写入）
    internal var lineNumberEndX: Float = 0f
    internal var gutterEndX: Float = 0f
}

sealed class HitZone {
    data class LineNumber(val docLine: Int) : HitZone()
    data class Gutter(val docLine: Int) : HitZone()
    data class Text(val charOffset: Int) : HitZone()
}

data class OffsetViewport(
    val x: Float,
    val yTop: Float,
    val lineHeightPx: Float
) {
    val baselineY: Float get() = yTop + lineHeightPx * 0.78f
    val bottomY: Float get() = yTop + lineHeightPx
}
```

### 4.2 VisualLineLayout

```kotlin
internal class VisualLineLayout(
    val docLine: Int,
    /** 此文档行在文档中的起始 charOffset（= textBuffer.getLineStart(docLine)） */
    val lineStartOffset: Int,
    val segmentIndex: Int,
    val startColumn: Int,
    val endColumn: Int,
    val yTopInViewport: Float,
    val segmentStartXPx: Float,
    val prefixLayout: EditorLineLayoutCache.PrefixLayout,
    val lineText: String
) {
    fun columnToXPx(column: Int): Float =
        prefixLayout.prefix[column.coerceIn(0, prefixLayout.length)]

    fun columnToViewportX(column: Int, textStartX: Float): Float =
        textStartX + (columnToXPx(column) - segmentStartXPx)

    /** viewport 内容区 X → column（段内） */
    fun xToColumn(contentX: Float): Int {
        val absoluteX = segmentStartXPx + contentX
        val prefix = prefixLayout.prefix
        var low = startColumn
        var high = endColumn
        while (low < high) {
            val mid = (low + high) ushr 1
            if (prefix[mid] < absoluteX) low = mid + 1 else high = mid
        }
        val right = low.coerceIn(startColumn, endColumn)
        val left = (right - 1).coerceAtLeast(startColumn)
        if (right == left) return right
        return if ((absoluteX - prefix[left]) <= (prefix[right] - absoluteX)) left else right
    }

    fun baselineY(lineHeightPx: Float): Float = yTopInViewport + lineHeightPx * 0.78f
    val isContinuation: Boolean get() = segmentIndex > 0
}
```

---

## 5. 消费者改造（全量 charOffset）

### 5.1 CursorRenderer

```kotlin
// Before: 接收 state + textPaint + lineLayoutCache + lineTextProvider，内部做 5 次查询
// After:
fun drawCursor(drawScope: DrawScope, state: EditorState, layout: EditorFrameLayout) {
    if (!state.isFocused || !state.cursorBlinkVisible) return
    val vp = layout.offsetToViewport(state.cursorOffset) ?: return
    drawScope.drawLine(
        color = state.colorScheme.cursor,
        start = Offset(vp.x, vp.yTop + 2f),
        end = Offset(vp.x, vp.bottomY - 2f),
        strokeWidth = 2f
    )
}
```

### 5.2 SelectionRenderer

```kotlin
fun drawSelection(drawScope: DrawScope, state: EditorState, layout: EditorFrameLayout) {
    val range = state.selectionRange ?: return
    if (range.isEmpty) return
    val startOffset = range.start
    val endOffset = range.end
    for (line in layout.lines) {
        // 计算此视觉行与选区的交集（用 charOffset）
        val lineStart = line.lineStartOffset + line.startColumn
        val lineEnd = line.lineStartOffset + line.endColumn
        val selStart = maxOf(startOffset, lineStart)
        val selEnd = minOf(endOffset, lineEnd)
        if (selEnd <= selStart) continue
        val startCol = selStart - line.lineStartOffset
        val endCol = selEnd - line.lineStartOffset
        val x = line.columnToViewportX(startCol, layout.textStartX)
        val width = (line.columnToXPx(endCol) - line.columnToXPx(startCol)).coerceAtLeast(0f)
        drawScope.drawRect(
            color = state.colorScheme.selectionBackground,
            topLeft = Offset(x, line.yTopInViewport),
            size = Size(width, layout.lineHeightPx)
        )
    }
}
```

### 5.3 EditorGestureCoordinator.onTap

```kotlin
// Before: syncPaintForHitTest + hitZones + visualLine + docLine + column + Position → 20 行
// After:
fun onTap(position: Offset, layout: EditorFrameLayout) {
    if (shouldBlockBasicGestures()) return
    onContextMenuVisibleChange(false)
    when (val zone = layout.hitZone(position.x, position.y, state.pinLineNumber)) {
        is HitZone.LineNumber -> state.onLineNumberTap?.invoke(zone.docLine)
        is HitZone.Gutter -> state.dispatchGutterFoldToggle(zone.docLine)
        is HitZone.Text -> {
            state.moveCursorTo(zone.charOffset)
            interactionController.requestEditorFocusAndKeyboard()
            interactionController.syncSelectionToIme()
        }
    }
}
```

### 5.4 缩放锚点

```kotlin
// Before: 45 行跨 3 个缓存 + Paint 状态同步
// After:
fun computeScaleAnchor(layout: EditorFrameLayout, focusX: Float, focusY: Float): ScaleAnchor {
    val offset = layout.hitTest(focusX, focusY)
    val vp = layout.offsetToViewport(offset)
    val yRatio = if (vp != null) {
        ((focusY - vp.yTop) / layout.lineHeightPx).coerceIn(0f, 1f)
    } else 0.5f
    return ScaleAnchor(charOffset = offset, focusX = focusX, focusY = focusY, yRatio = yRatio)
}

data class ScaleAnchor(
    val charOffset: Int,
    val focusX: Float,
    val focusY: Float,
    val yRatio: Float
)
```

### 5.5 EditorRenderer 签名

```kotlin
// Before:
fun render(drawScope: DrawScope, state: EditorState, textPaint: Paint, lineNumberPaint: Paint)

// After:
fun render(drawScope: DrawScope, state: EditorState, layout: EditorFrameLayout)
// textPaint/lineNumberPaint 由 layout 构建时已经使用，渲染时从 layout.lines 直接取数据
// 对于需要 Paint 的 canvas.drawText 调用，Paint 作为 Renderer 内部持有的字段
```

---

## 6. wrapSegmentCount 统一

独立于 charOffset 迁移，可以最先做：

```kotlin
// EditorState.visualLineMap() 内
// Before:
val segments = wrapSegmentCount(lineText, wrapColumns, tabSize)  // 独立实现

// After:
val wrapLayout = wordWrapLayoutCache.getWrapLayout(
    line = docLine, lineText = lineText,
    textVersion = currentVersion,
    wrapColumns = wrapColumns, tabSize = tabSize
)
val segments = wrapLayout.segmentCount
```

删除 `wrapSegmentCount()` (~50 行)。

---

## 7. 新数据流

```
  TextBuffer (offset-native)
       │  insert(offset) / delete(start, end) / substring(start, end)
       │  getLineStart(line) / offsetToLine(offset)
       │
       ▼  不再做 Position ↔ offset 转换（内部全 offset）
  EditorState (offset-based)
       │  cursorOffset: Int
       │  selectionRange: OffsetRange?
       │  moveCursorTo(offset)
       │  selectRange(startOffset, endOffset)
       │
       ▼  Canvas draw block 开头，构建一次
  EditorFrameLayout (immutable snapshot)
       │  构建过程合并：
       │  ├── lineMap → fold 隐藏
       │  ├── visualLineMap → 视觉行映射
       │  ├── wordWrapLayoutCache.getWrapLayout → 分段（唯一实现）
       │  ├── lineLayoutCache.getPrefixLayout → 像素宽度
       │  └── textBuffer.getLineStart → lineStartOffset
       │
       │  对外：
       │  ├── hitTest(x, y) → charOffset
       │  ├── hitZone(x, y) → HitZone
       │  ├── offsetToViewport(offset) → (x, yTop, lineHeight)?
       │  └── lines[i] → VisualLineLayout
       │
       ▼  传给所有消费者
  ┌──────────────────┐
  │ TextRenderer      │ 遍历 layout.lines，直接读 lineText + prefix
  │ SelectionRenderer │ selectionRange.start/end 与 lineStartOffset 做交集
  │ CursorRenderer    │ offsetToViewport(cursorOffset) 画光标
  │ DiagnosticRenderer│ 诊断也存为 offset range，offsetToViewport 画波浪线
  │ GestureCoordinator│ hitTest/hitZone 做命中
  │ HandleDragCoord.  │ hitTest 做拖拽定位
  │ ScaleTransformC.  │ hitTest 做锚点
  └──────────────────┘

  LSP 边界（唯一做 Position 转换的地方）：
  ┌──────────────────┐
  │ LspEditorManager  │ cursorOffset → Position → LSP (line, character)
  │ LspSemanticTokenD.│ LSP (line, char) → offset → 存入 semanticTokens
  │ LspDiagnosticsBridge / TinaCodeEditorPage │ LSP (line, char) → editor diagnostics
  └──────────────────┘
```

---

## 8. 受影响模块清单

### 8.1 core:editor-view（全量改）

| 文件 | 改动 |
|---|---|
| `EditorState.kt` | `cursorPosition` → `cursorOffset`；`selection` → `selectionRange`；所有 Position API → offset |
| `EditorStateContracts.kt` | 接口改为 offset |
| `Selection.kt` | 删除，替换为 `OffsetRange` |
| `EditorEvent.kt` | CursorMoved/SelectionChanged 改为 offset |
| `EditorStateEditOperations.kt` | 内部 Position 调用 → offset |
| `EditorRenderer.kt` | render 签名加 `EditorFrameLayout` |
| `TextRenderer.kt` | 从 layout.lines 取数据 |
| `SelectionRenderer.kt` | 用 offsetToViewport / offset 范围交集 |
| `CursorRenderer.kt` | 用 offsetToViewport |
| `DiagnosticRenderer.kt` | 诊断改为 offset range |
| `SelectionHandleLayout.kt` | 用 offsetToViewport |
| `SelectionHandleDragCoordinator.kt` | 用 hitTest |
| `EditorGestureCoordinator.kt` | 用 hitTest/hitZone |
| `EditorScaleTransformCoordinator.kt` | 用 hitTest 做锚点 |
| `EditorCanvasLayer.kt` | 构建 EditorFrameLayout |
| `EditorInputConnection.kt` | 已经大部分用 offset |
| `EditorScrollGestureCoordinator.kt` | 不涉及 Position，无改动 |
| `EditorKeyboardShortcuts.kt` | moveCursorTo → offset 版 |
| `EditorOverlays.kt` | 补全位置改用 offsetToViewport |
| `TinaEditorSession.kt` | 适配新 API |
| `TinaEditorUiState.kt` | 无改动 |

### 8.2 core:editor-view 新增

| 文件 | 内容 |
|---|---|
| `OffsetRange.kt` | `OffsetRange(anchor, caret)` |
| `EditorFrameLayout.kt` | `EditorFrameLayout` + `VisualLineLayout` + `HitZone` + `OffsetViewport` |

### 8.3 app 层适配

| 文件 | 改动 |
|---|---|
| `TinaCodeEditorPage.kt` | `cursorPosition.line/column` → `cursorLine/cursorColumn` |
| `EditorContainerState.kt` | `CodeEditorCallback` Position API → offset |
| `LspEditorManager.kt` | 在 LSP 请求边界做 `offset ↔ Position` 转换 |

### 8.4 不需要改的

- `core:text-engine` — `TextBuffer` 接口不变（已经是 offset-native）
- `Position.kt` — 保留（LSP 边界还要用）
- `core:editor-lsp` — `CompletionProvider` 等接口视情况，如果参数是 Position 则改为 offset

---

## 9. 效果对比

| 指标 | 现状 | 目标 |
|---|---|---|
| 内部坐标 | `Position(line, column)` | `charOffset: Int` |
| Selection | `Selection(Position, Position)` + normalizedStart/End | `OffsetRange(anchor, caret)` + start/end |
| 命中测试 | 4-5 层映射 × 6 消费者 | `hitTest(x, y)` 一步 → offset |
| `moveLeft/Right` | Position→offset→offset±1→Position (2次转换) | `cursorOffset ± 1` (0次转换) |
| `selectedText()` | 2×Position→2×offset→substring (2次转换) | `substring(range.start, range.end)` (0次转换) |
| 缩放锚点 | 45行跨3缓存 | 5行 hitTest |
| 分段规则 | 两套实现 | 一套 |
| 每帧缓存查找 | ~200-400次 | ~30-60次 |
| Position↔offset 转换 | 每帧数十次 | 仅 LSP 边界 |
