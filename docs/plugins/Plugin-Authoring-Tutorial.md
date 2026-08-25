# TinaIDE 插件编写教程（基于模板）

> 文档更新：2026-07-15
> 适用对象：第一次为 TinaIDE 编写插件的开发者
> 说明：开始前请先从插件市场 / Registry 安装并启用 `TinaIDE Plugin Starters`；如果你是第一次写插件，先做 `config` 插件，先把主题和代码片段跑通。

---

## 1. 先选插件类型，不要一上来就写脚本

开始之前，先判断你到底要哪一类插件：

- **配置插件 `config`**
  适合主题、代码片段、菜单、文件图标、项目模板这类“声明式扩展”
- **脚本插件 `script`**
  适合编辑器自动化、命令扩展、事件响应、简单工具集成
- **LSP 插件 `lsp`**
  适合给某个语言接入补全、诊断、跳转定义、格式化

推荐选择顺序：

1. 能用 `config` 解决，就不要上 `script`
2. 能用 `script` 解决，就不要先做 `hybrid`
3. 只有做语言服务时，再选 `lsp`

---

## 2. 用模板创建你的第一个插件工程

当模板插件就绪后，推荐从插件教程或设置页的“创建插件项目”快捷入口开始。
如果你第一次写插件，先选 `Tina Config Plugin`，本教程后面的最小示例也是按它写的：

1. 打开 TinaIDE
2. 进入插件教程，点击“创建插件项目”
3. 确认向导标题是“新建插件项目”
4. 选择以下模板之一：
   - `Tina Config Plugin`
   - `Tina Script Command Plugin`
   - `Tina Script Plugin`
   - `Tina LSP Plugin`
5. 输入项目名
6. 创建项目

第一次写插件时，优先只做 `Tina Config Plugin`。`script` 和 `lsp` 放在后面章节，不要一上来就把权限和运行时复杂度拉高。

如果你是从项目页右下角 **+** 手动进入，则仍会打开通用“新建项目”向导；
这时请主动选择带“插件”标识的模板，不要停留在默认 C/C++ 模板。

### 2.1 插件入口和通用入口有什么区别

插件项目模板当前复用宿主的两步式项目向导，而不是单独复制一套创建逻辑。
因此你仍会看到“选择模板 → 填写配置 → 创建项目”的流程。

从插件教程快捷入口进入时，宿主会直接进入插件项目创建语境，
标题显示为“新建插件项目”，只展示插件项目模板，并优先定位到已安装的 starter 模板，
避免用户落到普通 C/C++ 模板列表。

如果这里显示“暂无插件项目模板”，优先检查 `TinaIDE Plugin Starters` 是否已安装并启用。

插件模板应声明 `buildSystem: "plugin"` 与 `primaryLanguage: "MIXED"`。
这样配置页不会显示 C++ 标准这类无关字段。

排查细节见：[Plugin-Project-Template-Wizard-Troubleshooting.md](Plugin-Project-Template-Wizard-Troubleshooting.md)。

---

## 3. 创建后先做的第一件事：改 `manifest.json`

无论你选的是哪种模板，第一步都应该先改 `manifest.json`。

至少要检查这些字段：

- `id`：插件唯一标识，建议用反向域名风格
- `name`：插件显示名称
- `version`：建议从 `0.1.0` 开始
- `apiVersion`：当前稳定值为 `1`，省略时按 `1` 处理
- `minAppVersion`：只有确实依赖新宿主能力时才填写最低 TinaIDE 版本
- `type`：必须和模板类型一致
- `description`：一句话说明用途
- `author.name`：作者名

一个安全的 `id` 例子：

```json
{
  "id": "com.example.my-first-plugin",
  "name": "My First Plugin",
  "version": "0.1.0",
  "apiVersion": 1,
  "type": "config",
  "description": "My first TinaIDE plugin.",
  "author": {
    "name": "Your Name"
  }
}
```

`minAppVersion` 是兼容门禁，不是普通展示字段。省略它时，历史插件继续兼容；填写后，
旧 IDE 的 Registry 视图不会展示该版本，宿主安装、启用和运行时也会再次校验。不要为了
“跟随最新版”随意抬高它；只有插件真正使用了该宿主版本新增的能力时才更新并提升插件版本。

### 3.1 `id` 的约束

`manifest.id` 不是随便写的。当前宿主对它有明确约束：

- 不能为空
- 只能包含字母、数字、`.`、`_`、`-`
- 不能包含 `..`
- 不能包含路径分隔符

所以不要把文件路径、中文空格、URL 直接拿来当插件 ID。

---

## 4. 如果你写的是 `config` 插件

### 4.1 你最适合从哪种能力开始

建议顺序：

1. 先做主题或代码片段
2. 再做菜单扩展
3. 最后再加文件图标或项目模板

原因很简单：主题和片段最好验证，也最不容易踩命令注册、权限和上下文菜单条件问题。

### 4.2 推荐目录

```text
my-config-plugin/
├── manifest.json
├── README.md
├── pack.ps1
├── pack.sh
├── themes/
│   └── starter-theme.json
├── snippets/
│   └── starter-snippets.json
└── icons/
    └── starter-file.svg
```

### 4.3 最小示例

```json
{
  "id": "com.example.my-config-plugin",
  "name": "My Config Plugin",
  "version": "0.1.0",
  "type": "config",
  "description": "Starter config plugin for TinaIDE.",
  "author": {
    "name": "Your Name"
  },
  "contributions": {
    "themes": [
      "themes/starter-theme.json"
    ],
    "snippets": [
      "snippets/starter-snippets.json"
    ]
  }
}
```

### 4.4 菜单扩展要注意什么

菜单扩展不是写进 manifest 就一定显示。当前宿主有两个硬约束：

- `command` 必须是宿主内置支持的命令，或当前插件运行时已注册的插件命令
- `when` 只支持很少的固定表达式

对纯 `config` 插件来说，没有 Lua 运行时可以调用 `tina.commands.register(...)`，
所以菜单通常只能绑定宿主内置命令，例如 `file.copyPath`、`editor.save`、`view.toggleFileTree`。

对 `script` / `hybrid` 插件来说，可以在 `manifest.json` 里声明菜单，
再在 `main.lua` 中注册同一个命令 ID。前提是插件声明了 `command.execute` 权限，
并且运行时成功执行了 `tina.commands.register(...)`。

这里要特别区分三件事：

- `contributions.commands`：声明命令标题，用于菜单显示，不等于已经有可执行逻辑
- 宿主内置命令：稳定 ID 由 `HostCommands` 暴露，元数据以 `HostCommandCatalog` 为单一来源，配置插件可以直接引用白名单命令
- 插件运行时命令：由脚本在加载时调用 `tina.commands.register(...)` 注册

菜单项最终会按“宿主内置命令 → 当前插件已注册命令”的顺序判断能否显示。
如果两个都不命中，菜单项会被忽略。
`editor/toolbar` 菜单项除了显示在编辑器标签栏右侧插件动作菜单，也会进入主编辑器命令面板。
主界面内部命令不是插件 API，例如 `project.rebuildRun`、`project.debug`、`project.packageApk`、`project.cmake.*`、`view.split.*`、`view.globalSearch`。这些命令只服务当前 Activity 的 UI 编排，插件不要直接依赖。

文件树菜单常用的 `when`：

- `isDirectory`
- `isFile`
- `!isDirectory`
- `!isFile`

编辑器上下文菜单常用的 `when`：

- `isDirty`
- `!isDirty`

如果菜单不显示，先检查命令是否可解析、`when` 是否命中当前上下文，
而不是先怀疑安装链路。

---

## 5. 如果你写的是 `script` 插件

脚本插件仍属于进阶能力，但 Lua 已固定运行在独立的 isolated process 中；
死循环、Lua/JNI crash 和资源越限不会直接拖垮宿主进程。

所以更稳妥的发布建议是：

- 明确脚本模板受 isolated process、权限与资源上限约束
- 先面向高级用户或内部测试开放
- 不依赖 `io`、`debug`、`loadfile/dofile`、native `loadlib` 或 Java/luajava 反射
- 多文件脚本只用受限 `require("module.name")` 加载插件目录内的 `.lua` 文件
- 把自动隔离、重新启用确认和资源上限纳入测试

### 5.1 先选哪一个脚本模板

当前脚本模板已经拆成两个方向：

- `Tina Script Command Plugin`
  适合命令注册、编辑器上下文菜单、活动编辑器读写
- `Tina Script Plugin`
  适合事件监听、工作区读写、诊断快照、自动化流程

如果你现在的目标是“先做出一个能点、能写、能调试的 IDE 命令插件”，
优先选 `Tina Script Command Plugin`。

### 5.2 先从“最小权限”开始

脚本插件的正确姿势不是把权限一次性全开，而是只申请当前需要的权限。

命令型 starter 的一个好起点通常是：

- `ui.notification`
- `editor.read`
- `editor.selection`
- `editor.write`
- `command.execute`

把下面这些作为第二阶段再开启：

- `diagnostics.read`
- `workspace.read`
- `workspace.write`
- `network.fetch`

### 5.3 推荐目录

```text
my-script-plugin/
├── manifest.json
├── README.md
├── pack.ps1
├── pack.sh
├── main.lua
└── docs/
    └── permissions.md
```

### 5.4 Starter 示例

`manifest.json`：

```json
{
  "id": "com.example.my-script-plugin",
  "name": "My Script Plugin",
  "version": "0.1.0",
  "type": "script",
  "description": "Command-focused Lua plugin starter for TinaIDE.",
  "author": {
    "name": "Your Name"
  },
  "main": "main.lua",
  "contributions": {
    "commands": [
      {
        "id": "com.example.my-script-plugin.toggleFileTree",
        "title": "Toggle File Tree"
      },
      {
        "id": "com.example.my-script-plugin.insertHeader",
        "title": "Insert Starter Header"
      },
      {
        "id": "com.example.my-script-plugin.wrapSelection",
        "title": "Wrap Selection"
      }
    ],
    "menus": {
      "editor/context": [
        {
          "command": "com.example.my-script-plugin.insertHeader",
          "group": "5_editor"
        },
        {
          "command": "com.example.my-script-plugin.wrapSelection",
          "group": "5_editor"
        },
        {
          "command": "com.example.my-script-plugin.toggleFileTree",
          "group": "9_plugin"
        }
      ]
    }
  },
  "permissions": [
    "ui.notification",
    "editor.read",
    "editor.selection",
    "editor.write",
    "command.execute"
  ]
}
```

`main.lua`：

```lua
local command_ids = {
  toggle_file_tree = "com.example.my-script-plugin.toggleFileTree",
  insert_header = "com.example.my-script-plugin.insertHeader",
  wrap_selection = "com.example.my-script-plugin.wrapSelection"
}

local function comment_prefix_for(language_id)
  if language_id == "python" or language_id == "shell" or language_id == "yaml" then
    return "#"
  end
  return "//"
end

local function active_editor_or_warn()
  local editor = tina.editor.getActiveEditor()
  if editor == nil then
    tina.log.warn("No active editor")
    return nil
  end
  return editor
end

function on_toggle_file_tree()
  local ok, err = tina.commands.execute("view.toggleFileTree")
  if ok then
    tina.log.info("Forwarded host command: view.toggleFileTree")
    return
  end
  tina.log.warn("Failed to execute host command: " .. tostring(err))
end

function on_insert_header()
  local editor = active_editor_or_warn()
  if editor == nil then
    return
  end

  local file_name = editor.fileName or "unknown"
  local prefix = comment_prefix_for(editor.languageId)
  local header = prefix .. " Generated by My Script Plugin for " .. file_name .. "\n"
  local ok = tina.editor.insertText(header, 0, 0)
  if ok then
    tina.log.info("Inserted starter header into " .. file_name)
  else
    tina.log.warn("insertText returned false")
  end
end

function on_wrap_selection()
  local editor = active_editor_or_warn()
  if editor == nil then
    return
  end

  local prefix = comment_prefix_for(editor.languageId)
  local selection = tina.editor.getSelection()
  local selected_text = selection and selection.text or ""
  if selected_text ~= "" then
    local replacement = prefix .. " BEGIN\n" .. selected_text .. "\n" .. prefix .. " END"
    local ok = tina.editor.replaceSelection(replacement)
    if ok then
      tina.log.info("Wrapped current selection")
    else
      tina.log.warn("replaceSelection returned false")
    end
    return
  end

  local ok = tina.editor.insertText(prefix .. " TODO: plugin command ran here\n")
  if ok then
    tina.log.info("Inserted fallback text at cursor")
  else
    tina.log.warn("Fallback insertText returned false")
  end
end

local function register_command(command_id, callback_name, title)
  local ok, err = tina.commands.register(command_id, callback_name, title)
  if ok then
    tina.log.info("Registered plugin command: " .. command_id)
    return
  end
  tina.log.warn("Register command failed: " .. tostring(err))
end

tina.log.info("My Script Plugin command starter loaded")
tina.ui.showMessage("My Script Plugin loaded")

register_command(command_ids.toggle_file_tree, "on_toggle_file_tree", "Toggle File Tree")
register_command(command_ids.insert_header, "on_insert_header", "Insert Starter Header")
register_command(command_ids.wrap_selection, "on_wrap_selection", "Wrap Selection")
```

### 5.5 当前脚本 API 可以怎么理解

当前脚本运行时已注册了几组宿主 API 命名空间，入口统一挂在 `tina.*` 下：

- `tina.editor`：读写编辑器文本、光标、选择区
- `tina.diagnostics`：读取当前诊断快照
- `tina.ui`：弹消息
- `tina.workspace`：在项目根目录内查找、读写文件
- `tina.fs`：历史兼容命名空间，新插件优先使用 `tina.workspace`
- `tina.commands`：注册插件命令，并执行宿主命令或已注册插件命令
- `tina.events`：监听宿主事件
- `tina.network`：网络请求
- `tina.storage` / `tina.db`：插件数据存储
- `tina.log`：插件日志

第一次写脚本插件时，建议先用：

- `tina.ui`
- `tina.editor`
- `tina.commands`

如果你只是验证“命令型插件链路”，先保留下面这些就够了：

- `tina.commands.register(...)`
- `tina.commands.execute(...)`
- `tina.editor.getActiveEditor()`
- `tina.editor.insertText(...)`
- `tina.editor.replaceSelection(...)`

如果你需要更完整的自动化能力，再切到 `Tina Script Plugin`，继续使用：

- `tina.events`
- `tina.diagnostics`
- `tina.workspace`

### 5.6 哪些问题最常见

如果脚本插件“安装成功但没反应”，优先检查：

1. `type` 是不是 `script`
2. `main` 指向的文件是否真实存在
3. 插件是否已经启用
4. 权限弹窗是否被拒绝
5. `contributions.menus` 里的命令 ID 是否和 `register()` 一致
6. 事件名是否写对

### 5.7 当前推荐监听的事件

下面这些事件更适合 `Tina Script Plugin` 这种自动化 starter：

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

示例：

```lua
function on_file_renamed(data)
  tina.log.info("Renamed: " .. data.oldPath .. " -> " .. data.newPath)
end

function on_selection_changed(data)
  if data.hasSelection then
    tina.log.info("Selected text length: " .. #data.selection.text)
  end
end

tina.events.on("file.renamed", "on_file_renamed")
tina.events.on("editor.selectionChanged", "on_selection_changed")
```

---

## 6. 如果你写的是 `lsp` 插件

### 6.1 先抓住两个核心对象

LSP 插件本质上只有两块：

- `languageServers`
- `toolchains`

前者定义“怎么启动语言服务器”，后者定义“宿主要先装什么”。

### 6.2 最小示例

```json
{
  "id": "tinaide.lsp.mylang",
  "name": "MyLang Language Support",
  "version": "0.1.0",
  "type": "lsp",
  "description": "Starter LSP plugin for TinaIDE.",
  "author": {
    "name": "Your Name"
  },
  "contributions": {
    "languageServers": [
      {
        "id": "mylang-server",
        "name": "MyLang Server",
        "languages": [
          "mylang"
        ],
        "fileExtensions": [
          "mlg"
        ],
        "server": {
          "type": "stdio",
          "command": "mylang-lsp"
        },
        "capabilities": {
          "completion": true,
          "hover": true,
          "definition": true
        }
      }
    ],
    "toolchains": [
      {
        "id": "mylang-lsp",
        "name": "MyLang LSP",
        "type": "npm",
        "packages": [
          "mylang-lsp"
        ],
        "required": true,
        "verifyCommand": "mylang-lsp --version",
        "verifyPattern": ".+"
      }
    ]
  },
  "activationEvents": [
    "onLanguage:mylang"
  ]
}
```

### 6.3 LSP 插件最容易写错的地方

优先检查这 5 项：

1. `fileExtensions` 是否和真实文件后缀一致
2. `command` 是否就是容器里最终可执行的命令
3. `toolchains.packages` 是否真的能安装
4. `verifyCommand` / `verifyPattern` 是否能成功命中
5. `activationEvents` 是否和语言、工程文件匹配

---

## 7. 运行、打包与你的插件

如果你是通过 `TinaIDE Plugin Starters` 创建的插件工程，推荐优先使用 IDE
顶部的构建/运行按钮：

- **构建**：校验 `manifest.json` 与资源路径，然后生成 `dist/<id>-<version>.tinaplug`
- **运行**：校验、打包，并热安装到当前 TinaIDE
- **调试**：插件项目暂不接入断点调试；脚本插件先通过日志和宿主 API 验证

安装后默认 **不需要重启 IDE**。新安装的纯 `config` 插件会自动启用；`script`、`hybrid`、
`lsp`、`system` 等插件必须先在详情页明确启用。升级会保留原有启用意图。宿主会刷新
插件列表和启用态：

- 配置类贡献（主题、片段、菜单、文件图标）会随插件状态刷新
- 脚本 / LSP 这类有运行时的插件，会由对应管理器按启用态同步
- 如果某个已打开编辑器缓存了旧状态，通常只需要重开相关文件或重新触发能力

也就是说，开发插件时最常用的闭环应该是：

```text
修改 manifest / 资源 / Lua
        ↓
点击“运行”
        ↓
IDE 校验 + 打包 + 热安装
        ↓
纯 config 插件立即生效；可执行插件到详情页确认权限并启用
        ↓
看插件日志 / 菜单 / 主题 / LSP 是否生效
```

### 7.1 手工打包

最简单的规则只有一条：

- **压缩包根目录必须直接包含 `manifest.json`**

手工打包主要用于离线分发，或你想把 `.tinaplug` 发给其他用户时使用。

PowerShell：

```powershell
Compress-Archive -Path .\* -DestinationPath .\my-plugin.tinaplug
```

Linux / macOS：

```bash
zip -r ./my-plugin.tinaplug .
```

### 7.2 推荐把打包命令固化成脚本

建议模板默认带一个能排除 `dist/` 的 `pack.ps1`：

```powershell
$root = Resolve-Path .
$manifest = Get-Content (Join-Path $root "manifest.json") -Raw | ConvertFrom-Json
$distDir = Join-Path $root "dist"
$stagingDir = Join-Path $root ".pack"
$outFile = Join-Path $distDir ("{0}-{1}.tinaplug" -f $manifest.id, $manifest.version)

if (Test-Path $stagingDir) {
    Remove-Item $stagingDir -Recurse -Force
}

New-Item -ItemType Directory -Force -Path $distDir | Out-Null
New-Item -ItemType Directory -Force -Path $stagingDir | Out-Null

Get-ChildItem $root -Force | Where-Object {
    $_.Name -notin @("dist", ".pack")
} | ForEach-Object {
    Copy-Item $_.FullName -Destination $stagingDir -Recurse -Force
}

if (Test-Path $outFile) {
    Remove-Item $outFile -Force
}

Compress-Archive -Path (Join-Path $stagingDir "*") -DestinationPath $outFile
Remove-Item $stagingDir -Recurse -Force
Write-Host "Packed to $outFile"
```

---

## 8. 安装与验证

开发阶段优先点击“运行”热安装；如果你拿到的是别人发来的 `.tinaplug`，
再使用文件安装入口：

1. 设置
2. 插件
3. 从文件安装
4. 选择 `.tinaplug`

从文件安装前会做同一套预检：`manifest`、权限、命令、菜单、资源路径、
`networkHosts` 等有错误会阻断安装，有警告会提示后允许继续。

安装后建议按下面顺序验证：

新安装插件不会自动启动。先进入详情页检查权限和状态，再主动启用。若详情页显示“已自动隔离”，先查看脱敏故障阶段和时间；只有确认风险后才重新启用。

### 8.1 Config 插件验证

- 主题是否出现在列表里
- 片段是否能触发补全
- 菜单是否在正确上下文出现
- 文件图标是否生效

### 8.2 Script 插件验证

- 插件是否处于启用状态
- 首次加载是否弹权限确认
- 执行相关操作时是否有消息或日志输出

### 8.3 LSP 插件验证

- 工具链安装流程是否开始
- 安装后验证命令是否通过
- 打开目标语言文件后是否触发语言服务

---

## 9. 排错清单

### 9.1 安装失败

优先检查：

- zip 根目录是否有 `manifest.json`
- manifest 是否是合法 JSON
- 资源文件路径是否写错
- 路径是否包含 `..`

### 9.2 配置插件没生效

优先检查：

- `contributions` 路径是否真实存在
- 纯 `config` 菜单命令是否是宿主内置支持的命令
- 如果改成 `script` / `hybrid` 命令菜单，是否已在运行时注册同一个命令 ID
- `when` 表达式是否在当前支持范围内

### 9.3 脚本插件没生效

优先检查：

- `main.lua` 是否存在
- 权限是否被拒绝
- 事件名是否正确
- 插件日志里是否有加载错误

### 9.4 LSP 插件没生效

优先检查：

- 服务器命令是否可执行
- 工具链验证命令是否正确
- `activationEvents` 是否匹配
- 文件扩展名是否写对

---

## 10. 推荐开发顺序

如果你要给普通用户写教程，我建议把正文顺序固定成下面这样：

1. 先解释 3 种插件类型该怎么选
2. 再教用户用模板创建工程
3. 再教用户改 `manifest.json`
4. 再按 `config` / `script` / `lsp` 分支讲差异
5. 最后再讲打包、安装、排错

原因是这个顺序最符合真实开发节奏，也最容易减少“先看一堆高级内容，结果连第一个包都打不出来”的挫败感。

---

## 11. 给你这个项目的最终建议

如果这份教程要配套你即将做的模板插件一起发布，我建议：

- 教程正文主线先讲 `config` 和 `lsp`
- `script` 放到进阶章节，并明确权限、隔离与资源边界
- 所有示例都控制在“最小可运行”
- 高级能力放到补充文档，不要塞进入门教程

你当前的插件系统已经足够支撑这套入门链路。真正要控制的不是“功能够不够多”，
而是 **第一版教程能不能让用户稳定做出第一个插件**。
