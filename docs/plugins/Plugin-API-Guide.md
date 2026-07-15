# TinaIDE 插件 API 指南

> 文档更新：2026-07-14
> 目标：给插件开发者一份“当前真实可用”的 API 清单，避免继续踩字段存在但宿主没接完的坑。

---

## 1. 先看稳定边界

`apiVersion 1` 中写入本指南和 [插件 API 契约](../plugin-api-contract.md) 的能力均按稳定契约维护。
未写入契约的新模块或字段仍视为实验能力。

如果你要写给普通用户的教程，主线只推荐：

1. `config`
2. `lsp`
3. `script` / `hybrid` 作为受权限与隔离边界约束的进阶能力

### 1.1 Script 运行安全边界

Lua 不再运行在 TinaIDE 主进程。宿主通过 Binder 调用非导出的 `:plugin_runtime` isolated process，隔离进程异常、死循环或 native crash 只会终止该进程。宿主会隔离故障插件并恢复其他仍有效的脚本插件。

插件作者需要遵守以下 API v1 边界：

- 不依赖 `io`、`debug`、`loadfile/dofile`、native `loadlib`、Java/luajava 反射。
- 多文件代码使用受限 `require("module.name")`；模块必须位于插件目录、扩展名为 `.lua`，不能使用绝对路径或 `..`。
- 宿主对象和真实路径不会进入 Lua；所有文件、编辑器、UI、Clipboard、Network 与 Database 能力都通过 `tina.*` 和权限检查访问。
- 新安装插件默认禁用。安装完成后需在详情页明确启用；权限等待、自动隔离和 runtime 不可用会显示为不同状态。
- 被自动隔离的插件必须由用户确认重新启用，或安装严格更高版本后再尝试运行。
- 市场安装会在写入插件目录前绑定请求的 `pluginId` 与版本，并复用文件安装的权限确认；身份不一致的包会被拒绝。市场下载与系统文件选择器导入都执行 64 MiB 流式上限。
- 只有 script/hybrid 的必需权限会在安装事务中授予；L0 权限自动授予，其他级别先确认，安装失败时恢复原授权。配置类插件不会被预授予脚本权限。

资源上限、故障分类和恢复规则以 [插件 API 契约](../plugin-api-contract.md) 为准。

---

## 2. 插件类型与入口

### 2.1 `config`

适合：

- 主题
- 代码片段
- 菜单
- 文件图标
- 项目模板

### 2.2 `script`

适合：

- 编辑器自动化
- 项目事件响应
- 宿主命令调用

说明：

- 运行时为 Lua
- 需要权限声明
- `apiVersion 1` 宿主 API 已稳定；高级能力仍必须遵守权限、资源上限和 isolated process 边界

### 2.3 `lsp`

适合：

- 语言服务器接入
- 工具链安装
- 语言补全与诊断

---

## 3. Script API 命名空间

脚本插件统一从 `tina.*` 访问宿主 API。

### 3.1 稳定可用

- `tina.ui`
- `tina.log`
- `tina.editor`
- `tina.diagnostics`
- `tina.workspace`
- `tina.fs`（历史兼容）
- `tina.config`
- `tina.storage`
- `tina.db`
- `tina.network`
- `tina.commands`
- `tina.events`
- `tina.panels`

### 3.2 使用建议

第一次写脚本插件时，优先只用：

- `tina.ui`
- `tina.log`
- `tina.editor`
- `tina.events`
- `tina.diagnostics`
- `tina.workspace`

等第一版跑通，再逐步引入：

- `tina.commands`
- `tina.config`
- `tina.storage`
- `tina.db`
- `tina.network`
- `tina.panels`

---

## 4. 具体 API

### 4.1 `tina.ui`

用途：用户可见消息。

已提供：

- `tina.ui.showMessage(message)`
- `tina.ui.showWarning(message)`
- `tina.ui.showError(message)`

权限：

- `ui.notification`

### 4.2 `tina.log`

用途：插件日志。

已提供：

- `tina.log.debug(message)`
- `tina.log.info(message)`
- `tina.log.warn(message)`
- `tina.log.error(message)`

额外说明：

- 全局 `print()` 也会转到插件日志

### 4.3 `tina.editor`

用途：读取或修改当前编辑器上下文。

已提供：

- `tina.editor.getActiveEditor()`
- `tina.editor.getText()`
- `tina.editor.setText(text)`
- `tina.editor.getSelection()`
- `tina.editor.setSelection(startLine, startColumn, endLine, endColumn)`
- `tina.editor.insertText(text, line, column)`
- `tina.editor.replaceSelection(text)`
- `tina.editor.getLanguage()`
- `tina.editor.getCursorPosition()`
- `tina.editor.setCursorPosition(line, column)`
- `tina.editor.getFilePath()`
- `tina.editor.getFileName()`

权限映射：

- 读：`editor.read`
- 写：`editor.write`
- 选区：`editor.selection`

`tina.editor.getActiveEditor()` 返回当前活动编辑器快照。当前稳定字段：

- `tabId`
- `filePath`
- `fileName`
- `languageId`
- `isDirty`
- `cursor.line`
- `cursor.column`

说明：

- `insertText()` 与 `replaceSelection()` 现在会同步等待宿主编辑器结果，不再提前返回 `false`
- 选区内容仍然通过 `tina.editor.getSelection()` 单独读取，避免和 `editor.selection` 权限边界混淆

### 4.4 `tina.diagnostics`

用途：读取当前诊断面板里的错误、警告、提示。

已提供：

- `tina.diagnostics.get()`
- `tina.diagnostics.get(filePath)`

返回约定：

- 返回诊断快照表，不抛出宿主异常
- `diagnostics` 是诊断数组
- 计数字段包含 `totalCount`、`errorCount`、`warningCount`、`infoCount`、`hintCount`
- `filePath` 支持项目相对路径或项目内绝对路径

权限：

- `diagnostics.read`

### 4.5 `tina.workspace`

用途：访问当前项目根目录内的文件。

已提供：

- `tina.workspace.readFile(path)`
- `tina.workspace.writeFile(path, content)`
- `tina.workspace.findFiles(pattern, maxResults)`

返回约定：

- `readFile` 成功返回文本；失败返回 `nil, error`
- `writeFile` 成功返回 `true`；失败返回 `false, error`
- `findFiles` 返回相对路径数组，路径统一使用 `/`，并按相对路径升序排列

约束：

- 只能在当前项目根目录下访问
- 不允许路径逃逸
- `findFiles` 支持 `*`、`?`、`**/`
- `findFiles` 会跳过 `.git`、`.gradle`、`.idea`、`.cxx`、`build`、`node_modules`
- `maxResults` 默认 200，限制在 1 到 1000；匹配项排序后才截取前 N 项，未触及扫描上限的同一目录树不受 Windows/Linux 遍历顺序影响
- 单次调用最多扫描 50,000 项；触及上限时结果只覆盖已扫描集合，不能当作完整工作区索引

权限：

- 读：`workspace.read` 或 `file.read`
- 写：`workspace.write` 或 `file.write`

### 4.6 `tina.fs`

用途：历史兼容的工作区文件 API。

已提供：

- `tina.fs.readFile(path)`
- `tina.fs.writeFile(path, content)`
- `tina.fs.exists(path)`
- `tina.fs.isDirectory(path)`
- `tina.fs.listDir(path)`
- `tina.fs.mkdir(path)`

说明：

- 新插件优先使用 `tina.workspace.*`
- 老插件可以继续使用 `tina.fs.*`
- 权限仍映射到 `workspace.read` / `workspace.write` 对应的底层文件权限

### 4.7 `tina.config`

用途：读取和更新 manifest `configuration.properties` 中声明的插件配置。

已提供：

- `tina.config.get(key)`
- `tina.config.get(key, fallback)`
- `tina.config.set(key, value)`
- `tina.config.reset(key)`

说明：

- 不需要 `storage.local` 权限。
- 只能访问当前插件 manifest 声明过的配置 key。
- 未保存值时，`get()` 会先返回 manifest `default`，再使用调用方传入的 fallback。
- `set()` 会校验类型；`string` + `enum` 会拒绝未声明的枚举值。
- 支持类型为 `boolean`、`string`、`number`。
- 配置变化会触发 `config.changed`，该事件只发给配置所属插件，不会暴露给其他插件。

### 4.8 `tina.storage`

用途：插件级键值存储。

已提供：

- `tina.storage.get(key)`
- `tina.storage.set(key, value)`
- `tina.storage.remove(key)`

权限：

- `storage.local`

### 4.9 `tina.db`

用途：插件独立 SQLite 数据库。

已提供：

- `tina.db.execute(sql, params)`
- `tina.db.query(sql, params)`
- `tina.db.transaction(callback)`
- `tina.db.close()`
- `tina.db.tableExists(tableName)`

权限：

- `storage.database`

隔离与清理：

- 数据库文件名由完整插件 ID 的 SHA-256 派生，不会因 `.` / `_` 归一化产生跨插件碰撞。
- 旧版数据库仅在同名映射没有其他已安装插件竞争时迁移。
- 卸载会撤销该插件的权限授权，并清理 `tina.storage` 与 `tina.db` 持久化数据；普通升级不会清理。

### 4.10 `tina.network`

用途：网络请求。

已提供：

- `tina.network.fetch(url, method, body, contentType)`
- `tina.network.get(url)`
- `tina.network.post(url, body, contentType)`

权限：

- `network.fetch`
- `network.unrestricted`

约束：

- 使用 `network.fetch` 时，目标主机必须命中白名单
- HTTP 重定向的每一跳都会重新检查白名单，不能通过首跳允许域名跳转到未声明主机
- 若要完全放开，需要更高风险权限

### 4.11 `tina.commands`

用途：调用宿主现有命令，或注册当前插件自己的命令回调。

已提供：

- `tina.commands.execute(commandId)`
- `tina.commands.execute(commandId, relativePath)`
- `tina.commands.execute(commandId, relativePath, isDirectory)`
- `tina.commands.register(commandId, callbackName)`
- `tina.commands.register(commandId, callbackName, title)`
- `tina.commands.unregister(commandId)`

说明：

- 第二个参数会解析为当前项目根目录内的目标路径
- 如果命令不需要目标文件，直接只传 `commandId` 即可
- `register()` 只能注册当前插件自己的命令
- 宿主命令 ID 与插件命令 ID 不能冲突
- 跨插件重复命令 ID 会被拒绝

插件命令回调会收到一个 payload table，当前稳定字段：

- `commandId`
- `filePath`
- `fileName`
- `isDirectory`
- `isDirty`

当前适合优先使用的命令：

- `view.toggleFileTree`
- `view.toggleSymbols`
- `view.settings`
- `editor.format`
- `editor.save`
- `editor.saveAll`
- `project.refresh`
- `project.build`
- `project.run`

对文件目标依赖更强的命令：

- `file.copyPath`
- `file.copyName`
- `file.copyRelativePath`
- `file.delete`
- `file.rename`

权限：

- `command.execute`
- `commands.execute` 也会被归一化为同一权限

菜单绑定规则：

- `contributions.menus["filetree/context"]`
- `contributions.menus["editor/context"]`

上述 `command` 字段现在支持两类值：

- 宿主内置命令
- 当前插件已注册的插件命令

### 4.12 `tina.events`

用途：监听宿主事件。

已提供：

- `tina.events.on(eventId, callbackName)`
- `tina.events.off(eventId)`
- `tina.events.emit("custom", payload)`
- `tina.events.clear()`

当前宿主已接入的事件：

- `project.opened`
- `project.closed`
- `build.started`
- `build.finished`
- `editor.opened`
- `editor.closed`
- `editor.activeChanged`
- `editor.selectionChanged`
- `editor.dirtyChanged`
- `editor.saved`
- `file.created`
- `file.deleted`
- `file.renamed`
- `diagnostics.changed`
- `config.changed`

`custom` 是插件自身的稳定定向事件。它只会派发给当前插件，不能用于伪造宿主事件；`payload` 必须是 JSON object。

常见事件数据：

- `editor.opened` / `editor.closed` / `editor.saved`
  包含 `tabId`、`filePath`、`fileName`，打开/关闭事件还包含 `contentType`
- `editor.activeChanged`
  包含 `tabId`、`filePath`、`fileName`、`contentType`、`isDirty`
- `editor.selectionChanged`
  包含 `tabId`、`filePath`、`fileName`、`hasSelection`、`selection`
- `editor.dirtyChanged`
  包含 `tabId`、`filePath`、`fileName`、`isDirty`
- `file.created` / `file.deleted`
  包含 `filePath`、`fileName`、`isDirectory`
- `file.renamed`
  包含 `oldPath`、`oldName`、`newPath`、`newName`、`isDirectory`
- `diagnostics.changed`
  包含 `fileUri`、`fileName`、`totalCount`、`errorCount`、`warningCount`、`diagnostics`
- `config.changed`
  包含 `pluginId`、`key`、`value`、`previousValue`
- `project.opened` / `project.closed`
  包含 `rootPath`、`projectName`
- `build.started` / `build.finished`
  包含 `rootPath`

高频事件说明：

- `editor.selectionChanged` 已在宿主侧做 180ms 防抖
- `diagnostics.changed` 由 LSP / 内置语言服务诊断变化触发
- `editor.dirtyChanged` 只在脏状态真正变化时触发
- 选区文本最多携带 16 Ki 字符；超出时 `selection.textTruncated=true`
- 单次诊断事件最多携带 32 条详情、每条消息最多 384 字符，并限制 URI、文件名、来源与错误码长度；原始总数仍由 `totalCount` 提供，截断时 `diagnosticsTruncated=true`
- 宿主拒绝的超大事件载荷属于宿主输入限制，只返回调用错误，不会把健康插件误判为 runtime crash

### 4.13 `tina.panels`

用途：把插件生成的纯文本状态、日志或分析结果显示在编辑器底部“插件”面板。

- manifest 先声明 `contributions.panels`，每个插件最多 16 个面板。
- `tina.panels.setContent(panelId, text)` 替换内容。
- `tina.panels.appendContent(panelId, text)` 追加内容。
- `tina.panels.clear(panelId)` 清除内容。
- 单面板最多 256 KiB UTF-8 文本；禁用、卸载、隔离或 runtime 死亡会清理内容。
- 面板不执行 HTML、Markdown、Lua 或动态 UI 代码。

---

## 5. 权限清单

### 5.1 低风险

- `editor.read`
- `editor.selection`
- `diagnostics.read`
- `ui.notification`

### 5.2 中低风险

- `editor.write`
- `clipboard.read`
- `clipboard.write`
- `command.execute`

### 5.3 中风险

- `file.read`
- `file.write`
- `workspace.read`（等价于 `file.read`）
- `workspace.write`（等价于 `file.write`）
- `network.fetch`
- `storage.local`
- `storage.database`

### 5.4 高风险

- `file.system`
- `shell.execute`
- `network.unrestricted`

---

## 6. 一个最小可运行示例

```lua
function on_project_opened(data)
  local root_path = data and data.rootPath or "unknown"
  tina.log.info("Project opened: " .. root_path)
  tina.ui.showMessage("Project opened: " .. root_path)
end

function on_editor_saved(data)
  local file_name = data and data.fileName or "unknown"
  tina.log.info("Editor saved: " .. file_name)
end

function on_file_created(data)
  local path = data and data.filePath or "unknown"
  tina.log.info("File created: " .. path)
end

function on_diagnostics_changed(data)
  local errors = data and data.errorCount or 0
  local warnings = data and data.warningCount or 0
  tina.log.info("Diagnostics changed: errors=" .. errors .. ", warnings=" .. warnings)
end

tina.events.on("project.opened", "on_project_opened")
tina.events.on("editor.saved", "on_editor_saved")
tina.events.on("file.created", "on_file_created")
tina.events.on("diagnostics.changed", "on_diagnostics_changed")
tina.commands.execute("view.toggleFileTree")
tina.ui.showMessage("Plugin loaded")
```

配套权限：

```json
{
  "permissions": [
    "ui.notification",
    "editor.read",
    "command.execute"
  ]
}
```

## 7. 尚未进入稳定契约的点

以下内容没有进入 apiVersion 1 稳定契约，不应对普通用户写成“已支持”：

- 未写入 [插件 API 契约](../plugin-api-contract.md) 的新命名空间或字段
- 动态 DEX、任意原生模块、Java/LuaJava 反射和插件自定义 Compose UI

---

## 8. 推荐公开表达方式

如果你要把这套 API 发给用户，我建议文案保持这个口径：

- `config`、`lsp`、`script` 和 `hybrid` 的 apiVersion 1 契约是正式能力
- `script` / `hybrid` 是进阶能力，并受 isolated process、权限、白名单、限流与资源上限约束
- 教程只使用已写入契约并有测试覆盖的事件和 API

这样最稳，不会再把用户带进“文档说支持，实际没接完”的坑里。
