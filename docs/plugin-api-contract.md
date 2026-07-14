# 插件 API 契约（apiVersion 1）

## Manifest

| 字段 | 必填 | 稳定性 | 说明 |
| --- | --- | --- | --- |
| `id` | 是 | 稳定 | 插件唯一标识。 |
| `name` | 是 | 稳定 | 展示名称。 |
| `version` | 是 | 稳定 | 插件版本。 |
| `apiVersion` | 否 | 稳定 | 当前固定为 `1`，省略时默认 `1`。宿主会拒绝其他版本。 |
| `type` | 否 | 稳定 | 当前重点支持 `script`、`hybrid`、`lsp`。 |
| `main` | `script`/`hybrid` 必填逻辑项 | 稳定 | 省略时默认 `main.lua`，宿主要求对应文件存在。 |
| `permissions` | 否 | 稳定 | 必需权限声明。未声明的宿主 API 调用会被拒绝。 |
| `optionalPermissions` | 否 | 稳定 | 按需授权声明。用户可在插件详情页单项授予或撤销；未显式授予时调用会被拒绝。 |
| `activationEvents` | 否 | 稳定 | 仅适用于 LSP 插件；apiVersion 1 支持 `onLanguage:<languageId>`，且语言必须由 `languageServers` 声明。 |
| `contributions.commands` / `menus` | 否 | 稳定 | 已用于命令与菜单贡献。 |
| `contributions.panels` | 否 | 稳定 | `script` / `hybrid` 可声明文本面板，并通过 `tina.panels.*` 发布内容。 |
| `requires` | 否 | 稳定 | 依赖声明提示字段；宿主解析并展示/诊断提示，但不检测真实安装状态，也不自动安装依赖。 |
| `configuration` | 否 | 稳定 | 插件配置 schema；宿主在插件详情页自动生成设置 UI，并提供 `tina.config.*` 读写。 |
| `locales` | 否 | 稳定 | 插件用户可见元数据的多语言覆盖文件；宿主按当前 Locale 自动选择。 |

## Manifest 多语言

插件可在 `manifest.json` 中声明 `locales`，把展示名称、描述、配置标题、配置项说明和贡献项展示文案放入独立 JSON 文件。宿主按 `语言-地区 -> 语言 -> default -> manifest 原字段` 回退。

```json
{
  "name": "Demo Plugin",
  "description": "Demo description",
  "locales": {
    "default": "en",
    "files": {
      "en": "locales/en.json",
      "zh-CN": "locales/zh-CN.json",
      "zh": "locales/zh-CN.json"
    }
  }
}
```

locale 文件只覆盖用户可见字段，不覆盖 `id`、`version`、`type`、入口路径、权限等行为字段。文件路径必须是安全相对路径，并且位于插件根目录的 `locales/` 下。

```json
{
  "name": "演示插件",
  "description": "演示描述",
  "configuration": {
    "title": "演示配置",
    "properties": {
      "feature.enabled": { "description": "启用功能" }
    }
  },
  "contributions": {
    "commands": {
      "demo.run": { "title": "运行演示" }
    },
    "projectTemplates": {
      "cpp": {
        "name": "C++ 模板",
        "description": "C++ 项目模板"
      }
    }
  }
}
```

## `tina` 全局对象

稳定字段：

- `tina.pluginId`
- `tina.apiVersion`
- `tina.log.*`
- `tina.events.*`
- `tina.panels.*`
- `tina.editor.*`
- `tina.diagnostics.*`
- `tina.workspace.*`
- `tina.commands.*`
- `tina.config.*`
- `tina.fs.*`
- `tina.clipboard.*`
- `tina.network.*`
- `tina.db.*`
- `tina.storage.*`
- `tina.ui.*`

实验字段：

- 任何尚未写入本文件的新模块或新字段

## 权限模型

宿主在调用 API 前会做两层校验：

1. **manifest 声明**：权限必须出现在 `permissions` 或 `optionalPermissions`
2. **运行时授权**：`permissions` 中的 L0 基础权限自动可用，其他必需权限需要安装确认；`optionalPermissions` 无论风险级别都必须由用户显式授予

当前支持的权限标识：

| 能力 | 支持的 manifest ID | 风险级别 | 备注 |
| --- | --- | --- | --- |
| 工作区读 | `workspace.read`、`file.read` | L2 | 两者等价，宿主归一化为同一权限。 |
| 工作区写 | `workspace.write`、`file.write` | L2 | 两者等价。 |
| 命令执行 | `commands.execute`、`command.execute` | L1 | 两者等价。 |
| 编辑器只读 | `editor.read` | L0 | 仍需 manifest 声明。 |
| 选区读取 | `editor.selection` | L0 | 仍需 manifest 声明。 |
| 诊断读取 | `diagnostics.read` | L0 | 仍需 manifest 声明。 |
| 编辑器写入 | `editor.write` | L1 | 需要授权。 |
| 剪贴板读 | `clipboard.read` | L1 | 需要授权。 |
| 剪贴板写 | `clipboard.write` | L1 | 需要授权。 |
| 通知 | `ui.notification` | L0 | 仍需 manifest 声明。 |
| 网络白名单访问 | `network.fetch` | L2 | 受 `networkHosts` 约束。 |
| 非受限网络 | `network.unrestricted` | L3 | 高风险。 |
| 本地存储 | `storage.local` | L2 | 需要授权。 |
| 数据库 | `storage.database` | L2 | 需要授权。 |
| 系统文件 | `file.system` | L3 | 高风险。 |
| Shell 执行 | `shell.execute` | L3 | 高风险。 |

## 宿主 API

### `tina.workspace.*`

`workspace` 是 apiVersion 1 的正式工作区文件 API。所有路径均限制在当前项目根目录内，返回路径统一使用 `/` 作为分隔符。

| API | 权限 | 稳定性 | 返回 |
| --- | --- | --- | --- |
| `readFile(path)` | `workspace.read` / `file.read` | 稳定 | 成功返回文本；失败返回 `nil, error`。 |
| `writeFile(path, content)` | `workspace.write` / `file.write` | 稳定 | 成功返回 `true`；失败返回 `false, error`。 |
| `findFiles(pattern, maxResults)` | `workspace.read` / `file.read` | 稳定 | 返回相对路径数组；`pattern` 支持 `*`、`?`、`**/`，默认 `**/*`，结果最多 1000 条。 |

工作区 API 只接受项目相对路径，Unix、Windows drive 和 UNC 绝对路径都会被拒绝。
`findFiles` 会跳过符号链接和常见重目录：`.git`、`.gradle`、`.idea`、`.cxx`、`build`、`node_modules`；
为避免插件触发无界目录遍历，单次调用最多扫描 50,000 项，超大工作区应使用更具体的 pattern。

### `tina.editor.*`

`editor` 是 apiVersion 1 的正式编辑器上下文 API。当前已用于读取活动编辑器快照和修改当前编辑器内容。

| API | 权限 | 稳定性 | 返回 |
| --- | --- | --- | --- |
| `getActiveEditor()` | `editor.read` | 稳定 | 返回当前活动编辑器快照；无活动编辑器时返回 `nil`。 |
| `insertText(text, line, column)` | `editor.write` | 稳定 | 成功返回 `true`；失败返回 `false`。 |
| `replaceSelection(text)` | `editor.write` | 稳定 | 成功返回 `true`；失败返回 `false`。 |

`getActiveEditor()` 当前稳定字段：

- `tabId`
- `filePath`
- `fileName`
- `languageId`
- `isDirty`
- `cursor`

`cursor` 当前稳定字段：

- `line`
- `column`

### `tina.diagnostics.*`

`diagnostics` 是 apiVersion 1 的正式诊断读取 API。它读取宿主当前诊断面板里的快照，字段与 `diagnostics.changed` 事件保持一致。

| API | 权限 | 稳定性 | 返回 |
| --- | --- | --- | --- |
| `get()` | `diagnostics.read` | 稳定 | 返回所有当前诊断快照。 |
| `get(filePath)` | `diagnostics.read` | 稳定 | 返回指定项目相对路径或绝对路径的诊断快照。 |

返回对象稳定字段：

- `available`
- `totalCount`
- `errorCount`
- `warningCount`
- `infoCount`
- `hintCount`
- `requestedFilePath`
- `diagnostics`

`diagnostics` 列表项稳定字段：

- `fileUri`
- `filePath`
- `fileName`
- `line`
- `column`
- `endLine`
- `endColumn`
- `message`
- `severity`
- `source`
- `code`

### `tina.commands.*`

`commands` 是 apiVersion 1 的正式命令 API。当前包含“调用命令”和“注册插件命令”两类能力，统一受
`commands.execute` / `command.execute` 权限保护。

| API | 权限 | 稳定性 | 返回 |
| --- | --- | --- | --- |
| `execute(commandId)` | `commands.execute` / `command.execute` | 稳定 | 成功返回 `true`；失败返回 `false, error`。 |
| `execute(commandId, relativePath)` | `commands.execute` / `command.execute` | 稳定 | 第二个参数按当前项目根目录解析。 |
| `execute(commandId, relativePath, isDirectory)` | `commands.execute` / `command.execute` | 稳定 | 可显式覆盖目标是否目录。 |
| `register(commandId, callbackName)` | `commands.execute` / `command.execute` | 稳定 | 成功返回 `true`；失败返回 `false, error`。 |
| `register(commandId, callbackName, title)` | `commands.execute` / `command.execute` | 稳定 | `title` 会作为运行时回退标题。 |
| `unregister(commandId)` | `commands.execute` / `command.execute` | 稳定 | 返回是否成功取消注册。 |

当前命令模型约束：

- `HostCommandCatalog` 是宿主命令元数据单一来源；`HostCommands` 只保留稳定命令 ID 与查询代理。
- 插件白名单、starter 校验、命令面板和内置快捷键应统一从 `HostCommandCatalog` 派生，避免多处手写清单漂移。
- MainActivity 内部命令不属于插件 API，例如 `project.rebuildRun`、`project.debug`、`project.packageApk`、`project.cmake.*`、`view.split.*`、`view.globalSearch`。
- 插件命令 ID 必须全局唯一；同一插件重复注册会覆盖，跨插件重复注册会被拒绝。
- 插件命令不能覆盖宿主内置命令 ID。
- `contributions.menus[*].command` 现在允许两类值：
  - 宿主白名单命令
  - 当前插件已通过 `tina.commands.register()` 注册的插件命令
- `contributions.menus["editor/toolbar"]` 的命令项会同时出现在编辑器标签栏右侧插件动作菜单和主编辑器命令面板。
- 宿主内部统一使用 `ResolvedPluginCommand` 表示已解析的插件命令，稳定元数据包括 `pluginId`、`pluginName`、`commandId`、`group`、`surface` 与 `source`。
- `surface` 当前包括 `EDITOR_TOOLBAR`、`EDITOR_CONTEXT`、`FILE_TREE_CONTEXT`；`source` 当前包括 `HOST` 与 `PLUGIN`。
- 旧菜单 UI 可以继续消费 `ResolvedHostMenuItem` 兼容模型；命令面板等需要插件来源、搜索关键词或诊断信息的入口应优先消费 `resolveEditorToolbarCommands()` 等统一命令解析入口。
- `contributions.keybindings` 与 `contributions.menus` 共用同一套命令解析规则：宿主白名单命令可直接执行，插件自定义命令必须属于当前插件且已完成运行时注册。
- 插件禁用、卸载或脚本 runtime 卸载时，宿主会清理该插件的事件订阅、API runtime 与 `PluginCommandRegistry` 注册命令；已经缓存的快捷键在执行前仍会重新校验注册状态。

插件命令回调稳定 payload 字段：

- `commandId`
- `filePath`
- `fileName`
- `isDirectory`
- `isDirty`

### `tina.config.*`

`config` 是 apiVersion 1 的正式插件配置 API。它只读写当前插件在 manifest `configuration.properties`
中声明过的 key，不需要 `storage.local` 权限，也不会访问其他插件配置。

| API | 权限 | 稳定性 | 返回 |
| --- | --- | --- | --- |
| `get(key)` | 无 | 稳定 | 返回已保存值；没有保存值时返回 manifest `default`；未知 key 返回 `nil, error`。 |
| `get(key, fallback)` | 无 | 稳定 | 在没有保存值且没有 manifest `default` 时返回类型匹配的 fallback。 |
| `set(key, value)` | 无 | 稳定 | 成功返回 `true`；未知 key、类型不匹配或枚举值非法时返回 `false, error`。 |
| `reset(key)` | 无 | 稳定 | 删除已保存值并回退到 manifest `default`；未知 key 返回 `false, error`。 |

插件可通过 `tina.events.on("config.changed", callbackName)` 监听自身配置变化。该事件只会定向派发给配置所属插件，
不会广播其他插件的配置值。

### `tina.panels.*`

`panels` 是 apiVersion 1 的稳定文本面板 API。插件必须先在 `contributions.panels` 中声明面板 ID，
然后才能调用：

| API | 权限 | 稳定性 | 行为 |
| --- | --- | --- | --- |
| `setContent(panelId, text)` | 无 | 稳定 | 替换当前插件指定面板的文本内容。 |
| `appendContent(panelId, text)` | 无 | 稳定 | 追加文本。合并后的 UTF-8 内容不得超过 256 KiB。 |
| `clear(panelId)` | 无 | 稳定 | 清除面板内容。 |

面板内容仅保存在宿主进程内存中；插件禁用、卸载、隔离或 runtime process 死亡时立即清理，
插件重新激活后应自行重新发布。插件不能写入其他插件或未声明的面板，面板只渲染可选择、可滚动的纯文本，
不会执行 HTML、Markdown、Lua 或动态 UI 代码。

当前 schema 约束：

- `type` 仅支持 `boolean`、`string`、`number`。
- `enum` 仅支持 `string` 配置项。
- 配置 key 必须匹配 `^[A-Za-z0-9][A-Za-z0-9._-]*$`。
- 插件卸载时宿主会清理该插件 ID 下的配置。

### `tina.fs.*`

`fs` 是历史兼容命名空间，当前仍保留。新插件应优先使用 `tina.workspace.*`。

## 事件 Payload

### 稳定事件

| 事件 | 稳定字段 | 实验字段 |
| --- | --- | --- |
| `project.opened` / `project.closed` | `rootPath`、`projectName` | 无 |
| `editor.opened` / `editor.closed` | `tabId`、`filePath`、`fileName` | `contentType` |
| `editor.activeChanged` | `tabId`、`filePath`、`fileName`、`isDirty` | `contentType` |
| `editor.saved` | `tabId`、`filePath`、`fileName` | 无 |
| `editor.dirtyChanged` | `tabId`、`filePath`、`fileName`、`isDirty` | 无 |
| `editor.selectionChanged` | `tabId`、`filePath`、`fileName`、`hasSelection` | `selection` |
| `file.created` / `file.deleted` | `filePath`、`fileName`、`isDirectory` | 无 |
| `file.renamed` | `oldPath`、`oldName`、`newPath`、`newName`、`isDirectory` | 无 |
| `build.started` / `build.finished` | `rootPath` | 无 |
| `diagnostics.changed` | `fileUri`、`fileName`、`totalCount`、`errorCount`、`warningCount`、`infoCount`、`hintCount` | `diagnostics` |
| `config.changed` | `pluginId`、`key`、`value`、`previousValue` | 无 |
| `custom` | 插件传入的 JSON object 字段 | 无 |

`tina.events.emit("custom", payload)` 只向当前插件自身的 `custom` 订阅者异步派发；插件不能通过
`emit` 伪造 `editor.saved`、`build.finished` 等宿主事件。未知事件 ID 会被拒绝，重复的同名订阅只保留一份。

### 实验字段定义

- `selection`: `{ text, startLine, startColumn, endLine, endColumn }`
- `diagnostics`: 列表项当前包含 `{ fileUri, fileName, line, column, endLine, endColumn, message, severity, source, code }`
- `contentType`: 由宿主编辑器当前内容类型直接透传，后续可能调整

## 运行隔离、自愈与资源边界

`apiVersion 1` 的公开 `tina.*` 语义保持不变，但脚本执行模型已经收紧：

- `script` / `hybrid` 的 Lua 与 LuaJava JNI 只在非导出的 `:plugin_runtime` isolated process 中执行；宿主进程没有 Lua fallback。
- 隔离进程不接收 `Context`、宿主文件对象或真实私有路径。Lua 源码通过只读 FD 传入，参数与结果只使用有界 JSON / 基础类型。
- `io`、`debug`、`loadfile`、`dofile`、`package.loadlib`、`java` 和 `luajava` 不可用。`require()` 只允许加载插件目录内由字母、数字、`_`、`-` 和 `.` 组成的纯 Lua 模块名；绝对路径、`..` 与 native module 会被拒绝。
- 每次 Host API 调用都会重新检查插件 generation、启用/隔离状态、manifest 声明和用户授权。文件访问仍受 Workspace 白名单约束，网络仍受 `networkHosts`、超时与限流约束。
- 纯 Lua 连续执行默认上限为 5 秒，单次调用总上限为 60 秒；纯 Lua 超时、资源越限或隔离进程死亡会终止并重建 runtime process，不会等待同步 JNI 返回。
- 插件进程总 PSS 上限为 192 MiB，单次调用增长上限为 64 MiB。Binder JSON 请求和普通响应上限为 256 KiB；Host API 的受支持大响应改走只读 FD，最大 8 MiB。Lua 回调直接返回的序列化结果若超过 256 KiB，runtime 会返回有界 `RESOURCE_LIMIT` 响应并隔离当前插件，不会尝试发送超大 Binder transaction。Network body 上限为 8 MiB。
- 插件包上限为 64 MiB，解压后上限为 256 MiB、4096 项；单项上限 64 MiB、压缩比上限 `100:1`；单个 Lua 文件上限 1 MiB，Lua 源码合计上限 8 MiB。
- 单条插件日志最多 8 KiB，每插件最多保留 1000 条并有限速；绝对私有路径与常见 Token 字段会脱敏。

新安装插件默认禁用。用户明确启用后，启动异常、未处理的事件/命令回调、超时、资源越限、runtime crash、无效贡献或成功启动后的 LSP 异常退出会使当前版本自动进入隔离状态。权限拒绝、普通网络失败、依赖未安装以及 toolchain/Linux 未就绪不会被归因为插件故障。

隔离只会在用户确认“重新启用”或安装严格更高版本后清除；升级采用同文件系统 staging/backup/atomic rename，并在停止旧 runtime 前同步写入安装 journal。进程中断后会恢复旧目录、启用状态和故障状态；journal 损坏时插件子系统 fail-closed。健康插件升级失败时回滚旧版本。

## 当前宿主保证

- `apiVersion != 1` 的插件会在安装/刷新阶段直接判定为无效。
- `script` / `hybrid` 插件如果缺少主脚本，会在安装/刷新阶段直接判定为无效。
- 主脚本执行失败不会再被错误地覆盖成 `ACTIVE`。
- 启动前会先审计残留的 in-flight journal，审计完成前不会由权限或插件状态 collector 抢先加载插件。
- disable / uninstall / upgrade 是可等待的串行状态操作；过期 generation 回调不能重新激活插件。
- 插件日志页支持按插件过滤，插件详情页支持直接重载和跳转日志。
