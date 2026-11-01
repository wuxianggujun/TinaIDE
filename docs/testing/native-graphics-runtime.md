# SDL / raylib Native 图形运行回归

> 更新：2026-11-01。当前修复基线为 raylib **6.0 revision 3** 与统一图形运行宿主。
> 首轮双 ABI Release 已构建并完成签名/分发检查；API 36 / x86_64 完整 IDE 已验证
> 83 轮运行/退出、编译取消、触摸、后台恢复和超过 10 分钟前台运行。导出壳后续修复另行验收，实际范围、证据和
> 未覆盖项见 [0.18.39 清单](release-0.18.39-manual-checklist.md)，不扩大为所有真机已验证。

## 当前支持策略

- 只支持当前模板与修复包；raylib 基线为 6.0 revision 3，模板仍为 1.0.1。
  本次宿主修复随 0.18.39 交付，模板原有最低版本声明 0.18.36 不代表旧宿主包含本次修复。
- 不兼容旧 raylib r1/r2，不在运行时二进制打补丁，不重写项目源码。
  安装、运行 staging 和 APK 导出均拒绝旧库；仅把元数据改成 r3 不能绕过库内标记校验。
- 包管理器在上游版本相同时比较 `package_revision`；已有 6.0 r2 也能发现 r3 更新，
  依赖规划不再把旧 raylib 当成可用安装。更新资源后仍需清理并重编译项目。
  刷新/检查更新同时失效 `PackageApiClient` 的内存索引与详情；刷新前迟到的详情不能污染新索引。
- 下述旧配置迁移保留现有运行能力与 APK 导出能力的边界修复，
  不代表宿主能兼容旧包的错误帧循环或 ABI/入口契约。
- Registry 使用独立的 `packages/raylib/6.0-r3/` 路径。当前树已清理旧 `6.0/`、`6.0-r2/`
  归档，保留 Git 历史；主仓库也删除两份重复的 ABI build archive，仅保留规范 Registry 归档。

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
[打包脚本](../../docker/tinaide-pkg/package-native-library.sh) 最初将修复标记为
`packageRevision: 2`；当前 r3 继续保留以下 `EndDrawing` 反汇编门禁：

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

### 4. 手势退出后第二次运行黑屏（r3）

**触发条件**：系统手势/宿主返回控件调用 `finish()`，而非用户循环自己处理 `KEY_BACK`。
NativeActivity 的 UI 线程销毁时等待 `android_main` 返回；旧 raylib 已收到 `destroyRequested`，
却仍进入 `ALooper_pollOnce(-1)`。两边相互等待，放在 `super.onDestroy()` 后的 kill 永远不可达。
旧图形进程残留，重跑复用它就会黑屏。

修复落在固定上游的 [生命周期补丁](../../docker/tinaide-pkg/patches/raylib-android-destroy.patch)：
收到销毁请求立即退出轮询；窗口尚未创建时也返回失败，不无限等窗口。构建脚本在 CMake 前
检查并应用补丁，打包额外核对库内 `TinaIDE lifecycle r3` 标记，拒绝旧库冒充新 revision。

2026-11-01，ARM64 / Android 17 / 手势导航的无 watchdog 独立夹具 A/B：

- r2 首次有帧，手势后只有 `destroy-enter`、没有 `destroy-return`，二次同一图形 PID 黑屏。
- r3 连续 5 次有可见绘制、`main-return` 和 `destroy-return`，图形 PID 每次换新，launcher PID 不变。
- 可维护夹具重跑：launcher PID 2640，图形 PID 3577 / 4927 / 5731 / 6120 / 6522；
  第 5 次截图已检查，蓝色背景、方块和帧数可见。这是 raylib/bridge 的证据，不是新 IDE APK 的验收。
- 较早 r3 Home 对照保持同一图形 PID，后台暂停后恢复继续出帧；可维护夹具的其他套件仍需单独执行。

证据在 `app/build/reports/native-repeat-20261101/` 与
`app/build/reports/native-activity-regression/gesture/`，不随 Git 分发。
复现工具见 [夹具说明](../../tools/native-activity-regression/README.md)。

### 5. 快速连点、迟到编译结果与迟到 Activity

运行按钮原来在保存文件之后才变为忙碌，两个缓冲队列又会依次交付编译结果和启动事件。
单独在启动器加 Mutex 无法阻止锁释放后执行的第二个旧事件。

- `CompileActionGate` 在第一次保存前获取一次性 ticket，覆盖保存、编译、结果队列和窗口启动。
  重复输入不排队；结果与启动分别只能消费一次。编译进程暂时 IDLE 不会提前解锁按钮。
- ticket 与两个事件队列由 `CompilerViewModel` 持有，Activity 重建不会遗失排队请求的所有者。
  停止使旧结果失效，旧 finally 不能解除新请求；重新编译等待已取消任务清理完成。
  未交付事件会释放所属 ticket，避免生命周期取消恰逢 Channel 收取时留下永久忙碌状态。
  顶栏 Stop 与 Run 分开，避免连续点击 Run 变成取消/重新启动切换。
- 三种引擎共享 `GraphicalRuntimeProcessController`：启动前只回收同 UID 的 `:gui/:sdl/:sdl2`，
  确认旧进程退出后才覆盖 staging 文件；不影响 IDE、X11、插件进程。
- `GraphicalRuntimeLaunchSignal` 使用一次性同步 Binder 票据。取消/超时先撤销票据，
  迟到 Intent 在加载引擎前被拒绝；NativeActivity/SDL 的空生命周期不改写新运行的 native 全局状态。
- 入口确认只表示 Activity 已接受，不冒充首帧成功；最终仍须观察真实画面和输入。

### 6. 无限游戏运行与有限退出时间是不同概念

`GraphicalRuntimeExitGuard` 不设置游戏总运行时限，Home / onPause / 普通 onStop 不触发杀进程。
只有明确退出、finish 或真正销毁时启动独立工作线程的 2 秒退出保护；它必须在可能阻塞的
native 回调之前生效，不能依赖 UI Handler 或 lifecycleScope。SDL 先发 quit，最多给 800 ms
优雅退出时间。启动入口等待 10 秒、旧进程退出等待 2 秒也都不是游戏运行时限。
编译任务原有超时配置与终端会话管理保持不变。

### 7. SDL3 启动时旋转往返与 APK 导出壳

SDL3 旧市场模板先用 flags `0` 创建 960×540 窗口，再调用 `SDL_SetWindowResizable`。
API 36 日志记录到方向请求 `6 → 13` 和 Surface `720×1600 → 1600×720 → 720×1600`，
部分启动截图短暂只绘制上方区域。将 `SDL_WINDOW_RESIZABLE` 直接传给
`SDL_CreateWindowAndRenderer` 后，不再出现这次初始化方向往返；市场模板提升为 **1.0.4**，
保留 SDL_main 入口与原许可证。旧项目需自行同步这一行，新模板不会重写用户源码。

导出壳与 IDE 内运行是两条路径。API 36 的旧 NativeActivity 导出壳在重复启动测试中
出现 `Activity destroy timeout`、新线程 shader 初始化失败以及停留启动页；原壳既允许
重复 Activity 实例，也没有 IDE 宿主的进程退出保护。API 34 的旧壳对照曾连续通过，
不能据此把问题描述成所有 Android 版本必现，或将 API 36 的现象只归因于 ADB 断连。

两种内置图形 APK 模板现在使用 `singleTask` 防止并行引擎实例，系统返回明确 `finish()`，
并复用 `tools/template-common` 的 `TemplateRuntimeExitGuard`；正常销毁与超时兜底均只回收
独立导出 APK 自己的进程。它与 IDE 宿主遵守同一退出契约，但不让独立 APK 引入宿主依赖。
Home 不触发退出保护。模板源码、二进制资产及源码哈希须一起更新；具体 A/B 与完整 IDE
导出验证结果按 [Release 清单](release-0.18.39-manual-checklist.md) 单独记录。

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

## 历史设备验证结果（r2，不能覆盖现代手势退出）

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

当前规范归档位于 `docker/tinaide-pkg/output/registry/raylib/6.0/`，包内 revision 为 3；
Registry 对应下载路径为 `packages/raylib/6.0-r3/`。三个归档的 SHA-256：

| 文件 | SHA-256 |
| --- | --- |
| `raylib.tar.xz` | `2dd92263294d9e8efdc4431ce3ea3e59dff17c7b4c304e8075476f388103aeb0` |
| `raylib-arm64-v8a.tar.xz` | `ef6a09b6634cfd40b0739f7c3b8a7ec9f5827ce060458e5d825e1dad125b93cf` |
| `raylib-x86_64.tar.xz` | `29b889a8a8ec2c6a6976f0bc8deb65c45b9cc69e623f28c3c92ee21c95940144` |

2026-11-01 从这些归档逐个核对的 **revision 3 库文件** SHA-256 如下。它们用于检查
实际安装/装载的文件，不要把归档校验和与解压后的 `.so` 校验和混用：

| 包内路径 | SHA-256 |
| --- | --- |
| `lib/arm64-v8a/libraylib.so` | `edfab8bc9f6c8cdefce46e7676fc5117a75670e2a3436294ac6cb4bb6ced1df1` |
| `lib/x86_64/libraylib.so` | `ffea4c84b55d2cea2c466acc720dc2adb05bf5ce7be49eaa8a98448894b267aa` |

已按授权清理旧 raylib 备份、旧对照库和旧对照 APK，保留日志与截图；未清理其他模块、
其他会话缓存或其模拟器数据，也未重写 Git 历史。

仅升级 APK 不会自动替换用户已安装的库。本次 `PackageManagerImpl.checkForUpdates()` 已增加
同版本 revision 比较，并使更新公告后再次安装使用最新详情/下载信息，而非旧缓存。
刷新资源包市场，检查更新并安装 raylib，然后清理构建目录重编译；手动放进项目的旧 `.so`
必须移除或替换，宿主不会删项目源码。界面上游版本仍可能显示为 `6.0 → 6.0`，以 revision 为准。
旧归档从当前树删除而非重写 Git 历史；不要把旧下载缓存或旧导出的 APK 作为新修复包验收。

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
   的 `packageRevision: 3` 和对应 ABI 的库文件校验和；仅看到版本 `6.0` 不够。
   Release 包通常不能通过 `adb run-as` 读取私有目录，应在 App 自身终端/同 UID 环境检查，
   不要为排查清空整个 App 数据。
3. 执行“清理构建目录 / 清理并重新配置”再编译；检查链接和运行日志，避免项目目录内
   的旧 `libraylib.so` 遮蔽新安装包。宿主优先搜索主库目录和项目附加运行库目录。
4. 验收可见动画、触摸、系统手势 Back、快速连点与连续至少 20 次运行，再测切后台/恢复。仅有循环计数、
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

1. 以 [0.18.39 清单第 7 节](release-0.18.39-manual-checklist.md#7-本轮自动化与制品记录)
   区分已完成的模拟器端到端测试、独立夹具和未覆盖项。本轮用户选择模拟器，不要求真机配合；
   ARM64 GPU、厂商系统、低内存和 16 KB 页设备仍不能由 x86_64 模拟器推断通过。
2. 已实测全新安装、Registry 下载 r3、设备内编译、重复运行、Home 恢复和前台长运行。
   升级安装、旧包替换拒绝、APK 导出、音频/控制器等分别记录，不以这一路通过代替其他能力。
3. API 36 横竖屏切换不再黑屏，但 raylib 画面横屏存在拉伸；后续仍需完善引擎的 resize/比例适配，
   不能把“仍在出帧”当成正确的横屏布局。游戏总运行时长与明确退出的 2 秒兜底保持分离。

具体步骤、预期和待测状态见 [0.18.39 手测清单](release-0.18.39-manual-checklist.md)。
本次不调整 PRoot、X11 或编辑器内核，也不兼容旧 raylib；恢复旧包会重新引入已知黑屏。
