# 插件开发者指南

> 文档更新：2026-07-14

当前仓库已具备“配置插件 + LSP 插件 + 脚本 / hybrid 插件”的基础闭环，
当前已支持：

- 安装/卸载/启用/禁用插件（基于 `manifest.json` + zip 解包）
- 插件管理 UI（设置 → 插件）
- 从插件中加载并切换 **编辑器主题**（`contributions.themes`）
- 代码片段、文件树菜单、编辑器 Tab 长按菜单、编辑器工具栏动作菜单
- 插件快捷键、依赖声明提示、插件配置 UI
- 项目模板（`contributions.projectTemplates`）
- 插件教程 / 设置页显式“新建插件项目”入口（只展示插件模板）
- APK 导出模板（`contributions.apkExports`）
- **LSP 插件**：通过插件安装语言服务器，提供代码补全、诊断等功能（`type: "lsp"`）
- **脚本 / hybrid 插件**：Lua 运行时、权限确认、日志与宿主 API 边界
- **插件文本面板**：manifest 声明、脚本发布内容、底部面板展示与生命周期清理
- 内置兜底插件（assets 自动安装；当前包含基础项目模板与 C/C++ snippets）

> 为满足 Google Play 合规性：当前仍不支持动态加载 DEX；脚本 / hybrid
> 插件走仓库内已集成的 Lua 运行时与权限确认流程，不再是“QuickJS 未来阶段”。

---

## 文档索引

| 文档 | 说明 |
|------|------|
| [Plugin-Roadmap.md](Plugin-Roadmap.md) | 插件系统路线图 |
| [Plugin-State-Model.md](Plugin-State-Model.md) | 插件状态模型与消费规则 |
| [Plugin-Template-Design.md](Plugin-Template-Design.md) | **插件模板插件设计方案** |
| [Plugin-Project-Template-Wizard-Troubleshooting.md](Plugin-Project-Template-Wizard-Troubleshooting.md) | 历史参考：插件项目模板向导排查记录 |
| [Plugin-Tutorial-Acceptance-Checklist.md](Plugin-Tutorial-Acceptance-Checklist.md) | 插件教程端到端验收清单 |
| [Plugin-Tutorial-Maintenance-Log.md](Plugin-Tutorial-Maintenance-Log.md) | 历史参考：插件教程维护记录与设计决策 |
| [Plugin-Authoring-Tutorial.md](Plugin-Authoring-Tutorial.md) | **插件编写教程（基于模板）** |
| [Plugin-API-Guide.md](Plugin-API-Guide.md) | **插件 API 指南（apiVersion 1 稳定边界）** |
| [LSP-Plugin-Development-Guide.md](LSP-Plugin-Development-Guide.md) | **LSP 插件开发指南**（新） |
| [Plugin-Marketplace-Troubleshooting.md](Plugin-Marketplace-Troubleshooting.md) | 插件市场 Registry 安装与更新排障 |

---

## 路线图

见：`docs/plugins/Plugin-Roadmap.md`

## 快速开始

优先使用 IDE 内的 **新建插件项目** 入口，不再把手工创建目录作为第一步。
如果你第一次写插件，先做 `config-basic`，先把主题和代码片段跑通，再碰脚本和 LSP。

### 1. 打开新建插件项目向导

推荐入口：

- 插件教程顶部快捷操作：`创建插件项目`
- 设置 → 插件 → `创建插件项目`

预期行为：

- 向导标题显示 `新建插件项目`
- 模板列表只展示插件项目模板
- 默认优先定位到已安装的 starter 模板

如果从项目页右下角 `+` 进入，仍是通用“新建项目”向导；
这条路径只作为手动兜底，需要主动选择带插件标识的模板。

### 2. 选择模板并创建工程

`TinaIDE Plugin Starters` 需要先从插件市场 / Registry 安装并启用。当前提供：

- `config-basic`：第一次写插件先选它，适合主题、片段、菜单等声明式扩展
- `script-command`：脚本命令插件，适合先验证菜单命令闭环
- `script-basic`：脚本插件基础工程，适合逐步接入宿主 API
- `lsp-basic`：LSP 插件基础工程，适合语言服务集成

创建后，项目根目录必须包含 `manifest.json`；
模板元数据应声明 `buildSystem: "plugin"`。

### 3. 修改 `manifest.json`

先把模板中的 `id`、`name`、`version`、`type` 和贡献项改成你的插件信息。
第一次写插件时，先保留最小的 `themes` + `snippets`，不要一上来就加脚本和权限。

```json
{
  "id": "com.example.my-first-plugin",
  "name": "My First Plugin",
  "version": "0.1.0",
  "type": "config",
  "description": "My first TinaIDE plugin.",
  "author": {
    "name": "Your Name"
  },
  "contributions": {
    "themes": [
      "themes/my-theme.json"
    ],
    "snippets": [
      "snippets/my-snippets.json"
    ]
  }
}
```

`id` 只能包含字母、数字、`.`、`_`、`-`，不能是路径，也不能带 `..`。

配置 key 必须匹配 `^[A-Za-z0-9][A-Za-z0-9._-]*$`。宿主会按插件 ID 隔离保存配置；
脚本 / hybrid 插件可通过 `tina.config.get/set/reset` 读取和更新自己声明过的配置项，并可监听
`config.changed` 响应自身配置变化。

### 4. 运行热安装

在插件项目中点击 `运行`：

- 校验 `manifest.json` 和资源路径
- 打包生成 `dist/<id>-<version>.tinaplug`
- 热安装到当前 TinaIDE

首次安装后插件保持禁用，需在“设置 → 插件”详情页检查权限并明确启用；升级保留原有启用意图。安装或刷新列表本身不会执行新插件。

点击 `构建` 时只生成 `.tinaplug`，不执行热安装。

### 5. 手工兜底结构

只有在 starter 模板不可用时，才建议手工创建最小目录：

```
my-plugin/
├── manifest.json
└── themes/
    └── my-theme.json
```

手工打包时，压缩包根目录必须直接包含 `manifest.json`。

---

## 当前能力边界

- `contributions.themes`：主题（编辑器 `EditorColorScheme` 颜色映射）
- `contributions.menus["filetree/context"]`：文件树右键菜单扩展（宿主命令 + 当前插件已注册命令）
- `contributions.menus["editor/context"]`：编辑器 Tab 长按菜单扩展（宿主命令 + 当前插件已注册命令）
- `contributions.menus["editor/toolbar"]`：编辑器标签栏右侧插件动作菜单（宿主命令 + 当前插件已注册命令）
- `contributions.keybindings`：快捷键扩展（JSON 文件声明，宿主命令 + 当前插件已注册命令；用户自定义/内置快捷键优先）
- `contributions.snippets`：代码片段（补全列表显示 + Snippet 插入）
- `contributions.projectTemplates`：新建项目模板（插件携带 zip 模板资源）
- `contributions.apkExports`：APK 导出模板扩展（插件携带模板 APK，宿主负责通用打包逻辑）
- `configuration`：插件配置 schema（宿主在插件详情页自动生成设置 UI）
- `manifest.type = "script" / "hybrid"`：Lua 脚本运行时（需权限确认；不支持 DEX）
- `contributions.panels`：脚本 / hybrid 文本面板；内容由 `tina.panels.*` 发布，在编辑器底部“插件”面板展示
- 插件安装/卸载/启用/禁用（本地目录）

> 备注（与源码同步）：`editor/toolbar` 已接入编辑器标签栏右侧插件动作菜单，
> 同时会进入主编辑器命令面板；`keybindings` 已接入 MainActivity 硬件键盘快捷键分发。
> 宿主内部统一使用 `ResolvedPluginCommand` 表示已解析的插件命令，包含 `pluginId`、`pluginName`、
> `commandId`、`group`、`surface` 与 `source`；旧 `ResolvedHostMenuItem` 仅作为菜单 UI 兼容层保留。

## 宿主内置命令总览

> 以下命令可被配置插件直接引用；宿主会在对应场景下执行，未知命令会被忽略。
> 源码事实：`HostCommandCatalog` 是宿主命令元数据单一来源，`HostCommands` 只保留稳定 ID 与查询代理。
> 插件校验、命令面板和内置快捷键都应从该目录派生；主界面内部命令不进入插件白名单。
> 不要在插件中依赖 `project.rebuildRun`、`project.debug`、`project.packageApk`、`project.cmake.*`、`view.split.*`、`view.globalSearch` 等 app-local 命令。

### 文件操作（11 个）

- `file.new`：新建文件（仅目录）
- `file.newFolder`：新建文件夹（仅目录）
- `file.rename`：重命名
- `file.delete`：删除
- `file.copyPath`：复制绝对路径
- `file.copyName`：复制文件名
- `file.copyRelativePath`：复制相对路径（相对项目根；不可用时回退绝对路径）
- `file.duplicate`：复制文件/目录
- `file.openWith`：用其他应用打开（文件；不支持目录）
- `file.share`：分享（文件/目录；目录会先导出 zip）
- `file.revealInFileManager`：在文件管理器中显示（定位到文件树并展开）

### 编辑器操作（32 个）

- `editor.save`：保存
- `editor.saveAll`：保存全部
- `editor.close`：关闭当前标签
- `editor.closeAll`：关闭全部标签
- `editor.closeOthers`：关闭其他标签
- `editor.nextTab`：切换到下一个标签
- `editor.previousTab`：切换到上一个标签
- `editor.undo`：撤销
- `editor.redo`：重做
- `editor.selectAll`：全选
- `editor.copy`：复制
- `editor.cut`：剪切
- `editor.paste`：粘贴
- `editor.find`：查找
- `editor.replace`：替换（Replace All）
- `editor.gotoLine`：跳转到行
- `editor.toggleWordWrap`：切换自动换行
- `editor.format`：格式化代码
- `editor.toggleComment`：切换注释（行注释）
- `editor.peekDefinition`：预览定义
- `editor.gotoDefinition`：跳转到定义
- `editor.findReferences`：查找引用
- `editor.gotoTypeDefinition`：跳转到类型定义
- `editor.gotoImplementation`：跳转到实现
- `editor.codeActions`：代码操作/快速修复
- `editor.renameSymbol`：重命名符号
- `editor.switchHeaderSource`：切换头文件/源文件
- `editor.toggleBookmark`：切换书签
- `editor.nextBookmark`：跳转到下一个书签
- `editor.previousBookmark`：跳转到上一个书签

### 终端操作（4 个）

- `terminal.toggle`：显示/隐藏终端（当前实现为打开终端 Activity）
- `terminal.new`：新建终端
- `terminal.openHere`：在此处打开终端
- `terminal.clear`：清空终端（执行 `clear`）

### 项目操作（5 个）

- `project.refresh`：刷新文件树
- `project.build`：构建项目（仅编译）
- `project.run`：运行项目
- `project.settings`：项目设置（运行配置）
- `project.close`：关闭项目

### 视图切换（6 个）

- `view.toggleFileTree`：显示/隐藏文件树
- `view.toggleSymbols`：显示/隐藏符号面板
- `view.toggleTerminal`：显示/隐藏终端（同 `terminal.toggle`）
- `view.commandPalette`：打开命令面板
- `view.bookmarks`：打开书签面板
- `view.settings`：打开设置

## 内置插件（assets）放置位置

宿主启动时会自动将 assets 内的“内置插件”安装到 `filesDir/plugins`，支持两种放置方式（二选一）：

- 目录形式：`app/src/main/assets/bundled_plugins/<pluginId>/manifest.json`
- 压缩包形式：`app/src/main/assets/bundled_plugins/<any>.tinaplug`（zip 内容根目录必须包含 `manifest.json`）

也支持使用 `app/src/main/assets/plugins` 作为目录别名（结构同上）。

## 文件树右键菜单扩展（阶段 1.5）

插件可以在 `manifest.json` 中声明：

- `contributions.commands`：为菜单项提供标题（可选；运行时未提供标题时优先使用这里的标题）
- `contributions.menus["filetree/context"]`：菜单项列表，`command` 支持宿主命令或当前插件已注册命令

### 支持的宿主内置命令

> 宿主内置支持的命令始终可直接引用；插件命令只有在当前插件运行时已注册后才会显示。

- `file.*`：支持全部 `file.*` 命令（详见“宿主命令总览”）

### `when`（最小支持）

当前仅支持以下写法（不支持复杂表达式；未知表达式会被忽略）：

- `isDirectory` / `isDirectory == true`
- `isFile` / `isFile == true`
- `!isDirectory` / `isDirectory == false`
- `!isFile` / `isFile == false`

## 编辑器 Tab 长按菜单扩展（阶段 1.5）

> 当前 `editor/context` 扩展点落在“编辑器标签页（Tab）长按上下文菜单”；后续可再扩展到“编辑器文本选择/光标右键菜单”。

manifest 键：

- `contributions.menus["editor/context"]`

当前支持绑定两类命令：

- 宿主内置命令
- 当前插件通过 `tina.commands.register()` 注册的命令

常见宿主命令用法：

- `editor.*`：保存、撤销、查找、格式化等
- `project.*`：构建/运行/项目设置等
- `view.*` / `terminal.*`：打开终端、切换视图等
- `file.*`：复制路径、分享等

`when`（最小支持）：

- `isDirty` / `isDirty == true`
- `!isDirty` / `isDirty == false`

## 代码片段（snippets）（阶段 1.5）

插件可以在 `manifest.json` 中声明：

- `contributions.snippets`: 片段文件路径列表（相对插件根目录）

片段文件格式（JSON）：

```json
{
  "language": "cpp",
  "snippets": [
    {
      "prefix": "fori",
      "name": "for (int i=0; i<n; i++)",
      "description": "常用 for 循环模板",
      "body": [
        "for (int i = 0; i < ${1:n}; i++) {",
        "  $0",
        "}"
      ]
    }
  ]
}
```

说明：

- `language`：语言标识（当前建议：`c` / `cpp` / `cmake`）
- `prefix`：触发前缀（输入后出现在补全列表中）
- `body`：多行文本；支持 `${1:default}`、`$0` 等 snippet 占位符

## 职责边界（重要）

为了避免与工具链/包管理逻辑重复、冲突，本项目建议保持以下边界：

- 插件系统：负责“安装/启用/禁用/卸载插件”和“把插件贡献注入到宿主预留扩展点（主题/菜单/模板等）”
- 工具链/包管理系统：负责“依赖下载、安装、升级、校验、镜像源、权限与回滚”等

插件 **不直接安装依赖库**。插件可以“声明依赖”，由宿主提示用户并跳转到现有安装流程执行。

插件状态的统一约束见：

- [Plugin-State-Model.md](Plugin-State-Model.md)

## 插件包格式

- 文件扩展名：`.tinaplug`（本质是 `.zip`）
- 根目录必须包含：`manifest.json`

目录示例：

```
my-plugin.tinaplug
├── manifest.json
└── themes/
    └── my-theme.json
```

## 示例插件

仓库不再额外保留示例插件目录。直接按本文给出的 `manifest.json` / `theme`
 / `snippet` 示例自行组织目录后打包即可。

## 主题文件格式（最小）

`themes/*.json` 当前只使用：

- `name`: 主题名称
- `type`: `"dark"` / `"light"`
- `colors`: `Map<String, String>`

其中 `colors` 的 key 支持三类：

- `EditorColorScheme` 的颜色常量名，例如 `WHOLE_BACKGROUND`、`KEYWORD`
- 纯数字颜色 ID（例如彩虹括号 `256-261`）

颜色值支持：`#RRGGBB` / `#AARRGGBB` / `0xAARRGGBB`。

补充：`colors` 也支持 TinaEditor 的“分组 key”（更易扩展，推荐新主题优先使用），例如：

- `editor.background` / `editor.foreground` / `editor.selection` / `editor.cursor` / `editor.cursorLine`
- `editor.lineNumber` / `editor.lineNumberActive`
- `gutter.background` / `gutter.divider` / `gutter.breakpoint` / `gutter.bookmark` / `gutter.diagnostic`
- `folding.iconExpanded` / `folding.iconCollapsed` / `folding.iconWarning`
- `diagnostic.error` / `diagnostic.warning` / `diagnostic.info` / `diagnostic.hint`
- `scrollbar.track` / `scrollbar.thumb` / `scrollbar.thumbHover`
- `syntax.keyword` / `syntax.string` / `syntax.number` / `syntax.comment` / `syntax.function` / `syntax.variable` / `syntax.type` / `syntax.operator` / `syntax.punctuation`

优先级：数字 ID > 常量名 > 分组 key。

（小技巧）颜色值为 `"0"` 会被视为“未设置”，编辑器会回退到内置主题颜色。

---

## 主题颜色属性完整参考

插件主题文件 `colors` 支持三类 key：

- 常量名：例如 `WHOLE_BACKGROUND`、`KEYWORD`
- 数字 ID：例如 `4`、`21`（覆盖常量名；也用于未来新增颜色的前向兼容）

颜色值支持：`#RRGGBB` / `#AARRGGBB` / `0xAARRGGBB`。

也支持 TinaEditor 的“分组 key”（推荐）：见上方示例列表。

### 分类参考（常用项）

#### 基础/背景

| 属性名 | ID | 说明 |
|-------|----|------|
| `WHOLE_BACKGROUND` | 4 | 编辑器背景 |
| `LINE_NUMBER_BACKGROUND` | 3 | 行号区背景 |
| `CURRENT_LINE` | 9 | 当前行高亮 |
| `CURRENT_ROW_BORDER` | 80 | 当前行边框 |
| `LINE_DIVIDER` | 1 | 行分隔线 |

#### 行号

| 属性名 | ID | 说明 |
|-------|----|------|
| `LINE_NUMBER` | 2 | 行号文字 |
| `LINE_NUMBER_CURRENT` | 45 | 当前行号文字 |
| `LINE_NUMBER_PANEL` | 16 | 行号面板背景 |
| `LINE_NUMBER_PANEL_TEXT` | 17 | 行号面板文字 |
| `LINE_BLOCK_LABEL` | 18 | 已标记“不再支持”（保留） |

#### 选择/光标/强调

| 属性名 | ID | 说明 |
|-------|----|------|
| `TEXT_NORMAL` | 5 | 普通文本 |
| `TEXT_SELECTED` | 30 | 选中文本（0 表示不改变） |
| `SELECTION_INSERT` | 7 | 光标 |
| `SELECTION_HANDLE` | 8 | 选择手柄 |
| `SELECTED_TEXT_BACKGROUND` | 6 | 选区背景 |
| `SELECTED_TEXT_BORDER` | 79 | 选区边框 |
| `UNDERLINE` | 10 | 下划线 |
| `STRIKETHROUGH` | 57 | 删除线（0 表示跟随文本色） |

#### 滚动条/缩进线

| 属性名 | ID | 说明 |
|-------|----|------|
| `SCROLL_BAR_THUMB` | 11 | 滚动条滑块 |
| `SCROLL_BAR_THUMB_PRESSED` | 12 | 滚动条按下 |
| `SCROLL_BAR_TRACK` | 13 | 滚动条轨道 |
| `BLOCK_LINE` | 14 | 缩进引导线 |
| `BLOCK_LINE_CURRENT` | 15 | 当前缩进引导线 |
| `SIDE_BLOCK_LINE` | 38 | 侧边引导线 |

#### 语法高亮（核心）

| 属性名 | ID | 说明 |
|-------|----|------|
| `KEYWORD` | 21 | 关键字 |
| `COMMENT` | 22 | 注释 |
| `OPERATOR` | 23 | 运算符 |
| `LITERAL` | 24 | 字面量（字符串/数字） |
| `IDENTIFIER_VAR` | 25 | 变量 |
| `IDENTIFIER_NAME` | 26 | 标识符（类型/类名等） |
| `FUNCTION_NAME` | 27 | 函数名 |
| `ANNOTATION` | 28 | 注解 |
| `HTML_TAG` | 32 | HTML/XML 标签 |
| `ATTRIBUTE_NAME` | 33 | 属性名 |
| `ATTRIBUTE_VALUE` | 34 | 属性值 |

#### 补全窗口

| 属性名 | ID | 说明 |
|-------|----|------|
| `COMPLETION_WND_BACKGROUND` | 19 | 背景 |
| `COMPLETION_WND_CORNER` | 20 | 圆角区域 |
| `COMPLETION_WND_TEXT_PRIMARY` | 42 | 主文本 |
| `COMPLETION_WND_TEXT_SECONDARY` | 43 | 次文本 |
| `COMPLETION_WND_ITEM_CURRENT` | 44 | 当前项背景 |
| `COMPLETION_WND_TEXT_MATCHED` | 67 | 匹配高亮 |

#### 匹配/搜索高亮

| 属性名 | ID | 说明 |
|-------|----|------|
| `MATCHED_TEXT_BACKGROUND` | 29 | 匹配背景 |
| `MATCHED_TEXT_BORDER` | 78 | 匹配边框 |
| `TEXT_HIGHLIGHT_BACKGROUND` | 74 | 文本高亮背景 |
| `TEXT_HIGHLIGHT_BORDER` | 77 | 文本高亮边框 |
| `TEXT_HIGHLIGHT_STRONG_BACKGROUND` | 73 | 强高亮背景 |
| `TEXT_HIGHLIGHT_STRONG_BORDER` | 76 | 强高亮边框 |

#### 括号/分隔符高亮

| 属性名 | ID | 说明 |
|-------|----|------|
| `HIGHLIGHTED_DELIMITERS_FOREGROUND` | 39 | 前景 |
| `HIGHLIGHTED_DELIMITERS_UNDERLINE` | 40 | 下划线 |
| `HIGHLIGHTED_DELIMITERS_BACKGROUND` | 41 | 背景 |
| `HIGHLIGHTED_DELIMITERS_BORDER` | 75 | 边框 |

#### 诊断/问题

| 属性名 | ID | 说明 |
|-------|----|------|
| `PROBLEM_ERROR` | 35 | 错误下划线/提示 |
| `PROBLEM_WARNING` | 36 | 警告 |
| `PROBLEM_TYPO` | 37 | 拼写/弱提示 |
| `DIAGNOSTIC_TOOLTIP_BACKGROUND` | 53 | 诊断提示背景 |
| `DIAGNOSTIC_TOOLTIP_BRIEF_MSG` | 54 | 简要消息 |
| `DIAGNOSTIC_TOOLTIP_DETAILED_MSG` | 55 | 详细消息 |
| `DIAGNOSTIC_TOOLTIP_ACTION` | 56 | 操作按钮 |

#### 代码片段/内联提示

| 属性名 | ID | 说明 |
|-------|----|------|
| `SNIPPET_BACKGROUND_EDITING` | 48 | 正在编辑片段背景 |
| `SNIPPET_BACKGROUND_RELATED` | 47 | 关联片段背景 |
| `SNIPPET_BACKGROUND_INACTIVE` | 46 | 非活动片段背景 |
| `TEXT_INLAY_HINT_FOREGROUND` | 50 | 内联提示文字 |
| `TEXT_INLAY_HINT_BACKGROUND` | 49 | 内联提示背景 |

#### 悬停/签名提示

| 属性名 | ID | 说明 |
|-------|----|------|
| `SIGNATURE_TEXT_NORMAL` | 58 | 签名普通文字 |
| `SIGNATURE_TEXT_HIGHLIGHTED_PARAMETER` | 59 | 高亮参数 |
| `SIGNATURE_BACKGROUND` | 60 | 签名背景 |
| `SIGNATURE_BORDER` | 71 | 签名边框 |
| `HOVER_TEXT_NORMAL` | 68 | 悬停普通文字 |
| `HOVER_TEXT_HIGHLIGHTED` | 72 | 悬停高亮文字 |
| `HOVER_BACKGROUND` | 69 | 悬停背景 |
| `HOVER_BORDER` | 70 | 悬停边框 |

#### 其他 UI

| 属性名 | ID | 说明 |
|-------|----|------|
| `TEXT_ACTION_WINDOW_BACKGROUND` | 65 | 文本操作窗口背景 |
| `TEXT_ACTION_WINDOW_ICON_COLOR` | 66 | 文本操作窗口图标 |
| `STATIC_SPAN_BACKGROUND` | 63 | 静态 span 背景 |
| `STATIC_SPAN_FOREGROUND` | 64 | 静态 span 前景 |
| `NON_PRINTABLE_CHAR` | 31 | 不可打印字符 |
| `HARD_WRAP_MARKER` | 51 | 硬换行标记 |
| `FUNCTION_CHAR_BACKGROUND_STROKE` | 52 | 函数字符背景描边 |
| `STICKY_SCROLL_DIVIDER` | 62 | 粘性滚动分隔线 |

#### 折叠

| 属性名 | ID | 说明 |
|-------|----|------|
| `FOLDING_ICON` | 81 | 折叠图标 |
| `FOLDING_ICON_BACKGROUND` | 82 | 折叠图标背景 |
| `FOLDED_TEXT_BACKGROUND` | 83 | 折叠文本背景 |
| `FOLDED_TEXT_COLOR` | 84 | 折叠文本颜色 |

#### 彩虹括号（自定义数字 ID）

| ID | 说明 |
|----|------|
| `256` | 彩虹括号颜色 1 |
| `257` | 彩虹括号颜色 2 |
| `258` | 彩虹括号颜色 3 |
| `259` | 彩虹括号颜色 4 |
| `260` | 彩虹括号颜色 5 |
| `261` | 彩虹括号颜色 6 |

### 完整 ID 表（1-84）

| ID | 属性名 | 备注 |
|----|--------|------|
| 1 | `LINE_DIVIDER` | |
| 2 | `LINE_NUMBER` | |
| 3 | `LINE_NUMBER_BACKGROUND` | |
| 4 | `WHOLE_BACKGROUND` | |
| 5 | `TEXT_NORMAL` | |
| 6 | `SELECTED_TEXT_BACKGROUND` | |
| 7 | `SELECTION_INSERT` | |
| 8 | `SELECTION_HANDLE` | |
| 9 | `CURRENT_LINE` | |
| 10 | `UNDERLINE` | |
| 11 | `SCROLL_BAR_THUMB` | |
| 12 | `SCROLL_BAR_THUMB_PRESSED` | |
| 13 | `SCROLL_BAR_TRACK` | |
| 14 | `BLOCK_LINE` | |
| 15 | `BLOCK_LINE_CURRENT` | |
| 16 | `LINE_NUMBER_PANEL` | |
| 17 | `LINE_NUMBER_PANEL_TEXT` | |
| 18 | `LINE_BLOCK_LABEL` | 不再支持（保留） |
| 19 | `COMPLETION_WND_BACKGROUND` | |
| 20 | `COMPLETION_WND_CORNER` | |
| 21 | `KEYWORD` | |
| 22 | `COMMENT` | |
| 23 | `OPERATOR` | |
| 24 | `LITERAL` | |
| 25 | `IDENTIFIER_VAR` | |
| 26 | `IDENTIFIER_NAME` | |
| 27 | `FUNCTION_NAME` | |
| 28 | `ANNOTATION` | |
| 29 | `MATCHED_TEXT_BACKGROUND` | |
| 30 | `TEXT_SELECTED` | |
| 31 | `NON_PRINTABLE_CHAR` | |
| 32 | `HTML_TAG` | |
| 33 | `ATTRIBUTE_NAME` | |
| 34 | `ATTRIBUTE_VALUE` | |
| 35 | `PROBLEM_ERROR` | |
| 36 | `PROBLEM_WARNING` | |
| 37 | `PROBLEM_TYPO` | |
| 38 | `SIDE_BLOCK_LINE` | |
| 39 | `HIGHLIGHTED_DELIMITERS_FOREGROUND` | |
| 40 | `HIGHLIGHTED_DELIMITERS_UNDERLINE` | |
| 41 | `HIGHLIGHTED_DELIMITERS_BACKGROUND` | |
| 42 | `COMPLETION_WND_TEXT_PRIMARY` | |
| 43 | `COMPLETION_WND_TEXT_SECONDARY` | |
| 44 | `COMPLETION_WND_ITEM_CURRENT` | |
| 45 | `LINE_NUMBER_CURRENT` | |
| 46 | `SNIPPET_BACKGROUND_INACTIVE` | |
| 47 | `SNIPPET_BACKGROUND_RELATED` | |
| 48 | `SNIPPET_BACKGROUND_EDITING` | |
| 49 | `TEXT_INLAY_HINT_BACKGROUND` | |
| 50 | `TEXT_INLAY_HINT_FOREGROUND` | |
| 51 | `HARD_WRAP_MARKER` | |
| 52 | `FUNCTION_CHAR_BACKGROUND_STROKE` | |
| 53 | `DIAGNOSTIC_TOOLTIP_BACKGROUND` | |
| 54 | `DIAGNOSTIC_TOOLTIP_BRIEF_MSG` | |
| 55 | `DIAGNOSTIC_TOOLTIP_DETAILED_MSG` | |
| 56 | `DIAGNOSTIC_TOOLTIP_ACTION` | |
| 57 | `STRIKETHROUGH` | 别名：`STRIKE_THROUGH` |
| 58 | `SIGNATURE_TEXT_NORMAL` | |
| 59 | `SIGNATURE_TEXT_HIGHLIGHTED_PARAMETER` | |
| 60 | `SIGNATURE_BACKGROUND` | |
| 61 | （保留） | 编辑器预留 ID |
| 62 | `STICKY_SCROLL_DIVIDER` | |
| 63 | `STATIC_SPAN_BACKGROUND` | |
| 64 | `STATIC_SPAN_FOREGROUND` | |
| 65 | `TEXT_ACTION_WINDOW_BACKGROUND` | |
| 66 | `TEXT_ACTION_WINDOW_ICON_COLOR` | |
| 67 | `COMPLETION_WND_TEXT_MATCHED` | |
| 68 | `HOVER_TEXT_NORMAL` | |
| 69 | `HOVER_BACKGROUND` | |
| 70 | `HOVER_BORDER` | |
| 71 | `SIGNATURE_BORDER` | |
| 72 | `HOVER_TEXT_HIGHLIGHTED` | |
| 73 | `TEXT_HIGHLIGHT_STRONG_BACKGROUND` | |
| 74 | `TEXT_HIGHLIGHT_BACKGROUND` | |
| 75 | `HIGHLIGHTED_DELIMITERS_BORDER` | |
| 76 | `TEXT_HIGHLIGHT_STRONG_BORDER` | |
| 77 | `TEXT_HIGHLIGHT_BORDER` | |
| 78 | `MATCHED_TEXT_BORDER` | |
| 79 | `SELECTED_TEXT_BORDER` | |
| 80 | `CURRENT_ROW_BORDER` | |
| 81 | `FOLDING_ICON` | |
| 82 | `FOLDING_ICON_BACKGROUND` | |
| 83 | `FOLDED_TEXT_BACKGROUND` | |
| 84 | `FOLDED_TEXT_COLOR` | |

---

## manifest.json（最小字段）

必须字段：

- `id`：插件唯一 ID（建议反域名风格，如 `sample.theme.dracula`）
- `name`
- `version`

常用字段：

- `description`
- `contributions.themes`：主题文件相对路径列表，例如 `["themes/dracula.json"]`

## manifest 多语言（推荐）

插件可以把用户可见文案放进 `locales/*.json`，宿主会按当前语言自动选择：

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

回退顺序为 `zh-CN -> zh -> default -> manifest 原字段`。locale 文件只覆盖展示字段，不覆盖 `id`、`version`、`type`、入口路径、权限等行为字段。

示例 `locales/zh-CN.json`：

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

当前支持覆盖：插件 `name` / `description`、`configuration.title`、`configuration.properties.*.description`、`projectTemplates`、`apkExports`、`commands`、`panels`、`languageServers` 和 `toolchains` 的显示文案。

## 依赖声明（建议字段，已支持提示）

为支持“插件需要某些工具链组件/系统包”的场景，可以在 `manifest.json` 增加 `requires` 字段。
宿主当前会解析该字段，在插件详情页展示依赖清单，并通过 Plugin Doctor 给出 INFO 级诊断提示。
该能力只做声明与提示，不会自动安装工具链或系统包。

支持结构：

```json
{
  "requires": {
    "toolchain": {
      "recommended": ["clangd", "cmake", "ninja", "git"],
      "optional": ["lldb"]
    },
    "packages": {
      "proot": ["python3", "nodejs"]
    }
  }
}
```

落地策略建议：

- 插件系统只做“声明 + 展示 + doctor 提示”
- 需要安装时，由用户按插件说明前往现有工具链安装或包管理 UI 处理

## 版本与更新策略

- 安装目录：`filesDir/plugins/<pluginId>/`
- 再次安装同 `id` 插件会覆盖旧版本（“从文件安装”）
- `assets/bundled_plugins/*` 属于“内置插件”：同版本自动跳过，版本变化会自动更新（用于开发测试）
- 启用/禁用状态会保留（更新不会重置开关）

实现约束补充：

- 页面详情选中态应保存 `pluginId`，不要保存整块插件对象
- 功能模块应消费“启用态”，不要直接消费“安装态”
- 市场页安装/更新态应复用统一解析逻辑，不要在多个页面重复计算

## 选择插件主题

在设置 → 插件页面内，可以对“提供主题的插件”直接选择并应用主题。

也可以通过写入 `SharedPreferences` 的 `editor_theme` 来选择：

- 内置值：`GRAY` / `DARK` / `LIGHT` / `AUTO`
- 插件主题：`plugin:<pluginId>/<themeRelativePath>`

例如：

`plugin:com.example.my-theme/themes/my-theme.json`

## 当前内置插件（assets）

公开发布事实源已经迁移到 TinaIDE Registry：

```text
https://github.com/wuxianggujun/TinaIDE-Registry
```

Registry 中的 `sources/plugins/**`、`plugins/index.v2.json` 和详情文件负责当前客户端市场分发；
v1 兼容索引默认不再生成，只服务旧客户端。主仓库当前随 APK 分发的内置插件位于
`app/src/main/assets/bundled_plugins/`，用于首次启动的兜底自动安装：

- `tinaide.project.templates`：基础项目模板
- `tinaide.cpp.snippets`：内置 C/C++ 代码片段

应用启动时会自动把 `assets/bundled_plugins/*` 安装到 `filesDir/plugins/`（若已安装同版本则跳过）。

更新内置插件的方式：

- 市场发布：在 `TinaIDE-Registry` 更新 `sources/plugins/**` 或包文件，运行
  `scripts/build-registry.ps1`，提交生成的 v2 轻量索引和详情文件；如仍支持旧客户端，
  显式运行 `scripts/build-registry.ps1 -IncludeLegacyV1` 后再提交 v1 兼容索引。
- 修改 `app/src/main/assets/bundled_plugins/<pluginId>/manifest.json` 的 `version`（例如 `1.0.1`）
- 下次启动会自动覆盖安装到 `filesDir/plugins/<pluginId>/`

## 菜单/快捷键/命令扩展（阶段 1.5：P0 已落地）

当前已支持插入并执行插件菜单项与快捷键：

- 插件通过 `contributions.menus["filetree/context"]` 声明菜单项（`command` + `group` + `when`）
- 插件通过 `contributions.menus["editor/context"]` 声明菜单项（当前落点：编辑器 Tab 长按菜单）
- 插件通过 `contributions.menus["editor/toolbar"]` 声明菜单项（当前落点：编辑器标签栏右侧插件动作菜单）
- 插件通过 `contributions.keybindings` 声明 keybindings JSON 文件
- `command` 支持宿主内置命令，或当前插件运行时已注册的插件命令
- `contributions.commands` 会参与菜单标题解析；若未声明，则回退到运行时注册标题或命令 ID
- 菜单解析统一输出 `ResolvedPluginCommand` 元数据，`surface` 当前包括 `EDITOR_TOOLBAR`、`EDITOR_CONTEXT`、
  `FILE_TREE_CONTEXT`，`source` 当前包括 `HOST` 与 `PLUGIN`
- 命令面板消费 `resolveEditorToolbarCommands()`，不要从旧菜单项临时拼接插件名、来源或搜索关键词
- 插件快捷键会在用户自定义/内置快捷键未命中后尝试执行，避免覆盖用户设置

`contributions.keybindings` 示例：

```json
{
  "contributions": {
    "keybindings": ["keybindings.json"]
  }
}
```

`keybindings.json` 支持顶层数组，或包含 `keybindings` 字段的对象：

```json
[
  { "key": "Ctrl+Alt+S", "command": "editor.save", "when": "isDirty" },
  { "key": "Shift+F6", "command": "editor.renameSymbol", "when": "editorFocus == true" }
]
```

当前支持的 `when` 条件：空条件、`isDirty`、`!isDirty`、`editorFocus`、`!editorFocus`
以及对应的 `== true/false` 写法。

示例插件：

- 直接按本文示例 `manifest.json` 声明 `filetree/context`、`editor/context` 或 `editor/toolbar`
  菜单项即可。

后续扩展仍以宿主命令白名单、纯文本安全渲染和权限收敛为边界，不引入动态 DEX 或插件自定义宿主 UI 代码。
