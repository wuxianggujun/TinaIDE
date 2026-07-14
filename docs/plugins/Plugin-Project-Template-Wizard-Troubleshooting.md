# 插件教程新建插件项目入口排查与修改方案

> 文档更新：2026-04-26
> 状态：已实施
> 目标：记录“插件教程里创建插件项目为什么仍进入默认新建项目流程”的原因、期望行为、修改方案与排查入口。

---

## 1. 结论先行

插件教程里的“创建插件项目”入口，语义上必须是 **新建插件项目**，不应该让用户感觉进入了默认 C/C++ 新建项目。

历史问题的根因是：插件项目创建复用了 TinaIDE 已有的新建项目向导，而教程快捷入口没有显式传入“插件项目”语境。
因此向导曾经可能仍按默认模板进入，例如默认停在 C++ 单文件模板。

当前已通过插件项目专用启动参数修正：教程快捷入口会进入“新建插件项目”语境，
只展示 `ProjectBuildSystem.PLUGIN` 模板。

插件模板通过用户已安装并启用的 `tinaide.plugin.starters` 的
`contributions.projectTemplates` 注入到模板列表里。也就是说，插件模板目前只是通用项目模板列表中的一类模板。

当前真实流程仍然是：

1. 选择模板
2. 填写项目配置
3. 创建项目

这不是模板 zip 解压失败，也不是插件教程文档丢失，而是入口语义没有和插件模板选择绑定。

---

## 2. 为什么会像“默认新建项目流程”

真实调用链如下：

```text
插件教程快捷操作
└── NewProjectWizardActivity
    ├── BuiltInProjectTemplates.DEFAULT_TEMPLATE_ID
    ├── PluginManager.listProjectTemplateOptions()
    └── NewProjectWizardScreen
        ├── TemplateSelectionStep
        └── ConfigurationStep
```

也就是说：

- 内置基础 C/C++ 模板和已安装插件模板共用同一个 `templateOptions` 列表
- 插件模板只是额外模板项，不会自动生成全新的插件专属页面
- 教程快捷入口如果只调用默认新建项目 Intent，就不会天然选中插件模板
- 创建阶段统一走 `ProjectCreationService.createProject()`
- 模板内容由 `ProjectTemplateInstaller` 解压，并写入项目元数据

---

## 3. 期望行为

从插件教程点击“创建插件项目”时，期望行为应该是：

- 打开新建插件项目语境的向导，而不是普通 C/C++ 新建项目语境
- 插件入口下只展示插件项目模板，避免用户误选普通项目模板
- 优先选择已安装的 starter 模板 `plugin:tinaide.plugin.starters:config-basic`
- 如果精确模板暂时不可用，则回退到第一个可用插件模板
- 如果没有任何插件模板，显示“暂无插件项目模板”的明确提示
- 配置步骤隐藏 C++ 标准等无关字段

这样才能符合用户预期：插件教程就是创建插件项目，不是创建普通项目后让用户手动找插件模板。

---

## 4. 修改方案（已实施）

本次采用最小改动，不拆新的独立 Activity：

1. 在 `NewProjectWizardActivity` 增加“初始模板 / 优先插件模板”启动参数
2. 在插件教程快捷入口中使用插件项目专用启动方法
3. 向导收到插件语境后，仅展示 `ProjectBuildSystem.PLUGIN` 模板
4. 优先选中 `tinaide.plugin.starters` 的 `config-basic` 模板
5. 如果目标模板不存在，则查找第一个 `ProjectBuildSystem.PLUGIN` 模板
6. 如果仍没有插件模板，则显示插件模板缺失提示，并禁用下一步
7. 插件语境下标题显示为“新建插件项目”

已新增或调整的能力：

- `EXTRA_INITIAL_TEMPLATE_ID`
- `EXTRA_PREFER_PLUGIN_TEMPLATE`
- `createPluginProjectIntent()`
- `resolveVisibleTemplateOptions()`
- `resolveInitialTemplateSelection()`

这些能力用于把插件教程快捷入口和插件模板选择绑定起来。

---

## 5. 设计边界

本方案不建议第一步就把插件创建拆成独立 Activity 或独立向导。

原因是：

- 当前项目模板系统已经支持 `ProjectBuildSystem.PLUGIN`
- 插件模板、C++ 模板共用解压、占位符替换、项目元数据写入逻辑
- 立即拆独立插件向导会引入重复创建逻辑，违背 KISS / DRY
- 插件专属字段还不稳定，暂不扩大 UI 复杂度

因此建议的最小可维护方案是：

- 通用向导继续承载所有项目模板
- 插件教程入口负责显式传入“插件项目”语境
- 插件模板卡片与配置页提示负责解释后续运行 / 打包 / 热安装闭环

---

## 6. 排查清单

如果后续又出现“插件教程打开后不像插件创建”的问题，按下面顺序查：

1. 检查 starter 插件是否安装并启用
   - 插件 ID：`tinaide.plugin.starters`
   - 来源：插件市场 / GitHub Registry

2. 检查模板贡献项是否存在
   - 文件：`manifest.json`
   - 字段：`contributions.projectTemplates`
   - 插件模板必须声明 `buildSystem: "plugin"`
   - 插件模板建议声明 `primaryLanguage: "MIXED"`

3. 检查模板 zip 是否真实存在
   - `templates/tina-config-plugin.zip`
   - `templates/tina-script-command-plugin.zip`
   - `templates/tina-script-plugin.zip`
   - `templates/tina-lsp-plugin.zip`

4. 检查向导是否收到插件模板偏好
   - 入口：`NewProjectWizardActivity.createPluginProjectIntent()`
   - 参数：`EXTRA_INITIAL_TEMPLATE_ID`
   - 参数：`EXTRA_PREFER_PLUGIN_TEMPLATE`

5. 检查插件模板是否被向导解析
   - `PluginManager.listProjectTemplateOptions()`
   - `resolveProjectTemplateOption()`
   - `PluginManifestValidator.parseProjectBuildSystem()`

6. 检查配置页是否仍显示无关 C++ 字段
   - `NewProjectWizardSupport.shouldShowCppStandard()`
   - `primaryLanguage` 为 `MIXED` 时不应显示 C++ 标准

---

## 7. 相关代码入口

- `feature/wizard/src/main/java/com/wuxianggujun/tinaide/ui/wizard/NewProjectWizardActivity.kt`
- `feature/wizard/src/main/java/com/wuxianggujun/tinaide/ui/wizard/NewProjectWizardViewModel.kt`
- `feature/wizard/src/main/java/com/wuxianggujun/tinaide/ui/wizard/NewProjectWizardSupport.kt`
- `feature/wizard/src/main/java/com/wuxianggujun/tinaide/ui/wizard/NewProjectWizardScreen.kt`
- `core/plugin/src/main/java/com/wuxianggujun/tinaide/plugin/PluginManager.kt`
- `core/project/src/main/java/com/wuxianggujun/tinaide/project/ProjectCreationService.kt`
- `core/project/src/main/java/com/wuxianggujun/tinaide/project/ProjectTemplateInstaller.kt`
- `feature/help/src/main/assets/help/plugin-quick-start.md`

---

## 8. 后续可选改进

如果后续要继续提升体验，建议按优先级拆：

1. P1：插件模板列表增加分组或筛选，让插件模板和 C/C++ 模板更容易区分
2. P1：插件模板配置页增加更明确的“运行即热安装、打包生成 `.tinaplug`”说明
3. P2：插件项目创建完成后自动打开 `manifest.json`
4. P2：为插件项目提供专属的首次任务面板
5. 暂不建议：立即拆独立插件创建向导，除非插件模板需要大量专属字段

---

## 9. 2026-04-26 二次教程审查记录

本轮继续检查“插件教程”和“starter 模板”是否还有旧口径，发现并修正：

1. `Plugin-Authoring-Tutorial.md` 仍把插件创建描述成偏通用新建项目流程。
   - 已改为优先从“创建插件项目”快捷入口进入。
   - 已说明项目页 **+** 仍是通用入口，需要手动选择插件模板。

2. 教程仍有“菜单命令必须是宿主支持命令”的旧说法。
   - 已改为：菜单命令可以是宿主内置命令，或当前插件运行时注册的插件命令。
   - 已补充 `config` 插件和 `script` / `hybrid` 插件的差异。

3. 脚本教程示例比内置 `script-command` starter 简略。
   - 已同步为更接近 starter 的写法：集中维护命令 ID、注册命令时记录日志、调用编辑器 API 时检查返回值。

4. 应用内快速开始只说“新建项目”，没有强调教程快捷入口的插件语境。
   - 已改为优先点击“创建插件项目”。
   - 已补充 `TinaIDE Plugin Starters` 缺失时的排查路径。

5. `tools/plugin-starters/*/README.md` 源模板仍偏向手工 `validate` / `pack`。
   - 已改为 IDE 内点击 **运行** 优先，脚本打包用于离线分发。
   - 已修正 `config-basic` 的菜单命令边界说明。

6. starter zip 已根据源模板重新生成，并应同步到 Registry 发布目录。
   - `tina-config-plugin.zip`
   - `tina-script-command-plugin.zip`
   - `tina-script-plugin.zip`
   - `tina-lsp-plugin.zip`

7. 因内置插件安装逻辑会跳过同版本插件，已将 `tinaide.plugin.starters` 从 `1.0.0` 提升到 `1.0.1`，确保新版 starter 可覆盖安装。

---

## 10. 审阅验收清单

后续人工审阅或真机验证时，建议按下面顺序走，不需要先跑完整编译：

1. **教程快捷入口**
   - 打开设置页帮助中心，进入“插件开发快速开始”。
   - 点击“创建插件项目”。
   - 预期：进入“新建插件项目”，只看到 `Tina Config Plugin`、`Tina Script Command Plugin`、`Tina Script Plugin`、`Tina LSP Plugin`。

2. **项目页通用入口**
   - 回到项目页，点击右下角 **+**。
   - 预期：仍进入通用“新建项目”向导，普通模板和插件模板可以共存。
   - 这是刻意保留的通用入口，不应该强制只显示插件模板。

3. **插件模板缺失场景**
   - 临时禁用或移除 `TinaIDE Plugin Starters` 后，再从教程入口进入。
   - 预期：显示“暂无插件项目模板”，并禁用“下一步”。

4. **配置页字段**
   - 选择任意插件模板进入配置页。
   - 预期：不显示 C++ 标准这类无关字段，并显示插件项目下一步提示。

5. **创建后闭环**
   - 输入项目名并创建插件项目。
   - 打开创建出的项目后点击 **运行**。
   - 预期：IDE 走“校验 → 打包 → 热安装”插件闭环，而不是运行普通 C/C++ 程序。

6. **回归测试关注点**
   - `NewProjectWizardActivityIntentTest` 锁定插件入口必须携带 `EXTRA_PREFER_PLUGIN_TEMPLATE` 和默认 starter 模板 ID。
   - `NewProjectWizardSupportTest` 锁定插件模板过滤、空模板列表、缺失目标模板回退逻辑。

---

## 11. 插件项目运行 / 打包链路

插件项目创建后，点击 **构建** 或 **运行** 不应该进入普通 C/C++ 编译链路。

当前真实链路如下：

```text
顶部构建 / 运行按钮
└── CompileActionsHelper
    └── CompileProjectUseCase.execute()
        ├── BuildSystemDetector.detect(projectRoot)
        ├── BuildSystem.PLUGIN
        └── executePluginProjectAction()
            ├── BUILD → PluginProjectActions.build()
            │   └── 校验 + 打包 dist/<id>-<version>.tinaplug
            └── RUN / REBUILD_RUN / TERMINAL → PluginProjectActions.install()
                └── 校验 + 打包 + PluginManager.install() 热安装
```

关键判断点：

- `BuildSystemDetector` 优先读取 `.tinaide/project.json`，并能通过根目录 `manifest.json` 识别插件项目。
- `ProjectBuildSystem.PLUGIN` 会映射到 `BuildSystem.PLUGIN`。
- `CompileProjectUseCase` 一旦检测到 `BuildSystem.PLUGIN`，会提前返回插件项目动作，不再继续解析 CMake / Makefile / 单文件策略。
- `AndroidPluginProjectActions` 会调用 `PluginDoctor.inspectDirectory()` 做安装前同源诊断。
- 构建只生成 `.tinaplug`；运行会在打包后调用 `PluginManager.install()` 热安装。
- 成功热安装后，`CompileActionsHelper` 显示插件安装成功提示，并打开构建日志。

相关回归测试：

- `AndroidPluginProjectActionsTest`：验证构建会生成 `.tinaplug`、排除开发辅助文件，运行会委托 `PluginManager.install()`。
- `BuildSystemDetectorTest`：验证插件元数据和根目录插件 `manifest.json` 都会识别为 `BuildSystem.PLUGIN`。
- `CompileActionsHelperTest`：验证插件热安装成功后显示插件安装成功提示。

如果后续又出现“插件项目点击运行像普通 C/C++ 项目”的问题，优先检查：

1. 创建出来的 `.tinaide/project.json` 是否写入 `buildSystem: "PLUGIN"`。
2. 项目根目录 `manifest.json` 是否包含 `id`、`name`、`version`，以及插件 `type` 或插件贡献/权限字段。
3. `CompileProjectUseCase` 注入时是否传入 `PluginProjectActions`。
4. `AndroidPluginProjectActions` 是否能成功读取 manifest 并生成 `dist/*.tinaplug`。
