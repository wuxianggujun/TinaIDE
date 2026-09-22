# 文件预览指南

> 最后人工核验：2026-09-09

TinaIDE 的文件打开逻辑由编辑器容器统一分发：普通文本、源码、Markdown 与 JSON 默认进入代码编辑器；大文本、图片与二进制文件会进入只读查看器，避免把不适合编辑的内容直接加载到代码编辑器。

## 如何打开

1. 在左侧文件树中点击文件。
2. TinaIDE 会根据文件类型自动选择合适的预览方式：
   - 可编辑文本：进入编辑器
   - 可预览格式：进入对应预览器

如果你希望交给系统应用处理，可以在文件树的上下文菜单中选择“用其他应用打开/分享”。

## 支持的查看类型

### 大文本

- 用途：分段查看超大文本文件，减少一次性加载造成的卡顿。
- 常见扩展名：任意文本文件，按文件大小自动判定；当前阈值为 10 MB，由 `EditorTabManager` 中的 `LARGE_TEXT_THRESHOLD_BYTES` 决定。
- 该查看器只读，但提供“以编辑器打开”和“以 Hex 打开”两个回退入口。
- 相关实现：`feature/viewer/src/main/java/.../ui/compose/viewer/LargeTextViewerScreen.kt`

### 图片

- 用途：预览项目内图片资源。
- 常见扩展名：`.png` / `.jpg` / `.jpeg` / `.gif` / `.webp` / `.bmp` / `.svg` / `.ico`，以 `FileTypeUtils.IMAGE_EXTENSIONS` 为准。
- 相关实现：`feature/viewer/src/main/java/.../ui/compose/viewer/ImagePreviewScreen.kt`

### Hex（二进制）

- 用途：查看二进制文件的十六进制内容。
- 常见扩展名：任意（通常用于 `.so` / `.bin` 等）
- 相关实现：`feature/viewer/src/main/java/.../ui/compose/viewer/HexViewerScreen.kt`
- 详细设计：[Hex Viewer 设计说明](Hex-Viewer-Design.md)

## 注意事项

- Markdown 与 JSON 不再维护独立文件查看器页面；它们按可编辑文本进入代码编辑器。`ContentType.JSON` 仍保留为独立标签类型，但渲染走同一个代码编辑器页面。
- `core/designsystem/src/main/java/.../ui/compose/components/MarkdownViewer.kt` 是复用型 Markdown 渲染组件，用于帮助页、教程正文、版本更新弹窗和编辑器 Hover 等只读内容，不是文件树入口。
- 二进制文件默认只适合“查看”，不建议直接编辑。
