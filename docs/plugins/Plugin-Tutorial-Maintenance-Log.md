# 插件教程维护记录

> 文档更新：2026-07-15
> 目标：记录插件教程从“像普通新建项目”到“新建插件项目闭环”的修正过程、设计决策和后续维护规则。

---

## 1. 背景

本轮维护来自一个明确问题：

> 插件教程里的“创建插件项目”不应该进入默认普通新建项目交互，而应该进入“新建插件项目”语境。

原体验容易让用户误判：

- 插件教程像是在引导普通 C/C++ 新建项目。
- 插件模板和普通模板混在一起，用户可能停在默认 C/C++ 模板。
- 创建后的插件项目点击运行时，用户不清楚应该是热安装插件，而不是运行普通程序。
- 教程、starter README、Registry 模板包和排查文档的说法不完全一致。

本轮目标不是新增复杂系统，而是把现有项目模板能力收束成一个清晰、可验收、可维护的插件开发闭环。

### 1.1 2026-07 教程完整性补强

后续走查发现，教程目录虽然已有插件快速开始，但课程密度不足；教程详情还绕过帮助中心，
直接读取固定 asset，缺少统一的语言选择、缓存和缺失资源回退。这会让部分语言环境或资源
不同步时出现空白/加载失败体验。

本轮补强：

- 教程详情复用 `HelpRepository.loadDocumentContentByLinkTarget()`，统一 UTF-8、本地化、缓存与回退。
- 插件课程从 1 篇扩展为 6 篇：快速开始、Manifest 兼容、Script API、面板事件、LSP 排错、测试自愈。
- 6 篇课程全部提供中文与英文 asset，并互相形成站内“继续学习”链路。
- 增加目录唯一性、链接解析、双语资源存在性和非空内容测试。
- 不修改插件公开 API、`apiVersion 1` 或旧 IDE 兼容选择协议。

---

## 2. 最终用户路径

当前插件教程期望用户走这条路径：

```text
插件教程 / 帮助页 / 教程页
→ 点击“创建插件项目”
→ 新建插件项目向导
→ 只展示插件模板
→ 创建插件项目
→ 点击运行：校验 + 打包 + 热安装
→ 点击打包：生成 dist/<manifest.id>-<manifest.version>.tinaplug
→ 设置 → 插件 → 从文件安装：再次预检
```

这条路径是后续所有教程、starter 和回归测试的基准。

---

## 3. 本轮关键设计决策

### 3.1 复用项目向导，而不是新建一套插件向导

决策：继续复用 `NewProjectWizardActivity`，通过显式插件语境参数切换行为。

原因：

- 当前项目模板系统已经能承载插件模板。
- 单独复制一套向导会增加 UI、状态和模板安装逻辑的重复。
- 通过插件模式过滤模板，更符合 KISS / DRY。

关键能力：

- `EXTRA_INITIAL_TEMPLATE_ID`
- `EXTRA_PREFER_PLUGIN_TEMPLATE`
- `createPluginProjectIntent()`
- `NewProjectWizardSupport.resolveVisibleTemplateOptions()`
- `NewProjectWizardSupport.resolveInitialTemplateSelection()`

### 3.2 插件入口只显示插件模板

决策：插件教程、帮助页、教程页、设置页中的“创建插件项目”都必须走插件专用入口。

期望：

- 标题显示“新建插件项目”。
- 只展示 `ProjectBuildSystem.PLUGIN` 模板。
- 默认优先选择 `plugin:tinaide.plugin.starters:config-basic`。
- 没有插件模板时显示空状态并禁用下一步。

边界：

- 项目页右下角 `+` 仍保持通用新建项目语义。
- 普通入口可以同时展示普通模板和插件模板。
- 不把普通项目入口强制改成插件入口。

### 3.3 插件项目运行必须走热安装链路

决策：`BuildSystem.PLUGIN` 在 `CompileProjectUseCase` 中提前分流，不进入普通 CMake / Makefile / 单文件构建策略。

链路：

```text
CompileActionsHelper
→ CompileProjectUseCase.execute()
→ BuildSystemDetector.detect(projectRoot)
→ BuildSystem.PLUGIN
→ executePluginProjectAction()
→ PluginProjectActions.build() / install()
→ AndroidPluginProjectActions
```

行为：

- `BUILD`：校验并生成 `.tinaplug`。
- `RUN / REBUILD_RUN / TERMINAL`：校验、打包并调用 `PluginManager.install()` 热安装。
- `DEBUG`：明确提示插件项目暂不支持调试。

### 3.4 打包和安装复用同源诊断

决策：插件项目打包和从文件安装都应经过同一套插件诊断思路。

当前落点：

- `AndroidPluginProjectActions` 调用 `PluginDoctor.inspectDirectory()`。
- 从文件安装流程继续使用插件安装前预检。
- 应用内 FAQ 引导用户按诊断类别排查。

### 3.5 `TinaIDE Plugin Starters` 必须升版本

决策：starter 插件内容变化时，必须提升 `manifest.json` 版本。

原因：插件仓库同版本可能跳过覆盖安装，导致用户仍拿到旧模板和旧说明。

本轮版本：

- `tinaide.plugin.starters`：`1.0.0` → `1.0.1`

### 3.6 源模板和 Registry zip 必须同步

决策：修改 `tools/plugin-starters/**` 后，必须重新生成 starter zip，并同步到 TinaIDE Registry 的 `sources/plugin-starters/**` 或对应发布目录。

原因：用户新建插件项目时拿到的是已安装插件里的 zip，不是 Android 仓库中的源目录。

本轮同步的模板包：

- `tina-config-plugin.zip`
- `tina-script-command-plugin.zip`
- `tina-script-plugin.zip`
- `tina-lsp-plugin.zip`

---

## 4. 本轮变更批次

### 4.1 插件入口和向导

完成内容：

- 设置页 / 帮助页 / 教程页中的插件快捷入口统一走 `createPluginProjectIntent()`。
- 向导支持插件模式参数。
- 插件模式只展示插件模板。
- 插件模式标题、说明、空状态和按钮禁用逻辑已补齐。
- 插件模板隐藏 C++ 标准这类无关配置。

主要文件：

- `app/src/main/java/com/wuxianggujun/tinaide/settings/SettingsActivity.kt`
- `app/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/main/tutorial/TutorialScreen.kt`
- `feature/wizard/src/main/java/com/wuxianggujun/tinaide/ui/wizard/NewProjectWizardActivity.kt`
- `feature/wizard/src/main/java/com/wuxianggujun/tinaide/ui/wizard/NewProjectWizardSupport.kt`
- `feature/wizard/src/main/java/com/wuxianggujun/tinaide/ui/wizard/NewProjectWizardScreen.kt`
- `feature/wizard/src/main/java/com/wuxianggujun/tinaide/ui/wizard/NewProjectWizardViewModel.kt`

### 4.2 插件运行 / 打包链路

完成内容：

- 新增 `PluginProjectActions` 作为 core 与 app 的边界接口。
- 新增 `AndroidPluginProjectActions` 执行插件项目校验、打包、热安装。
- `CompileProjectUseCase` 检测到 `BuildSystem.PLUGIN` 后提前分流。
- 插件热安装成功后显示成功提示并打开构建日志。

主要文件：

- `core/compile/src/main/java/com/wuxianggujun/tinaide/core/compile/PluginProjectActions.kt`
- `core/compile/src/main/java/com/wuxianggujun/tinaide/core/compile/CompileProjectUseCase.kt`
- `app/src/main/java/com/wuxianggujun/tinaide/plugindev/AndroidPluginProjectActions.kt`
- `app/src/main/java/com/wuxianggujun/tinaide/ui/CompileActionsHelper.kt`

### 4.3 应用内教程和 FAQ

完成内容：

- `plugin-quick-start.md` 明确教程快捷入口会打开“新建插件项目”。
- 新增用户版“快速排错 FAQ”。
- FAQ 覆盖入口、模板缺失、热安装、`.tinaplug` 输出、从文件安装、菜单、资源、`networkHosts`。
- 教程页文章顶部也补齐“创建插件项目 / 打开插件设置”快捷操作。

主要文件：

- `feature/help/src/main/assets/help/plugin-quick-start.md`
- `feature/help/src/main/java/com/wuxianggujun/tinaide/core/help/HelpQuickActionSupport.kt`
- `feature/help/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/help/HelpScreen.kt`
- `app/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/main/tutorial/TutorialScreen.kt`

### 4.4 开发者文档

完成内容：

- 新增插件编写教程。
- 新增模板设计方案。
- 新增向导排查记录。
- 新增端到端验收清单。
- 修正 `README.md` 快速开始：优先走“新建插件项目”，手工目录仅作兜底。
- 统一“宿主内置命令 / 插件运行时注册命令”的说法。

主要文件：

- `docs/plugins/Plugin-Authoring-Tutorial.md`
- `docs/plugins/Plugin-Template-Design.md`
- `docs/plugins/Plugin-Project-Template-Wizard-Troubleshooting.md`
- `docs/plugins/Plugin-Tutorial-Acceptance-Checklist.md`
- `docs/plugins/README.md`
- `docs/plugins/Plugin-Roadmap.md`

### 4.5 Starter 模板

完成内容：

- 新增 / 更新 4 个 starter 源模板 README。
- 统一运行、打包、输出路径、从文件安装预检说明。
- 修正 `command.execute` 权限说明：注册插件命令和调用宿主命令都需要它。
- 同步 starter 模板 zip。
- starter 插件版本提升到 `1.0.1`。

主要文件：

- `tools/plugin-starters/config-basic/README.md`
- `tools/plugin-starters/script-command/README.md`
- `tools/plugin-starters/script-command/docs/permissions.md`
- `tools/plugin-starters/script-basic/README.md`
- `tools/plugin-starters/script-basic/docs/permissions.md`
- `tools/plugin-starters/lsp-basic/README.md`
- `tools/plugin-starters/dist/tinaide.plugin.starters/templates/*.zip`
- TinaIDE Registry 中的 `sources/plugin-starters/**`

---

## 5. 回归测试锁点

本轮新增或补强的测试关注点：

- 插件入口 Intent 必须携带插件模式和默认 starter 模板 ID。
- 普通 `createIntent()` 必须保持通用向导语义。
- 插件模式只展示插件模板。
- 无插件模板时返回空列表并禁用下一步。
- 目标插件模板缺失时回退第一个插件模板。
- 插件项目 `BUILD` 走 `PluginProjectActions.build()`。
- 插件项目 `RUN / REBUILD_RUN` 走 `PluginProjectActions.install()`。
- 插件项目不得进入普通构建编排器。
- 插件热安装成功后显示成功提示并打开构建日志。
- `AndroidPluginProjectActions` 打包排除开发辅助文件，并委托 `PluginManager.install()`。

相关测试文件：

- `feature/wizard/src/test/java/com/wuxianggujun/tinaide/ui/wizard/NewProjectWizardActivityIntentTest.kt`
- `feature/wizard/src/test/java/com/wuxianggujun/tinaide/ui/wizard/NewProjectWizardSupportTest.kt`
- `core/compile/src/test/java/com/wuxianggujun/tinaide/core/compile/CompileProjectUseCasePluginProjectTest.kt`
- `app/src/test/java/com/wuxianggujun/tinaide/plugindev/AndroidPluginProjectActionsTest.kt`
- `app/src/test/java/com/wuxianggujun/tinaide/ui/CompileActionsHelperTest.kt`
- `feature/help/src/test/java/com/wuxianggujun/tinaide/core/help/HelpRepositoryTest.kt`

---

## 6. 本轮已做的轻量验证

未运行 Gradle 编译 / 测试，因为本轮用户明确要求不需要编译测试。

已做轻量验证：

- `git diff --check`
- 尾随空白扫描
- 冲突标记扫描
- 旧误导文案扫描
- starter 源模板与发布 zip 关键文件一致性比对
- 4 个 starter 的 `validate.ps1` 校验

说明：

- starter 校验中的 `{{PROJECT_NAME}}` 警告属于模板占位符预期行为。
- Windows 下 `LF will be replaced by CRLF` 是 Git 换行提示，不是内容错误。

---

## 7. 后续维护规则

### 7.1 修改插件教程时

必须同步检查：

1. `feature/help/src/main/assets/help/plugin-quick-start.md`
2. `docs/plugins/Plugin-Authoring-Tutorial.md`
3. `docs/plugins/Plugin-Tutorial-Acceptance-Checklist.md`
4. `docs/plugins/Plugin-Project-Template-Wizard-Troubleshooting.md`
5. `docs/plugins/README.md`

如果新增用户侧失败场景，优先更新应用内 `plugin-quick-start.md` 的“快速排错 FAQ”。

### 7.2 修改 starter 模板时

必须同步检查：

1. 源模板目录：`tools/plugin-starters/<template>/`
2. 发布模板 zip：`tools/plugin-starters/dist/tinaide.plugin.starters/templates/*.zip` 或 Registry 对应目录
3. starter 插件版本：Registry 中 `tinaide.plugin.starters` 的 `manifest.json`
4. 对应 README / permissions 文档
5. `Plugin-Tutorial-Acceptance-Checklist.md` 中的模板验收项

如果模板 zip 内容变化，通常需要提升 `tinaide.plugin.starters` 版本，避免同版本跳过覆盖安装。

### 7.3 修改插件运行链路时

必须同步检查：

1. `BuildSystemDetector` 是否仍能识别插件项目。
2. `CompileProjectUseCase` 是否仍在 `BuildSystem.PLUGIN` 时提前分流。
3. `PluginProjectActions` 是否仍由 app 层注入。
4. `AndroidPluginProjectActions` 是否仍排除开发辅助文件。
5. `CompileActionsHelper` 是否仍正确处理 `LaunchSpec.PluginInstalled`。
6. 应用内 FAQ 是否仍描述真实行为。

### 7.4 修改命令 / 菜单模型时

必须同步检查：

1. `HostCommands` 支持的宿主内置命令。
2. `PluginCommandRegistry` 的注册与分发行为。
3. `PluginMenuResolver` 对菜单项显示条件的判断。
4. `tools/plugin-starters/shared/validation-rules.json`。
5. 应用内 FAQ、starter README 和 `Plugin-Roadmap.md` 的命令菜单说明。

---

## 8. 明确不做的事

本轮没有做：

- 新增独立插件项目向导 Activity。
- 把普通项目入口改成插件入口。
- 为插件项目接入断点调试。
- 引入动态 DEX 插件能力。
- 放宽脚本 / hybrid 权限确认模型。
- 运行 Gradle 编译 / 测试。

这些都不是本轮修复“插件教程入口和闭环混乱”的必要条件。

---

## 9. 后续推进建议

建议按优先级继续：

1. **P0：真实设备走查**
   - 按 `Plugin-Tutorial-Acceptance-Checklist.md` 从入口到热安装完整走一遍。
2. **P1：截图或短视频补充**
   - 给应用内教程补几张关键界面截图或简短动图。
3. **P1：插件日志入口增强**
   - 在热安装成功提示后，引导用户查看插件日志。
4. **P2：模板字段可视化说明**
   - 后续可把 `manifest.json` 常见字段做成轻量说明卡。
5. **P2：脚本 API 示例矩阵**
   - 按 `tina.editor`、`tina.commands`、`tina.workspace` 分组维护最小示例。
