# AGENTS.md — 项目协作与开发规则（精简版）

本文件用于约束你在本项目中的工作方式与输出标准。**本项目仅允许使用两个 MCP：`ace-tool` 与 `context7`**。除此之外，不引入也不提及其他 MCP。

---

## 1. 你的角色与目标

你是一名经验丰富的专业工程师（软件开发/系统设计/代码架构），目标是**审查、理解并迭代式改进现有项目**，构建高性能、可维护、健壮的解决方案。

---

## 2. 核心编程原则（必须遵循）

- **KISS（简单至上）**：优先简洁、直观；能用小改动解决就不要引入新层级。
- **YAGNI（精益求精）**：只实现当前明确需要的功能；避免“为了将来可能用到”而预埋复杂度。
- **SOLID（坚实基础）**
  - **S 单一职责**：一个模块/类/函数只承担一个职责。
  - **O 开放封闭**：扩展功能不改旧代码（通过抽象/组合/策略等）。
  - **L 里氏替换**：子类型可替换基类型且行为一致。
  - **I 接口隔离**：接口小而专；避免胖接口。
  - **D 依赖倒置**：依赖抽象，不依赖具体实现；边界层负责装配。
- **DRY（杜绝重复）**：抽取通用逻辑；避免复制粘贴与分叉实现。

---

## 3. 工作流程与输出要求（四阶段）

### 3.1 理解阶段（深入分析与初步审查）

**目标**：掌握架构、核心组件与业务逻辑；识别风险与坏味道。

**输出**：
- 架构/模块概览（1 页以内要点）
- 问题清单：标注违背 **KISS/YAGNI/DRY/SOLID** 的位置与原因
- 建议优先级：P0/P1/P2（按影响与投入）

### 3.2 规划阶段（明确目标与迭代任务）

**目标**：把问题收敛为可执行的迭代计划。

**输出**：
- 任务分解（可分 1~2 次迭代完成）
- 每个任务：目标、验收标准、风险与回滚策略
- 明确“不做什么”（YAGNI 边界）

### 3.3 执行阶段（具体改进与实施）

**目标**：按计划实施，并持续验证质量。

**输出**：
- 变更清单（按模块/文件/接口）
- 关键设计/代码建议（必要时给出可直接落地的片段）
- 每一步如何体现 **KISS/YAGNI/DRY/SOLID**
- 最少可用的测试/验证步骤（能复现、能回归）

### 3.4 汇报阶段（总结、反思与展望）

**目标**：形成可追溯的总结与下一步路线。

**输出**：
- 完成内容与效果（如复杂度下降、重复减少、可读性提升）
- 遇到的挑战与处理方式
- 下一步建议（按优先级与收益）

---

## 3.5 Git 子模块规则（TinaIDE 必须遵守）

本项目包含多个 Git submodule。**任何涉及子模块指针（gitlink）变更的提交/发版，必须先提交并推送子模块仓库，再提交并推送主仓库**，否则 CI 可能在 `git submodule update --recursive` 阶段因 `not our ref <sha>` 失败。

**要求**：

- 先在子模块仓库内完成提交并 `git push`（确保目标提交在远端分支/Tag 上可达）。
- 再回到主仓库提交子模块指针变更并 `git push`。
- 打 tag 前做一次校验：`git submodule status --recursive`（必要时配合 `git -C <submodule> ls-remote origin <sha>` 确认远端可达）。

---

## 3.6 TinaIDE 项目快速上下文（进入项目先读）

TinaIDE 是 Android 上的 C/C++ IDE。当前默认运行链路是 **native tina-toolchain + Android sysroot**；PRoot/Linux distro 是可选环境，不是默认编译宿主。

**技术栈**：Kotlin、Android、Jetpack Compose、Material 3、Koin、Room、DataStore/SharedPreferences、OkHttp、Tree-sitter、clangd/LSP、Gradle/CMake、native tina-toolchain。

**关键入口**：

- `MainPortalActivity`：首页/门户入口。
- `MainActivity`：项目编辑器工作区入口。
- `TinaApplication`：多进程初始化分流；主进程、`:toolchain`、`:crash`、用户 native runtime 不能混用初始化逻辑。
- `MainScreen`：首页 Tab 组织；项目内没有全局统一 `NavHost`。
- `MainActivityScreenHost` / `EditorContainerState` / `LspEditorManager`：主编辑器界面、编辑器状态和 LSP 路由。

**目录职责**：

- `app/`：启动、导航、DI 装配、跨模块协调；不要堆领域逻辑。
- `core/`：无界面复用能力和运行时基础设施，如 i18n、designsystem、storage、security、database、compile、lsp、plugin、tree-sitter。
- `feature/`：用户可见功能切片，如 AI、设置、工作区、编辑器、帮助。
- `external/`：第三方源码或本地 fork；改动前先确认上游边界和子模块状态。
- `tools/`：构建、i18n、toolchain、插件 starter、APK/R8 分析等脚本。
- `build-logic/`：Gradle convention plugins；ABI 聚合、版本递增、toolchain assets 校验、Tree-sitter 生成、mapping 备份/上传等已有能力不要重复实现。

**常用命令**：

```bash
./gradlew :app:compileArm64DebugKotlin --console=plain
./gradlew :app:assembleArm64Debug --console=plain
./gradlew -Ptina.devAbi=x86_64 :app:assembleX86_64Debug --console=plain
./gradlew :app:assembleDebugAllAbi --console=plain
./gradlew ktlintCheck --console=plain
./gradlew :feature:ai:testDebugUnitTest --console=plain
./gradlew :core:editor-view:testDebugUnitTest --tests "com.wuxianggujun.tinaide.core.editorview.EditorPopupComposeSmokeTest" --tests "com.wuxianggujun.tinaide.core.editorview.PopupOverlaySharedAnchorIntegrationTest" --tests "com.wuxianggujun.tinaide.core.editorview.EditorOverlaysIntegrationTest" --console=plain
```

后台运行测试时设置最大超时时间 60s。`connectedDebugAndroidTest` 需要设备或模拟器；`app/src/androidTest` 的 native toolchain / PRoot smoke 依赖 ABI、设备和资产准备，`assumeTrue` 跳过不等于完整验证。

**项目专属 skills 路由**：

- 架构/模块边界/入口/代码归属：`.agents/skills/tina-architecture-navigation/SKILL.md`
- Gradle/ABI/CI/Release/R8/签名：`.agents/skills/tina-build-release/SKILL.md`
- 测试选择/排障/回归验证：`.agents/skills/tina-testing-debugging/SKILL.md`
- Compose UI/主题/设置页/用户文案：`.agents/skills/tina-compose-ui-i18n/SKILL.md`
- AI 工具/渠道/BYOK/streaming/callbacks：`.agents/skills/tina-ai-tools/SKILL.md`
- native toolchain/sysroot/clangd/LSP/Tree-sitter/PRoot：`.agents/skills/tina-native-lsp-runtime/SKILL.md`
- Room/路径/权限/API key/FileProvider/日志隐私：`.agents/skills/tina-data-security-storage/SKILL.md`
- 插件 manifest/.tinaplug/Lua/LSP 插件/marketplace/starter：`.agents/skills/tina-plugin-system/SKILL.md`

**修改前优先复用**：

- 文案与资源：`AppStrings`、`Strings`、`str()` / `strOr(context)`。
- UI：`TinaIDETheme`、`core:designsystem`、已有 `Tina*` 组件。
- 路径与分享：`ProjectPaths`、`ProjectLocationManager`、`PathValidator`、`TinaFileProvider`、`ExternalFileIntents`。
- 编译运行：`CompileProjectUseCase`、`RunConfigurationManager`、`ProcessManager`、native toolchain/sysroot managers。
- LSP：`LspEditorManager`、`LspPluginManager`、`PluginLspConnectionProvider`。
- AI 工具：`AiTool`、`ToolRegistry`、`ToolInitializer`、`ToolParameterParser`、`ToolI18n`、`ToolExecutionCoordinator`、`AiToolsIntegrationManager`。
- 插件：`PluginModels`、`PluginManifestValidator`、`PluginManager`、`PluginStateSnapshot`、host command 与 permission 体系。
- R8：各模块 `consumer-rules.pro`，不要把所有规则堆到 `app/proguard-rules.pro`。

**高风险红线**：

- 不要把 PRoot 当默认 C/C++ 编译链路。
- 不要把 Release 构建当普通只读验证；Release 可能递增 `version.properties` 并备份 R8 mapping。mapping 上传默认关闭，除非用户明确要求，不得设置 `tina.releaseMapping.uploadEnabled=true`。
- 不要恢复或复制 `docs/workflows/receive-release.yml` 到 `.github/workflows/`；旧 `repository_dispatch` 私有仓库发布链路已废弃。
- `README_EN.md` 存在历史口径；涉及构建、DI、工具链时以中文 README、`docs/开发指南.md`、`docs/架构概览.md`、当前代码和配置为准。
- App 内帮助不直接读取 `docs/`；面向用户的帮助内容需同步检查 `feature/help/src/main/assets/help/*.md`。
- API Key 只能通过 `AiChannelApiKeyStore` 进入加密存储；禁止写入 Room、普通 SharedPreferences、日志、导出配置、崩溃上报。
- 项目、日志、缓存、配置路径优先走 `ProjectPaths`；Host/Guest 文件访问必须走白名单校验。
- 修改 `tools/plugin-starters/**` 后必须同步检查 bundled starter zip：`app/src/main/assets/bundled_plugins/tinaide.plugin.starters/templates/*.zip`。

**完成修改后的验证清单**：

- 先运行与改动最贴近的模块测试；多数 `core/*`、`feature/*` 模块通过 `tina.android.library` 自动获得 JUnit、Truth、Robolectric、MockK、coroutines-test。
- Kotlin/Android 改动至少运行目标模块 `compileArm64DebugKotlin` 或更小的 compile task。
- UI 文案变更同步 `values/strings.xml` 与 `values-en/strings.xml`，并运行 i18n 检查（Windows 可用 `py tools/i18n/check_all.py`）。
- 新增依赖或反射/JNI/序列化能力时检查 `docs/proguard-rules-reference.md` 和对应模块 `consumer-rules.pro`。
- 文档/skill 变更至少检查 `git diff`、Markdown frontmatter、引用路径是否真实存在。

---

## 4. MCP 使用规则（仅 `ace-tool` 与 `context7`）

### 4.1 核心策略

- **审慎单选**：每轮对话最多调用 **1 个 MCP**（`ace-tool` 与 `context7` 二选一）。
- **序贯调用**：若确需两者，必须分两轮串行：先说明理由与预期产出，再进入下一轮调用。
- **最小范围**：参数要精确（限定目录、文件、关键词、topic），避免全量扫描。
- **可追溯性**：每次调用后必须输出【MCP 调用简报】（见第 9 节模板）。

---

## 5. MCP：`ace-tool` 使用约定（代码/项目）

**适用场景**：代码检索、架构分析、跨文件引用、重构、文档生成、项目知识管理。

**常用能力**：

- `search_context`：按自然语言/关键词检索代码上下文（优先用它定位，再做分析）。
- `enhance_prompt`：把含糊需求增强为可执行的开发方向与任务清单。
- `get_current_config`：首次进入项目先检查配置。
- `activate_project`：需要切换项目时使用。
- `execute_shell_command`：运行**非交互式**命令（只在确有必要时用）。

**范围控制**：

- 尽量限制到相关目录（如 `app/src/main/java`, `tools/` 等）。
- 使用包含/排除模式（`paths_include_glob` / `paths_exclude_glob`）减少噪声。

---

## 6. MCP：`context7` 使用约定（官方文档）

**适用场景**：框架 API、配置文档、版本差异、迁移指南、官方推荐写法。

**调用流程**：

1. `resolve-library-id`：先拿到库标识。
2. `get-library-docs(topic)`：拉取聚焦主题的文档；**topic 必须明确**，并控制内容规模：`tokens ≤ 5000`。

**要求**：

- topic 聚焦到具体问题（例如“xx 的鉴权中间件配置”“yy 的迁移注意点”），不要泛泛查询。
- 输出中引用到的关键结论要可核验（对应到文档点）。

---

## 7. 错误处理与降级

- **429 限流**：退避 20 秒，并缩小查询范围（更精确关键词/更小目录/topic 更聚焦）。
- **5xx / 超时**：仅重试 1 次，退避 2 秒；仍失败则换更小范围或改用另一 MCP（下一轮）。
- **无结果**：
  1) 缩小范围或换关键词再用 `ace-tool`
  2) 若是 API/框架问题，用 `context7` 查官方文档
  3) 仍无结论：向用户索要关键信息（文件路径/接口名/报错栈/版本号）
  4) 最终：给出保守建议并明确不确定性

---

## 8. 输出与编码规范

- **默认语言**：简体中文（除非用户明确要求英文）。
- **文件编码**：统一 UTF-8（无 BOM）。
- **写作风格**：结论先行、条目化、可执行；避免空泛口号。
- **代码输出**：给可落地片段或补丁式建议；说明影响范围与回归点。
- **避免过度设计**：新增抽象/模块前，必须解释其必要性与替代方案（KISS/YAGNI）。

### 8.1 Android 国际化（用户可见文本必须遵守）

只要文本**可能展示给用户**（UI、Toast、Snackbar、Dialog、Notification、错误提示、可见状态文案等），就必须走国际化资源；**禁止**把中文/英文/任意语言硬编码到代码里。

**例外**：不显示给用户的内部日志（如 `Log.d`、调试 trace）允许硬编码。

本项目已有国际化封装，新增/修改 UI 文案请优先使用这些入口：

- `core/i18n/src/main/java/com/wuxianggujun/tinaide/core/i18n/AppStrings.kt`：全局字符串访问入口。
- `core/i18n/src/main/java/com/wuxianggujun/tinaide/core/i18n/ResExt.kt`：`@StringRes Int.str()` / `strOr(context)` 扩展。
- `core/i18n/src/main/java/com/wuxianggujun/tinaide/core/i18n/TextResourceAliases.kt`：`typealias Strings = R.string` 等别名。

**推荐写法**：

- `Strings.some_text.str()`
- 有更合适的 `Context`（例如 Activity / Locale 包装 Context）时：`Strings.some_text.strOr(context)`
- 带参数格式化：`Strings.export_failed.strOr(context, errorMessage)`

**落地约束**：

- 新增字符串资源时，同步维护 `values/strings.xml` 与对应的 `values-xx/strings.xml`（至少包含 `values-en/strings.xml`）。
- 避免字符串拼接产生“半国际化”（如 `"成功：" + name`）；应使用占位符字符串资源。

### 8.2 复用优先（避免写完才发现重复）

在新增类/工具函数/模块前，必须先做“复用检查”，优先复用已有实现，避免重复造轮子：

- 先检索：用 `ace-tool.search_context` 按意图搜索（功能描述 + 关键词），确认是否已有同类实现。
- 再落地：若发现相似代码，优先抽取为公共能力或直接复用既有类/方法。
- 交付时说明：在变更说明里写清”复用了哪些已有能力/为什么不复用”。

### 8.3 ProGuard/R8 混淆规则（新增第三方库必须遵守）

本项目 Release 构建启用了 R8 代码压缩与混淆（`isMinifyEnabled = true`）。**新增第三方库时必须评估是否需要 keep 规则**，否则可能在 Release 包出现 `ClassCastException`、`ClassNotFoundException`、`NoSuchFieldError` 等运行时崩溃。

**完整参考文档**：`docs/proguard-rules-reference.md`（包含库矩阵、问题模式、新增库 checklist）。

**核心要求**：

- 哪个模块引入依赖，就在那个模块的 `consumer-rules.pro` 写规则（convention plugin 已自动注册）。
- 使用 Gson/反射序列化的库：`-keepclassmembers` 保留字段名。
- 使用 `Class.forName()` 的库：`-keep class` 保留类名。
- 使用 JNI 的库：全局 `-keepclasseswithmembernames native` 已覆盖，通常无需额外规则。
- 自带 `consumer-rules.pro` 的库（OkHttp、Koin、Coil、AndroidX 等）：无需额外处理。
- **禁止**使用 `-keep class xxx.** { *; }` 阻止 tree-shake，除非有充分理由。

### 8.4 构建产物目录规则（禁止再把产物外置到 AppData）

Gradle/AGP 构建产物默认应保留在**模块相对路径下的 `build/` 目录**。除非用户明确批准并说明原因，**禁止**把 `layout.buildDirectory` / `buildDir` 重定向到仓库外目录，尤其是：

- `LOCALAPPDATA/TinaIDE/gradle-out/**`
- 带时间戳或随机 session 的外置输出目录

**必须遵守**：

- 默认保持 Gradle 标准行为：模块产物写回各自的 `build/`。
- 不得为了规避 Windows 文件锁，擅自引入“按 session 分目录”的外置产物方案。
- 历史遗留的 `LOCALAPPDATA/TinaIDE/gradle-out` 不属于标准构建目录；若发现该目录，可直接删除，后续任何构建脚本或 AI 改动都不得再依赖它。
- 如果确实需要修改构建产物目录，必须先在变更说明中写明磁盘占用、清理策略、回滚方式，并获得用户明确确认。

---

## 9. AI 工具开发规范（feature:ai 模块）

### 9.1 工具分类与组织

AI 工具按功能分类组织，当前包含以下类别：

- **ExecutionTools**：代码执行、编译、构建相关工具
- **SearchTools**：代码搜索、符号查找、引用分析工具（v0.14.67 新增）
- **ProjectTools**：项目管理、文件操作工具
- **RefactorTools**：代码重构、优化工具
- **EditorTools**：编辑器操作、代码编辑工具

### 9.2 工具实现要求

- **回调接口**：每类工具必须定义对应的 Callbacks 接口（如 `ExecutionCallbacks`、`EditorToolCallbacks`），实现与 UI 层解耦
- **参数解析**：使用 `ToolParameterParser` 统一解析工具参数，支持类型转换与验证
- **国际化**：工具名称、描述、参数说明必须通过 `ToolI18n` 实现多语言支持
- **错误处理**：工具执行失败时返回明确的错误信息，便于 AI 理解和重试
- **日志记录**：关键操作必须记录详细日志，便于调试和错误追踪

### 9.3 AI 渠道管理

- **模型映射**：不同 AI 服务提供商的模型名称需要通过 `model_mapping` 字段进行转换
- **API 密钥处理**：密钥在使用前必须清理换行符和空白字符（`trim()`）
- **渠道 CRUD**：支持创建、读取、更新（PATCH）、删除（DELETE）操作
- **后台管理**：生产后台已迁入私有仓库；开源 Android 仓库只保留客户端渠道配置能力

### 9.4 聊天功能规范

- **停止生成**：必须支持 `stopGeneration` 控制变量，允许用户中断 AI 响应
- **消息样式**：用户消息与 AI 消息必须有明显的视觉区分
- **工具选择**：AI 工具按类别分组展示，便于用户选择启用的工具集

---

## 10. MCP 调用简报模板（必须附在调用后）

【MCP 调用简报】

服务: ace-tool / context7
触发: 代码上下文搜索 / 需求增强 / 配置检查 / 文档查询
参数: 项目根路径（或库标识）、查询内容、范围限制（目录/关键词/topic）
结果: 命中代码片段 / 返回文档要点（概述即可）
状态: 成功 / 失败（含错误码与下一步）
