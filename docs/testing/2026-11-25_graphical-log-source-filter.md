# 所有图形宿主的悬浮日志来源过滤

日期：2026-11-25（Asia/Shanghai）。基于 `dev` 的 `e4c5ef183`，修复位于 `codex/graphical-log-sources`；日常修复进入 `dev` 和 CHANGELOG 的 Unreleased，不更新正式发布分支 `main`。

## 现象与根因

悬浮日志原命令为 `logcat --pid=<当前图形进程> -v brief -T 200 *:V`，只限制 PID，没有区分程序输出与宿主诊断。Android 窗口、输入法、Java Activity 生命周期等日志也会进入同一个 500 行缓冲，刷掉用户正在查看的输出；显示时又去除了 Tag，用户无法辨认来源。

SDL2／SDL3 Java 宿主与原生 `SDL_Log` 还共用 `SDL` 标签。简单限制为 `SDL:I` 会丢失用户的 verbose／debug 日志；只允许少量引擎标签则会丢失 raylib 和自定义 Android Tag，因此不能采用这些过滤方式。

## 修复与覆盖范围

- `ExternalSdlActivity`、`ExternalSdl2Activity`、`ExternalNativeActivity` 都经 `GraphicalRuntimeActivityHost` 接入同一个悬浮窗与 stdout/stderr 重定向。SDL2、SDL3、NativeActivity／raylib 统一采用 `GraphicalRuntimeLogPolicy`，不按引擎复制过滤器。
- 默认允许未知标签的所有等级，保留 stdout/stderr、`SDL`、`raylib`、任意用户自定义 Tag，以及 Java 崩溃、native 致命错误和宿主警告／错误。
- 已识别的窗口／输入法杂项在 logcat 命令层过滤；带 Activity 名或对象标识的 `VRI[...]@...`、`ViewRootImpl[...]`、`SurfaceView@...` 在进入有界日志缓冲前再过滤。不是按消息中是否含有 `java` 等词来删除内容。
- SDL2／SDL3 Java Activity、Surface 和辅助代码使用独立的 `TINA_SDL_HOST` 标签，原生 SDL 用户输出继续使用原标签。异常退出弹窗和日志导出同时接入新标签。
- 更多菜单新增中英“显示全部进程日志”。默认关闭；开启后保留全部标签并显示 `[Tag]`。切换会取消旧采集、替换视图并重放最近日志，不将两种来源混在一起；收起重开保留选择。
- 保留原 TinaEditor 的等级配色、选择复制、换行和有界缓冲；多行日志续行继承前一条日志的等级、来源与过滤决定。中英帮助同步，文件为 UTF-8 无 BOM。

过滤依据是已识别的 Tag，不是 Java／native 语言识别。未识别的厂商框架标签仍默认保留，避免误删用户自定义日志；收到具体噪声 Tag 后可按来源补充规则。

## 验证

原始结果：`app/build/verification/graphical-log-sources-20261125/`。

- App Kotlin／Java 编译通过；6 类定向 JVM／Robolectric 测试共 60 项，零失败、零错误、零跳过。XML 独立归档到 `app-junit/`，统计见 `app-tests-summary.json`。
- `core:logging` 编译与 `LogcatDumpPlannerTest` 的 2 项测试通过，零失败／错误／跳过，确认日志导出包含独立的 Java SDL 宿主标签；本轮合计 62 项测试通过。
- 用例覆盖三类图形宿主的窗口标签、自定义 Tag／引擎的全部等级、连续 600 条窗口杂项不挤掉用户输出、UTF-8、取消与进程释放、切换来源、收起重开、原生 PopupWindow 定位和复制。
- PMS110（Android API 37）运行独立 native 日志探针，向当前探针 PID 写入 10 条混合日志，完整模式读到 10 条，默认命令保留预期 6 条：stdout、SDL verbose、raylib debug、自定义 Vulkan Tag、宿主警告和用户错误。`device-filter-result.json`、`device-all-process.log`、`device-program.log` 保存结果。
- 真机探针仅验证 Android logcat 命令的 Tag／等级过滤；没有启动图形 Activity、安装 APK、操作其他应用小窗口或清空设备日志。探针结束后删除本次创建的远端临时可执行文件，不将此结果写成新 App 完整真机验收。
- 中英资源一致性、文档检查、UTF-8、共同宿主路径与 Java SDL 标签隔离检查通过。首轮 Gradle 命令因 PowerShell 拆分未加引号的 `-P` 参数失败，未执行测试；修正参数后运行通过，初始失败日志保留。

最小回归（Windows 使用 `gradlew.bat`，`-P` 参数加引号）：

```bash
./gradlew :app:testArm64DebugUnitTest --tests '*GraphicalRuntimeLogcatTest' --tests '*GraphicalRuntimeLogcatCommandTest' --tests '*GraphicalRuntimeLogPolicyTest' --tests '*GraphicalRuntimeOverlayTest' --tests '*GraphicalRuntimePopupAnchorTest' --tests '*GraphicalRuntimeLogDocumentTest' -I tools/testing/single-worker-tests.init.gradle '-Ptina.devAbi=arm64' '-Ptina.buildProotFromSource=false' --no-daemon --console=plain
./gradlew :core:logging:testDebugUnitTest --tests '*LogcatDumpPlannerTest' -I tools/testing/single-worker-tests.init.gradle --no-daemon --console=plain
```

后续安装新 APK 时，分别运行 SDL2、SDL3、raylib 工程，输出 `printf`／`std::cout`、原生引擎日志和自定义 Android Tag；查看默认模式下的用户输出，再切换完整模式辨认宿主标签，最后回归长按复制和窗口收起重开。本轮不递增版本号、不创建 Tag 或 Release。
