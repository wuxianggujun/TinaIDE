# 图形运行控制／日志悬浮窗改造与验收

本轮从 2026-11-23 续接至 2026-11-24（Asia/Shanghai）。目标是先提交上一轮修复，再改善图形程序的悬浮交互，不直接发布未经验收的版本。

## 1. 仓库与发布边界

- 继续使用原仓库的 `dev` 分支，只有一个 worktree，没有新建工作区。
- 上一轮编辑器子模块提交 `d48117ded57748e82ebdbcc17055f8707543a6d1` 已先推送到 `origin/main`；主仓库提交 `7fa374b5f6aa9d973b62e352f8e738dd35caec28` 随后推送到 `origin/dev`。本轮没有新的子模块修改。
- 上一轮 [Core Regression Checks / 45190389534](https://github.com/wuxianggujun/TinaIDE/actions/runs/45190389534) 成功；它不代表本轮悬浮窗代码已在 CI 验证。
- 本轮只构建 Debug 验证包，不打 Tag、不创建 GitHub Release。`version.properties` 仍为 `0.19.6 / 1907`，正式版本仍是 `v0.19.2`。
- 修改对象是**控制／日志悬浮窗**，不是把 SDL 原生渲染 Surface 改成可浮动、可缩放的程序窗口，也不改动 X11 桌面。

## 2. 现象与设计

原界面同时显示返回按钮、日志控制球和大面板内的重复返回入口，占用图形画面；窗口尺寸固定，缺少完整的拖动、缩放和恢复语义。

本轮采用三个小边界，复用现有 PopupWindow 和设计系统，不新增系统悬浮权限或第三方依赖：

| 边界 | 文件 | 职责 |
| --- | --- | --- |
| 几何状态 | `app/src/main/java/com/wuxianggujun/tinaide/ui/runtime/GraphicalOverlayWindowState.kt` | 控制球、普通窗口、最大化／恢复、安全区限幅、dp 快照 |
| Android 宿主 | `app/src/main/java/com/wuxianggujun/tinaide/ui/runtime/GraphicalRuntimeActivityHost.kt` | 独立 PopupWindow、真实位置与尺寸、insets、Bundle、生命周期 |
| Compose UI | `app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/GraphicalRuntimeOverlay.kt` | 紧凑标题栏、菜单、拖动／缩放手势、退出确认 |

数据流：`手势／菜单 → 几何状态 → onBoundsChanged → PopupWindow.update`。状态只保存几何和模式，不把 Activity、View 或 LayoutCoordinates 写进快照。

### 用户交互

1. 默认只显示一个 48 dp 控制球；点击打开菜单，拖动改变位置，不自动展开日志。
2. 日志窗标题栏支持拖动和双击最大化／还原，右下角支持缩放；放大／缩小同时提供无障碍操作。
3. 标题栏只保留最大化／还原、收起和更多菜单，不重复显示控制球或大返回按钮。
4. 收起不退出程序；重新打开保留尺寸和位置。最大化保留普通窗口的恢复几何。
5. 返回编辑器放在菜单内，仍需确认；取消后程序继续运行。
6. 窗口外触摸继续交给原生程序，拖动、缩放和窗口按钮不应误触程序。位置和尺寸限制在系统栏与挖孔安全区内。

复用了 `TinaOverlayPanelSurface`、`TinaPanelSegmentButton`、`TinaConfirmDialog`、原有退出 guard 和 PID 限定 UTF-8 logcat 捕获。文案同步 `values` / `values-en`，中英文图形教材和构建帮助同步解释操作及渲染尺寸边界。

## 3. 发现并处理的测试／拖动问题

- **取消按钮选择**：包含非重要节点后，驱动命中了 Compose Text 叶节点，直接调用 ACTION_CLICK 失败。改为点击最近的启用、具有点击 action 的祖先；未修改生产确认弹窗，也未绕过退出确认。
- **横屏缩放轨迹**：最初先向下拖到安全区底边，再要求继续增高，触发了正常限幅。横屏改为向上拖动留出空间，仍要求宽高都增大、再都缩小，不放宽成“任一方向改变”。
- **拖动跟手**：原先“坐标改变”断言过弱。补充屏幕位移检查后，实测 touchSlop 为 14 px，控制球横坐标应到约 87.21，却只到 60。PopupWindow 移动改变本地坐标原点，直接累加本地 delta 会丢失部分位移。修复为比较真实屏幕坐标，首个拖动仍保留越过 touchSlop 的 delta；保留浮点累积，仅更新真实窗口时取整。
- **设备树同步**：输入注入成功不代表最后一帧布局已完成，拖动结束后等待 UI idle，并直接等待预期位移。Android 34 的虚拟节点缓存还会保留旧位置／可见性，驱动在支持的系统上使用公开 `UiAutomation.clearCache()` 查询当前树；找不到节点时保留目标标签、窗口 bounds 和失败截图。
- **最大化采样**：一次 JUnit 已完成但证据解析仍拒绝了越界矩形：旧原点加上新尺寸属于中间帧。最终设备断言同时等待宽高增大和完整矩形位于显示范围内，再保存最大化截图；没有放宽屏幕边界检查。最终英文横屏最大化矩形为 `[14, 56, 1266, 664]`，而不是旧的 `[114, 163, 1366, 771]`。

跟手断言同时覆盖控制球和标题拖动：扣除实际设备 touchSlop 后，坐标误差不超过 4 px。证据解析器也检查实际位移，不能只用一个 `pointerFollowing=true` 标志冒充验证。

## 4. 验证入口

定向 JVM / Robolectric 测试（每个 Test task 最长 60 秒）：

```bash
./gradlew -Ptina.devAbi=arm64 -I tools/testing/single-worker-tests.init.gradle \
  :app:testArm64DebugUnitTest \
  --tests '*GraphicalOverlayWindowStateTest' --tests '*GraphicalRuntimeOverlayTest' \
  --tests '*GraphicalRuntimeActivityHostTest' --tests '*GraphicalRuntimeExitGuardTest' \
  --tests '*NativeActivityHostContractTest' --tests '*GraphicalRuntimeLogcatCommandTest' \
  :app:compileArm64DebugKotlin --no-daemon --console=plain
python -m unittest discover -s tools/testing -p test_editor_runtime_regression.py
python tools/i18n/check_all.py
python tools/checks/check_all.py
```

Windows PowerShell 使用 `gradlew.bat`，并把 `'-Ptina.devAbi=x86_64'` 等属性整体引用。本轮没有同时启动其他会话的 Gradle，也没有停止共享 daemon、删除缓存或重定向 build 目录。

真实 SDL3 浮窗与普通课程基线：

```bash
python tools/testing/run_editor_runtime_regression.py --adb adb --serial emulator-5580 \
  --suite graphics-overlay --orientation portrait --output app/build/verification/overlay-manual-portrait
python tools/testing/run_editor_runtime_regression.py --adb adb --serial emulator-5580 \
  --suite graphics-overlay --orientation landscape --output app/build/verification/overlay-manual-landscape
python tools/testing/run_editor_runtime_regression.py --adb adb --serial emulator-5580 \
  --suite graphics-course --output app/build/verification/overlay-course-baseline
```

每个设备用例由现有 runner 限制为 60 秒，设备 runner / 协程还有独立期限。失败、超时或缺少完整报告均不计为通过。

`graphics-overlay` 使用实际中英文教材蓝图和最后一层触摸参考片段，在 `files/course-acceptance` 内生成测试专用源码；通过 native CMake、安装的 SDL3 包和正式隔离宿主运行，不改写 `files/projects` 中的用户工程。

## 5. 最终结果与证据

### 本地测试与构建

| 验证项 | 实际结果 | 证据 |
| --- | --- | --- |
| ARM64 图形宿主定向测试及 Kotlin 编译 | 39 项通过，0 失败／错误／跳过 | `verified-unit-summary.json`、`arm64-verified-test-compile.log` |
| x86_64 图形宿主定向测试及 Debug 构建 | 39 项通过，0 失败／错误／跳过 | `verified-unit-summary.json`、`x86-pointer-fix-build-and-tests.log` |
| 教程模块测试 | 92 项通过，0 失败／错误／跳过 | `feature/tutorial/build/test-results/testDebugUnitTest/TEST-*.xml` |
| Python 设备证据解析 | 44 项通过 | `python-evidence-tests-final.log` |
| 国际化、维护及 diff 检查 | 全部通过 | `i18n-final.log`、`maintenance-final.log`、`git diff --check` |
| 最终 AndroidTest APK 重建 | 成功；包含最大化等待及截图补丁 | `android-test-stable-maximized-build.log` |

39 项包括几何状态 12、Compose 交互 9、宿主生命周期 5、退出 guard 4、native 宿主契约 8、PID 限定 logcat 命令 1。两种 ABI 是各自的定向测试结果，不是 ARM64 真机测试。

### SDL3 设备最终矩阵

设备为本任务启动的 `tina_native_runtime_api34`，`emulator-5580`，Android 34 / x86_64。四组浮窗用例均验证真实课程的 native CMake 构建、SDL3 画面和触摸参考片段，并操作实际隔离进程的 PopupWindow。

| 用例 | 用时 | runId | 结果目录 |
| --- | --- | --- | --- |
| 中文教材／竖屏浮窗 | 21.26 s | `6cfa94f25ed743bdaaad6ff612047591` | `final-portrait/` |
| 英文教材／竖屏浮窗 | 22.45 s | `fbf98325f7ef40b482d9edd04448e3d7` | `final-portrait/` |
| 中文教材／横屏浮窗 | 23.68 s | `b3a1373e74d044a5af0a0fd280bf22b3` | `final-landscape/` |
| 英文教材／横屏浮窗 | 25.10 s | `66b863716f704b4e92e0c6e43feed46a` | `final-landscape/` |
| 中文教材／关闭日志的课程基线 | 6.98 s | `27b259daed1f4f5aa477cf5e70e80a66` | `final-course-baseline/` |
| 英文教材／关闭日志的课程基线 | 7.21 s | `43934c28d8364e7ca4711831b7430bca` | `final-course-baseline/` |

上述 6 项全部通过，均未超时、未跳过；每组 `results.json` 和本次 runId 的 metrics 同时通过现有 runner 校验。先行的英文横屏单项也通过，保留在 `final-stable-landscape-en/`，不重复计入六项矩阵。

`device-final-summary.json` 汇总六项结果、UTC 启动时间、原始报告和实际截图 SHA256。拉取截图前核对设备 JSON 的 runId，并检查 PNG 修改时间不早于本组运行开始、图像尺寸与本次报告一致，避免引用上一轮截图。竖屏为 720 × 1280，横屏为 1280 × 720；已视觉检查控制球、展开窗和最大化安全区。

代表截图：`final-portrait/graphics-overlay-en-sdl3-control.png`、`final-portrait/graphics-overlay-en-sdl3-window.png`、`final-landscape/graphics-overlay-en-sdl3-maximized.png`。完整矩阵包括四组各三张浮窗截图，以及两张课程基线截图。

### 最终 APK 身份

仅覆盖安装到上述模拟器，没有卸载或清空应用。`final-apk-digests.json` 记录本地与设备已安装 `base.apk` 的一致性：

- 主 Debug APK：`app/build/outputs/apk/x86_64/debug/TinaIDE-0.19.6-x86_64-debug.apk`，SHA256 `bfba993cac78d09f47bcd9dbed1ae4bc15c8b1d2c98375fd062f965c66e24b71`。
- 最终测试 APK：`app/build/outputs/apk/androidTest/x86_64/debug/app-x86_64-debug-androidTest.apk`，SHA256 `fb59d1c73140ac3030afc7959931e7513b7a8f148940494b03d6b588019c93bc`。

CI 已增加六类图形宿主测试和 Python 证据解析测试；提交推送后须另查本轮 commit 的结果，上一轮成功记录不能替代新 CI。

本地证据统一保留在 `app/build/verification/graphical-overlay-20261123/`，包含失败轨迹、构建日志、JUnit 汇总、每次调用的 runId 和实际截图。启动阶段截图不保证 PopupWindow 已附着；控制球截图须在 `waitTag` 完成后截取，展开截图须在几何和输入断言完成后截取。

中英文矩阵指教材资产语言；模拟器 UI 跟随其应用／系统语言，不能把 `courseLanguage=zh` 当作中文 UI 的设备验收。

## 6. 未覆盖与下一步

- 本轮没有新的 ARM64 Release/R8 构建、真机安装或正式发布。此前验证安装到 PMS110 的 `0.19.6 / 19072` ARM64 Release 不包含本轮悬浮窗。
- SDL2 / raylib 独立运行探针未在本模拟器准备，未将其设备用例或未执行项写成通过。公共宿主的静态／生命周期契约测试不能替代这些引擎的实际设备回归。
- 横竖屏分别启动和密度／视口状态测试不等于已验证所有运行中旋转、所有 Android 版本或所有真机。
- 推荐在本轮 CI 通过后，按现有自动递增机制构建 ARM64 Release 候选、覆盖安装到真机，再手测快速／连续拖动、缩放边界、收起重开、取消／确认退出及重跑程序，最后正式发版。
- 可单独回退悬浮窗提交，不必回退已经推送的 Markdown 高亮、fmt 和项目名称修复。
