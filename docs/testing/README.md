# TinaIDE 测试文档

> 最后人工核验：2026-07-15

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

GitHub Actions 的 `Plugin Device Stability Gate` 是手动触发任务，目标 runner 必须带有 `self-hosted`、`Windows`、`X64`、`android-device` 标签并连接可用设备。未配置设备 runner 时不要把工作流排队状态当作测试通过。

## 相关指南

- [LSP 调试指南](../guides/LSP-Debug-Guide.md) - LSP 调试方法
- [远程 LSP 使用指南](../guides/Remote-LSP-Guide.md) - 远程 LSP 功能使用
