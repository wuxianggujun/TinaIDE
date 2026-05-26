---
name: tina-plugin-system
description: TinaIDE 插件系统、.tinaplug、manifest、权限、Lua script/hybrid、LSP plugin、插件市场、内置插件和 starter 模板开发指南。用于新增/修改插件 API、插件包、host commands、插件模板或插件 UI。
---

# TinaIDE 插件系统

## 先读文件

- `core/plugin/**`：插件解析、安装、权限、扩展点、script API、marketplace。
- `docs/plugins/README.md`：插件系统总览。
- `docs/plugin-api-contract.md`：host API 契约。
- `docs/plugins/Plugin-State-Model.md`：插件状态模型。
- `docs/plugins/LSP-Plugin-Development-Guide.md`：LSP 插件开发。
- `docs/plugins/Plugin-Authoring-Tutorial.md`、`docs/plugins/Plugin-API-Guide.md`。
- `tools/plugin-starters/**`：starter 模板与校验/打包脚本。
- `plugins/**`、`test-plugins/**`、`app/src/main/assets/bundled_plugins/**`。

## 项目事实

- `.tinaplug` 本质是 zip，根目录必须有 `manifest.json`。
- `apiVersion` 当前固定为 1；非 1 插件会无效。
- 支持 contributions：themes、menus `filetree/context`、`editor/context`、snippets、projectTemplates、apkExports 等。
- `editor/toolbar` 和 keybindings 在文档中属于已定义但暂未实现能力，改动前必须核对当前代码。
- 不支持动态 DEX；script/hybrid 走 Lua runtime 和权限确认。
- 插件权限是两层：manifest 声明 + 运行时授权。
- 稳定 `tina` API 包括 pluginId、apiVersion、log、events、editor、diagnostics、workspace、commands、fs、clipboard、network、db。
- 插件系统负责安装、启用、禁用、卸载和注入扩展点；工具链/包管理负责依赖安装，插件不直接安装依赖库。
- 内置插件目录支持 `app/src/main/assets/bundled_plugins/<pluginId>/manifest.json` 或 `.tinaplug`。
- 宿主行为应消费启用态插件，例如 `enabledPluginsFlow` 或中心状态快照；不要遍历安装态插件后临时过滤。

## 修改流程

1. 先确认是 host API、manifest schema、权限、Lua runtime、LSP plugin、marketplace 还是 starter 模板。
2. 修改 manifest/API 前阅读 `docs/plugin-api-contract.md` 和对应 core/plugin tests。
3. 新增 manifest 字段或贡献点时，同步更新 `PluginModels.kt`、`PluginManifestValidator.kt`、相关 resolver/manager、文档和测试。
4. 新增权限时同时更新 manifest 解析、授权流程、文案和测试。
5. 修改 starter 模板后运行模板自己的 `validate.ps1` 或 `validate.sh`。
6. 修改 `tools/plugin-starters/**` 后同步 `app/src/main/assets/bundled_plugins/tinaide.plugin.starters/templates/*.zip`。

## 禁止事项

- 不要引入动态 DEX 插件能力，除非项目明确改变安全模型。
- 不要让插件直接安装系统/项目依赖库。
- 不要只看 docs 就实现贡献点；必须核对 `core/plugin` 是否已落地。
- 不要绕过权限声明和运行时授权。
- 不要忘记提升 starter plugin manifest version。
- 不要在 `script/hybrid` 禁用或卸载时遗漏 Lua 运行时、事件订阅和插件命令注册清理。

## 验证

```powershell
./gradlew :core:plugin:testDebugUnitTest --console=plain
pwsh ./tools/plugin-starters/script-basic/validate.ps1
pwsh ./tools/plugin-starters/lsp-basic/validate.ps1
pwsh ./tools/plugin-starters/build-bundled-plugin-starters.ps1
```

- 插件包变更检查 `.tinaplug` 根目录是否含 `manifest.json`。
- 插件市场或设置页 UI 变更同时跑 settings/plugin 相关测试。
