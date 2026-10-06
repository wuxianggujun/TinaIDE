# core:linux-desktop — X11 Desktop GUI Runtime

为 PRoot Linux 环境提供图形桌面支持，基于 [termux-x11](https://github.com/termux/termux-x11) 的 Android 原生 X server（libXlorie.so）。

## 功能

- **X11 服务器**：在 Android SurfaceView 中运行原生 X.Org 服务器
- **输入映射**：触摸、鼠标、键盘输入转换为 X11 事件
- **生命周期集成**：与 PRoot 环境启停联动
- **显示管理**：开机分辨率按真机屏幕算（`resolveX11DisplayConfig`），旋转与多显示器（未来）

## 架构

```
主进程                                    :x11 进程
────────────────────────────────         ──────────────────────────────────
UbuntuLinuxDesktopCoordinator            X11ServerService (前台服务)
  ├─ UbuntuDesktopProvisioner              ├─ CmdEntryPoint.startEmbedded()
  │    (guest apt 安装/校验)                │     ↓
  ├─ UbuntuDesktopSessionPreparer          │  libXlorie.so (完整 X.Org 栈)
  │    (XDG_RUNTIME_DIR / machine-id)      │     ↓
  ├─ LinuxDesktopService                   │  监听 <rootfs>/tmp/.X11-unix/X<n>
  │    ├─ 状态机 X11ServerState             │
  │    └─ X11SocketLayout (TMPDIR 契约)     └─ LinuxDesktopSessionSupervisor
  ├─ UbuntuDesktopSessionPlanner           │     └─ proot + dbus-run-session
  │    (算 guest 命令与 DISPLAY 环境)        │          + startxfce4
  ├─ LinuxDesktopHostProcessPlanner        │
  │    (prootModule 注入，包成 host argv)   │
  └─ X11ServerProcessLauncher ──AIDL──→ ───┘
       (bind + 等 socket + 递交会话计划)
                                           MainActivity + LorieView
                                             (SurfaceView 渲染 + 输入)
                                                  ↑
IAppNavigator.openLinuxDesktop() ─────────────────┘
```

X server、guest XFCE 会话与桌面窗口都在 `:x11`，但生命周期分离：窗口关掉后
X server 与会话继续跑，下次打开窗口重新连上即可。

### 为什么 guest 会话也必须在 `:x11` spawn

`init-proot.sh` 带 `--kill-on-exit`，proot 进程树的存亡跟着 **spawn 它的那个进程**。
早期实现由主进程 `startInteractive()` 拉起 XFCE，于是主进程被系统回收（或用户划掉
IDE）就会连带杀死桌面——与"X server 常驻、窗口可开可关"的拓扑直接矛盾。

现在主进程只负责**算**：探测组件、准备 `XDG_RUNTIME_DIR` 与 `machine-id`、
拿到 `Running` 的 DISPLAY、用 `PRootManager` 组装出完整 argv 与环境
（`LinuxDesktopHostProcessPlan`）；真正的 `exec` 与看护重启都发生在 `X11ServerService`。
命令行必须由主进程组装，因为只有 `:core:proot` 知道 proot 二进制、init 脚本和那一整套
`ROOTFS_PATH` / `LINKER` / `PROOT_*` 变量，而本模块**不能**反向依赖它（会成环），
在 `:x11` 里起 Koin 又会把 database/plugin/editor 一并拖进 X server 进程。

### 为什么 X server 必须独立进程

`lorie/src/main/cpp/lorie/cmdentrypoint.cpp` 把 libc 的 `exit()` / `abort()` 覆盖成
`_exit()`，而 X server 主线程执行 `exit(dix_main(argc, argv, envp))`：

```c
void abort(void) { _exit(134); }
void exit(int code) { _exit(code); }
```

X server 任何一次 `FatalError()` 或正常退出都会**直接终止整个进程**，不走 JVM 关闭流程、
不抛异常、不可捕获。若 in-process 启动，X server 崩溃会连带杀死 TinaIDE 主进程。
上游用 `app_process` 起独立进程正是这个原因。`X11ServerLauncher` 接口固化了这条边界，
`X11ServerProcessLauncher` + `X11ServerService`（`android:process=":x11"`）是其实现。

同样因为退出路径是 `_exit()`，X server **无法在进程内停止**，也无法在同一进程里换 display
重启（`dix_main` 只能跑一次）。`X11ServerService` 因此只记录是否已启动、不提供 stop；
`terminate()` 的实际手段是解绑 + `stopService` 让 `:x11` 进程被回收。

### 为什么保留 lorie 的 `MainActivity` 和 `LorieBroadcastReceiver`

`MainActivity` 的类名写死在 `.so` 里（`activity.cpp` 的
`FindClassOrDie("com/termux/x11/MainActivity")` 与
`GetFieldID(..., "activity", "Lcom/termux/x11/MainActivity;")`），不能替换成 Compose 界面。
沿用它同时白拿了约 3000 行触摸手势、额外按键栏、鼠标辅助键、PiP、剪贴板同步和 IME 处理。

`LorieBroadcastReceiver` 是 Binder 的唯一递送路径：`MainActivity` 没有 `onNewIntent`，
`LorieView.requestConnection()` 敲 127.0.0.1:7892 → native `lorieListenForKnocks` 回调
`CmdEntryPoint.sendBroadcast()` → `ACTION_START` 广播携带 Binder → receiver →
`MainActivity.onReceiveConnection()`。去掉 receiver 等于切断整条链路。

两者在本模块 manifest 里都改成 `exported="false"` + `process=":x11"` 并去掉入口
`intent-filter`；`KeyInterceptor`、`LoriePreferences$Receiver` 和 `WRITE_SECURE_SETTINGS`
则直接移除。

### `$TMPDIR` 是唯一的交汇点

`CmdEntryPoint.start()` 不接受 socket 路径参数，它只读 `$TMPDIR`，并把监听地址
硬编码为 `$TMPDIR/.X11-unix/X<display>`；又用 `dirname($TMPDIR)` 推导 chroot 根
去定位 xkb 数据与字体。

`X11SocketLayout` 因此把布局定为 `<rootfs>/tmp`：host 侧用绝对路径，guest 侧用 `/tmp`，
两边是同一个 inode（guest 的 libX11 同样按 `/tmp/.X11-unix/X<n>` 拼接，PRoot 不会替它
改写路径）。`dirname` 恰好落在 rootfs 根上，于是能直接复用 guest 里 `xkb-data`
装出来的真实数据，无需往 APK 塞一份 xkb 副本。

## 依赖与许可证

- **:termux-x11-lorie**：GPL-3.0-or-later（完整 X.Org 栈编译为单个 .so）
- **:termux-x11-shell-loader-stub**：compileOnly 依赖，提供 Android 隐藏 API 桩

Gradle 路径是扁平的 `:termux-x11-lorie`，不是 `:termux-x11:lorie`：后者会隐式创建
`:termux-x11` 父项目并执行上游根 `build.gradle`，其自带 repository 声明与本仓库的
`FAIL_ON_PROJECT_REPOS` 冲突。

本模块受 GPL-3.0 传染，整个 TinaIDE 已改为 GPL-3.0-or-later。

## 构建要求

### 宿主环境（Windows 开发时）

Native X11 栈构建依赖：

- **NDK 29.0.14206865**（termux-x11 pinned 版本，见 `external/termux-x11/lorie/version.gradle`）
- **CMake 3.22.1+**（Android SDK 自带）
- **Python 3**（X.Org / libepoxy 代码生成）
- **GNU Bison**（XKB 语法解析器生成）
- **GNU patch**（X.Org 上游补丁应用；Git for Windows 自带 `usr/bin/patch.exe`）
- **host C 编译器**（生成 `ks_tables.h` 的 `makekeys`；MSYS2 MinGW `gcc.exe` 已验证可用）

Windows 上已验证可直接构建，无需 WSL。构建命令：

```bash
export TEMP=/c/gradle-tmp TMP=/c/gradle-tmp
./gradlew :termux-x11-lorie:assembleDebug --no-daemon --console=plain
```

`TEMP` / `TMP` 是必需的：JDK 17 在 Windows 上的 AF_UNIX 临时目录问题会让 Gradle 报
`Unable to establish loopback connection`。只在 `gradle.properties` 里加
`-Djdk.net.unixdomain.tmpdir` 不够，launcher JVM 仍会失败。

CMake 会自动探测上述 host 工具；路径不同时用 Gradle property 覆盖，**不要**把
Cygwin/MSYS 目录提到整个 shell PATH 最前面（那会让 `gradlew` 把 JDK 解析成
`/cygdrive/...` 从而找不到 `java`）：

```bash
./gradlew :termux-x11-lorie:assembleDebug \
  -Ptina.termuxX11.python=D:/Programs/Python/Python313/python.exe \
  -Ptina.termuxX11.bison=D:/Programs/msys64/usr/bin/bison.exe \
  -Ptina.termuxX11.hostCompiler=D:/Programs/msys64/mingw64/bin/gcc.exe
```

`tina.termuxX11.hostToolPath` 可单独指定 host 工具运行期需要的 DLL 目录，默认取
host 编译器所在目录（MinGW 的 `cc1.exe` 需要它才能加载 `libmpfr-6.dll`）。

对 vendored 上游共有五处本地改动（三处为 Windows 构建，两处为安全与 APK 内启动），
逐条说明见 `CHANGELOG.md` 的 `Unreleased` 小节；升级 termux-x11 时需人工复核：

1. `cpp/CMakeLists.txt`：`target_apply_patch` 直接调 `patch.exe`，不走 `bash -c`
2. `cpp/generate-ks-tables.cmake`（新增）：`ks_tables.h` 生成拆成独立 CMake 脚本
3. `cpp/recipes/xkbcomp.cmake`：`xlocale.h` shim，绕开大小写不敏感文件系统的头文件撞名
4. `cpp/lorie/cmdentrypoint.cpp`：敲门端口 7892 从 `INADDR_ANY` 改绑 `INADDR_LOOPBACK`
5. `java/com/termux/x11/CmdEntryPoint.java`：新增 `startEmbedded()`，内含
   `System.loadLibrary("Xlorie")`（`JNI_OnLoad` 是本类 native 方法的唯一注册者）

### Linux / macOS

直接使用系统包管理器安装 `python3 bison patch gcc`，NDK 29 由 Android SDK Manager 安装即可。
macOS 与 Windows 一样是大小写不敏感文件系统，同样需要 `xkbcomp.cmake` 里的 `xlocale.h` shim。

## 使用

> **真机上尚未验证**：整条链路的代码已完整（Service 宿主、AIDL、Koin 装配、设置页入口），
> 但还没有在设备上实际跑出 XFCE 桌面。别把"代码路径完整"当成"功能可用"。
>
> `startX11Server()` 不接受 `SurfaceView`：渲染 Surface 属于 `:x11` 进程里的 `LorieView`，
> 主进程无从提供。不传 launcher、rootfs 未安装、xkb 缺失、display 格式非法都会**明确失败**
> 并落到 `X11ServerState.Error`，不会伪装成 `Running`——否则 guest 只会拿到一个指向不存在的
> X server 的 `DISPLAY`，表现为一堆难以诊断的 "cannot open display"。

日常调用走 coordinator，它保证 DISPLAY 只在 X server 真的 `Running` 之后才注入 guest：

```kotlin
val coordinator: UbuntuLinuxDesktopCoordinator = koinInject()

// 环境可用 → 桌面软件包齐备 → 启动 X server → 拿到 Running 的 DISPLAY
// → 准备 runtime dir / machine-id → 组装 host argv → 交给 :x11 spawn XFCE
coordinator.startSession()
    .onSuccess { navigator.openLinuxDesktop(context) }
    .onFailure { error -> /* 环境未启用 / 组件未装 / X server 启动失败 */ }

// 关窗口不会结束会话；真正停止桌面要显式调用（先停会话，再停 X server）
coordinator.stopSession()

// 会话状态源在 :x11，每次都跨进程问，不缓存
coordinator.isSessionActive()
coordinator.sessionPhase()   // RUNNING / RESTARTING / FAILED / ...
```

底层两层可以单独使用：

```kotlin
val desktopService = LinuxDesktopService.create(
    serverLauncher = X11ServerProcessLauncher(context),
    socketLayoutProvider = { X11SocketLayout.forRootfs(rootfsPath) },
)

desktopService.startX11Server(display = ":0")

// 仅在 serverState 为 Running 时才返回非空，可直接并入 PRoot 环境变量
val env = desktopService.getX11EnvironmentVariables()  // {"DISPLAY": ":0"}
```

## Koin 装配

```
linuxDesktopModule   X11ServerLauncher / LinuxDesktopService / UbuntuLinuxDesktopCoordinator
prootModule          X11SocketLayoutProvider          （当前已安装的 Ubuntu profile 路径）
                     LinuxDesktopHostProcessPlanner   （把 guest 命令包成 host argv+env）
appModule            IAppNavigator.openLinuxDesktop   （启动 MainActivity）
```

socket 布局与 host 命令行都由 `prootModule` 注入而非本模块自取：本模块不能依赖
`:core:proot`（`:core:proot` 已经 `implementation` 本模块，反向依赖会成环）。两个
provider 缺失时都退化成 `null`，启动会明确失败而不是静默降级——尤其是 host 命令行，
硬造一条只会让 `:x11` exec 出一个立刻退出的进程，表面上像"桌面启动了又消失"。

不导出 `XAUTHORITY`：X server 以 `-ac` 启动，socket 位于应用私有 rootfs 内，隔离由
Android 沙箱而非 X 认证负责。反过来说 `$TMPDIR` **绝不能**指向 `/sdcard` 或
`/data/local/tmp` 等世界可读位置。

## 开机分辨率

`resolveX11DisplayConfig(context)` 按 `maximumWindowMetrics`（API 30+）或
`displayMetrics`（API 28/29）取**整块屏幕**尺寸，长边当宽做横屏归一化，并把尺寸
clamp 到 640..7680、DPI clamp 到 72..640。

为什么这件事必须做对：guest XFCE 现在先于桌面窗口启动，面板和桌面图标会按开机时
拿到的 root window 尺寸布一次版。之后 `LorieView.sendWindowChange()` 会把真实几何
推给 X server，但那已经是"先错后纠"——开机值离真机太远时，第一次打开窗口就能看到
面板宽度不对。`X11DisplayConfig.default()` 的 1920x1080 只是拿不到屏幕信息时的兜底。

取整屏而不是当前应用窗口：桌面窗口是全屏 Activity，分屏下的 IDE 窗口尺寸不代表
桌面将来会拿到多大。

## 仍未验证

1. 真机 XFCE 会话没跑通过——以上都是代码路径。
2. `CmdEntryPoint` 静态初始化里的 `Looper.prepareMainLooper()` 在 Service 进程中的行为。
3. Android 15+ 前台服务启动限制对本路径的影响。
4. 关闭桌面窗口后再打开时，敲门 → 广播 → Activity 的重连路径。

## 未来工作

- [ ] 真机验证 XFCE 会话、输入映射与窗口关闭/重开
- [ ] 桌面环境可选项：i3wm / Fluxbox
- [ ] 硬件加速渲染（OpenGL ES passthrough，termux-x11 上游已有实验性实现）
- [ ] 音频：**目前完全没有实现**。内置 lorie 只搬像素和输入，`lorie/src/main/cpp` 里
      没有任何 AAudio / OpenSLES / PulseAudio 通道，宿主也没有 PulseAudio daemon。
      `LinuxDesktopEndpoint.audioServer` 是留好的入口（一条环境变量直通），但当前没有
      任何调用方会填它；`pulseaudio-utils` 只是装了 `pactl` 这类客户端工具，不等于有声音。
      真要做，得先在宿主起一个音频端点（PulseAudio over TCP 或自写 AAudio sink）。
- [ ] Wayland 协议支持（termux-x11 上游已有）
- [ ] 多显示器 / 分屏支持

剪贴板同步与 IME 处理已由沿用的 `MainActivity` 提供，不在本清单内。
