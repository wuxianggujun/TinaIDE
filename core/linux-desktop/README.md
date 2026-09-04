# core:linux-desktop — X11 Desktop GUI Runtime

为 PRoot Linux 环境提供图形桌面支持，基于 [termux-x11](https://github.com/termux/termux-x11) 的 Android 原生 X server（libXlorie.so）。

## 功能

- **X11 服务器**：在 Android SurfaceView 中运行原生 X.Org 服务器
- **输入映射**：触摸、鼠标、键盘输入转换为 X11 事件
- **生命周期集成**：与 PRoot 环境启停联动
- **显示管理**：分辨率适配、旋转、多显示器支持（未来）

## 架构

```
:core:linux-desktop (Kotlin API)
    ├─ LinuxDesktopService: 管理 X11 服务器生命周期
    ├─ X11DisplayConfig: 显示配置与环境变量导出
    └─ X11InputBridge: 触摸/键鼠输入 → X11 事件转换
         ↓ JNI
    libXlorie.so (from :termux-x11-lorie)
         ↓
    libX11.so, libxcb.so, libpixman.so, etc.
```

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

> **当前不可用**：`LinuxDesktopServiceImpl` 仍是骨架，`startX11Server()` 只推进状态机，
> 没有任何 JNI 调用。返回 `Running` 不代表 X server 真的启动。下面是目标 API 形态，
> 待 JNI 绑定完成后生效。

```kotlin
// 在 PRoot 环境启动后
val desktopService = get<LinuxDesktopService>()
desktopService.startX11Server(
    display = ":0",
    surfaceView = binding.x11Surface
)

// PRoot 环境内导出环境变量
export DISPLAY=:0
startxfce4  # 或其他桌面环境
```

## 未来工作

- [ ] Wayland 协议支持（termux-x11 上游已有实验性实现）
- [ ] 硬件加速渲染（OpenGL ES passthrough）
- [ ] 剪贴板同步（X11 ↔ Android）
- [ ] 虚拟键盘集成（X11 IME）
- [ ] 多显示器 / 分屏支持
