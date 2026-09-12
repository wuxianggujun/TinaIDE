# TinaIDE 文档状态与生命周期

> 最后人工核验：2026-09-09

本文用于说明仓库内文档的可信层级、维护边界和后续清理规则。遇到文档内容冲突时，先按这里的顺序判断，不要直接以历史设计稿或旧路线图作为当前实现依据。

## 可信层级

1. 当前代码与构建配置
   - `settings.gradle.kts`
   - `app/build.gradle.kts`
   - `build-logic/convention/**`
   - `core/**`、`feature/**`、`app/**` 当前源码
2. 许可证与分发事实源
   - `LICENSE`（GPL-3.0 全文）
   - `COPYRIGHT.md`
   - `NOTICE.md`

   许可证口径以这三个文件为准，其他文档只做引用，不复制维护条款。
3. 当前事实源文档
   - `README.md`
   - `README_EN.md`
   - `docs/README.md`
   - `docs/快速开始.md`
   - `docs/开发指南.md`
   - `docs/架构概览.md`
   - `docs/模块功能说明.md`
   - `docs/documentation-status.md`
   - `docs/game-engine-plugin-sdl.md`
   - `docs/linux-distro-self-hosted-runtime.md`
   - `docs/registry/GitHub-Registry.md`
   - `docs/toolchain-build-guide.md`
   - `docs/proguard-rules-reference.md`
   - `docs/guides/MT-Data-Files-Provider.md`
   - `core/linux-desktop/README.md`（X11 桌面模块的当前实现说明）
4. 当前专题文档
   - `docs/plugins/**`
   - `docs/guides/**`
   - `docs/testing/**`
   - `docs/troubleshooting/**`
5. 设计与规划参考
   - `docs/design/**`
   - `docs/planning/**`
6. 历史记录
   - `CHANGELOG.md`
   - 明确标注为历史参考、阶段记录或迁移说明的文档
7. 对外申报材料
   - `docs/software-copyright/**`：软著申报材料，功能描述必须与当前实际功能一致；
     `private/` 属于保密信息，`generated/` 是生成产物，都不参与常规文档核验。
8. 外部源码文档
   - `external/**` 下第三方项目或子模块自带文档

## 当前分级结果

### 当前事实源

- 许可证：TinaIDE 整体以 **GPL-3.0-or-later** 分发（`0.18.29`，2026-09-03 变更）。根 `LICENSE` 是 FSF 官方 GPL-3.0 全文，`COPYRIGHT.md` 声明 SPDX 与分发要求，`NOTICE.md` 维护第三方组件清单。旧自定义许可证 "TinaIDE Open Source License Version 1.0" 已废弃，备份在 `docs/third-party-notices/TinaIDE-Custom-License-v1.0-superseded.txt`。变更起因是集成 termux-x11 的 X server（GPL-3.0）。
- 分发阻塞项：`external/rikkahub` 采用 AGPL-3.0 加非商业与 ≤10 用户附加限制，违反 GPL-3.0 第 7 条。**冲突解决前，包含 RikkaHub 的构建产物不得对外分发。** 文档不得把“可对外分发的完整 APK”当成当前事实。
- Linux 发行版：只支持 Ubuntu 24.04。Alpine 支持已在 `0.18.29` 整体移除，`AlpineMirrorManager`、Alpine 镜像设置 UI 和 `ConfigKeys.AlpineMirrorUrl` 都已删除；文档中的 Alpine 内容一律按过时处理，只有 `CHANGELOG.md` 允许保留历史记录。
- X11 图形桌面：`:core:linux-desktop` 与 vendored `external/termux-x11`（`:termux-x11-lorie`、`:termux-x11-shell-loader-stub`）已进主干，`libXlorie.so` 在 Windows 宿主构建通过，X server 与渲染 UI 都在 `:x11` 独立进程。**尚未在真机验证 XFCE 桌面**，文档不得写成已交付功能。
- 默认编译 / LSP：`native tina-toolchain + Android sysroot`，PRoot 只是可选 Linux 环境。
- 编辑器 LSP 编排：`LspEditorManager`、内建 CMake / Make 会话和语义 token 解码已位于 `core:editor-lsp`，不再位于 `app` 状态包。
- Linux distro manifest：启动和普通列表只读缓存或内置 asset；显式刷新可读取 Registry，按“新鲜缓存 → 远程多端点 → 过期缓存 → 内置 asset”回落，并支持下载镜像规则。
- Android SDK 口径：`minSdk=28`、`targetSdk=36`、`compileSdk=37`，以 `app/build.gradle.kts` 为准。注意这三个值只描述 `:app`；`core:*` / `feature:*` 等 library 模块由 `TinaVersions` 决定，其中 `COMPILE_SDK` 常量当前是 **36**，与 `:app` 的 37 不一致。引用编译期 SDK 时必须说明是哪一侧，只写一个数字会写错另一半。
- 模块清单只以 `settings.gradle.kts` 为准，本文与其他文档不维护副本。当前为 29 个 `core:*` 与 11 个 `feature:*`。注意 `feature/` 磁盘上还有 `license`、`login`、`membership` 三个目录，它们未注册进构建、git 也未跟踪，只是本地 `build/` 残留，不代表这些功能存在。
- 版本口径：当前 `versionName=0.18.29`、`versionCode=1830`，以 `version.properties` 为准。
- 进程边界：除主进程外还有 `:x11`（X server 与桌面渲染）、`:sdl`、`:sdl2`、`:gui`、`:crash`；初始化逻辑不能混用，见 `TinaApplication` 的多进程分流。
- RikkaHub：TinaIDE 主仓库不再维护自研 `feature:ai`；AI 聊天、模型、渠道、MCP 和 API Key 配置由内嵌 RikkaHub 维护。
- App 内帮助：中文正文位于 `feature/help/src/main/assets/help/*.md`，英文正文位于 `feature/help/src/main/assets/help/en/*.md`；英文缺失或加载失败时回落到中文。
- 远程 LSP：默认使用 WSS 且 Bearer Token 必需，WS 仅允许回环地址；Token 加密保存并排除备份。内置项目同步要求 `tina/syncProject`、`tina/syncProjectStart` 和每个 `tina/syncProjectChunk` 按 JSON-RPC `id` 返回 ACK。当前仓库不内置 PC 代理实现。
- Release 构建：可能递增 `version.properties` 并备份 R8 mapping；mapping 文件仅由公开构建逻辑做本地归档。
- Registry：当前 Android 主干读取插件 `plugins/index.v3.json`、依赖包 `packages/index.v2.json` 与内置 `linux-distro/manifest.v1.json`；插件 `plugins/index.v2.json` 仅作为旧宿主兼容视图保留。Registry 资源只接受 HTTPS；插件与 DOWNLOAD 依赖包必须提供有效 SHA-256，下载和解包都有硬性预算。远程 Linux distro manifest 在具备签名验证前保持禁用。
- MT 管理器访问：默认开启；只暴露 TinaIDE 自己的 `data`、`Android/data`、`Android/obb` 和 `user_de_data`，可在设置中关闭。

### 设计参考

`docs/design/**` 用于保存仍有维护价值的设计、审计和实现说明。它们不是单独的事实源；涉及当前实现时必须回到源码确认。

适合继续保留：

- 编辑器渲染、补全、snippet、主题、布局快照等设计说明。
- PRoot 与 Linux distro 的当前边界说明。
- UI 组件和设计系统规范。

需要按“历史参考”阅读：

- 带有 `Phase`、`Roadmap`、预计工期或阶段性审计口径的文档。
- 明确写着旧实现、旧类名、历史参考或迁移说明的文档。

### 规划参考

`docs/planning/**` 是路线图和追踪文档，适合判断方向，不适合直接判断功能是否已经落地。实现状态应以代码、测试和 `CHANGELOG.md` 为准。

### 维护者工具文档

- `docker/**`：只服务运行资产、PRoot、rsync、第三方 native 包等维护者构建流程；普通 App 构建不需要 Docker。
- `tools/**`：只服务本地开发辅助、i18n、Linux distro manifest、插件 starter、项目模板等脚本；不是运行时源码入口。

### 不纳入本轮清理

- `external/**`：子模块或第三方源码文档，保持上游边界，不做主仓库口径批量改写。
- `CHANGELOG.md`：历史版本记录允许保留旧实现描述，不能因为包含旧口径就删除。

## 人工核验日期规则

- “最后人工核验”表示维护者已经把文档中的关键事实与当前代码、构建配置或资源入口逐项对照，不等同于 Git 最后修改时间。
- 只有完成事实复核后才更新日期；仅修正错别字、排版或无关链接时不要机械刷新。
- 已发布版本的 Changelog 区块保持稳定，Tag 之后的开发变化先写入 `Unreleased`，发布时再归档到新版本。
- 自动检查负责发现本地链接、当前事实源中的仓库路径、已知模块迁移旧路径、帮助目录注册和关键版本口径漂移，不能替代人工判断模块边界和运行时行为。

## 删除和归档规则

文档删除属于高风险操作，必须满足以下条件才执行：

1. 已确认没有入口文档、代码注释、测试或脚本引用该文档。
2. 内容已被当前事实源文档完整覆盖，且没有历史追溯价值。
3. 已在变更说明中列出删除文件、影响范围和回滚方式。
4. 获得明确确认后再删除。

不满足以上条件时，应优先：

- 从索引中降级为“历史参考”。
- 在文档顶部补充状态说明。
- 新增替代文档链接。

## 后续审计建议

- 每次 Release 前检查根 README、`docs/快速开始.md`、`docs/开发指南.md`、`docs/架构概览.md` 是否仍和构建脚本一致。
- 文档改动至少运行 `py tools/checks/check_documentation.py`，并检查 `git diff` 与引用路径。
- 修改插件 Registry、toolchain assets、PRoot/Linux distro、X11 桌面、Release/R8 行为后，同步检查本文的“当前事实源”。
- 新增或升级第三方依赖后，同步检查 `NOTICE.md` 的组件清单与许可证兼容性结论。
- X11 桌面在真机验证通过后，需要同时更新本文、`docs/架构概览.md`、根 README 和 `core/linux-desktop/README.md` 里的“尚未验证”表述。
- 新增设计稿时，在 `docs/design/README.md` 中标注状态：`当前实现说明`、`设计参考` 或 `历史参考`。
