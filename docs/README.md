# TinaIDE 文档中心

> 最后人工核验：2026-07-11

这里汇总 TinaIDE 当前仍然有效的项目文档，并标出应该优先回看的源码入口。

## 优先阅读

- [快速开始](快速开始.md)：构建 APK、首次启动、默认运行资产与常见问题
- [架构概览](架构概览.md)：启动入口、模块分层、编辑器语言服务分流
- [模块功能说明](模块功能说明.md)：当前 Gradle 模块、外部本地模块与复合构建职责
- [开发指南](开发指南.md)：本地开发、验证命令、提交与协作约束
- [文档状态与生命周期](documentation-status.md)：文档可信层级、历史参考边界与后续清理规则
- [项目 README](../README.md)：项目定位、功能概览、仓库结构
- [English README](../README_EN.md)：与中文首页同步维护的英文项目入口

## 当前事实源

当文档和代码冲突时，以下文件优先：

- 模块清单与 included builds：`settings.gradle.kts`
- App 构建、ABI flavor、工具链校验任务：`app/build.gradle.kts`
- 首页与主编辑器入口：`app/src/main/java/com/wuxianggujun/tinaide/ui/MainPortalActivity.kt`、`app/src/main/java/com/wuxianggujun/tinaide/MainActivity.kt`
- 本地构建脚本：`tools/build-apk.ps1`
- 首次启动与依赖安装：`app/src/main/java/com/wuxianggujun/tinaide/startup/StartupFlowManager.kt`
- 依赖安装页状态机：`feature/workspace/src/main/java/com/wuxianggujun/tinaide/ui/workspace/DependencyInstallViewModel.kt`
- Native 工具链与 sysroot：`core/ndk/src/main/java/com/wuxianggujun/tinaide/core/ndk/AndroidNativeToolchainManager.kt`、`core/ndk/src/main/java/com/wuxianggujun/tinaide/core/ndk/AndroidSysrootManager.kt`
- 编译与运行主流程：`core/compile/src/main/java/com/wuxianggujun/tinaide/core/compile/CompileProjectUseCase.kt`
- 编辑器语言服务分流：`app/src/main/java/com/wuxianggujun/tinaide/ui/compose/state/editor/LspEditorManager.kt`
- 内建 CMake / Make 语言服务：`app/src/main/java/com/wuxianggujun/tinaide/ui/compose/state/editor/BuiltinLanguageServiceSession.kt`、`app/src/main/java/com/wuxianggujun/tinaide/ui/compose/state/editor/CMakeLanguageServiceSession.kt`、`app/src/main/java/com/wuxianggujun/tinaide/ui/compose/state/editor/MakeLanguageServiceSession.kt`
- LSP 会话与连接提供者：`core/lsp/src/main/java/com/wuxianggujun/tinaide/core/lsp/LspClientSession.kt`
- 插件 LSP：`core/plugin/src/main/java/com/wuxianggujun/tinaide/plugin/lsp/LspPluginManager.kt`
- RikkaHub 入口：`app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/DrawerContent.kt`、`app/src/main/java/com/wuxianggujun/tinaide/settings/SettingsActivity.kt`、`external/rikkahub/embedded`
- 帮助文档入口：`feature/help/src/main/java/com/wuxianggujun/tinaide/core/help/HelpRepository.kt`、`feature/help/src/main/assets/help/*.md`、`feature/help/src/main/assets/help/en/*.md`
- PRoot / Linux 环境：`core/proot/src/main/java/com/wuxianggujun/tinaide/core/proot/PRootBootstrap.kt`、`core/proot/src/main/java/com/wuxianggujun/tinaide/core/proot/LinuxDistroCatalogRepository.kt`、`core/proot/src/main/java/com/wuxianggujun/tinaide/core/proot/RemoteLinuxDistroManifestSource.kt`、`core/linux-distro/src/main/assets/linux-distro/manifest.json`

## 文档导航

### 入门

- [快速开始](快速开始.md)
- [开发指南](开发指南.md)
- [国际化规范](i18n.md)
- [项目约定](project-conventions.md)

### 架构与设计

- [架构概览](架构概览.md)
- [模块功能说明](模块功能说明.md)
- [文档状态与生命周期](documentation-status.md)
- [设计文档索引](design/README.md)

### 功能与实现

- [Android-hosted LLVM 工具链对比与风险分析](android-hosted-llvm-toolchain-analysis.md)
- [Clang Android 执行权限修复](clang-android-exec-fix.md)
- [Toolchain 构建与同步指南](toolchain-build-guide.md)
- [ProGuard / R8 规则参考](proguard-rules-reference.md)
- [自研 Linux 发行版运行时](linux-distro-self-hosted-runtime.md)
- [游戏引擎插件图形运行（SDL / NativeActivity）](game-engine-plugin-sdl.md)

### 使用指南

- [LSP 调试指南](guides/LSP-Debug-Guide.md)
- [远程 LSP 指南](guides/Remote-LSP-Guide.md)
- [PC LSP 代理配置](guides/PC-LSP-Proxy-Setup-Guide.md)
- [MT Data Files Provider](guides/MT-Data-Files-Provider.md)
- [文件预览指南](guides/File-Viewer-Guide.md)
- [Hex Viewer 设计说明](guides/Hex-Viewer-Design.md)
- [分支管理指南](guides/Branch-Management-Guide.md)

### 测试与排障

- [测试文档索引](testing/README.md)
- [故障排查目录](troubleshooting)
- [LSP 明文通信错误](troubleshooting/LSP-CLEARTEXT-ERROR.md)

### 插件与规划

- [插件文档索引](plugins/README.md)
- [插件 API 契约](plugin-api-contract.md)
- [GitHub Registry](registry/GitHub-Registry.md)
- [规划文档索引](planning/README.md)

## 当前文档口径

为了避免继续沿用旧叙事，先明确以下口径：

- 默认编译 / 运行链路依赖的是 `Android sysroot + native tina-toolchain`，不是 PRoot。
- PRoot 是可选 Linux 环境，主要服务终端、Linux 工具和插件 / 调试扩展能力。
- 编辑器语言服务不是单一路径：C/C++ 走 `clangd`，CMake / Make 走内建语言服务，其他语言可走插件 LSP。
- App 首次启动默认只安装内置运行资产；只有显式进入 Linux 环境相关流程时，才会通过自研 Linux 发行版管理器安装 rootfs 与 guest toolchain。
- 设计、规划、Docker 与工具脚本文档的可信层级，以 [文档状态与生命周期](documentation-status.md) 为准。

## 说明

- 遇到运行时、构建或模块边界问题，优先回到“当前事实源”校对。
- 版本变更请以 [CHANGELOG.md](../CHANGELOG.md) 为准。
