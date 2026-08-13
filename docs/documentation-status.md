# TinaIDE 文档状态与生命周期

> 最后人工核验：2026-08-13

本文用于说明仓库内文档的可信层级、维护边界和后续清理规则。遇到文档内容冲突时，先按这里的顺序判断，不要直接以历史设计稿或旧路线图作为当前实现依据。

## 可信层级

1. 当前代码与构建配置
   - `settings.gradle.kts`
   - `app/build.gradle.kts`
   - `build-logic/convention/**`
   - `core/**`、`feature/**`、`app/**` 当前源码
2. 当前事实源文档
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
3. 当前专题文档
   - `docs/plugins/**`
   - `docs/guides/**`
   - `docs/testing/**`
   - `docs/troubleshooting/**`
4. 设计与规划参考
   - `docs/design/**`
   - `docs/planning/**`
5. 历史记录
   - `CHANGELOG.md`
   - 明确标注为历史参考、阶段记录或迁移说明的文档
6. 外部源码文档
   - `external/**` 下第三方项目或子模块自带文档

## 当前分级结果

### 当前事实源

- 默认编译 / LSP：`native tina-toolchain + Android sysroot`，PRoot 只是可选 Linux 环境。
- 编辑器 LSP 编排：`LspEditorManager`、内建 CMake / Make 会话和语义 token 解码已位于 `core:editor-lsp`，不再位于 `app` 状态包。
- Linux distro manifest：启动和普通列表只读缓存或内置 asset；显式刷新可读取 Registry，按“新鲜缓存 → 远程多端点 → 过期缓存 → 内置 asset”回落，并支持下载镜像规则。
- Android SDK 口径：`minSdk=28`、`targetSdk=36`、`compileSdk=37`，以 `app/build.gradle.kts` 为准。
- RikkaHub：TinaIDE 主仓库不再维护自研 `feature:ai`；AI 聊天、模型、渠道、MCP 和 API Key 配置由内嵌 RikkaHub 维护。
- App 内帮助：中文正文位于 `feature/help/src/main/assets/help/*.md`，英文正文位于 `feature/help/src/main/assets/help/en/*.md`；英文缺失或加载失败时回落到中文。
- 远程 LSP：Android 客户端保留 WebSocket remote LSP 能力；当前仓库不内置 `tools/tina-lsp-proxy.py` 或 `tools/tina-lsp-proxy-kt` PC 代理实现。
- Release 构建：可能递增 `version.properties` 并备份 R8 mapping；mapping 文件仅由公开构建逻辑做本地归档。
- Registry：当前 Android 主干读取插件 `plugins/index.v3.json`、依赖包 `packages/index.v2.json` 与 `linux-distro/manifest.v1.json`；插件 `plugins/index.v2.json` 仅作为旧宿主兼容视图保留。Android 二进制依赖包按已安装 APK 的实际 ABI 选择 source，并在安装前校验 source 级大小与 checksum。
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
- 修改插件 Registry、toolchain assets、PRoot/Linux distro、Release/R8 行为后，同步检查本文的“当前事实源”。
- 新增设计稿时，在 `docs/design/README.md` 中标注状态：`当前实现说明`、`设计参考` 或 `历史参考`。
