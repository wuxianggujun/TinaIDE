# PRoot 与自研 Linux 发行版运行时设计说明

> 更新日期：2026-09-09
> 最后人工核验：2026-09-09

本文替代旧 PRoot 阶段性分析稿，只记录当前仍有效的运行时边界。
历史 rootfs 下载器、历史镜像源设置、历史发行版管理器和编译包装均不再作为当前入口。

## 当前定位

- PRoot 提供可选 Linux shell、guest 命令执行、插件/调试扩展，以及 X11 桌面会话的 guest 侧宿主命令行。
- Linux 环境是插件门控能力：由 `PluginCapabilities.LINUX_ENVIRONMENT` 控制。插件关闭时设置页不暴露 `proot` 运行模式。
- 默认编译与运行链路走 Android sysroot 与 native tina-toolchain，不依赖 PRoot。
- Linux rootfs 安装统一走 :core:linux-distro 的官方 manifest 与 `SelfHostedLinuxDistroRuntime`。
- 内置 manifest（`core/linux-distro/src/main/assets/linux-distro/manifest.json`）当前只收录 Ubuntu 24.04 base（APT）；`RootfsPackageManager` 侧同时支持 APK / APT / DNF，具体取决于已安装 rootfs。
- 设置页和工作区安装页只暴露自研 Linux 发行版管理器，不再提供旧镜像源或旧 rootfs 导入入口。

## 模块边界

- :core:linux-distro：维护 manifest schema、官方 rootfs 元数据、下载、校验、解包和安装状态机。
- :core:proot：负责 profile 注册、PRoot 启动、guest 命令执行、rootfs 健康检查、安装进度桥接；并通过 `PRootDesktopHostProcessPlanner` 把 guest 桌面命令包成 host 命令行。
- :core:linux-desktop：承载 X11 桌面链路（`X11ServerService`、`X11ServerLauncher`、`UbuntuDesktopProvisioner`、`LinuxDesktopSessionSupervisor` 等）。X server 与桌面渲染 UI 都跑在独立 `:x11` 进程，与主进程隔离；依赖方向是 `:core:proot` → `:core:linux-desktop`，反向由 `LinuxDesktopHostProcessPlanner` 接口回调打断。
- :feature:settings：展示发行版安装入口、profile 切换、重命名、删除与状态展示。
- :feature:workspace：在显式安装 Linux 环境时串联 Linux distro runtime、rootfs 与 guest toolchain。

## 安装流程

1. 用户显式请求安装 Linux 环境。
2. RootfsDistroRuntime 从 :core:linux-distro assets 读取可安装发行版。
3. SelfHostedLinuxDistroRuntime 调用 LinuxDistroManager 下载并校验 rootfs。
4. LinuxDistroRootfsProfileMapper 将安装结果写入 RootfsProfileStore。
5. PRootBootstrap 将进度同步给安装页。
6. PRootEnvironment 基于活动 profile 创建 PRootManager。

## 已删除边界

- 旧脚本模块与旧运行时类。
- 旧 runtime mode 配置和开发者灰度开关。
- 历史 rootfs 下载器。
- 旧镜像源设置页和旧镜像组件。
- PRoot 内编译工具链 manifest / symlink 兼容包装。

## 当前验证重点

- :core:linux-distro:compileDebugKotlin
- :core:proot:compileDebugKotlin
- :core:linux-desktop:compileDebugKotlin
- :feature:settings:compileDebugKotlin
- :feature:workspace:compileDebugKotlin
- :core:proot:compileDebugUnitTestKotlin
- :core:linux-desktop:testDebugUnitTest
- :feature:settings:compileDebugUnitTestKotlin

## 不再恢复

- 不恢复旧 rootfs 下载链路。
- 不恢复旧镜像源设置页。
- 不恢复旧运行时模式开关。
- 不把 Linux 环境作为默认首次启动必装项。
- 不在 PRoot 内重新承担主编译链职责。
