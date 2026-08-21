---
name: tina-plugin-system
description: TinaIDE 插件系统、.tinaplug、manifest、权限、Lua script/hybrid、LSP plugin、插件市场、内置插件和 starter 模板开发指南。用于新增/修改插件 API、插件包、host commands、插件模板或插件 UI。
---

# TinaIDE 插件系统

## 先读文件

- `core/plugin/**`：插件解析、安装、权限、扩展点、script API、marketplace。
- `docs/plugins/README.md`：插件系统总览。
- `docs/registry/GitHub-Registry.md`：公开插件/依赖包 Registry 仓库、目录结构与索引格式。
- `docs/plugin-api-contract.md`：host API 契约。
- `docs/plugins/Plugin-State-Model.md`：插件状态模型。
- `docs/plugins/LSP-Plugin-Development-Guide.md`：LSP 插件开发。
- `docs/plugins/Plugin-Authoring-Tutorial.md`、`docs/plugins/Plugin-API-Guide.md`。
- `tools/plugin-starters/**`：starter 模板与校验/打包脚本。
- `plugins/**`、`test-plugins/**`、`app/src/main/assets/bundled_plugins/**`。
- `https://github.com/wuxianggujun/TinaIDE-Registry`：插件/依赖包发布事实源，包含 `sources/plugins/**`、`sources/plugin-starters/**`、插件 v2/v3、`packages/index.v2.json`、详情 JSON 和 `scripts/build-registry.ps1`。

## 项目事实

- `.tinaplug` 本质是 zip，根目录必须有 `manifest.json`。
- `apiVersion` 当前固定为 1；非 1 插件会无效。
- 支持 contributions：themes、menus `filetree/context`、`editor/context`、`editor/toolbar`、keybindings、snippets、projectTemplates、apkExports 等。
- 插件菜单与命令解析统一输出 `ResolvedPluginCommand` 元数据，包含 `pluginId`、`pluginName`、`commandId`、`group`、`surface` 与 `source`。
- 旧 `ResolvedHostMenuItem` 仅作为菜单 UI 兼容层保留；命令面板等需要插件来源、搜索关键词或诊断信息的入口应优先消费 `resolveEditorToolbarCommands()` 等统一命令解析入口。
- 不支持动态 DEX；script/hybrid 走 Lua runtime 和权限确认。
- 插件权限是两层：manifest 声明 + 运行时授权。
- 稳定 `tina` API 包括 pluginId、apiVersion、log、events、editor、diagnostics、workspace、commands、fs、clipboard、network、db。
- 插件系统负责安装、启用、禁用、卸载和注入扩展点；工具链/包管理负责依赖安装，插件不直接安装依赖库。
- 内置插件目录支持 `app/src/main/assets/bundled_plugins/<pluginId>/manifest.json` 或 `.tinaplug`。
- 公开插件与依赖包 Registry 固定为 `https://github.com/wuxianggujun/TinaIDE-Registry`。
- `0.18.11+` 客户端读取 `plugins/index.v3.json`，按 Plugin API 与 `min_app_version` 选择最高兼容版本；旧客户端读取只含 `0.17.11 + API v1` 兼容版本的 `plugins/index.v2.json`。依赖包仍读取 `packages/index.v2.json`；各入口均不回退 v1，优先 GitHub Raw，失败后回退 jsDelivr CDN。
- Registry v2 从 `0.17.11` 引入；`0.18.0` 起 Android 客户端删除 v1 fallback；Registry 默认停止生成 v1 全量索引。确实需要服务旧客户端时，Registry 仓库可显式使用 `build-registry.ps1 -IncludeLegacyV1` 与 `validate-registry.ps1 -AllowLegacyV1`。
- 该 Registry 承载索引、可下载包文件、官方插件源码、starter 源模板和索引构建脚本；不承载后端、数据库或管理后台。
- 如果 v2 的 `plugins` / `packages` 索引为空，市场列表为空属于 Registry 未发布内容，不是 Android 仓库缺代码。
- 宿主行为应消费启用态插件，例如 `enabledPluginsFlow` 或中心状态快照；不要遍历安装态插件后临时过滤。
- 首次安装时纯 `config` 插件自动启用；`script`、`hybrid`、`lsp`、`system` 等插件默认禁用。升级保留用户原有启用意图。

## 修改流程

1. 先确认是 host API、manifest schema、权限、Lua runtime、LSP plugin、marketplace 还是 starter 模板。
2. 修改 manifest/API 前阅读 `docs/plugin-api-contract.md` 和对应 core/plugin tests。
3. 新增 manifest 字段或贡献点时，同步更新 `PluginModels.kt`、`PluginManifestValidator.kt`、相关 resolver/manager、文档和测试。
4. 新增权限时同时更新 manifest 解析、授权流程、文案和测试。
5. 修改 starter 模板后运行模板自己的 `validate.ps1` 或 `validate.sh`。
6. 修改插件市场发布内容时，优先在 `TinaIDE-Registry` 更新 `sources/plugins/**` / `sources/plugin-starters/**` / `packages/**`，提升插件版本，运行 `scripts/build-registry.ps1` 并提交 v2/v3 索引、详情和不可变制品。
7. 如果仍需保留 APK 内置兜底插件，再同步 `app/src/main/assets/bundled_plugins/**`。

## 禁止事项

- 不要引入动态 DEX 插件能力，除非项目明确改变安全模型。
- 不要让插件直接安装系统/项目依赖库。
- 不要只看 docs 就实现贡献点；必须核对 `core/plugin` 是否已落地。
- 不要把市场插件、依赖包索引或包文件回填到 Android 主仓库；发布内容应进入 `wuxianggujun/TinaIDE-Registry`，大文件可在索引里填写可信 CDN/对象存储绝对 URL。
- 不要绕过权限声明和运行时授权。
- 不要忘记提升 starter plugin manifest version。
- 不要在 `script/hybrid` 禁用或卸载时遗漏 Lua 运行时、事件订阅和插件命令注册清理。

## 验证

```powershell
./gradlew :core:plugin:testDebugUnitTest --no-daemon --console=plain
pwsh ./tools/plugin-starters/script-basic/validate.ps1
pwsh ./tools/plugin-starters/lsp-basic/validate.ps1
pwsh ../TinaIDE-Registry/scripts/build-registry.ps1
pwsh ../TinaIDE-Registry/scripts/validate-registry.ps1 -SkipBuild -SkipGitDiffCheck
```

- 插件包变更检查 `.tinaplug` 根目录是否含 `manifest.json`。
- 插件市场或设置页 UI 变更同时跑 settings/plugin 相关测试。
