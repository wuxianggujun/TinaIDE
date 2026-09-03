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
    libXlorie.so (from :termux-x11:lorie)
         ↓
    libX11.so, libxcb.so, libpixman.so, etc.
```

## 依赖与许可证

- **termux-x11/lorie**：GPL-3.0-or-later（完整 X.Org 栈编译为单个 .so）
- **:termux-x11:shell-loader-stub**：compileOnly 依赖，提供 Android 隐藏 API 桩

本模块受 GPL-3.0 传染，整个 TinaIDE 已改为 GPL-3.0-or-later。

## 构建要求

### 宿主环境（Windows 开发时）

Native X11 栈构建依赖：

- **NDK 29.0.14206865**（termux-x11 pinned 版本，见 `external/termux-x11/lorie/version.gradle`）
- **CMake 3.22.1+**（Android SDK 自带）
- **Python 3**（Cygwin 或 WSL，用于 X.Org 构建脚本）
- **Bison**（Cygwin 或 WSL，用于 XKB 语法解析器生成）
- **GNU patch**（Cygwin 或 WSL，用于 X.Org 上游补丁应用）

**已知问题**：Windows 环境下 CMake 的 `execute_process(COMMAND "bash" ...)` 对 Cygwin 路径处理有问题，
可能导致 patch 步骤失败。建议：

1. 使用 WSL 2 Ubuntu 进行首次构建，产物复制回 Windows 项目
2. 或在 Linux / macOS 环境构建后提交 `.cxx/` 产物（不推荐，体积大）
3. 或使用 Android Studio 的内置 NDK 构建（AGP 可能有更好的路径处理）

### Linux / macOS

直接使用系统包管理器安装 `python3 bison patch`，NDK 29 由 Android SDK Manager 安装即可。

## 使用

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
