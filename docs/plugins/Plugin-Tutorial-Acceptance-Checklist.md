# 插件教程验收清单

> 文档更新：2026-07-15
> 目标：把“插件教程是否真的可用”变成可复查的验收项，避免后续再次回落到普通新建项目、普通 C/C++ 运行链路或旧命令模型。

---

## 1. 验收结论

插件教程必须满足下面这条主线：

```text
打开插件教程
→ 点击“创建插件项目”快捷操作
→ 进入“新建插件项目”向导
→ 只看到插件项目模板
→ 创建插件项目
→ 点击运行完成校验、打包、热安装
→ 点击打包生成 dist/<id>-<version>.tinaplug
→ 从文件安装可复用同一套预检规则
```

如果任一步进入普通 C/C++ 新建项目或普通 C/C++ 编译运行链路，本轮验收失败。

---

## 2. 入口一致性验收

### 2.1 帮助页入口

1. 打开 `设置 → 帮助`。
2. 进入“插件开发快速开始”。
3. 确认页面顶部出现快捷操作：
   - `创建插件项目`
   - `打开插件设置`
4. 点击 `创建插件项目`。
5. 预期：进入“新建插件项目”向导。
6. 预期：向导只展示 `ProjectBuildSystem.PLUGIN` 模板。
7. 预期：默认优先定位到 `plugin:tinaide.plugin.starters:config-basic`。

关键代码：

- `HelpQuickActionSupport.PLUGIN_QUICK_START_DOCUMENT_ID`
- `HelpQuickAction.CREATE_PLUGIN_PROJECT`
- `SettingsActivity → HelpScreen.onCreatePluginProject`
- `NewProjectWizardActivity.createPluginProjectIntent()`

### 2.2 教程页入口

1. 打开主界面的教程页。
2. 进入“插件开发快速开始”。
3. 确认文章顶部也出现快捷操作：
   - `创建插件项目`
   - `打开插件设置`
4. 点击 `创建插件项目`。
5. 预期：同样进入“新建插件项目”向导，而不是通用新建项目向导。

关键代码：

- `TutorialRepository.createPluginQuickStartTutorial()`
- `TutorialScreen.TutorialPluginQuickActions`
- `NewProjectWizardActivity.createPluginProjectIntent()`

### 2.3 设置页入口

1. 打开 `设置 → 插件` 或插件教程相关入口。
2. 点击“创建插件项目”。
3. 预期：进入“新建插件项目”向导。
4. 预期：携带 `EXTRA_PREFER_PLUGIN_TEMPLATE=true`。
5. 预期：携带 `EXTRA_INITIAL_TEMPLATE_ID=plugin:tinaide.plugin.starters:config-basic`。

关键代码：

- `SettingsActivity`
- `NewProjectWizardActivity.EXTRA_PREFER_PLUGIN_TEMPLATE`
- `NewProjectWizardActivity.EXTRA_INITIAL_TEMPLATE_ID`

### 2.4 教程内容完整性

1. 教程页“进阶功能”分类至少包含以下连续课程：
   - 插件开发快速开始
   - 插件 Manifest 与版本兼容
   - Script API 与最小权限
   - 插件面板与事件联动
   - LSP 插件开发与排错
   - 插件测试、自愈与发布前检查
2. 每个课程都能打开非空正文，不显示空白详情页。
3. 中文环境加载 `help/<file>.md`，英文环境优先加载 `help/en/<file>.md`。
4. “继续学习”中的教程链接留在教程流程内，普通帮助链接进入帮助中心。
5. 任一目录条目缺少中英文 asset、内容为空或链接无法解析时，自动化测试必须失败。

### 2.5 加载、搜索与阅读状态

1. 首次进入教程页时，目录加载完成前应显示 Loading，不得先闪现“暂无教程”。
2. 目录加载完成且确实没有数据时，才显示空状态。
3. 在帮助中心搜索只出现在插件教程正文、没有出现在标题或摘要中的关键词，预期仍能命中对应文档。
4. 中文和英文文档使用各自正文缓存；切换语言后搜索结果不得复用另一语言正文。
5. 打开教程文章时先显示 Loading；正文成功返回后再显示文章内容。
6. 正文链接缺失、内容为空、读取返回失败或抛出异常时，应显示错误状态和“重试”操作，不得留下空白页。
7. 点击“重试”后应重新加载正文；后续读取成功时，错误状态应被文章内容替换。
8. ARTICLE 教程首次阅读后应显示“阅读中 / Reading in progress”，不得显示没有真实计算依据的 `0%`。

自动化锁点：

- `HelpRepositoryTest`：正文关键词搜索、中文/英文缓存隔离。
- `TutorialCatalogUiStateTest`：初始 Loading、加载完成和分类排序。
- `TutorialArticleLoadStateTest`：成功、缺失/空白、`Result.failure` 和 loader 抛异常。

### 2.6 普通新建项目入口

1. 打开项目页右下角 `+`。
2. 选择普通新建项目。
3. 预期：仍进入通用“新建项目”向导。
4. 预期：普通模板和插件模板可以共存。
5. 预期：不会强制过滤成只剩插件模板。

这个入口保持通用语义，不属于插件教程快捷入口。

---

## 3. 向导验收

### 3.1 有插件模板时

1. 确保 `TinaIDE Plugin Starters` 已安装并启用。
2. 从插件教程快捷入口进入。
3. 预期：标题显示“新建插件项目”。
4. 预期：模板列表只包含插件模板：
   - `Tina Config Plugin`
   - `Tina Script Command Plugin`
   - `Tina Script Plugin`
   - `Tina LSP Plugin`
5. 预期：插件模板卡片显示“插件”标识。
6. 预期：配置页隐藏 C++ 标准、NDK API 这类无关字段。
7. 预期：配置页显示插件项目下一步提示。

### 3.2 没有插件模板时

1. 禁用或移除 `TinaIDE Plugin Starters`。
2. 从插件教程快捷入口进入。
3. 预期：显示“暂无插件项目模板”。
4. 预期：显示引导用户检查 `TinaIDE Plugin Starters` 的说明。
5. 预期：`下一步` 按钮禁用。
6. 预期：不会回退到 C/C++ 默认模板。

### 3.3 目标模板缺失时

1. 保留至少一个插件模板，但让默认目标模板 ID 不存在。
2. 从插件教程快捷入口进入。
3. 预期：回退选中第一个 `ProjectBuildSystem.PLUGIN` 模板。
4. 预期：不会选中普通 C/C++ 模板。

---

## 4. 创建项目验收

### 4.1 基础文件

创建插件项目后，项目根目录至少应包含：

- `manifest.json`
- 对应模板需要的运行时文件或资源文件
- `README.md`
- `pack.ps1` / `pack.sh`
- `validate.ps1` / `validate.sh`

不同模板的关键文件：

- `config-basic`：`themes/`、`snippets/`、`icons/`
- `script-command`：`main.lua`、命令菜单示例
- `script-basic`：`main.lua`、事件或自动化示例
- `lsp-basic`：LSP 服务器配置示例

### 4.2 项目元数据

创建出的 `.tinaide/project.json` 应正确表达插件项目语义：

- `buildSystem` 应为插件语义，对应 `ProjectBuildSystem.PLUGIN`
- 如果历史模板元数据不正确，`BuildSystemDetector` 应能通过根目录 `manifest.json` 纠正为 `BuildSystem.PLUGIN`

---

## 5. 运行 / 打包验收

### 5.1 运行插件项目

1. 打开刚创建的插件项目。
2. 点击顶部 `运行`。
3. 预期链路：

```text
CompileActionsHelper
→ CompileProjectUseCase.execute()
→ BuildSystemDetector.detect(projectRoot)
→ BuildSystem.PLUGIN
→ PluginProjectActions.install()
→ PluginDoctor.inspectDirectory()
→ 打包 dist/<id>-<version>.tinaplug
→ PluginManager.install()
```

4. 预期：显示插件已安装并立即生效的提示。
5. 预期：打开构建日志。
6. 预期：不会启动普通终端程序。
7. 预期：不会进入 CMake / Makefile / 单文件构建策略。

### 5.2 打包插件项目

1. 点击顶部 `打包` 或构建动作。
2. 预期：只生成 `.tinaplug`。
3. 预期：输出路径为 `dist/<id>-<version>.tinaplug`。
4. 预期：不调用 `PluginManager.install()`。
5. 预期：最终包不包含开发辅助文件：
   - `README.md`
   - `pack.ps1`
   - `pack.sh`
   - `validate.ps1`
   - `validate.sh`
   - `.tinaide/`
   - `.git/`
   - `dist/`

### 5.3 调试按钮

插件项目当前不支持断点调试。

1. 点击调试入口。
2. 预期：显示不支持调试的明确提示。
3. 预期：提示用户使用运行来校验、打包、热安装。

---

## 6. 从文件安装验收

1. 进入 `设置 → 插件 → 从文件安装插件`。
2. 选择刚生成的 `.tinaplug`。
3. 预期：安装前执行同源诊断。
4. 预期：有 error 时阻止安装。
5. 预期：只有 warning 时允许用户确认后继续。
6. 预期：脚本 / hybrid 插件需要敏感权限时弹出权限确认。

---

## 7. 命令菜单验收

### 7.1 配置插件菜单

纯 `config` 插件没有 Lua 运行时，因此菜单命令只能引用宿主内置命令。

验收点：

- `contributions.commands` 只提供菜单标题，不生成可执行逻辑。
- `contributions.menus` 中的 `command` 必须能被宿主内置命令解析。
- 不支持的命令会被忽略。
- `when` 只能使用当前支持的固定表达式。

常用宿主内置命令示例：

- `file.copyPath`
- `editor.save`
- `view.toggleFileTree`

### 7.2 脚本插件自定义菜单

`script` / `hybrid` 插件如果要显示自定义命令菜单，必须同时满足：

1. `contributions.commands` 声明同一个命令 ID。
2. `contributions.menus` 引用同一个命令 ID。
3. `permissions` 声明 `command.execute`。
4. `main.lua` 在插件加载时调用 `tina.commands.register(...)` 注册同一个命令 ID。
5. 插件成功启用，并且运行时没有加载错误。

---

## 8. 常见失败与排查入口

应用内 `plugin-quick-start.md` 的“快速排错 FAQ”应覆盖本节所有用户侧高频问题。
如果新增失败场景，优先同步到应用内 FAQ，再补充到本清单。

### 8.1 点击创建后仍像普通新建项目

优先检查：

1. 是否调用 `NewProjectWizardActivity.createPluginProjectIntent()`。
2. 是否携带 `EXTRA_PREFER_PLUGIN_TEMPLATE=true`。
3. 是否携带默认 starter 模板 ID。
4. `NewProjectWizardSupport.resolveVisibleTemplateOptions()` 是否只保留插件模板。
5. `NewProjectWizardScreen` 是否根据插件语境显示“新建插件项目”。

### 8.2 没有插件模板

优先检查：

1. `TinaIDE Plugin Starters` 是否安装。
2. 插件是否启用。
3. `manifest.json` 是否包含 `contributions.projectTemplates`。
4. 每个模板 zip 是否存在。
5. 每个模板声明的 `buildSystem` 是否为 `plugin`。

### 8.3 运行后没有热安装

优先检查：

1. `.tinaide/project.json` 是否表达插件项目。
2. 根目录 `manifest.json` 是否能被识别为插件 manifest。
3. `BuildSystemDetector.detect(projectRoot)` 是否返回 `BuildSystem.PLUGIN`。
4. `CompileProjectUseCase` 是否注入 `PluginProjectActions`。
5. `AndroidPluginProjectActions.install()` 是否成功调用 `PluginManager.install()`。

### 8.4 打包失败

优先检查：

1. `manifest.json` 是否存在。
2. `manifest.id`、`name`、`version`、`type` 是否合法。
3. manifest 引用的资源路径是否真实存在。
4. 路径是否包含 `..`。
5. `networkHosts` 是否误写成完整 URL。

---

## 9. 回归测试关注点

建议至少保留这些测试锁点：

- `NewProjectWizardActivityIntentTest`
  - 插件入口必须携带插件模式参数和默认 starter 模板 ID。
- `NewProjectWizardSupportTest`
  - 插件入口只展示插件模板。
  - 无插件模板时返回空列表。
  - 目标模板缺失时回退第一个插件模板。
- `AndroidPluginProjectActionsTest`
  - 构建生成 `.tinaplug`。
  - 打包排除开发辅助文件。
  - 运行委托 `PluginManager.install()`。
- `CompileProjectUseCasePluginProjectTest`
  - `BUILD` 分发到插件打包。
  - `RUN / REBUILD_RUN` 分发到插件热安装。
  - 插件项目不得进入普通构建编排器。
- `CompileActionsHelperTest`
  - 插件热安装成功后显示成功提示并打开构建日志。
- `HelpRepositoryTest`
  - 插件快速开始文档可加载、标题和正文均可搜索。
  - 中文和英文正文缓存互不串用。
  - 插件快速开始文档显示快捷操作。
- `TutorialCatalogUiStateTest`
  - 教程目录首次进入保持 Loading，加载完成后才显示内容或空状态。
  - 分类和课程按既定顺序输出。
- `TutorialArticleLoadStateTest`
  - 教程文章成功、缺失、空白、失败和异常状态均可重复验证；协程取消继续向上传播，不伪装成加载错误。
  - 界面重试按钮复用同一加载状态机；实际点击后的重新加载由 Compose UI / 真机走查确认。

---

## 10. 本轮不做什么

为避免过度设计，本验收清单不要求：

- 新增单独的插件项目向导 Activity。
- 把普通项目入口强行改成插件入口。
- 为插件项目接入断点调试。
- 为所有插件能力做可视化生成器。
- 放宽脚本 / hybrid 插件权限模型。

后续如果继续推进，优先按本清单逐项做真实设备或模拟器走查。
