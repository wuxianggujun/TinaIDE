# core:linux-desktop — X11 Desktop GUI Runtime

为 PRoot Linux 环境提供图形桌面支持，基于 [termux-x11](https://github.com/termux/termux-x11) 的 Android 原生 X server（libXlorie.so）。

## 功能

- **X11 服务器**：在 Android SurfaceView 中运行原生 X.Org 服务器
- **输入映射**：触摸、鼠标、键盘输入转换为 X11 事件
- **生命周期集成**：与 PRoot 环境启停联动
- **显示管理**：分辨率适配、旋转、多显示器支持（未来）

## 架构

```
主进程                                  :x11 独立进程
─────────────────────────────           ─────────────────────────────
LinuxDesktopService                     CmdEntryPoint (JNI start)
  ├─ 状态机 X11ServerState                    ↓
  ├─ X11SocketLayout (TMPDIR 契约)       libXlorie.so
  └─ X11ServerLauncher ──启动──→          ↓
       (接口，实现待补)                   libX11 / libxcb / libpixman
                                              ↓
                                        LorieView (SurfaceView 渲染 + 输入)
```

### 为什么 X server 必须独立进程

`lorie/src/main/cpp/lorie/cmdentrypoint.cpp` 把 libc 的 `exit()` / `abort()` 覆盖成
`_exit()`，而 X server 主线程执行 `exit(dix_main(argc, argv, envp))`：

```c
void abort(void) { _exit(134); }
void exit(int code) { _exit(code); }
```

X server 任何一次 `FatalError()` 或正常退出都会**直接终止整个进程**，不走 JVM 关闭流程、
不抛异常、不可捕获。若 in-process 启动，X server 崩溃会连带杀死 TinaIDE 主进程。
上游用 `app_process` 起独立进程正是这个原因。`X11ServerLauncher` 接口固化了这条边界。

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

为在 Windows 上完成构建，对 vendored 上游有三处本地改动，见 `CHANGELOG.md` 的
`Unreleased` 小节；升级 termux-x11 时需人工复核。

### Linux / macOS

直接使用系统包管理器安装 `python3 bison patch gcc`，NDK 29 由 Android SDK Manager 安装即可。
macOS 与 Windows 一样是大小写不敏感文件系统，同样需要 `xkbcomp.cmake` 里的 `xlocale.h` shim。

## 使用

> **当前不可用**：`X11ServerLauncher` 只有接口与契约，还没有实现类。不传 launcher 时
> `startX11Server()` 会**明确失败**并落到 `X11ServerState.Error`，不会伪装成 `Running`
> ——否则 guest 会拿到一个指向不存在的 X server 的 `DISPLAY`，只表现为一堆难以诊断的
> "cannot open display"。
>
> 注意 `startX11Server()` 不接受 `SurfaceView`：渲染 Surface 属于 X server 进程里的
> `LorieView`，主进程无从提供。

```kotlin
val desktopService = LinuxDesktopService.create(
    serverLauncher = x11ServerLauncher,
    socketLayoutProvider = { X11SocketLayout.forRootfs(rootfsPath) },
)

desktopService.startX11Server(display = ":0")
    .onFailure { error -> /* rootfs 未装 / xkb 缺失 / launcher 缺失 */ }

// 仅在 serverState 为 Running 时才返回非空，可直接并入 PRoot 环境变量
val env = desktopService.getX11EnvironmentVariables()  // {"DISPLAY": ":0"}
```

不导出 `XAUTHORITY`：X server 以 `-ac` 启动，socket 位于应用私有 rootfs 内，隔离由
Android 沙箱而非 X 认证负责。反过来说 `$TMPDIR` **绝不能**指向 `/sdcard` 或
`/data/local/tmp` 等世界可读位置。

## 未来工作

- [ ] `X11ServerLauncher` 实现：`:x11` 进程宿主 + `CmdEntryPoint` / `LorieView` 接线。
      需处理 `LorieView` 对 `com.termux.x11.MainActivity` 的硬依赖——Java 侧有
      `findActivity()` / `getPrefs()` / `MainActivity.handler`，native 侧
      `activity.cpp` 还会 `FindClassOrDie("com/termux/x11/MainActivity")` 并按名字读
      `LorieView.activity` 字段，所以不能简单把 `LorieView` 丢进 Compose。
- [ ] `PRootManager` 把 `<rootfs>/tmp` 绑到 guest `/tmp`（当前绑到 `/dev/shm`）
- [ ] 桌面安装与会话入口 UI（`UbuntuDesktopProvisioner` 目前仅有 API）
- [ ] Wayland 协议支持（termux-x11 上游已有实验性实现）
- [ ] 硬件加速渲染（OpenGL ES passthrough）
- [ ] 剪贴板同步（X11 ↔ Android）
- [ ] 虚拟键盘集成（X11 IME）
- [ ] 多显示器 / 分屏支持
