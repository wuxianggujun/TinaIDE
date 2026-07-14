# {{PROJECT_NAME}}

这是一个偏“自动化 / 事件 / 工作区读写”的 TinaIDE `script` 插件模板。

## 已包含能力

- `tina.ui.showMessage()`
- `tina.log.info()`
- `tina.editor.getActiveEditor()`
- `tina.editor.getLanguage()`
- `tina.editor.getSelection()`
- `tina.editor.insertText(...)`
- `tina.editor.replaceSelection(...)`
- `tina.diagnostics.get()`
- `tina.workspace.findFiles("*.lua", 5)`
- `tina.events.on("project.opened", ...)`
- `tina.events.on("editor.opened", ...)`
- `tina.events.on("editor.activeChanged", ...)`
- `tina.events.on("editor.selectionChanged", ...)`
- `tina.events.on("diagnostics.changed", ...)`
- `tina.events.on("file.created", ...)`
- `tina.events.emit("custom", {...})`
- `tina.panels.setContent(...)` / `appendContent(...)`
- `tina.commands.register(...)`
- `tina.commands.execute("view.toggleFileTree")`
- `contributions.menus["filetree/context"]`
- `contributions.menus["editor/context"]`
- `contributions.panels`

## 默认命令示例

- `Toggle File Tree`
  通过插件命令回调再调用宿主命令 `view.toggleFileTree`
- `Insert Starter Header`
  读取 `tina.editor.getActiveEditor()`，并在文件顶部插入注释头
- `Wrap Selection`
  若当前有选择区，则调用 `tina.editor.replaceSelection(...)`
  若没有选择区，则回退为 `tina.editor.insertText(...)`

## 推荐改法

1. 先确认 `manifest.json` 中的权限是否最小化
2. 先让插件在加载时弹一条消息，确认运行时生效
3. 先测试编辑器菜单里的 `Insert Starter Header` 和 `Wrap Selection`
4. 再逐步删掉你不需要的事件、命令和权限
5. 如果不需要读取诊断，删除 `diagnostics.read` 权限和诊断快照日志
6. 如果不需要扫描工作区文件，删除 `workspace.read` 权限和 `log_workspace_snapshot()`
7. 在 TinaIDE 中点击 **运行**，让 IDE 校验、打包并热安装
8. 需要离线分发时，再执行 `pack.ps1` 或 `pack.sh`
9. 用“设置 → 插件 → 从文件安装”验证生成的 `.tinaplug`

模板默认声明 `status` 文本面板。启用插件后，编辑器底部会出现“插件”标签页；
初始加载、项目打开和 `custom` 事件会更新面板内容。面板内容是有界纯文本，runtime 重启后由插件重新发布。

首次安装后插件默认禁用。进入详情页确认权限并明确启用。Lua 只在 isolated plugin runtime 中执行；不要使用 `io`、`debug`、`loadfile/dofile`、native `loadlib` 或 Java/luajava 反射。多文件代码使用 `require("module.name")` 加载插件目录内的纯 Lua 模块。

若插件因未处理异常、超时、资源越限或 runtime crash 被自动隔离，先查看详情页故障阶段和插件日志；确认修复后再手动重新启用或提升插件版本。

## 当前建议

- 把脚本插件当成进阶能力使用
- 第一版优先使用 `tina.workspace.*`，不要再从新代码里依赖历史 `tina.fs.*`
- `editor.write` 和 `editor.selection` 都是按需能力，不用就删掉
- 菜单里的自定义命令 ID 必须和 `tina.commands.register(...)` 注册的 ID 一致
- 第一版不要依赖复杂文件写入和网络请求
- 如果你只需要“注册命令 + 菜单入口 + 编辑器写入”，优先使用 `script-command`

## 打包说明

- 在 TinaIDE 中点击 **运行**：校验当前目录、打包 `.tinaplug`，并热安装到当前 IDE
- 在 TinaIDE 中点击 **打包**，或执行 `pack.ps1` / `pack.sh`：只生成插件包，不执行热安装
- 输出路径固定为 `dist/<manifest.id>-<manifest.version>.tinaplug`
- `pack.ps1` / `pack.sh` 会先自动执行校验
- 最终 `.tinaplug` 会排除 README、打包脚本和校验辅助文件
- 离线分发前，建议再用“设置 → 插件 → 从文件安装”选择生成的 `.tinaplug` 做一次预检
