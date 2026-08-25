# {{PROJECT_NAME}}

这是一个聚焦“命令注册 + 菜单入口 + 编辑器写入”的 TinaIDE `script` 插件模板。

## 已包含能力

- `tina.commands.register(...)`
- `tina.commands.execute("view.toggleFileTree")`
- `tina.editor.getActiveEditor()`
- `tina.editor.getSelection()`
- `tina.editor.insertText(...)`
- `tina.editor.replaceSelection(...)`
- `contributions.commands`
- `contributions.menus["editor/context"]`

## 默认命令示例

- `Toggle File Tree`
  通过插件命令回调转发宿主命令 `view.toggleFileTree`
- `Insert Starter Header`
  读取活动编辑器快照，并在文件顶部插入注释头
- `Wrap Selection`
  有选择区时替换选择区；没有选择区时回退到当前位置插入文本

## 推荐改法

1. 先把 `manifest.json` 里的 `id`、`name`、`author` 改成你自己的值
2. 先测试编辑器菜单里的三个示例命令
3. 如果不需要宿主命令转发，删除 `Toggle File Tree`
4. 如果不需要读取选择区，删除 `editor.selection` 权限和 `wrapSelection`
5. 在 TinaIDE 中点击 **运行**，让 IDE 校验、打包并热安装
6. 需要离线分发时，再执行 `pack.ps1` 或 `pack.sh`
7. 用“设置 → 插件 → 从文件安装”验证生成的 `.tinaplug`
8. 如果你需要事件、工作区读写或诊断快照，改用 `script-basic`

首次安装后插件默认禁用。进入详情页确认权限并明确启用。Lua 只在 isolated plugin runtime 中执行；不要依赖 `io`、`debug`、`loadfile/dofile`、native `loadlib` 或 Java/luajava 反射。未处理的命令回调异常会使当前插件版本自动隔离。

## 当前建议

- 第一版优先把命令入口和权限边界跑通
- `editor.write`、`editor.selection`、`command.execute` 都是按需能力，不用就删
- 菜单里的自定义命令 ID 必须和 `tina.commands.register(...)` 注册的 ID 一致
- 如果你只需要“命令触发编辑器操作”，这个模板比 `script-basic` 更合适

## 打包说明

- 在 TinaIDE 中点击 **运行**：校验当前目录、打包 `.tinaplug`，并热安装到当前 IDE
- 在 TinaIDE 中点击 **打包**，或执行 `pack.ps1` / `pack.sh`：只生成插件包，不执行热安装
- 输出路径固定为 `dist/<manifest.id>-<manifest.version>.tinaplug`
- `pack.ps1` / `pack.sh` 会先自动执行校验
- 最终 `.tinaplug` 会排除 README、打包脚本和校验辅助文件
- 离线分发前，建议再用“设置 → 插件 → 从文件安装”选择生成的 `.tinaplug` 做一次预检
