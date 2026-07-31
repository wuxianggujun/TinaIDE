# TinaIDE 架构优化任务清单

> 更新日期：2026-07-03
> 状态：推进中
> 范围：主工作区、编辑器、文件操作链路、LSP 连接链路、模块装配与测试维护性

## 1. 目标

本任务文档用于跟踪 TinaIDE 后续架构优化工作。目标不是一次性大重构，而是把已经暴露出问题的链路逐步收口，让主编辑器、侧边栏、RikkaHub 宿主入口、插件事件和项目文件状态保持一致。

核心目标：

- 降低 `app` 层继续膨胀的风险。
- 统一用户项目文件操作入口，避免删除、重命名、移动文件后编辑器和文件树状态不同步。
- 清理编辑器/LSP 链路中的阻塞点，降低卡顿、补全异常和交互延迟风险。
- 拆小主工作区装配层，减少参数爆炸和跨模块隐式耦合。
- 逐步拆分高复杂度类，保持 KISS、DRY、SOLID。

## 2. 明确暂缓项

以下事项暂时不纳入本轮任务：

- Manifest 暴露面和权限收紧。
  - 包括 `usesCleartextTraffic`、`exported`、高权限声明、provider 暴露策略等。
  - 当前先记录为后续安全专项，不影响本任务推进。
- 大规模目录重组。
- 全量模块依赖重构。
- PRoot 默认链路替换。
- Release 构建流程调整。

## 3. 当前主要问题

### P0-1：用户项目文件操作入口不够统一

现象：

- 文件删除、重命名、移动可能由不同入口直接调用 `delete()`、`deleteRecursively()`、`renameTo()`。
- 一旦绕过统一文件操作服务，文件树、编辑器 tab、插件事件、RikkaHub 侧边栏上下文就可能不同步。
- 最近“侧边栏删除已打开文件后 Toast 已删除但编辑器无反应”的问题就属于这个类型。

重点关注文件：

- `app/src/main/java/com/wuxianggujun/tinaide/ui/ProjectManagerViewModel.kt`
- `app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/FileTreeDialogs.kt`
- `app/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/main/project/ProjectScreen.kt`

### P0-2：编辑器/LSP 链路仍有阻塞点

现象：

- 生产代码中仍存在少量 `runBlocking` 和 `Thread.sleep(...)`。
- 如果这些逻辑进入编辑器交互、补全请求、LSP 连接探测路径，可能放大卡顿、补全延迟、焦点/光标异常。

重点关注文件：

- `core/lsp/src/main/java/com/wuxianggujun/tinaide/core/lsp/RemoteLspConnectionProvider.kt`
- `core/plugin/src/main/java/com/wuxianggujun/tinaide/plugin/lsp/PluginLspConnectionProvider.kt`
- `core/lsp/src/main/java/com/wuxianggujun/tinaide/core/lsp/NativeClangdConnectionProvider.kt`
- `core/proot/src/main/java/com/wuxianggujun/tinaide/core/proot/PRootManager.kt`
- `core/logging/src/main/java/com/wuxianggujun/tinaide/core/logging/FileLoggingTree.kt`

### P1-1：主工作区装配层参数过多

现象：

- `MainActivityScreenHost` 同时接收 Activity、多个 ViewModel、多个 manager、bridge、delegate、state 和 action。
- 参数爆炸会让后续主界面改动更容易互相影响，也增加测试构造成本。

重点关注文件：

- `app/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/main/MainActivityScreenHost.kt`
- `app/src/main/java/com/wuxianggujun/tinaide/MainActivity.kt`
- `app/src/main/java/com/wuxianggujun/tinaide/ui/MainActivityBindings.kt`

### P1-2：高复杂度类需要按职责逐步拆分

候选热点：

- `app/src/main/java/com/wuxianggujun/tinaide/ui/compose/state/editor/EditorContainerState.kt`
- `app/src/main/java/com/wuxianggujun/tinaide/ui/compose/state/editor/LspEditorManager.kt`
- `app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/editor/TinaCodeEditorPage.kt`
- `core/compile/src/main/java/com/wuxianggujun/tinaide/core/compile/cmake/NativeCMakeBuildExecutor.kt`
- `core/proot/src/main/java/com/wuxianggujun/tinaide/core/proot/PRootManager.kt`
- `feature/settings/src/main/java/com/wuxianggujun/tinaide/ui/compose/screens/settings/sections/PluginsSettingsSection.kt`

## 4. 迭代计划

### 迭代一：统一项目文件操作事件链路

优先级：P0

目标：

- 所有用户项目文件的创建、删除、重命名、移动统一进入一个可观测链路。
- 文件变更后统一通知文件树、编辑器、插件和 RikkaHub 侧边栏上下文。
- 避免再次出现“Toast 成功但编辑器状态没有变化”的问题。

任务：

- 梳理当前所有直接文件操作入口，按类型分组：
  - 用户项目文件操作。
  - 项目列表/工作区管理。
  - 模板文件管理。
  - 缓存、日志、runtime 资产清理。
- 在用户项目文件操作范围内复用或扩展 `IFileOperations`。
- 必要时增加轻量协调器，例如 `ProjectFileMutationCoordinator`。
- 删除、重命名、移动目录时，需要覆盖目录下已打开文件。
- 对脏 tab 不直接丢弃内容，应触发未保存确认。
- RikkaHub 侧边栏入口和侧边栏文件树必须走同一套结果同步规则。

验收标准：

- 删除已打开文件后，对应干净 tab 自动关闭。
- 删除包含已打开文件的目录后，目录内干净 tab 自动关闭。
- 删除脏文件后，不强制丢内容，弹出未保存确认。
- 重命名已打开文件后，编辑器 tab 路径同步更新，或明确关闭/重新打开策略。
- 侧边栏触发的删除/重命名文件与 UI 文件树行为一致。
- 重复收到文件删除事件时逻辑幂等，不崩溃、不重复弹窗。

建议验证：

```powershell
.\gradlew :app:testArm64DebugUnitTest --tests "com.wuxianggujun.tinaide.ui.compose.state.editor.EditorContainerStateTest" --console=plain
```

建议补充测试：

- 删除已打开单文件。
- 删除包含多个已打开文件的目录。
- 删除脏文件。
- 重命名已打开文件。
- 侧边栏重复删除同一文件。
- 文件树事件重复到达。

回滚策略：

- 保留现有 `IFileOperations` 行为。
- 新协调器只接管用户项目文件操作，不接管缓存、日志、runtime 清理。
- 如果新链路出现问题，可临时让具体入口回退到旧实现。

### 迭代二：清理编辑器/LSP 阻塞点

优先级：P0

目标：

- 移除 LSP 连接链路中的同步阻塞。
- 避免补全、诊断、光标交互、文件打开过程被 `runBlocking` 或 `Thread.sleep` 放大延迟。

任务：

- 审查 `RemoteLspConnectionProvider` 的 `runBlocking` 调用。
- 审查 `PluginLspConnectionProvider` 的 `runBlocking` 调用。
- 将连接探测改为 `suspend` + timeout。
- 将固定 `Thread.sleep(...)` 改为 coroutine `delay(...)` 或明确后台线程等待策略。
- 对确实运行在专用后台线程的 sleep 保留注释，说明不会阻塞 UI 和编辑器交互。

验收标准：

- LSP 连接失败时不会卡住 UI。
- LSP 连接超时有明确时间上限。
- 补全请求不会因连接探测同步阻塞而明显延迟。
- 相关单元测试可稳定运行，不依赖真实 sleep。

建议验证：

```powershell
.\gradlew :app:testArm64DebugUnitTest --tests "*Lsp*" --console=plain
```

如测试范围过大，先运行具体新增测试类。

回滚策略：

- 保留旧连接流程接口。
- 新 suspend 实现通过小范围适配接入，不一次性改完整 LSP 架构。

### 迭代三：主工作区装配参数分组

优先级：P1

目标：

- 降低 `MainActivityScreenHost` 参数数量。
- 让主工作区依赖按职责分组，减少后续改动冲突。

任务：

- 按职责引入轻量参数对象：
  - `MainActivityViewModels`
  - `MainActivityServices`
  - `MainActivityBridges`
  - `MainActivityDelegates`
  - `MainActivityRuntimeState`
- 保持对象只做分组，不放业务逻辑。
- 每次只迁移一组参数，避免大规模改动。

验收标准：

- `MainActivityScreenHost` 顶层参数明显减少。
- 参数对象职责清晰，不变成新的“大杂烩”。
- 原有主界面行为不变。
- 代码 review 时能快速判断新增依赖应该放在哪个分组。

建议验证：

```powershell
.\gradlew :app:compileArm64DebugKotlin --no-daemon --console=plain
```

回滚策略：

- 参数对象是纯结构调整，可按提交回滚。
- 不修改业务状态流，降低行为回归风险。

### 迭代四：拆分高复杂度状态类和 UI 文件

优先级：P1

目标：

- 降低编辑器、LSP、构建、PRoot、插件设置等热点文件复杂度。
- 每次只抽一个稳定职责，不做目录级大迁移。

推荐拆分顺序：

1. `EditorContainerState`
   - tab 生命周期。
   - split editor 状态。
   - 文件删除/重命名响应。
   - session 持久化。
2. `LspEditorManager`
   - LSP session routing。
   - 文档同步。
   - completion/diagnostic 分发。
3. `TinaCodeEditorPage`
   - 编辑器主体。
   - 顶部/底部状态区域。
   - overlay/menu/selection 相关 UI。
4. `NativeCMakeBuildExecutor`
   - 命令构造。
   - 环境变量。
   - 进程执行。
   - 日志解析。
5. `PRootManager`
   - 安装/校验。
   - 启动参数。
   - runtime 状态检查。

验收标准：

- 每次拆分后原有测试通过。
- 新类有明确单一职责。
- 不增加新的跨层依赖。
- 文件变小但调用关系更清晰，而不是简单搬代码。

建议验证：

```powershell
.\gradlew :app:compileArm64DebugKotlin --no-daemon --console=plain
```

涉及 core 模块时运行对应模块测试或编译任务。

回滚策略：

- 每次只拆一个职责点。
- 每个拆分点单独提交，方便按提交回滚。

### 迭代五：维护性检查自动化

优先级：P2

目标：

- 把容易反复出现的问题变成可检查项。

任务：

- 增强 i18n 检查，覆盖 Toast、Dialog、Snackbar、用户可见状态文案。
- 测试代码逐步从 `runBlocking` / `Thread.sleep` 迁移到 `runTest`。
- 整理 `settings.gradle.kts` 中 task-based include 逻辑，增加注释或改为显式 Gradle property。
- 建立“直接文件操作扫描”检查，避免用户项目文件再次绕过统一链路。

验收标准：

- 新增用户可见文案能被 i18n 检查发现。
- 新增直接 `delete()`、`deleteRecursively()`、`renameTo()` 的用户项目文件操作需要 review 说明。
- task-based include 逻辑有清晰注释或显式开关。

建议验证：

```powershell
py tools/i18n/check_all.py
```

```powershell
rg "deleteRecursively|\\.delete\\(|renameTo\\(" app feature core
```

## 5. 推荐执行顺序

1. 统一项目文件操作事件链路。
2. 清理编辑器/LSP 阻塞点。
3. 主工作区装配参数分组。
4. 按职责拆分高复杂度类。
5. 增强维护性检查自动化。

## 6. 设计边界

- 优先小步重构，不做一次性大改。
- 优先复用现有 `IFileOperations`、`EditorContainerState`、`LspEditorManager`、`ProjectPaths` 等已有能力。
- 不把业务逻辑继续上移到 `app` 层。
- 不为当前没有明确需求的能力提前设计复杂框架。
- 每个迭代都必须有可运行的验证命令。

## 7. 推进记录

### 2026-06-17 补充：P1-2 第十二步

- 已推进 P1-2 的第十二步：收口 `LspEditorManager` 的 tab 请求跟踪职责。
  - 新增 `LspTabRequestTracker`，集中维护 tab 请求 generation、inflight `CompletableFuture` 注册/移除、tab 请求 ticket 有效性校验和全量 drain 清理。
  - `LspEditorManager` 保留原有请求创建、有效性判断和等待入口，内部改为委托 tracker；不改 LSP 请求发起、超时、取消、completion/semantic tokens/navigation 行为。
  - 新增 `LspTabRequestTrackerTest`，覆盖请求失效、inflight 归还、untrack 清理、URI/连接状态校验和 drain 清理。
  - 本步不调整 LSP session routing、provider attach、协议请求参数和 UI 状态分发策略。
- 本轮补充验证：
```powershell
.\gradlew :app:testArm64DebugUnitTest --tests "com.wuxianggujun.tinaide.ui.compose.state.editor.LspTabRequestTrackerTest" --tests "com.wuxianggujun.tinaide.ui.compose.state.editor.LspRoutingSupportTest" --console=plain
```

结果：`BUILD SUCCESSFUL`。

### 2026-06-17

- 已推进 P0-1：用户项目文件操作事件链路。
  - 侧栏删除、重命名改为复用 `IFileOperations`，避免 UI 入口直接调用 `delete()` / `renameTo()` 后遗漏编辑器同步。
  - `FileChangeListener` 增加重命名事件，`FileManager` 的重命名/移动事件不再退化为 delete + create。
  - 编辑器支持删除文件/目录时关闭受影响 tab；脏 tab 触发未保存确认；目录删除覆盖子文件。
  - 编辑器支持重命名/移动文件或目录后 retarget 已打开 tab、底层 session、recent files 与 LSP 绑定。
  - 文件删除/移动逻辑委托 `IFileOperations`，并保留编辑器同步兜底。
  - 新增/扩展 `EditorContainerStateTest` 覆盖删除、目录删除、脏 tab、重命名/移动链路。
- 已推进 P0-2 的低风险部分：LSP/运行时等待点。
  - `NativeClangdConnectionProvider` 的启动探测从固定 `Thread.sleep` 改为 `Process.waitFor(timeout)`。
  - `PRootManager` 的退出等待从手动 sleep 轮询改为 `Process.waitFor(timeout)`。
  - `RemoteLspConnectionProvider.start()` 与 `PluginLspConnectionProvider` 的同步探测当前经 `LspEditorManager` 在 `Dispatchers.IO` 内调用，暂不做接口级大改。
- 本轮验证：

```powershell
.\gradlew :app:testArm64DebugUnitTest --tests "com.wuxianggujun.tinaide.ui.compose.state.editor.EditorContainerStateTest" --console=plain
```

结果：`BUILD SUCCESSFUL`。

### 2026-06-17 补充：P2 第二步

- 已推进 P2 的第二步：新增维护性检查总入口。
  - 新增 `tools/checks/check_all.py`，串行运行稳定的维护性检查。
  - 默认只运行低噪声的直接文件操作基线检查，保证本地收尾检查可稳定通过。
  - `--include-i18n` 可选接入 `tools/i18n/check_all.py`，避免当前阶段把 i18n 检查强制并入默认入口。
  - 更新 `tools/checks/README.md`，补充总入口命令和可选 i18n 参数。
- 本轮补充验证：

```powershell
py tools/checks/check_all.py
```

结果：`OK: all maintenance checks passed.`

### 2026-06-17 补充：P2 第一步

- 已推进 P2 的第一步：新增直接文件操作基线检查。
  - 新增 `tools/checks/check_direct_file_operations.py`，扫描生产 Kotlin/Java 源码中的 `deleteRecursively(...)`、`renameTo(...)` 和 `.delete()`。
  - 新增 `tools/checks/direct_file_operations_allowlist.txt`，按文件、操作类型和现有次数记录已审查基线，并要求每个基线项写明原因。
  - 新增 `tools/checks/README.md`，说明检查范围、运行命令和用户项目文件应优先走 `IFileOperations` 的约束。
  - 本步不改任何运行时代码，只把“新增直接文件操作必须被审查”变成可执行检查。
- 本轮补充验证：

```powershell
py tools/checks/check_direct_file_operations.py
```

结果：`OK: direct file operation baseline matched (101 entries, 175 operations).`

### 2026-06-17 补充：P1-2 第十一步

- 已推进 P1-2 的第十一步：收口 Peek Definition 面板状态职责。
  - 新增 `EditorPeekDefinitionState`，集中维护 peek definition panel 的 loading、results 和 owner 限定关闭逻辑。
  - `EditorContainerState` 保留 `peekDefinitionPanelState`、`showPeekDefinitionLoading(...)`、`showPeekDefinitionResults(...)`、`dismissPeekDefinitionPanel(...)` 对外入口，内部委托状态类。
  - 新增 `EditorPeekDefinitionStateTest`，覆盖 loading 展示、结果替换和非 owner tab 不关闭面板。
  - 本步不改变 LSP 导航代理、`TinaCodeEditorPage` 订阅方式、面板 UI 或 tab 关闭时的 dismiss 策略。
- 本轮补充验证：

```powershell
.\gradlew :app:testArm64DebugUnitTest --tests "com.wuxianggujun.tinaide.ui.compose.state.editor.EditorContainerStateTest" --tests "com.wuxianggujun.tinaide.ui.compose.state.editor.EditorPeekDefinitionStateTest" --console=plain
```

结果：`BUILD SUCCESSFUL`。

### 2026-06-17 补充：P1-2 第十步

- 已推进 P1-2 的第十步：收口 DocumentSession/editor binding 桥接职责。
  - 新增 `EditorDocumentSessionCoordinator`，集中封装 `IEditorManager.getSession(...)` / `getSessionState(...)` 相关转调。
  - `EditorContainerState` 保留 toolbar state flow、last edit flow、session alert flow、editor binding、detached snapshot、cursor/scroll/content dirty 通知等对外入口，内部委托会话协调器。
  - `notifyTabSelectionChanged(...)` 仍保留在 `EditorContainerState`，因为它依赖 tab 查找和插件 selection event 语义，不混入 session 桥接类。
  - 本步不改变 DocumentSession 状态流、editor binding 生命周期、detached snapshot 恢复和插件 selection 事件策略。
- 本轮补充验证：

```powershell
.\gradlew :app:testArm64DebugUnitTest --tests "com.wuxianggujun.tinaide.ui.compose.state.editor.EditorContainerStateTest" --console=plain
```

结果：`BUILD SUCCESSFUL`。

### 2026-06-17 补充：P1-2 第九步

- 已推进 P1-2 的第九步：收口 Save All 成功通知追踪职责。
  - 新增 `EditorSaveAllNotificationTracker`，集中维护 save-all 前的 dirty tab 快照和保存结果到成功通知目标的映射。
  - `EditorContainerState` 保留 `rememberDirtyTabsForSaveAllNotification()`、`resolveSuccessfulSaveAllNotificationTargets(...)` 和 `notifySuccessfulSaveAllResults(...)` 对外入口，内部委托追踪器。
  - `EditorContainerState` 仍负责真正触发 `notifyFileSaved(...)`，避免把插件保存事件分发和 LSP 保存通知语义搬入追踪类。
  - 本步不改变 save-all 结果顺序匹配、失败结果过滤和保存成功通知策略。
- 本轮补充验证：

```powershell
.\gradlew :app:testArm64DebugUnitTest --tests "com.wuxianggujun.tinaide.ui.compose.state.editor.EditorContainerStateTest" --console=plain
```

结果：`BUILD SUCCESSFUL`。

### 2026-06-17 补充：P1-2 第八步

- 已推进 P1-2 的第八步：收口 LSP UI 状态职责。
  - 新增 `EditorLspUiState`，集中维护 tab LSP status map、status flow、插件 LSP 依赖告警序号生成与消费。
  - `EditorContainerState` 保留 `pluginLspDependencyAlert`、`consumePluginLspDependencyAlert()`、`getLspStatus(...)`、`getLspStatusFlow(...)` 等对外入口，内部委托 LSP UI 状态类。
  - `EditorFileMutationCoordinator` 不再直接持有 LSP status map，文件移动/重命名后的 tab id remap 改为委托给 `EditorLspUiState`。
  - 本步不改变 LSP 状态来源、底部状态展示、插件依赖告警展示和 LSP 释放策略。
- 本轮补充验证：

```powershell
.\gradlew :app:testArm64DebugUnitTest --tests "com.wuxianggujun.tinaide.ui.compose.state.editor.EditorContainerStateTest" --console=plain
```

结果：`BUILD SUCCESSFUL`。

### 2026-06-17 补充：P1-2 第七步

- 已推进 P1-2 的第七步：收口 LSP diagnostics 状态职责。
  - 新增 `EditorDiagnosticsState`，集中维护 diagnostics state map、外部 diagnostics observer、file URI 归一化后的写入/读取/清理和插件事件分发。
  - `EditorContainerState` 保留 `onLspDiagnosticsChanged` 与 `getDiagnosticsFlow(...)` 对外入口，内部委托 diagnostics 状态类。
  - `EditorFileMutationCoordinator` 不再直接持有 diagnostics map，文件移动/重命名后的旧路径 diagnostics 清理改为委托给 `EditorDiagnosticsState`。
  - 本步不改变 LSP publishDiagnostics 来源、底部诊断面板订阅方式、插件事件语义和 UI 展示策略。
- 本轮补充验证：

```powershell
.\gradlew :app:testArm64DebugUnitTest --tests "com.wuxianggujun.tinaide.ui.compose.state.editor.EditorContainerStateTest" --console=plain
```

结果：`BUILD SUCCESSFUL`。

### 2026-06-17 补充：P1-2 第六步

- 已推进 P1-2 的第六步：收口 CodeEditor runtime 缓存职责。
  - 新增 `EditorCodeRuntimeCache`，集中维护 CodeEditor runtime 的 LRU 缓存、Tree-sitter highlighter/folding provider 创建、runtime loaded 标记、tab id remap 和释放。
  - `EditorContainerState` 保留对外 runtime 访问方法，内部委托缓存类，避免 UI 调用方变更。
  - `EditorFileMutationCoordinator` 不再直接持有 runtime map，文件移动/重命名后的 runtime tab id remap 改为委托给 `EditorCodeRuntimeCache`。
  - 本步不改变 CodeEditor runtime 缓存上限、脏 tab 保护、split active tab 保护、已绑定 editor callback 保护和 Tree-sitter provider 创建策略。
- 本轮补充验证：

```powershell
.\gradlew :app:testArm64DebugUnitTest --tests "com.wuxianggujun.tinaide.ui.compose.state.editor.EditorContainerStateTest" --console=plain
```

结果：`BUILD SUCCESSFUL`。

### 2026-06-17 补充：P1-2 第五步

- 已推进 P1-2 的第五步：收口 tab 生命周期清理职责。
  - 新增 `EditorTabLifecycleCoordinator`，集中处理 tab 关闭后的 LSP 释放、CodeEditor runtime 清理、pane 状态清理、搜索状态清理和 peek panel 关闭。
  - `EditorContainerState` 保留 tab 打开、选择、导航和 split editor 编排逻辑，不再在多个关闭入口重复散落资源释放代码。
  - `syncFromManager`、`requestCloseTab`、`confirmSaveAndClose`、`confirmDiscardAndClose`、`closeOtherTabs`、`closeAllTabs` 改为委托生命周期协调器处理关闭后资源清理。
  - 本步不改变 tab 关闭确认、脏文件保护、split pane 选择、LSP 路由和 editor runtime 缓存策略。
- 本轮补充验证：

```powershell
.\gradlew :app:testArm64DebugUnitTest --tests "com.wuxianggujun.tinaide.ui.compose.state.editor.EditorContainerStateTest" --console=plain
```

结果：`BUILD SUCCESSFUL`。

### 2026-06-17 补充：P1-2 第四步

- 已推进 P1-2 的第四步：收口 split editor 的 pane/mirror/active tab 基础状态维护。
  - 新增 `EditorSplitPaneState`，集中维护 tab 所属 pane、镜像 tab、pane active tab 三组 Compose state map。
  - `EditorContainerState` 保留 split editor 的业务动作编排，只通过 `EditorSplitPaneState` 读写底层 pane 状态。
  - `EditorFileMutationCoordinator` 不再直接持有三组 pane map，文件移动/重命名后的 tab id remap 改为委托给 `EditorSplitPaneState`。
  - 本步不改变 split editor UI、tab 选择、镜像打开、关闭 pane、关闭 tab 和文件 mutation 行为。
- 本轮补充验证：

```powershell
.\gradlew :app:testArm64DebugUnitTest --tests "com.wuxianggujun.tinaide.ui.compose.state.editor.EditorContainerStateTest" --console=plain
```

结果：`BUILD SUCCESSFUL`。

- 已推进 P1-2 的第二步：拆分 `EditorContainerState` 中的导航历史职责。
  - 新增 `EditorNavigationHistoryManager`，集中维护 back/forward 栈、跳转去重、历史长度限制和前进/后退逻辑。
  - `EditorContainerState` 只提供当前导航位置和打开目标位置两个回调，减少主状态类对导航栈细节的直接维护。
  - 文件移动/重命名 retarget 仍通过 `EditorFileMutationCoordinator` 更新导航栈，保持跨职责协作路径清晰。
  - 本步不改变导航 API、打开文件逻辑、光标跳转逻辑和 UI 行为。
- 本轮补充验证：
```powershell
.\gradlew :app:testArm64DebugUnitTest --tests "com.wuxianggujun.tinaide.ui.compose.state.editor.EditorContainerStateTest" --console=plain
```

结果：`BUILD SUCCESSFUL`。

- 已推进 P1-2 的第一步：拆分 `EditorContainerState` 中的文件变更响应职责。
  - 新增 `EditorFileMutationCoordinator`，集中处理已打开 tab 对删除、移动、重命名路径的响应。
  - `EditorContainerState` 保留 `closeTabsForDeletedPath` / `syncTabsForMovedPath` 对外 API，内部委托给协作者，降低状态类职责密度。
  - 路径归一化能力抽为同包 `normalizeOpenTabLookupPath`，避免删除/移动响应与主状态类重复实现。
  - 本步不调整 tab 生命周期、split editor 规则、LSP 路由接口和 UI 行为，只做职责边界拆分。
- 本轮补充验证：
```powershell
.\gradlew :app:testArm64DebugUnitTest --tests "com.wuxianggujun.tinaide.ui.compose.state.editor.EditorContainerStateTest" --console=plain
```

结果：`BUILD SUCCESSFUL`。

- 已推进 P1-1：主工作区装配参数分组。
  - 新增 `MainActivityContentViewModels`、`MainActivityContentServices`、`MainActivityContentBridges`、`MainActivityContentDelegates`、`MainActivityExternalFileActions` 作为纯参数对象。
  - `installMainActivityContent` 和 `MainActivityScreenHost` 改为按职责接收参数分组，降低顶层参数数量。
  - 参数对象只做装配分组，不承载业务逻辑，不改变原有状态流、回调流和 UI 行为。
  - 参数对象在 `setContent` 外创建，避免 Compose 重组时重复创建依赖分组。
- 本轮补充验证：
```powershell
.\gradlew :app:testArm64DebugUnitTest --tests "com.wuxianggujun.tinaide.ui.compose.state.editor.EditorContainerStateTest" --console=plain
```

结果：`BUILD SUCCESSFUL`。

### 2026-06-17 补充：P1-2 第三步

- 已推进 P1-2 的第三步：拆分 split editor 会话持久化/恢复协调职责。
  - 新增 `EditorSplitSessionCoordinator`，集中处理 project path 切换、pending snapshot、restore-once、无 tab 延迟恢复、保存/清理持久化。
  - `EditorContainerState` 保留 split editor pane 分配、布局、镜像规则，只委托 persist/restore 会话流程，避免一次性大拆。
  - 本步不改变 split editor UI 行为、tab 生命周期、文件 mutation 同步和 LSP 路由。
- 本轮补充验证：

```powershell
.\gradlew :app:testArm64DebugUnitTest --tests "com.wuxianggujun.tinaide.ui.compose.state.editor.EditorContainerStateTest" --console=plain
```

结果：`BUILD SUCCESSFUL`。
