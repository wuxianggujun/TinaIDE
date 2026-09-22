# 设计文档索引

> 更新日期：2026-09-09
> 最后人工核验：2026-09-09

本目录存放 TinaIDE 仍有维护价值的设计、审计与实现说明。这里的文档不是单独的当前事实源；涉及当前实现、类名、构建链路或用户可见行为时，必须回到源码、测试和 [文档状态与生命周期](../documentation-status.md) 校对。

## 入口

- **主文档中心**：`../README.md`
- **文档状态与生命周期**：`../documentation-status.md`

## 当前约定

- 本目录只索引仍有维护价值的文档。
- 带有 Phase、Roadmap、预计工期、旧类名或迁移叙事的内容，应按“历史参考”阅读。
- 当前功能是否已经落地，以代码、测试、`CHANGELOG.md` 和当前事实源文档为准。
- 当前目录除 `README.md` 外，已只保留索引中列出的现行设计文档。

## 当前实现说明

### 编辑器与语言服务

- [LSP Snippet 占位符处理](LSP-Snippet-Placeholder-Handling.md) — 当前实现说明
- [compile_commands 与已安装包同步机制](CompileCommands-Package-Sync-Design.md) — 当前实现说明

### 运行时与系统

- [PRoot 与自研 Linux 发行版运行时设计说明](PRoot-Feature-Analysis.md) — 当前实现说明（含 `:core:linux-desktop` / `:x11` 边界）

### UI 规范

- [UI 组件样式指南](UI-Components-Style-Guide.md) — 当前实现说明
- [TinaIDE 设计系统](TinaIDE-Design-System.md) — 当前实现说明

## 设计参考

- [高亮链路审查报告](TinaEditor-Highlight-Pipeline-Review.md) — 设计参考。原稿是 2026-03-28 的一次性审查；第 3、4、6 节已按 `IncrementalTreeSitterHighlightState` 增量高亮与 `EditorLineRenderPlanCache` 更新，第 2 节结论保留原审查口径。

## 历史参考

- [统一布局快照](unified-layout-snapshot.md) — 历史参考。`charOffset` 统一坐标体系已落地（见 `EditorState.kt`、`EditorGestureCoordinator.kt`）；视觉行段数当前由 `EditorVisualLineMapper` 的 `segmentCount` / `segmentCountForLine` 维护，不是本文设想的命名。`EditorFrameLayout` / `VisualLineLayout` / `HitZone` / `OffsetViewport` 方案**未采纳**（这四个类在源码中不存在），第 4、5、8.2 节不代表当前架构。
- [PRoot 运行时重构说明](PRoot-Runtime-Refactor.md) — 历史参考（迁移记录）。用于追溯已删除的 `ICompilerEnvironment`、`PRootCompiler`、`ToolchainManifest*`、`Symlink*` 等类；当前 PRoot 行为以 `PRoot-Feature-Analysis.md` 与源码为准。

需要判断编辑器当前行为时，优先看 `core/editor-view`、`core/tree-sitter`、`core/editor-lsp`、`feature/editor`、`app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/editor` 与 `app/src/main/java/com/wuxianggujun/tinaide/ui/compose/state/editor` 下的源码和测试。当前不再索引一次性性能审计稿、旧主题 UI 方案和未落地的补全状态机方案。

## 相关文档

- [文档状态与生命周期](../documentation-status.md)
- [插件开发者指南](../plugins/README.md)
- [插件路线图](../plugins/Plugin-Roadmap.md)
- [Toolchain 构建与同步指南](../toolchain-build-guide.md)
