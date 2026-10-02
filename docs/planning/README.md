# 规划文档索引

> 更新日期：2026-09-09

本目录存放 TinaIDE 的规划与跟踪文档（Roadmap）。规划文档用于判断方向和待办优先级，不等于当前实现事实源；功能是否已经落地，应以代码、测试、`CHANGELOG.md` 和 [文档状态与生命周期](../documentation-status.md) 中列出的当前事实源为准。

## Roadmap

- [TinaIDE（App/IDE）功能路线图](Feature-Roadmap.md)
- [TinaIDE 架构优化任务清单](Architecture-Optimization-Tasks.md)

## 说明

- `Feature-Roadmap.md`：聚焦 App/IDE 侧功能规划，并已同步当前插件、开源版账号移除、构建口径，以及 0.18.29 的 GPL-3.0 许可证变更与 X11 桌面状态。状态：**推进中**。
- `Architecture-Optimization-Tasks.md`：聚焦主工作区、编辑器、文件操作链路、LSP 连接链路、模块装配与测试维护性的后续优化任务。状态：**推进中**；P0-1、P1-1 与 P2 的检查自动化已落地（`tools/checks/check_all.py`、`tools/checks/check_direct_file_operations.py`），P0-2 仍有 `RemoteLspConnectionProvider` / `PluginLspConnectionProvider` 的接口级同步探测未改造，迭代四的职责拆分仍在逐步推进。
- Roadmap 中的阶段、预估工期和候选能力只代表规划口径，不能直接作为当前行为说明。
- 标为「已完成」的条目仍需对照 `CHANGELOG.md` 与源码核实；标注「真机未验证」的能力（如 X11 桌面）不得当作已交付功能。
