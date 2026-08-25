# 设计文档索引

> 更新日期：2026-08-13

本目录存放 TinaIDE 仍有维护价值的设计、审计与实现说明。这里的文档不是单独的当前事实源；涉及当前实现、类名、构建链路或用户可见行为时，必须回到源码、测试和 [文档状态与生命周期](../documentation-status.md) 校对。

## 入口

- **主文档中心**：`../README.md`
- **文档状态与生命周期**：`../documentation-status.md`

## 当前约定

- 本目录只索引仍有维护价值的文档。
- 带有 Phase、Roadmap、预计工期、旧类名或迁移叙事的内容，应按“历史参考”阅读。
- 当前功能是否已经落地，以代码、测试、`CHANGELOG.md` 和当前事实源文档为准。
- 当前目录除 `README.md` 外，已只保留索引中列出的现行设计文档。

## 当前实现说明与审计参考

### 编辑器与语言服务

- [高亮链路审查报告](TinaEditor-Highlight-Pipeline-Review.md)
- [统一布局快照](unified-layout-snapshot.md)
- [LSP Snippet 占位符处理](LSP-Snippet-Placeholder-Handling.md)
- [compile_commands 与已安装包同步机制](CompileCommands-Package-Sync-Design.md)

### 运行时与系统

- [PRoot 功能分析](PRoot-Feature-Analysis.md)
- [PRoot 运行时重构说明](PRoot-Runtime-Refactor.md)

### UI 规范

- [UI 组件样式指南](UI-Components-Style-Guide.md)
- [TinaIDE 设计系统](TinaIDE-Design-System.md)

## 设计参考

### 编辑器与语言服务

当前不再索引一次性性能审计稿、旧主题 UI 方案和未落地的补全状态机方案。需要判断当前行为时，优先看 `core/editor-view`、`core/editor-lsp`、`feature/editor`、`app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/editor` 下的源码和测试。

## 相关文档

- [文档状态与生命周期](../documentation-status.md)
- [插件开发者指南](../plugins/README.md)
- [插件路线图](../plugins/Plugin-Roadmap.md)
- [Toolchain 构建与同步指南](../toolchain-build-guide.md)
