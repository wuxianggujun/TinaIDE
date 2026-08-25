---
name: tina-testing-debugging
description: TinaIDE 测试、排障和质量验证指南。用于选择单元测试/仪器测试命令、定位失败原因、验证 editor/RikkaHub 宿主/plugin/database/security 等模块改动，或为文档变更做轻量检查。
---

# TinaIDE 测试与排障

## 先读文件

- `docs/testing/README.md`：项目已有测试说明和 editor popup 回归命令。
- 目标模块的 `build.gradle.kts`：确认测试依赖和 Android test 配置。
- 目标模块的 `src/test/**` 与 `src/androidTest/**`：优先复用现有测试风格。
- `gradle/libs.versions.toml`：JUnit、Truth、Robolectric、MockK、AndroidX Test、coroutines-test 等版本。

## 常用命令

```powershell
./gradlew :core:text-engine:testDebugUnitTest --no-daemon --console=plain
./gradlew :core:editor-view:compileDebugKotlin --no-daemon --console=plain
./gradlew :core:editor-view:testDebugUnitTest --tests "com.wuxianggujun.tinaide.core.editorview.EditorPopupComposeSmokeTest" --tests "com.wuxianggujun.tinaide.core.editorview.PopupOverlaySharedAnchorIntegrationTest" --tests "com.wuxianggujun.tinaide.core.editorview.EditorOverlaysIntegrationTest" --no-daemon --console=plain
./gradlew :rikkahub:embedded:compileDebugKotlin --no-daemon --console=plain
./gradlew :app:testArm64DebugUnitTest --tests "com.example.TestName" --no-daemon --console=plain
./gradlew :core:editor-view:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.wuxianggujun.tinaide.core.editorview.EditorCompletionPopupInstrumentationTest,com.wuxianggujun.tinaide.core.editorview.EditorSharedPopupInstrumentationTest --no-daemon --console=plain
```

- Android library 从所属模块的 `testDebugUnitTest` 或 `compileDebugKotlin` 开始；不要为了 library 改动默认编译 `app`。
- 只有宿主入口、ABI/flavor、打包或跨模块集成变化时，才运行 `:app:testArm64DebugUnitTest`、`:app:compileArm64DebugKotlin` 等任务。
- 一次性本地 Gradle 命令统一带 `--no-daemon`。若其他会话正在构建，只做静态检查；不要启动 Gradle，也禁止执行 `gradlew --stop`，只终止本任务明确启动的进程。
- 后台执行测试时设置最大超时 60 秒，避免卡死。
- `connectedDebugAndroidTest` 需要连接设备或模拟器。

## 文本编辑与工作区定向回归

以下类名已在 `src/test` 中核对存在。本轮按模块分别运行，不合并成全仓库或整 App 构建：

```bash
./gradlew :core:text-engine:testDebugUnitTest --tests "com.wuxianggujun.tinaide.core.textengine.EditHistoryTest" --tests "com.wuxianggujun.tinaide.core.textengine.LineIndexTest" --tests "com.wuxianggujun.tinaide.core.textengine.RopeTextBufferTest" --tests "com.wuxianggujun.tinaide.core.textengine.TextScanKernelTest" --no-daemon --console=plain
```

```bash
./gradlew :core:editor-view:testDebugUnitTest --tests "com.wuxianggujun.tinaide.core.editorview.EditorClipboardBridgeTest" --tests "com.wuxianggujun.tinaide.core.editorview.EditorInputConnectionEditTest" --tests "com.wuxianggujun.tinaide.core.editorview.EditorInputConnectionExtractedTextTest" --tests "com.wuxianggujun.tinaide.core.editorview.EditorInputConnectionImeSelectionTest" --tests "com.wuxianggujun.tinaide.core.editorview.EditorInputConnectionUtilsTest" --tests "com.wuxianggujun.tinaide.core.editorview.EditorInputHostLayerTest" --tests "com.wuxianggujun.tinaide.core.editorview.EditorKeyboardShortcutsTest" --tests "com.wuxianggujun.tinaide.core.editorview.EditorLineLayoutCacheTest" --tests "com.wuxianggujun.tinaide.core.editorview.EditorSelectionContextMenuCoordinatorTest" --tests "com.wuxianggujun.tinaide.core.editorview.EditorStateEventTest" --tests "com.wuxianggujun.tinaide.core.editorview.EditorStateSemanticTokensTest" --tests "com.wuxianggujun.tinaide.core.editorview.EditorStateSurrogatePairTest" --tests "com.wuxianggujun.tinaide.core.editorview.EditorStateWordWrapTest" --tests "com.wuxianggujun.tinaide.core.editorview.EditorToggleLineCommentTest" --tests "com.wuxianggujun.tinaide.core.editorview.EditorUndoRedoCursorTest" --tests "com.wuxianggujun.tinaide.core.editorview.EditorUserInputTest" --tests "com.wuxianggujun.tinaide.core.editorview.EditorVisualLineMapperTest" --tests "com.wuxianggujun.tinaide.core.editorview.SignatureHelpPopupLayoutResolverTest" --no-daemon --console=plain
```

```bash
./gradlew :app:testArm64DebugUnitTest --tests "com.wuxianggujun.tinaide.ui.CompileActionsHelperTest" --tests "com.wuxianggujun.tinaide.ui.compose.components.BottomPanelTabResolutionTest" --tests "com.wuxianggujun.tinaide.ui.compose.components.SwipeableDrawerStateTest" --tests "com.wuxianggujun.tinaide.ui.compose.state.editor.EditorContainerStateTest" --no-daemon --console=plain
```

## 覆盖重点

- `core/editor-view`：popup、gesture、overlay、rendering、instrumented editor 行为。
- RikkaHub 集成：主仓库入口、embedded 编译、侧边栏容器和设置入口。
- `core/database`：Room DAO、entity、migration 相关行为。
- `core/security`：`PathValidator`、server config HMAC 校验。
- `core/plugin`：manifest、权限、脚本 API、LSP plugin、marketplace、host command。
- `app`：主界面导航、LSP routing、testing screens、plugin bridge。
- 多数 `core/*` 与 `feature/*` Android library 模块通过 `tina.android.library` convention 自动获得 JUnit、Truth、Robolectric、MockK、coroutines-test；不要仅凭模块 `build.gradle.kts` 未写 `testImplementation` 就重复加依赖。
- RikkaHub 内部测试应在 `external/rikkahub` 子模块内按其测试约定运行；主仓库默认只要求 embedded 编译和宿主入口验证。

## 排障流程

1. 先用失败模块和测试名缩小范围，不要直接跑全量。
2. 查看同目录现有测试的 fixture、mock、rule 和 coroutine dispatcher 写法。
3. Android 资源或 Compose 相关单测优先确认模块是否启用了 `unitTests.isIncludeAndroidResources`。
4. 涉及数据库时检查 schema version、DAO、migration 和测试数据库创建方式。
5. 涉及外部进程、文件系统、设备权限时区分 unit test 与 instrumented test。

## 高风险误区

- 不要用新测试框架替代现有 JUnit4/Truth/Robolectric/MockK 风格，除非模块已有迁移。
- 不要在测试里依赖真实用户目录、真实 API key、真实 keystore 或生产网络。
- 不要忽略 editor popup 回归；这类 UI 行为已有专门 smoke/integration 测试。
- 不要把失败的 instrumented test 当作本地 unit test 可复现问题。
- 不要把 `app/src/androidTest` 的 native toolchain / PRoot smoke 当作稳定 CI 必跑项；它们依赖设备、ABI 和资产准备，`assumeTrue` 跳过不等于失败。

## 验证

- 代码变更跑目标模块最小测试，再跑该模块的 Kotlin compile；library 使用 `compileDebugKotlin`，不要默认升级到 `app` compile。
- UI/editor 交互变更补跑 docs/testing 中对应回归命令。
- RikkaHub 相关主仓库改动至少运行 `./gradlew :rikkahub:embedded:compileDebugKotlin --no-daemon --console=plain`；涉及宿主入口时再跑 `./gradlew :app:compileArm64DebugKotlin --no-daemon --console=plain`。
- 仅文档/skill 变更可做 Markdown 元数据、路径存在性和 `git diff` 检查。
