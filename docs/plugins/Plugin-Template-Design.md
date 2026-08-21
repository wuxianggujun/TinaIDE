# 插件模板插件设计方案

> 文档更新：2026-04-26
> 目标：为 TinaIDE 设计一套可直接交付给用户的“插件开发脚手架插件”，降低插件开发门槛，并严格贴合当前宿主已落地能力。

---

## 1. 结论先行

建议把“插件模板”做成一个 **`config` 类型插件**，通过
`contributions.projectTemplates` 向“新建项目向导”注入 4 个模板：

- `config-basic`：面向主题、代码片段、菜单、文件图标这类纯配置插件
- `script-command`：面向命令注册、菜单入口、编辑器写入这类命令型插件
- `script-basic`：面向 Lua 自动化、事件监听、工作区访问这类脚本插件
- `lsp-basic`：面向语言支持与工具链安装的 LSP 插件

不建议 v1 直接提供：

- `hybrid` 模板：概念比 `script` 更重，先不要把用户带进混合模式
- `system` 模板：这类插件更偏宿主内置能力，不适合作为普通用户入口
- `apkExport` 专用模板：受众窄，先保留在后续扩展

---

## 2. 为什么推荐做成“模板插件”

这个设计不是凭空想出来的，而是直接复用你当前宿主已经存在的能力：

- `PluginManager.listProjectTemplateOptions()` 已经会把启用插件里的
  `contributions.projectTemplates` 汇总出来
- `NewProjectWizardActivity` 已经把内置模板和插件模板合并到同一套
  项目模板列表，并可通过插件入口只展示插件模板
- `ProjectTemplateInstaller` 已支持从 zip 模板创建项目，并替换
  `{{PROJECT_NAME}}`、`{{PROJECT_NAME_UPPER}}` 这类占位符
- `PluginManager.validateManifest()` 已经会校验模板 zip 的路径和存在性

这意味着：

- **不需要复制另一套项目创建引擎**
- **不需要额外做模板下载器**
- **不需要单独维护另一套脚手架分发机制**

产品上可以提供“创建插件项目”快捷入口，但它只负责传入插件项目语境，
底层仍复用同一套模板解析、解压、占位符替换和项目元数据写入链路。

按 KISS 和 DRY 来看，这是当前成本最低、成功率最高的方案。

---

## 3. 推荐的产品形态

### 3.1 插件名称

建议名称：

- 显示名：`TinaIDE Plugin Starters`
- 插件 ID：`tinaide.plugin.starters`

### 3.2 用户体验路径

推荐的完整路径：

1. 用户安装并启用 `TinaIDE Plugin Starters`
2. 从插件教程或设置页点击“创建插件项目”
3. 进入“新建插件项目”语境，只看到 4 个插件模板
4. 选择一个模板后生成插件工程目录
5. 修改 `manifest.json` 与示例资源
6. 点击 IDE 顶部“运行”
7. IDE 自动完成校验、打包，并热安装到当前 TinaIDE

如果用户从项目页右下角 **+** 进入，则仍是通用新建项目入口；
这时需要在模板列表里手动选择带“插件”标识的模板。

模板里仍然保留 `validate.ps1` / `validate.sh`、`pack.ps1` / `pack.sh`。
它们用于离线打包、CI 校验和把 `.tinaplug` 分发给其他用户；IDE 内开发时，
“运行”按钮就是最短闭环。

安装后默认 **不需要重启 IDE**。纯 `config` 模板插件首次安装会自动启用，因此项目模板、
菜单和主题可以立即出现；`script`、`hybrid`、`lsp`、`system` 等插件首次安装仍需在详情页
明确启用。升级保留原有启用意图。插件管理器会刷新安装态和启用态；脚本、LSP、菜单、
主题等消费方应监听插件状态并热更新。极少数缓存场景只需要重开相关
编辑器或重新触发能力，不应要求用户重启整个 IDE。

这条路径最大的优点是：**用户始终待在 IDE 已有工作流里**。

---

## 4. 模板插件本体结构

推荐目录：

```text
tinaide.plugin.starters/
├── manifest.json
├── README.md
└── templates/
    ├── tina-config-plugin.zip
    ├── tina-script-command-plugin.zip
    ├── tina-script-plugin.zip
    └── tina-lsp-plugin.zip
```

推荐的 `manifest.json`：

```json
{
  "id": "tinaide.plugin.starters",
  "name": "TinaIDE Plugin Starters",
  "version": "1.0.0",
  "type": "config",
  "description": "Starter templates for TinaIDE plugin development.",
  "author": {
    "name": "TinaIDE Team"
  },
  "contributions": {
    "projectTemplates": [
      {
        "id": "config-basic",
        "name": "Tina Config Plugin",
        "description": "Theme/snippet/menu/file-icon plugin starter.",
        "templatePath": "templates/tina-config-plugin.zip",
        "buildSystem": "plugin",
        "primaryLanguage": "MIXED"
      },
      {
        "id": "script-command",
        "name": "Tina Script Command Plugin",
        "description": "Lua command plugin starter with editor actions and menus.",
        "templatePath": "templates/tina-script-command-plugin.zip",
        "buildSystem": "plugin",
        "primaryLanguage": "MIXED"
      },
      {
        "id": "script-basic",
        "name": "Tina Script Plugin",
        "description": "Lua automation and event plugin starter.",
        "templatePath": "templates/tina-script-plugin.zip",
        "buildSystem": "plugin",
        "primaryLanguage": "MIXED"
      },
      {
        "id": "lsp-basic",
        "name": "Tina LSP Plugin",
        "description": "Language server plugin starter.",
        "templatePath": "templates/tina-lsp-plugin.zip",
        "buildSystem": "plugin",
        "primaryLanguage": "MIXED"
      }
    ]
  }
}
```

### 4.1 为什么这里用 `plugin`

插件模板创建出来的是“插件源码工程”，不是 C/C++ 单文件工程，也不是
CMake/Make 工程。所以 `projectTemplates.buildSystem` 应声明为：

- `plugin`

宿主会用根目录 `manifest.json` 二次识别插件工程；即使旧模板曾写入
`single_file` 元数据，也会在打开项目时迁移为 `PLUGIN` 构建系统。

在这个构建系统下：

- **构建** = 校验 + 打包 `.tinaplug`
- **运行** = 校验 + 打包 + 热安装到当前 IDE
- **调试** = 暂不支持，后续再接脚本日志 / LSP 调试体验

### 4.2 为什么这里用 `MIXED`

当前 `ProjectLanguage` 并没有 `JSON` / `LUA` 这样的枚举项，插件工程又往往
包含 `manifest.json`、`README.md`、脚本、图标、配置文件的混合内容，所以
`primaryLanguage = "MIXED"` 是最稳妥的取值。

---

## 5. 四个模板该怎么设计

### 5.1 Config 模板

定位：

- 面向第一次写插件的用户
- 不引入运行时权限
- 聚焦“声明式扩展”

推荐目录：

```text
{{PROJECT_NAME}}/
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

建议默认演示这些已落地能力：

- `contributions.themes`
- `contributions.snippets`
- `contributions.commands`
- `contributions.menus["filetree/context"]`
- `contributions.menus["editor/context"]`
- `contributions.menus["editor/toolbar"]`
- `contributions.keybindings`
- `contributions.fileIcons`
- `contributions.panels`（适合 script / hybrid 模板按需演示）

`keybindings` 和 `panels` 都已可用，但会增加第一版模板的学习成本；基础模板可在 README 中说明，
由需要快捷键或底部文本面板的插件作者按需启用。

推荐示例 `manifest.json`：

```json
{
  "id": "com.example.{{PROJECT_NAME}}",
  "name": "{{PROJECT_NAME}}",
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
    ],
    "commands": [
      {
        "id": "file.copyPath",
        "title": "Copy Path"
      }
    ],
    "menus": {
      "filetree/context": [
        {
          "command": "file.copyPath",
          "group": "9_plugin",
          "when": "isFile"
        }
      ],
      "editor/context": [
        {
          "command": "editor.format",
          "group": "9_plugin",
          "when": "isDirty"
        }
      ]
    },
    "fileIcons": [
      {
        "icon": "icons/starter-file.svg",
        "extensions": [
          "tinaplugin"
        ],
        "priority": 10
      }
    ]
  }
}
```

### 5.2 Script Command 模板

定位：

- 面向“需要命令入口和编辑器操作”的用户
- 优先跑通 `commands.register + menus + editor.write`
- 作为第一个脚本插件的推荐入口

基于当前源码现状，更稳妥的发布策略是：

- 为 `script-command` 明确标注权限、隔离和资源限制
- 等宿主把脚本插件的公开加载链路补齐后，再把它提升为正式模板

原因是脚本运行时、权限和 API 模块已经存在，但仓库里暂时看不到一个像
LSP 插件那样清晰的公开加载入口。

推荐目录：

```text
{{PROJECT_NAME}}/
├── manifest.json
├── README.md
├── pack.ps1
├── pack.sh
├── main.lua
└── docs/
    └── permissions.md
```

推荐默认示例权限：

- `ui.notification`
- `editor.read`
- `editor.selection`
- `editor.write`
- `command.execute`

把下面这些留在注释或 README 里作为“按需开启”：

- `diagnostics.read`
- `workspace.read`
- `workspace.write`
- `network.fetch`

推荐示例 `manifest.json`：

```json
{
  "id": "com.example.{{PROJECT_NAME}}",
  "name": "{{PROJECT_NAME}}",
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
        "id": "com.example.{{PROJECT_NAME}}.toggleFileTree",
        "title": "Toggle File Tree"
      },
      {
        "id": "com.example.{{PROJECT_NAME}}.insertHeader",
        "title": "Insert Starter Header"
      },
      {
        "id": "com.example.{{PROJECT_NAME}}.wrapSelection",
        "title": "Wrap Selection"
      }
    ],
    "menus": {
      "editor/context": [
        {
          "command": "com.example.{{PROJECT_NAME}}.insertHeader",
          "group": "5_editor"
        },
        {
          "command": "com.example.{{PROJECT_NAME}}.wrapSelection",
          "group": "5_editor"
        },
        {
          "command": "com.example.{{PROJECT_NAME}}.toggleFileTree",
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

推荐示例 `main.lua`：

```lua
local function comment_prefix_for(language_id)
  if language_id == "python" or language_id == "shell" or language_id == "yaml" then
    return "#"
  end
  return "//"
end

function on_toggle_file_tree()
  local ok, err = tina.commands.execute("view.toggleFileTree")
  if not ok and err ~= nil then
    tina.log.warn("Toggle file tree failed: " .. tostring(err))
  end
end

function on_insert_header()
  local editor = tina.editor.getActiveEditor()
  if editor == nil then
    tina.log.warn("No active editor")
    return
  end

  local prefix = comment_prefix_for(editor.languageId)
  tina.editor.insertText(prefix .. " Generated by {{PROJECT_NAME}}\n", 0, 0)
end

function on_wrap_selection()
  local selection = tina.editor.getSelection()
  if selection ~= nil and selection.text ~= "" then
    tina.editor.replaceSelection("// BEGIN\n" .. selection.text .. "\n// END")
  else
    tina.editor.insertText("// TODO: plugin command ran here\n")
  end
end

tina.commands.register(
  "com.example.{{PROJECT_NAME}}.toggleFileTree",
  "on_toggle_file_tree",
  "Toggle File Tree"
)
tina.commands.register(
  "com.example.{{PROJECT_NAME}}.insertHeader",
  "on_insert_header",
  "Insert Starter Header"
)
tina.commands.register(
  "com.example.{{PROJECT_NAME}}.wrapSelection",
  "on_wrap_selection",
  "Wrap Selection"
)
```

这个模板要重点体现的不是“功能很多”，而是：

- 如何声明 `main`
- 如何最小化权限
- 如何注册插件命令
- 如何转发宿主命令
- 如何读取活动编辑器快照
- 如何直接修改当前编辑器内容

### 5.3 Script Automation 模板

定位：

- 面向“需要事件监听、工作区扫描、诊断快照”的用户
- 保留 `script-basic` 作为综合示例，而不是第一个脚本模板
- 适合作为 `script-command` 之后的第二步

推荐默认示例权限：

- `ui.notification`
- `editor.read`
- `editor.selection`
- `editor.write`
- `diagnostics.read`
- `workspace.read`
- `command.execute`

这个模板要重点体现：

- 如何监听宿主事件
- 如何读取诊断快照
- 如何使用 `tina.workspace.*`
- 如何在自动化场景里组合命令、日志和编辑器 API

### 5.4 LSP 模板

定位：

- 面向语言支持插件作者
- 让用户从“改 6 个关键字段”开始，而不是从零拼整份 manifest

推荐目录：

```text
{{PROJECT_NAME}}/
├── manifest.json
├── README.md
├── pack.ps1
└── pack.sh
```

推荐示例 `manifest.json`：

```json
{
  "id": "tinaide.lsp.{{PROJECT_NAME}}",
  "name": "{{PROJECT_NAME}} Language Support",
  "version": "0.1.0",
  "type": "lsp",
  "description": "Starter LSP plugin for TinaIDE.",
  "author": {
    "name": "Your Name"
  },
  "contributions": {
    "languageServers": [
      {
        "id": "{{PROJECT_NAME}}-server",
        "name": "{{PROJECT_NAME}} Language Server",
        "languages": [
          "replace-me"
        ],
        "fileExtensions": [
          "replace-me"
        ],
        "server": {
          "type": "stdio",
          "command": "replace-me"
        },
        "capabilities": {
          "completion": true,
          "hover": true,
          "definition": true,
          "references": true,
          "documentSymbol": true
        }
      }
    ],
    "toolchains": [
      {
        "id": "{{PROJECT_NAME}}-runtime",
        "name": "Replace Runtime",
        "type": "system",
        "packages": [
          "replace-runtime"
        ],
        "required": true,
        "verifyCommand": "replace-runtime --version",
        "verifyPattern": ".+"
      }
    ]
  },
  "activationEvents": [
    "onLanguage:replace-me"
  ]
}
```

这个模板里不要默认放具体语言生态的强绑定内容。先给一个“待替换骨架”，
再在 README 里提供 Python / Node / Rust 三种填写示例更合适。

---

## 6. 模板工程里必须自带的文件

每个 starter 都建议自带这些基础文件：

- `README.md`：写清楚这个模板当前覆盖哪些能力、如何打包、如何安装
- `pack.ps1`：Windows 一键打包
- `pack.sh`：Linux/macOS 一键打包
- `.gitignore`：忽略 `dist/`、`.idea/`、临时文件

推荐 `.gitignore`：

```gitignore
dist/
.idea/
.vscode/
*.log
```

---

## 7. 推荐把哪些“源码真实约束”写进模板说明

这些约束必须在模板文档里说透，否则用户很容易误判宿主能力边界：

- zip 根目录必须直接包含 `manifest.json`
- `manifest.id` 只能使用字母、数字、`.`、`_`、`-`
- 所有资源路径必须是插件根目录下的相对路径，不能包含 `..`
- `filetree/context` 和 `editor/context` 的 `when` 目前只支持少量固定表达式
- 菜单命令必须是宿主内置命令，或当前插件运行时已注册的插件命令，否则会被忽略
- 脚本插件需要权限确认，权限越少越好
- LSP 插件要同时考虑服务器配置和工具链验证命令

---

## 8. 模板插件 v1 的范围边界

v1 只做下面这些：

- 一个模板插件
- 四个 starter zip
- `config-basic`、`lsp-basic`、`script-command`、`script-basic` 均使用 apiVersion 1 稳定契约
- 每个 starter 各自的 `README` 和打包脚本
- 文档里明确区分“已实现能力”和“保留字段”

v1 不做：

- 在线生成器
- 图形化 manifest 编辑器
- 插件发布向导
- 自动上传 Registry
- `hybrid`/`system` 模板

这条边界很重要。先把“能让用户 10 分钟做出第一个插件”跑通，再做高级体验。

---

## 9. 建议的后续演进

当前已落地：

- 插件模板隐藏 C++ 标准等无关配置
- 插件教程 / 设置页可通过显式入口进入“新建插件项目”语境

如果 v1 跑通，下一步再考虑：

1. 为模板工程增加更完整的 IDE 内诊断提示
2. 增加 `hybrid` 进阶模板
3. 为插件 manifest 增加更强的本地化与 schema 校验能力
4. 插件项目创建完成后自动打开 `manifest.json`

---

## 10. 最终建议

如果你现在要做第一版，我建议只落一个产品包：

- `TinaIDE Plugin Starters`

它内部提供四种模板：

- `Tina Config Plugin`：正式
- `Tina Script Command Plugin`：正式
- `Tina Script Plugin`：正式
- `Tina LSP Plugin`：正式

这样设计最符合你当前源码现状，也最容易在后续真正落成一个可安装插件，
而不是停留在“文档建议”层面。
