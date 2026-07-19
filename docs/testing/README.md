# TinaIDE 测试文档

> 最后人工核验：2026-07-16

本目录只保留当前仍值得固定维护的测试入口说明。

## 文档一致性检查

文档或 App 内帮助变更后运行：

```powershell
py tools/checks/check_documentation.py
```

该入口检查当前事实源文档的本地链接、中英文帮助资产注册与文件名一致性、中英文根 README 的 Android SDK / Registry 口径，以及 Changelog 的 `Unreleased` 状态。它不会扫描历史设计稿中的旧路径，也不能替代人工核验运行时和模块边界。

## Popup 回归固定入口

编辑器 popup 的共享回归建议固定跑下面两组命令：

```bash
./gradlew :core:editor-view:testDebugUnitTest --tests "com.wuxianggujun.tinaide.core.editorview.EditorPopupComposeSmokeTest" --tests "com.wuxianggujun.tinaide.core.editorview.PopupOverlaySharedAnchorIntegrationTest" --tests "com.wuxianggujun.tinaide.core.editorview.EditorOverlaysIntegrationTest"
```

```bash
./gradlew :core:editor-view:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.wuxianggujun.tinaide.core.editorview.EditorCompletionPopupInstrumentationTest,com.wuxianggujun.tinaide.core.editorview.EditorSharedPopupInstrumentationTest
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
其中包含 workspace 确定性排序、runtime unavailable 单次加载、权限授予后激活，以及并发故障写入线程回收。
教程目录/文章状态和帮助全文搜索属于 app/feature 帮助测试，不计入这 176 项。测试数变化时应核对 XML，而不是只看 Gradle 的 `BUILD SUCCESSFUL`。

`Dev Static Checks` 会运行该任务，并额外固定执行教程/帮助 JVM 回归和 App 教程文章状态测试。插件、教程或帮助 JVM 步骤失败时会上传：

GitHub Actions 的 `CI=true` 配置只使用官方 Google/Maven Central/Gradle Plugin Portal，避免动态版本解析被国内镜像的临时 metadata 错误阻断；本地非 CI 环境仍保留 Aliyun 镜像优先策略。

- `core/plugin/build/test-results/testDebugUnitTest/`
- `core/plugin/build/reports/tests/testDebugUnitTest/`
- `feature/help/build/test-results/testDebugUnitTest/`
- `feature/help/build/reports/tests/testDebugUnitTest/`
- `feature/tutorial/build/test-results/testDebugUnitTest/`
- `feature/tutorial/build/reports/tests/testDebugUnitTest/`
- `app/build/test-results/testArm64DebugUnitTest/`
- `app/build/reports/tests/testArm64DebugUnitTest/`

制品名为 `plugin-jvm-test-reports-<run_id>-<run_attempt>`，保留 14 天；成功运行不会上传失败报告。
最近一次确认的成功记录为 run `29429731887`（提交 `e6635f737`）。

## 教程与帮助 JVM 稳定性门禁

教程目录、帮助资产和文章加载状态分属不同模块，不能只依赖插件模块测试。提交前可运行：

```powershell
./gradlew :feature:help:testDebugUnitTest :feature:tutorial:testDebugUnitTest --no-daemon --console=plain
```

```powershell
./gradlew :app:testArm64DebugUnitTest \
  --tests "com.wuxianggujun.tinaide.ui.compose.screens.main.tutorial.TutorialArticleLoadStateTest" \
  --tests "com.wuxianggujun.tinaide.ui.compose.screens.main.tutorial.TutorialRelatedLearningSupportTest" \
  --no-daemon --console=plain
```

两组回归分别覆盖：

- `feature:help`：中英文帮助资源、插件教程正文、全文搜索、正文缓存隔离和站内链接解析；
- `feature:tutorial`：目录 Loading/Content/Empty 状态、分类排序和教程进度模型；
- `app`：文章缺失、空正文、加载失败、重试所依赖的 `Content/Error` 状态，以及取消传播和关联学习链接。

CI 中的教程测试只选择与教程页面直接相关的 App 测试，不把无关的 App 全量单元测试混入插件稳定性门禁。

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

脚本只操作 `com.wuxianggujun.tinaide.core.plugin.test` 测试包，不会清除 TinaIDE 主 App 数据；结束时默认 force-stop 并卸载测试包，同时停止本轮 Gradle daemon。已提前构建时可传 `-SkipBuild`，排查时可传 `-KeepTestApp` 保留测试包。多设备环境必须传 `-Serial <device>`。

结果解析要求精确测试数；`0 tests`、ignored/assumption skip、instrumentation runner failure、阶段数量不匹配或 force-stop 后测试包进程仍存在均判定失败。手动 workflow 无论成功失败都会上传
`plugin-device-gate-<run_id>-<run_attempt>`，保留 14 天，内容包括设备信息、原始 instrumentation 输出、logcat、阶段汇总和 verdict。

GitHub Actions 的 `Plugin Device Stability Gate` 是手动触发任务，目标 runner 必须带有 `self-hosted`、`Windows`、`X64`、`android-device` 标签并连接可用设备。未配置设备 runner 时不要把工作流排队状态当作测试通过。

该 workflow 只有进入默认分支后才会在 Actions 页面完成注册。触发时可填写 `serial` 指定 ADB 设备；`skip_full_suite=true` 只运行 force-stop/relaunch 持久化阶段，不能替代完整 isolated runtime/native crash 回归。self-hosted runner 还必须预装 PowerShell 7、Android SDK/ADB，并完成设备 USB 调试授权；`adb devices` 中设备状态必须为 `device` 而不是 `offline` 或 `unauthorized`。

## 相关指南

- [LSP 调试指南](../guides/LSP-Debug-Guide.md) - LSP 调试方法
- [远程 LSP 使用指南](../guides/Remote-LSP-Guide.md) - 远程 LSP 功能使用
