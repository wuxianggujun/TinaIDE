# TinaIDE 测试文档

## 2026-11-25 0.19.12 发布验证

[0.19.12 候选与发布验证](2026-11-25_release-0.19.12.md) 记录图形日志来源过滤的已验证基线、版本推进与先合并 `main` 再打 Tag 的发布流程。

## 2026-11-25 图形日志来源过滤

[所有图形宿主的日志过滤与验证](2026-11-25_graphical-log-source-filter.md) 记录 SDL2、SDL3、NativeActivity／raylib 共享过滤、Java 宿主独立标签、自定义程序输出保留、完整日志开关与 60 项定向回归；真机独立探针验证 logcat 行为，不代表新 APK 的图形界面完整验收。

## 2026-11-25 日志配色与选区复制

[正文等级配色、只读复制与发布验证](2026-11-25_graphical-log-copy-release.md) 记录正文配色、长按／手柄／全选／复制、动态裁剪选区、83 项本地回归与原生悬浮窗菜单验证；已完成 `0.19.11 / 19122` 真机安装与冷启动，双 ABI 正式 Release 已发布。为保留用户其他应用小窗口，真机日志端到端交互未完成；本地安装包与公开 CI 产物的哈希分别记录。

## 2026-11-25 日志等级侧栏

[紧凑等级栏与断点边界](2026-11-25_graphical-log-gutter.md) 记录默认侧栏仍占空间的根因、每实例显示模式、等级与逻辑行同步，以及 133 项定向 JVM／Robolectric 回归、kit／宿主／独立消费者编译结果。随后已完成包含侧栏修复的 `0.19.10 / 19112` ARM64 Release 构建及 PMS110 真机覆盖安装；签名、UID、首次安装历史与 APK 哈希核验通过，图形交互手测仍待执行。

## 2026-11-25 图形菜单锚点与日志编辑器

[菜单定位、只读编辑器与回归](2026-11-25_graphical-overlay-menu-log-editor.md) 记录原生 PopupWindow 坐标根因、显式按钮锚点、2 dp 留白、固定行号与换行选择，以及日志增量／取消边界；区分 69 项 JVM/Robolectric 验证、已完成的 `0.19.8 / 19092` ARM64 Release 构建／真机覆盖安装与仍待执行的图形交互手测。

## 2026-11-24 项目卡片紧凑布局

[名称、标签与近期角标](2026-11-24_project-card-layout.md) 记录图标右侧的名称／标签列、完整名称与大字体回归、真实近期状态及本地 Compose 渲染预览；不将 JVM 布局验证当作真机或 Release 验收。

## 2026-11-24 图形运行悬浮窗

[悬浮窗改造与验收](2026-11-24_graphical-overlay-window.md) 记录单控制球、拖动跟手、缩放与最大化／恢复、退出确认和窗口内外输入边界，区分实际 SDL3 模拟器、公共宿主契约与仍待验证的真机 Release 范围。

## 2026-11-22 教程高亮、fmt 基线与项目名称

[三项用户反馈修复与验收](2026-11-22_tutorial-highlighting-fmt-project-titles.md) 记录 Markdown 代码绕过编辑器、fmt 空清单与项目名称被标签挤压的根因，编辑器只读渲染和完整课程基线，以及模块、模拟器和递增版本 Release 的独立验证范围。

## 2026-11-22 Release 真机安装与教程审查

[安装结果、手测清单与教程完善路线](2026-11-22_physical-install-and-tutorial-review.md) 记录 `0.19.5 / 19062` ARM64 Release 覆盖安装到 PMS110 的包版本与哈希，九章中英文教材静态检查，以及待用户执行的手测顺序。本轮不运行真机功能测试；最新 CI 的 Robolectric 下载 HTTP 429 单独记录，不写成完整门禁通过。

## 2026-11-21 教程一键创建与续开

[教程一键工程](2026-11-21_tutorial-project-creation.md) 记录九章完整工程准备、源码资产复用、同名冲突与保留修改、生命周期回调边界和验证范围。教程源码使用应用私有项目目录，创建不会自动安装依赖或确认考核。

## 2026-11-21 市场布局与考核回归优化

[市场布局与课程回归优化](2026-11-21_market-course-optimization.md) 记录布局宽度误报、窄窗口与大字体标签、中英文考核分阶段及市场库文件身份验证；本轮只覆盖模拟器 Debug，不执行真机或 Release/R8 验收。

## 2026-11-21 集成与 PMS110 真机验收

[集成与真机验收](2026-11-21_integration-device-acceptance.md) 记录整合后的 ARM64 Debug APK 安装、PMS110 回归和超时项；超时或缺少 instrumentation 报告的用例不计为通过，也不替代 Release/R8 验收。

## 2026-11-21 vcpkg 7zip 汇编修复

[7zip 汇编修复与真机验收](2026-11-21_vcpkg-7zip-asm-acceptance.md) 记录 PMS110 上的实际失败命令、
ASM 包装器修复、完整依赖安装及最终 ARM64 APK 的链接和压缩/解压回归，并区分短测超时与最终通过范围。

## 2026-11-18 续接验收

[vcpkg、市场与课程续接验收](2026-11-18_vcpkg-market-course-acceptance.md) 记录共享库取消修复、正式图形启动器课程测试及本轮候选/最终包验证；不把开发 Debug 结果当作所有 Release 或真机验收通过。

> Release/图形入口最后核验：2026-11-02；下文各模块的历史基线保留原核验日期。

本目录只保留当前仍值得固定维护的测试入口说明。

## 0.18.39 Release 设备验收

[0.18.39 Release 手测清单](release-0.18.39-manual-checklist.md) 是当前验收入口，
优先覆盖 raylib r3 手势返回重跑、快速连点、启动取消、三引擎切换和无限运行，
并回归 RikkaHub 模型页、网页预览、许可全文与 APK 导出。独立夹具通过不代表 IDE 端到端通过。
本轮按用户选择优先在模拟器上验证；记录实际 API、ABI 与 GPU 后端，不将结果扩大为所有真机已验证。
双 ABI Release、155 项定向 JVM / 57 项 Python 检查，以及完整 IDE 的重复运行、取消和前台长运行
证据均记录在清单第 7 节。首轮候选、导出壳 A/B、最终候选与失败驱动分开统计。

[0.18.38 历史清单](release-0.18.38-manual-checklist.md) 保留 Vector XML/R8 崩溃与许可修复的记录。

### 0.18.37 历史清单与可复用样例

[0.18.37 Release 真机手测清单](release-0.18.37-manual-checklist.md) 包含升级/全新安装、
RikkaHub 网页预览与生命周期、Mermaid 导出、IDE 编译/编辑/LSP 的 P0 验收，
以及完整版本的 P1 回归、可直接粘贴的测试样例和问题记录方法。
该清单记录的是待执行的真机测试，自动检查或本地 Release 构建通过不代表设备验收通过。

## 模块优先与共享会话约定

- 先运行改动所属模块：Android library 使用 `:module:testDebugUnitTest` 或 `:module:compileDebugKotlin`；只有 `app` 宿主、ABI/flavor、打包或跨模块集成变化时才运行 `:app:*`。
- 一次性本地 Gradle 命令统一带 `--no-daemon`，不为单次检查保留 daemon。
- 若其他会话正在构建，本会话只做 `git diff --check`、路径/类名核对等静态检查，不启动或停止 Gradle。
- 禁止执行 `gradlew --stop` 影响共享 daemon；清理时只终止本任务明确启动的进程。

## 缩进与崩溃来源隔离回归

[2026-11-14 缩进一致性与崩溃来源隔离](2026-11-14_indentation-crash-isolation.md) 记录统一缩进、源文件条件回写、崩溃任务与宿主权限边界以及简化布局。102 项定向测试、最终签名 Release 构建、无线真机覆盖安装及正常启动通过；7 项设备用例因测试框架初始化缺类尚未执行，不冒充设备行为回归通过。

## vcpkg 代理与取消集成回归

[2026-11-13 集成审查与验证](2026-11-13_集成审查-vcpkg-report.md) 包含完整归档校验后的镜像回退、损坏缓存恢复、Git 命令重试边界、代理配置热切换以及包市场批量取消摘要的复现和修复。18 个套件、118 项定向 JVM/Robolectric 测试通过，宿主与设备测试源码编译通过；本轮没有构建或安装 APK，不替代 ARM64 真机和 Release/R8 验收。

## Native 图形运行回归

[SDL / raylib Native 图形运行回归](native-graphics-runtime.md) 覆盖运行与 APK 导出能力边界、
raylib 包的帧循环契约、模板返回键退出，以及 SDL2/SDL3/NativeActivity 宿主隔离验证。
当前只支持修复包，不再把旧 raylib/SDL 包兼容作为交接验收目标。
文档分别记录包体静态验证、现有宿主 APK 的设备验证和仍未完成的端到端验证，不能互相替代。

### Native ABI 打包门禁

`AllAbi` 聚合任务必须同时覆盖宿主和 included build；不能只检查 APK 声明了正确 ABI。
2026-11-02 的候选包曾缺少 x86_64 Tree-sitter、RikkaHub workspace 和 Xlorie 原生库，
导致项目无法打开。以下探针只配置真实的三个 native 模块，不构建 App、不递增版本：

```bash
./gradlew verifyNativeAllAbi -I tools/checks/native-abi-probe.init.gradle \
  -Pprobe.expectedAbis=arm64-v8a,x86_64 --no-daemon --configure-on-demand --console=plain
./gradlew verifyNativeAllAbi -I tools/checks/native-abi-probe.init.gradle \
  -Ptina.allAbi=false -Ptina.devAbi=x86_64 -Pprobe.expectedAbis=x86_64 \
  --no-daemon --configure-on-demand --console=plain
```

本地后台检查按 60 秒窗口执行；可用 `-Pprobe.modules=tree-sitter,lorie` 和
`-Pprobe.modules=workspace` 分组。单 ABI 模式下还需验证 Android Studio 注入的
`android.injected.build.abi` 优先于 `tina.devAbi`，与宿主的 ABI 选择一致。

构建后仍必须检查实际 APK（含 26 个必需 runtime/grammar 库、重复路径、错误 ABI 和 ELF 机器类型）：

```bash
python tools/checks/check_apk_native_libs.py \
  --apk app/build/outputs/apk/x86_64/release/TinaIDE-0.18.39-x86_64-release.apk --abi x86_64
python tools/checks/check_apk_native_libs.py \
  --apk app/build/outputs/apk/arm64/release/TinaIDE-0.18.39-arm64-v8a-release.apk --abi arm64-v8a
```

Release 工作流在上传前执行此检查，单元测试复用 `tools/checks` 的 unittest discovery。

### APK 导出共存检查

```bash
./gradlew :core:apk-builder:testDebugUnitTest --no-daemon --console=plain
```

2026-11-02 全模块 20 项通过（failures/errors/skipped 均为 0），其中 Manifest 回归使用两份实际
图形 APK 模板验证 Provider authority/私有权限随包名重写、组件类名不变和未选权限不回流。
模拟器端仍须同时安装不同包名的 raylib 与 SDL3 导出包，不能只验证其中一个或靠卸载绕过冲突。

## 编辑器迁移与 SSH 持久化回归

2026-10-30 的宿主迁移使用新版 TinaEditor API，不再由宿主重复转发文本变更或覆盖高亮更新订阅。
关闭标签时应等最后一个分屏释放 UI 引用，再关闭 `EditorState`、语言服务和文本缓冲。

```bash
./gradlew :core:git:testDebugUnitTest --no-daemon --console=plain
./gradlew :core:editor-lsp:testDebugUnitTest --no-daemon --console=plain
./gradlew :app:testArm64DebugUnitTest --tests "*CodeBlockHighlightingTest" --tests "*CodeEditorRuntimeTest" --tests "*CodeEditorRuntimeLifecycleTest" --tests "*TinaCodeEditorSemanticTokenMappingTest" --tests "*SplitEditorSessionStorageTest" --no-daemon --console=plain
```

固定检查以下边界：

- SSH 密钥/元数据使用同目录唯一临时文件和原子替换；发布失败不能先删除旧文件，临时文件不能占用另一个合法的 `<key>.new` 密钥名；写入明确使用 UTF-8，fsync/清理错误不得静默丢失。
- `CodeBlockHighlightingTest` 使用编辑器的 LF/UTF-16 行索引覆盖 CRLF、独立 CR、中文/emoji、空行和越界高亮段；异步缓存完成后刷新，并在取消或打开失败时解除订阅。
- `CodeEditorRuntimeTest` 与 Compose 生命周期测试覆盖镜像分屏引用计数、缓存保留，以及关闭时清理新版 `EditorState` 的回调。
- 语义 token 映射与拆分会话存储保持宿主迁移后的行为；对应测试已纳入 `Core Regression Checks`。

如果宿主更新的 `editor-kit` 指针同时包含共享构建脚本变更，还需分别验证宿主与 kit 独立构建：

```bash
./gradlew :app:compileArm64DebugKotlin --no-daemon --console=plain
cd editor-kit && ./gradlew :core:editor-view:compileDebugKotlin --no-daemon --console=plain
```

## 文本编辑与工作区定向回归

以下测试类已于 2026-09-09 在对应 `src/test` 目录核对存在。按模块分别运行：

```bash
./gradlew :core:text-engine:testDebugUnitTest --tests "com.wuxianggujun.tinaide.core.textengine.EditHistoryTest" --tests "com.wuxianggujun.tinaide.core.textengine.LineIndexTest" --tests "com.wuxianggujun.tinaide.core.textengine.RopeTextBufferTest" --tests "com.wuxianggujun.tinaide.core.textengine.TextScanKernelTest" --no-daemon --console=plain
```

```bash
./gradlew :core:editor-view:testDebugUnitTest --tests "com.wuxianggujun.tinaide.core.editorview.EditorClipboardBridgeTest" --tests "com.wuxianggujun.tinaide.core.editorview.EditorInputConnectionEditTest" --tests "com.wuxianggujun.tinaide.core.editorview.EditorInputConnectionExtractedTextTest" --tests "com.wuxianggujun.tinaide.core.editorview.EditorInputConnectionImeSelectionTest" --tests "com.wuxianggujun.tinaide.core.editorview.EditorInputConnectionUtilsTest" --tests "com.wuxianggujun.tinaide.core.editorview.EditorInputHostLayerTest" --tests "com.wuxianggujun.tinaide.core.editorview.EditorKeyboardShortcutsTest" --tests "com.wuxianggujun.tinaide.core.editorview.EditorLineLayoutCacheTest" --tests "com.wuxianggujun.tinaide.core.editorview.EditorSelectionContextMenuCoordinatorTest" --tests "com.wuxianggujun.tinaide.core.editorview.EditorStateEventTest" --tests "com.wuxianggujun.tinaide.core.editorview.EditorStateSemanticTokensTest" --tests "com.wuxianggujun.tinaide.core.editorview.EditorStateSurrogatePairTest" --tests "com.wuxianggujun.tinaide.core.editorview.EditorStateWordWrapTest" --tests "com.wuxianggujun.tinaide.core.editorview.EditorToggleLineCommentTest" --tests "com.wuxianggujun.tinaide.core.editorview.EditorUndoRedoCursorTest" --tests "com.wuxianggujun.tinaide.core.editorview.EditorUserInputTest" --tests "com.wuxianggujun.tinaide.core.editorview.EditorVisualLineMapperTest" --tests "com.wuxianggujun.tinaide.core.editorview.SignatureHelpPopupLayoutResolverTest" --no-daemon --console=plain
```

```bash
./gradlew :app:testArm64DebugUnitTest --tests "com.wuxianggujun.tinaide.ui.CompileActionsHelperTest" --tests "com.wuxianggujun.tinaide.ui.compose.components.BottomPanelTabResolutionTest" --tests "com.wuxianggujun.tinaide.ui.compose.components.SwipeableDrawerStateTest" --tests "com.wuxianggujun.tinaide.ui.compose.state.editor.EditorContainerStateTest" --no-daemon --console=plain
```

## 诊断 Quick Fix 定向回归

诊断面板或 Code Action 请求边界发生变化时，至少运行：

```powershell
./gradlew :core:lsp:testDebugUnitTest --tests "com.wuxianggujun.tinaide.core.lsp.LspCodeActionServiceTest" --no-daemon --console=plain
./gradlew :app:testArm64DebugUnitTest --tests "com.wuxianggujun.tinaide.ui.WorkspaceEditPreviewTest" --tests "com.wuxianggujun.tinaide.ui.compose.components.DiagnosticQuickFixAvailabilityTest" --tests "com.wuxianggujun.tinaide.ui.compose.state.editor.EditorContainerStateTest" --no-daemon --console=plain
```

手工回归需覆盖五类场景：语言服务器提供可用 Quick Fix 时显示入口；普通语法错误或 disabled action 不显示入口；编辑器通用 Code Actions 仍可返回 Refactor / Source Action；仅当活动文件返回启用的 `source.fixAll` 时显示“全部修复”；多文件操作显示文件与编辑数量摘要，取消后返回操作列表且不显示失败提示。

## 返回事件与临时 UI 回归

主编辑器手工验证以下顺序：

1. 打开顶栏或底部面板的下拉菜单，按一次系统返回，确认只关闭菜单，工作区仍保持打开。
2. 展开底部面板，按一次返回，确认面板收起且编辑器仍在当前文件。
3. 在面板收起后再次返回；若存在未保存修改，应显示退出确认，而不是直接退出。
4. 打开文件树抽屉并同时保持其他临时状态，确认第一次返回优先关闭抽屉。

对应状态回归测试为 `BottomPanelDragStateTest`；菜单返回行为由 `TinaMenus` 的统一 `BackHandler` 入口覆盖。

## 文档一致性检查

文档或 App 内帮助变更后运行：

```powershell
py tools/checks/check_documentation.py
```

该入口检查当前事实源文档的本地链接、中英文帮助资产注册与文件名一致性、中英文根 README 的 Android SDK / Registry 口径，以及 Changelog 的 `Unreleased` 状态。它不会扫描历史设计稿中的旧路径，也不能替代人工核验运行时和模块边界。

## Popup 回归固定入口

编辑器软换行、行级着色缓存的工作量门禁和设备采样步骤见 [编辑器渲染性能回归](editor-render-performance.md)。

编辑器 popup 的共享回归建议固定跑下面两组命令：

```bash
./gradlew :core:editor-view:testDebugUnitTest --tests "com.wuxianggujun.tinaide.core.editorview.EditorPopupComposeSmokeTest" --tests "com.wuxianggujun.tinaide.core.editorview.PopupOverlaySharedAnchorIntegrationTest" --tests "com.wuxianggujun.tinaide.core.editorview.EditorOverlaysIntegrationTest" --no-daemon --console=plain
```

```bash
./gradlew :core:editor-view:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.wuxianggujun.tinaide.core.editorview.EditorCompletionPopupInstrumentationTest,com.wuxianggujun.tinaide.core.editorview.EditorSharedPopupInstrumentationTest --no-daemon --console=plain
```

其中：

- 第一组覆盖 popup 组件 smoke、共享 anchor/layout 回归、`EditorOverlays` 组合场景。
- 第二组覆盖设备侧补全框、签名提示、选择菜单 popup 的稳定 tag 与交互回归。

## 插件 JVM 稳定性门禁

插件 manifest、权限、Script API、隔离状态机、安装事务、LSP owner 和 Marketplace 回归统一运行：

```powershell
./gradlew :core:plugin:testDebugUnitTest --no-daemon --console=plain
```

2026-07-15 的基线为 39 个测试套件、176 项测试，要求 0 failures、0 errors、0 skipped。
该数字是当时的历史基线，不是当前值：`core/plugin/src/test` 现有 42 个 `*Test.kt`，套件数已经增长。
沿用这一门禁时按“0 failures / 0 errors / 0 skipped”判断，并从本次运行的 XML 重新取套件与用例数，不要拿 176 当作预期值。
其中包含 workspace 确定性排序、runtime unavailable 单次加载、权限授予后激活，以及并发故障写入线程回收。
教程目录/课程状态和帮助全文搜索属于 feature 教程/帮助测试，不计入这 176 项。测试数变化时应核对 XML，而不是只看 Gradle 的 `BUILD SUCCESSFUL`。

`Dev Static Checks` 会运行该任务，并额外固定执行教程/帮助 JVM 回归和 App 宿主回归。课程正文加载与取消测试已归入 `feature:tutorial`。相关 JVM 步骤失败时会上传：

- `core/plugin/build/test-results/testDebugUnitTest/`
- `core/plugin/build/reports/tests/testDebugUnitTest/`
- `feature/help/build/test-results/testDebugUnitTest/`
- `feature/help/build/reports/tests/testDebugUnitTest/`
- `feature/tutorial/build/test-results/testDebugUnitTest/`
- `feature/tutorial/build/reports/tests/testDebugUnitTest/`
- `app/build/test-results/testArm64DebugUnitTest/`
- `app/build/reports/tests/testArm64DebugUnitTest/`

制品名为 `core-jvm-test-reports-<run_id>-<run_attempt>`，保留 14 天；成功运行不会上传失败报告。
历史成功记录 run `29691804039`（提交 `76173ebe4`）验证的是当时的插件、帮助、教程和 App 文章测试，不作为当前课程实现的验收结果。

GitHub Actions 的 `CI=true` 配置只使用官方 Google/Maven Central/Gradle Plugin Portal，避免动态版本解析被国内镜像的临时 metadata 错误阻断；本地非 CI 环境仍保留 Aliyun 镜像优先策略。

## 教程与帮助 JVM 稳定性门禁

安装到真机后的手工步骤见 [真机课程与代码理解验收](course-device-manual.md)，覆盖课程提示、
真实 CMake 验收、类/方法导航、流程图、超时取消与进度恢复。

2026-11-20 新增 `CourseAssessmentTest`（实际输入/输出匹配、退出码、超时、源码变化与取消）、
`FunctionFlowTest`（循环跳转、switch 穿透、提前返回及 Mermaid 转义），以及
`LspCodeUnderstandingTest`（完整作用域/签名、出向调用目标位置和查询失败）。
`CompilerViewModelTest` 同时验证验收不得与已有编译重叠。

```powershell
./gradlew :feature:tutorial:testDebugUnitTest :feature:editor:testDebugUnitTest :core:lsp:testDebugUnitTest :core:editor-lsp:testDebugUnitTest :core:compile:testDebugUnitTest --no-daemon --console=plain --configure-on-demand
./gradlew :app:compileArm64DebugKotlin :app:compileArm64DebugAndroidTestKotlin --no-daemon --console=plain --configure-on-demand
```

`CxxFunctionFlowInstrumentationTest` 使用真实 Tree-sitter JNI 验证 Unicode 位置、循环与 switch，
必须在安装了匹配 ABI 原生库的设备上执行，JVM 图模型测试不代替原生解析验收。

`CourseAssessmentDeviceTest` 在独立项目中使用真实 `EditorManager`、`CompilerViewModel` 和原生
CMake 编译链路，从 `tutorial-course` 的正文及提示组装终端输入与折扣练习，验证保存未保存的编辑、
五组输入及退出码、进度回读、编译错误、错误金额、取消、五秒超时及修复后重试。取消与超时还会
核对记录的 native PID 已退出；测试使用独立进度 ID，结束时只移除自己的项目与进度。

```powershell
$env:ANDROID_SERIAL = '<device-serial>'
./gradlew.bat '-Ptina.devAbi=x86_64' :app:connectedX86_64DebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.wuxianggujun.tinaide.core.compile.CourseAssessmentDeviceTest' '-Pandroid.testInstrumentationRunnerArguments.courseLanguage=zh' --no-daemon --console=plain --configure-on-demand
```

将 `courseLanguage=zh` 改为 `courseLanguage=en` 可验证英文课程；设备 ABI 为 arm64 时使用
`-Ptina.devAbi=arm64` 和 `connectedArm64DebugAndroidTest`。PowerShell 的 `-P` 参数整体加引号。
运行前必须准备好对应 ABI 的工具链与 sysroot，不使用 assumption 跳过缺失环境。

`NativeCourseDeviceTest` 同样只读取 `tutorial-course` 资产。`courseLesson` 支持
`ide-workflow`、`cpp-basics`、`cpp-functions`、`cpp-collections`、`cpp-raii`、`cmake-practice`；
多文件和 CMake 章节保留前置计价实现。传 `courseRecovery=true` 可验证编译错误修复，
`courseLanguage=en` 切换英文。构建和 instrumentation 源码编译成功不能代替测试结果中的明确
`OK (1 test)`；零测试、跳过、runner 初始化失败均不算通过。

已有设备回归入口的 `course` suite 统一执行 12 组中英课程示例、一次编译错误恢复和两组自动验收。
APK 已构建并安装后运行；每个用例最多 60 秒，报告校验当前 run ID、语言、检查数和进程回收结果：

```powershell
py tools/testing/run_editor_runtime_regression.py --adb '<sdk>/platform-tools/adb.exe' --serial '<device-serial>' --suite course
```

只复测自动验收时追加 `--case assess-zh` 或 `--case assess-en`。2026-11-20 在 API 34 x86_64 模拟器
验证了两种语言的完整验收和 12 组课程示例（均包含编译错误恢复）；这些结果不覆盖图形、联网依赖
安装或 ARM 真机运行。

教程目录、课程加载与进度由 `feature:tutorial` 维护，帮助资产和搜索由 `feature:help` 维护。提交前可运行：

```powershell
./gradlew :feature:help:testDebugUnitTest :feature:tutorial:testDebugUnitTest --no-daemon --console=plain
```

两组回归分别覆盖：

- `feature:help`：中英文帮助资源、插件教程正文、全文搜索、正文缓存隔离和站内链接解析；
- `feature:tutorial`：目录加载与排序、课程内容缺失/空白/失败/取消、章节解锁、异步保存、快速关闭重开、重复完成、损坏进度修复，以及 I/O 失败后的有序重试和目录重载。

符号索引和 LSP 查询回归使用以下命令；涉及宿主文件事件或查询界面时追加 App 编译：

```bash
./gradlew :feature:editor:testDebugUnitTest :core:lsp:testDebugUnitTest :core:editor-lsp:testDebugUnitTest :app:compileArm64DebugKotlin --no-daemon --console=plain
```

- `CxxSymbolProviderTest`：重载、作用域、类成员、变量位置、函数指针、构造/析构和操作符；使用符合语法节点结构的 JVM fixture，不替代设备上的 native parser 验证。
- `ProjectSymbolIndexEventsTest`：删除、目录移动、外部修改及解析中删除文件。
- `SymbolIndexCacheTest`：签名、中文注释、所属作用域和位置的缓存往返。
- `LspSymbolQueryTest`：空结果、服务器失败和取消的区别；`FuzzyMatcherTest` 验证有限结果排序。

`TutorialCourseContentTest` 覆盖正文与逐级提示分离、CRLF、代码围栏中的提示标记、空正文拒绝，
并读取真实的 9 章中英资产，检查每章三个提示及参考代码一致性。提示使用同一 Markdown 文件末尾的
`<!-- tutorial-hint -->` 分隔，不在帮助目录重复登记；代码围栏内的同名文本保持原样。
独立文章推荐链路已删除。

宿主 UI 改动还需运行 `:app:compileArm64DebugKotlin`；设备上的 `TutorialCourseInstrumentationTest`
验证逐级展开与收起、章节切换后提示复位、代码复制到剪贴板，以及查看提示不会自动解锁或完成章节。
测试源码可先用 `:app:compileArm64DebugAndroidTestKotlin` 编译；编译通过不等于设备交互验收通过。
人工从第 7 章走到第 9 章时，检查控制台备份、SDL3 宿主切换、控制台恢复与缺少备份时的参考代码恢复。

## 插件设备稳定性门禁

连接一台 ADB 设备后，在仓库根目录运行：

```powershell
pwsh ./tools/testing/plugin-device-gate.ps1
```

该入口会构建并安装独立的 `core:plugin` instrumentation APK，依次验证：

- isolated runtime 主动终止、PSS 越限、watchdog、Lua 沙箱、generation 和 Binder 载荷限制；
- debuggable-only `SIGSEGV` 后宿主进程存活、故障插件进入 `RUNTIME_CRASH` quarantine、健康插件在新 runtime PID 恢复；
- in-flight journal 恢复与 quarantine 状态；
- `prepare -> adb force-stop -> verify` 两阶段真实进程重启后的 quarantine 持久化。

脚本只操作 `com.wuxianggujun.tinaide.core.plugin.test` 测试包，不会清除 TinaIDE 主 App 数据；结束时默认 force-stop 并卸载测试包。非 `-SkipBuild` 模式使用 `--no-daemon` 构建，不调用全局 `gradlew --stop`，因此不会主动终止其他会话的 Gradle daemon。已提前构建时可传 `-SkipBuild`，排查时可传 `-KeepTestApp` 保留测试包。多设备环境必须传 `-Serial <device>`。

结果解析要求精确测试数；`0 tests`、ignored/assumption skip、instrumentation runner failure、阶段数量不匹配或 force-stop 后测试包进程仍存在均判定失败。手动 workflow 无论成功失败都会上传
`plugin-device-gate-<run_id>-<run_attempt>`，保留 14 天，内容包括设备信息、原始 instrumentation 输出、logcat、阶段汇总和 verdict。

GitHub Actions 的 `Plugin Device Stability Gate` 是手动触发任务，目标 runner 必须带有 `self-hosted`、`Windows`、`X64`、`android-device` 标签并连接可用设备。未配置设备 runner 时不要把工作流排队状态当作测试通过。

该 workflow 只有进入默认分支后才会在 Actions 页面完成注册。触发时可填写 `serial` 指定 ADB 设备；`skip_full_suite=true` 只运行 force-stop/relaunch 持久化阶段，不能替代完整 isolated runtime/native crash 回归。self-hosted runner 还必须预装 PowerShell 7、Android SDK/ADB，并完成设备 USB 调试授权；`adb devices` 中设备状态必须为 `device` 而不是 `offline` 或 `unauthorized`。

## 相关指南

- [LSP 调试指南](../guides/LSP-Debug-Guide.md) - LSP 调试方法
- [远程 LSP 使用指南](../guides/Remote-LSP-Guide.md) - 远程 LSP 功能使用
