# SDL / raylib Native 图形运行回归

> 设备验证：2026-10-29；交接回归：2026-10-30；窗口/EGL 对照：2026-11-01。
> 修复包与模板已推送 Registry，但最新 APK 尚未完成 ARM64 真机端到端验收，
> 不能把包体检查或旧 Debug 宿主上的模拟器结果等同于真机验证。

## 支持策略（2026-10-30 交接范围）

- 只支持当前模板和符合运行契约的修复包；raylib 基线为 6.0 revision 2，模板为 1.0.1，
  最低宿主版本为 TinaIDE 0.18.36。旧 raylib/SDL 二进制包不再作为兼容或验收目标。
- 不新增旧包自动修补、降级兜底或包管理器全局迁移。已有安装需要重新安装修复包并重建项目；
  模板不重写既有源码。
- 下述旧配置迁移保留现有运行能力与 APK 导出能力的边界修复，
  不代表宿主能兼容旧包的错误帧循环或 ABI/入口契约。
- Registry 使用独立的 `packages/raylib/6.0-r2/` 制品路径，当前索引指向修复包；
  历史制品保持不可变，不覆盖旧文件，也不为旧包新增支持分支。

## 运行边界

- 默认编译环境是 native tina-toolchain + Android sysroot，不是 PRoot。
- SDL2/SDL3 共享库通过对应 SDL 宿主调用 `SDL_main`。
- raylib 使用 Android NativeActivity 后端：宿主加载项目及依赖库，raylib 导出
  `ANativeActivity_onCreate` 并引用未定义的普通 `main`，项目提供 `int main(void)`。
- 项目的 NativeActivity **运行能力**不要求目标叫 `main`；当前 APK 导出模板要求
  `libmain.so`。运行配置迁移不能用 APK 导出能力替代运行能力。
- 图形运行使用独立进程；结束运行后 IDE 主进程应保持存活，再次运行应创建新的运行进程。

## 已定位与修复

### 1. 旧 raylib 项目被错误交给 SDL 启动器

**触发条件**：schema < 7 的配置仍是 `outputMode=SDL`，项目被识别为 raylib
NativeActivity 工程，但输出目标不是 `main`，因此不具备当前模板的 APK 导出能力。

**根因**：旧迁移检查 `apkExportType == NATIVE_ACTIVITY`，把运行与导出两个契约混用。
这类项目未迁移，随后 SDL 启动器报缺少 `SDL_main`。

**修复**：[RunConfiguration.kt](../../core/compile/src/main/java/com/wuxianggujun/tinaide/core/compile/RunConfiguration.kt)
改为检查 `isNativeActivityRuntime()`，同时保留 schema 与 SDL 项目标记的保护条件。
不提高 schema，也不覆盖 schema >= 7 的显式 SDL 选择。

回归包含真实的非 `main` CMake 目标、schema 6 配置、落盘结果和重复加载幂等性。

### 2. raylib 成功加载后仍黑屏、没有输入

**根因**：固定上游 raylib 6.0 提交
`dbc56a87da87d973a9c5baa4e7438a9d20121d28` 的
`cmake/ParseConfigHeader.cmake` 会将未注释的 `SUPPORT_*` 定义置为 `ON`，忽略数值。
`CUSTOMIZE_BUILD=ON` 因而错误启用了原本为 `0` 的 `SUPPORT_CUSTOM_FRAME_CONTROL`，
使 `EndDrawing()` 丢失默认的交换帧、输入轮询和计时行为。仅检查入口符号无法发现此问题。

**修复**：[build-raylib.sh](../../docker/tinaide-pkg/libs/build-raylib.sh)
使用 `CUSTOMIZE_BUILD=OFF` 保留上游配置默认值；
[打包脚本](../../docker/tinaide-pkg/package-native-library.sh) 将 raylib 标记为
`packageRevision: 2`，并检查 `EndDrawing` 的反汇编：

- 必须包含 `SwapScreenBuffer` 或内联后的 `eglSwapBuffers`。
- 必须包含 `PollInputEvents`。
- 反汇编工具失败、只含其中一种调用、缺少两种调用均拒绝打包。

ARM64 和 x86_64 新库都通过入口、SONAME、无 SDL 依赖及 LOAD 对齐检查；旧库均被新增门禁拒绝。
此门禁针对固定版本和 NDK 构建；升级 raylib 或编译器后若调用进一步内联，应核验生成代码后调整，
不能直接删除门禁或把工具失败当成通过。

### 3. raylib 模板返回键不能退出

raylib Android 后端消费 Back，但不会因此让 `WindowShouldClose()` 返回 true。
[本地模板](../../test-plugins/tinaide.template.raylib/README.md) 1.0.1 在绘制前处理按键队列：

```c
bool exitRequested = false;
for (int key = GetKeyPressed(); key != 0; key = GetKeyPressed()) {
    if (key == KEY_BACK || key == KEY_ESCAPE) exitRequested = true;
}
if (exitRequested) break;
```

退出循环后仍执行 `CloseWindow()`。队列能捕获 DOWN/UP 在同一帧内完成的短按，
不依赖宿主浮层是否可见，也不改变其他 raylib 项目的全局按键语义。
模板更新不会重写既有项目，既有项目需按需补充相同逻辑。

## 自动化验证

从仓库根目录执行；其他会话正在构建时不要启动本轮 Gradle。

```bash
./gradlew :core:compile:testDebugUnitTest \
  --tests "com.wuxianggujun.tinaide.core.compile.RunConfigurationManagerNormalizationTest" \
  --tests "com.wuxianggujun.tinaide.core.compile.ProjectRunConfigBootstrapperTest" \
  --tests "com.wuxianggujun.tinaide.core.compile.launcher.SdlLauncherTest" \
  --tests "com.wuxianggujun.tinaide.core.compile.elf.ElfDynamicSymbolReaderTest" \
  --tests "com.wuxianggujun.tinaide.core.compile.RaylibTemplateRegressionTest" \
  --tests "com.wuxianggujun.tinaide.core.compile.Sdl3TemplateRegressionTest" \
  --no-daemon --console=plain

python -m unittest discover -s tools/checks -p 'test_*.py' -v
bash -n docker/tinaide-pkg/libs/build-raylib.sh
bash -n docker/tinaide-pkg/package-native-library.sh
```

Windows 的 Python 命令可替换为 `py -3 -X utf8`。Bash 测试需要 Git Bash；
如果 PowerShell 的 `Get-Command bash` 指向 `C:\Windows\System32\bash.exe`，
应在 Git Bash 中执行，或先将 Git 安装目录的 `bin` 加到当前进程 `PATH` 的最前面，
避免误用 WSL 入口。源码、模板、JSON 和文档均使用 UTF-8 无 BOM。

2026-10-29 的 6 个 Kotlin 测试类共 **41 项通过**，从 XML 核对 failures/errors/skipped 均为 0；
Python checks 共 **20 项通过**，其中 raylib 包检查 9 项。
Python 检查覆盖构建选项、revision、打包门禁的正反样例、制品校验和与多归档库文件一致性。
它已经由现有 `dev-static-check.yml` 的 unittest discovery 执行，不另建重复 CI 入口。

2026-10-30 交接时补充最低宿主版本与包依赖声明回归，重新确认：

- `:core:compile:compileDebugKotlin` 通过；上述 6 个测试类共 **42 项通过**，
  failures/errors/skipped 均为 0。首次限时运行未完成，最终使用同步有界命令完成编译与测试。
- Python **20 项通过**，两个 Bash 构建/打包脚本的 `bash -n` 通过。
- Registry 完整重建与校验通过；逐个核对 3 个制品与本仓库修复包完全相同，
  模板 `.tinaplug` 的 manifest、模板 zip、许可证及最低宿主门禁正确。
- 验证在当前工作区运行；本轮提交不包含工作区原有的编辑器/API 迁移改动，也不作为该迁移的验收。

## 本次设备验证结果

使用专属 `tina_native_runtime_api34` / `emulator-5580`，Android API 34、x86_64、
SwiftShader。所有 ADB 操作显式指定 serial，未使用其他会话的模拟器；验证后仅关闭本任务的 AVD。

宿主为已有的 `TinaIDE-0.18.36-x86_64-debug.apk`（2026-10-25 产物），
**不是本轮重建的 APK**。样例用 PC 上 NDK r27c 编译，经 debuggable 同 UID 启动器交给
真实 `ExternalNativeActivity` / SDL 宿主，没有更改 Activity 导出属性。
传输后逐文件核对 SHA-256。

| 场景 | 观察结果 |
| --- | --- |
| 旧 raylib 库的独立 NativeActivity 对照样例 | 黑屏，`GetFrameTime()` 为 0，未收到触摸 |
| 最终 raylib r2 包的真实 TinaIDE 宿主样例 | 可见绘制、709 帧、收到触摸，帧时间约 34 ms，正常退出 |
| SDL2 2.32.10 revision 1 | 进入 `SDL_main`，845 帧、收到触摸，正常退出 |
| SDL3 3.5.0 revision 2 | 进入 `SDL_main`，830 帧、收到触摸，正常退出 |
| 修复后的实际 raylib 模板 | ARM64/x86_64 均编译成功；x86_64 显示圆形动画与 FPS |
| 模板按返回键退出并再次运行 | 两次均记录 `Window closed successfully`；运行进程重建并退出，IDE 主 PID 不变 |

帧数和帧时间只是该模拟器的诊断样本，不是性能保证。截图已人工核对，不只依据循环日志判断渲染成功。
本地原始日志和截图位于 `.tmp/native-runtime-smoke/`，Kotlin 结果为
`.tmp/native-graphics-runtime-final-tests.log` / `.json`；这些临时证据不随 Git 分发。

### 2026-11-01：`GetWindowHandle() == 0` 与绘制线程的对照

仍使用专属 API 34 / x86_64 / SwiftShader 模拟器、已有的 0.18.36 Debug 宿主和
revision 2 动态库，额外在用户 `main` 的绘制线程查询 EGL。观察到：

```text
USER_MAIN_ENTERED pid=5372 renderTid=5402
WINDOW_PROBE publicHandle=0x0 currentContext=0x7fed2c9611d0
             drawSurface=0x7fed2c965430 surface=480x1010 ready=1
             renderTid=5402 eglError=0x3000
FRAME_OK frames=60 frameTime=0.033334
INPUT_TAP count=1
SMOKE_COMPLETE frames=742 touches=1
```

已人工检查截图：矩形可见并移动，点击后背景由蓝色变绿色，随后正常退出。
因此 **公共 handle 为 0、绘制线程不等于 UI 线程，都不能作为本问题的黑屏根因**。
这只是宿主路径的反证，不是对用户 Android 16 / ARM64 黑屏现场的复现或修复验收。
本轮证据保存在 `app/build/reports/native-graphics-20261101/` 和
`.tmp/native-runtime-smoke/ide-raylib-window-probe-egl-20261101-*`，不随 Git 分发。

## 固定制品与安装注意事项

本地既有 `docker/tinaide-pkg/output` 中的两个 build archive 与三个 Registry archive
已替换为修复版。复用生产打包脚本重新打包后，三个 Registry archive 的 SHA-256 一致：

| 文件 | SHA-256 |
| --- | --- |
| `raylib.tar.xz` | `ded1e14b407f13b7ffea1f4708593fea64a5e0bf2068d6136edf456ee4cfc145` |
| `raylib-arm64-v8a.tar.xz` | `0a2807e18383df9676382c4f37bdb7592036828416085dfe442566da244f7fa3` |
| `raylib-x86_64.tar.xz` | `c4f70f6107cf76e99cc2ba33f6ce8cfe67eed3988843efa84767165b3288a93d` |

2026-11-01 从这些归档逐个核对的 **revision 2 库文件** SHA-256 如下。它们用于检查
实际安装/装载的文件，不要把归档校验和与解压后的 `.so` 校验和混用：

| 包内路径 | SHA-256 |
| --- | --- |
| `lib/arm64-v8a/libraylib.so` | `6dd20f595afbe7788972f90113eeae73f06576cf73e8759413aa907312621b0f` |
| `lib/x86_64/libraylib.so` | `15c82ec88c65a5596f2fd6c81f9868f85289e8a83a3aac5f8b13c6eaff71b6fb` |

已按授权清理旧 raylib 备份、旧对照库和旧对照 APK，保留日志与截图；未清理其他模块、
其他会话缓存或其模拟器数据，也未重写 Git 历史。

`packageRevision` 是包元数据，不等于自动升级策略。当前
[BundledPackagesInstaller](../../core/packages/src/main/java/com/wuxianggujun/tinaide/core/packages/BundledPackagesInstaller.kt)
遇到有效已安装包会跳过；只升级 APK 不能替换旧库。
2026-10-30 已将市场包、索引与模板推送到
[TinaIDE-Registry 提交 fca514d](https://github.com/wuxianggujun/TinaIDE-Registry/commit/fca514d907b519a2adb3c703fec042b791e9ad02)：

- 修复包：`packages/raylib/6.0-r2/`，包内仍标记上游版本 `6.0` / `packageRevision: 2`。
- 模板：`tinaide.template.raylib` 1.0.1，最低宿主 `0.18.36`，只进入当前插件 v3 索引。
- 历史 `6.0/` 制品保持原样，当前包详情的所有下载源指向 `6.0-r2/`。

刷新市场后重新安装 raylib 并重建项目。本轮未创建 Release/tag，也未发布 APK。

## 黑屏现场排查

### 不要用无效指标判断离屏或线程问题

固定上游提交的 `src/platforms/rcore_android.c` 中，`GetWindowHandle()` 直接记录
“not implemented”并返回 `NULL`；它不是 `android_app->window` 的访问器。
该后端实际通过 `eglCreateWindowSurface(..., platform.app->window, ...)` 创建窗口
surface，再调用 `eglMakeCurrent`，不是通过 pbuffer 做离屏显示。

`ANativeActivity_onCreate` 接收 Android 生命周期回调，`native_app_glue` 的工作线程
再执行 `android_main` / 用户 `main`，这是正常路径。真正需要遵守的是 **EGL context
的线程归属**：同一 context 同一时刻只能 current 到一个线程，raylib 的初始化、绘制、
交换帧和销毁保持在其渲染线程。自行创建另一个线程直接调用绘图 API 确实可能出问题，
但不能由此推导为“必须在 Activity UI 线程绘制”。

### 最短验证顺序

1. 先保留旧项目，在独立的新项目中使用当前 raylib 模板。
2. 刷新市场并重新安装 raylib，核对 `files/installed-packages/raylib/package.json`
   的 `packageRevision: 2` 和对应 ABI 的库文件校验和；仅看到版本 `6.0` 不够。
   Release 包通常不能通过 `adb run-as` 读取私有目录，应在 App 自身终端/同 UID 环境检查，
   不要为排查清空整个 App 数据。
3. 执行“清理构建目录 / 清理并重新配置”再编译；检查链接和运行日志，避免项目目录内
   的旧 `libraylib.so` 遮蔽新安装包。宿主优先搜索主库目录和项目附加运行库目录。
4. 验收可见动画、触摸、Back、连续两次运行，再测切后台/恢复。仅有循环计数、
   `EGL 1.5` 或没有 `EGL Error` 不足以证明已交换帧并显示。
5. 如果新项目仍黑屏，在实际绘制线程、`InitWindow()` 返回后增加下面的诊断；
   不要在 Kotlin/UI 线程查询 EGL 后拿 `EGL_NO_CONTEXT` 当作 raylib 的状态。

文件顶部添加头文件，然后把以下查询放在 `InitWindow()` 后、绘制循环前：

```c
#include <EGL/egl.h>
#include <android/log.h>
#include <unistd.h>

/* Inside main(), immediately after InitWindow(). */
EGLDisplay display = eglGetCurrentDisplay();
EGLContext context = eglGetCurrentContext();
EGLSurface surface = eglGetCurrentSurface(EGL_DRAW);
EGLint priorError = eglGetError();
EGLint width = 0, height = 0;
EGLBoolean widthOk = EGL_FALSE, heightOk = EGL_FALSE;
if (display != EGL_NO_DISPLAY && surface != EGL_NO_SURFACE) {
    widthOk = eglQuerySurface(display, surface, EGL_WIDTH, &width);
    heightOk = eglQuerySurface(display, surface, EGL_HEIGHT, &height);
}
__android_log_print(ANDROID_LOG_INFO, "NativeRuntimeSmoke",
    "WINDOW_PROBE pid=%d tid=%d context=%p drawSurface=%p size=%dx%d "
    "sizeOk=%d ready=%d priorError=0x%x queryError=0x%x",
    getpid(), gettid(), context, surface, width, height,
    widthOk && heightOk, IsWindowReady(), priorError, eglGetError());
```

必要时在该测试项目的 CMake 目标上增加 `target_link_libraries(${PROJECT_NAME} PRIVATE EGL log)`。
`eglGetError()` 会消费当前线程的错误状态，上述代码仅用于定位，不要当成生产渲染逻辑。
另外在 `EndDrawing()` 返回后记录一次 `GetFrameTime()`，以区分 revision 1 的无交换帧循环。

保留复现前后日志（将 `<serial>` 换成目标设备；以下重定向在 Git Bash 中保持 UTF-8）：

```bash
adb -s <serial> logcat -d -v threadtime \
  TinaNativeActivity:I NativeActivityResolver:I NativeActivityStager:I \
  raylib:I NativeRuntimeSmoke:I AndroidRuntime:E '*:S' > raylib-logcat.txt
```

上报最小项目、包 revision/库校验和、ABI、日志与屏幕录像。当前附件中的 Java 模型列表
NPE 是另一条问题，不能替代这个图形进程的 EGL/窗口现场；其根因与修复见
[R8 平台接口冲突](../proguard-rules-reference.md#46-第三方-jar-重复定义-android-系统接口)。

## 尚需完成的验收

1. 构建当前 Debug APK，在独立设备中走“新建项目 → 安装修复包 → 设备编译 → 运行”，
   确认新 raylib 项目使用 NativeActivity。旧包兼容与旧项目自动迁移不作为交接门槛。
2. 在 ARM64 真机验证渲染、触摸、Back 退出和连续两次运行；如需承诺 16 KB 页兼容，
   还需对应设备测试，ELF 对齐检查不能替代它。
3. 验证 Registry 发布后的重装确实得到 revision 2 的库，而不是缓存中的 revision 1；
   核对 installed-packages 中的 `package.json`、ABI 和库文件校验和。

这轮不修改包管理器的全局升级策略、不重构 NativeActivity 浮层、不调整 PRoot 或编辑器模块，
也未验证音频、生命周期压力和 APK 导出。恢复旧 raylib 包会重新引入黑屏，不能作为有效修复回滚方案。
