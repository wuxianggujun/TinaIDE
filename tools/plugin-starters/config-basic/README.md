# {{PROJECT_NAME}}

这是一个 TinaIDE `config` 插件模板。

## 已包含能力

- 编辑器主题
- 代码片段
- 编辑器上下文菜单
- 文件树上下文菜单
- 文件图标

## 你应该先改什么

1. 修改 `manifest.json` 里的 `id`、`name`、`author`
2. 调整 `themes/starter-theme.json`
3. 调整 `snippets/starter-snippets.json`
4. 在 TinaIDE 中点击 **运行**，让 IDE 校验、打包并热安装
5. 需要离线分发时，再运行 `pack.ps1` 或 `pack.sh`
6. 用“设置 → 插件 → 从文件安装”验证生成的 `.tinaplug`

纯 `config` 插件首次安装后会自动启用，使声明式贡献立即可见；升级会保留用户原有启用状态。
它没有 Lua/LSP 运行时，安装或列表刷新不会执行第三方插件代码。

## 当前能力边界

- `editor/context`、`editor/toolbar` 与 `filetree/context` 已可用
- `keybindings` 已可用；第一版模板默认不启用，可按需添加 keybindings 文件
- 纯 `config` 插件没有 Lua 运行时，菜单命令通常只能绑定宿主内置命令
- `contributions.commands` 只提供菜单标题，不会自动生成可执行逻辑

如果你需要自定义命令回调，请改用 `script-command` 或 `script-basic`，
并在 `main.lua` 中用 `tina.commands.register(...)` 注册同一个命令 ID。

## 打包说明

- 在 TinaIDE 中点击 **运行**：校验当前目录、打包 `.tinaplug`，并热安装到当前 IDE
- 在 TinaIDE 中点击 **打包**，或执行 `pack.ps1` / `pack.sh`：只生成插件包，不执行热安装
- 输出路径固定为 `dist/<manifest.id>-<manifest.version>.tinaplug`
- `pack.ps1` / `pack.sh` 会先自动执行校验
- 最终 `.tinaplug` 会排除 README、打包脚本和校验辅助文件
- 离线分发前，建议再用“设置 → 插件 → 从文件安装”选择生成的 `.tinaplug` 做一次预检
