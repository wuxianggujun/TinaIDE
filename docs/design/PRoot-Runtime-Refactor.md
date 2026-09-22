# PRoot 运行时重构说明

> 状态：历史参考（迁移记录）
> 最后人工核验：2026-09-09
>
> 本文记录 PRoot 收缩为 Linux 运行时层那一轮重构的边界与删除清单，用于追溯「为什么这些类不在了」。
> 判断当前 PRoot 行为请以 [PRoot 与自研 Linux 发行版运行时设计说明](PRoot-Feature-Analysis.md)
> 和 `core/proot`、`core/linux-desktop` 源码为准。本次核验已就地修正与当前代码矛盾的描述。

## 目标

- 将 `PRoot` 明确收缩为 **Linux 运行时层**。
- 删除已废弃的编译兼容代码，不再保留空实现与伪兼容接口。
- 保留真实仍在使用的能力：`rootfs` 安装、命令执行、交互进程、路径映射。

## 新边界

### `PRootEnvironment`

现在只负责：

- `isInstalled()` / `needsUpdate()` / `isReady()`
- `initialize()` / `clean()`
- `execute()` / `executeWithOutput()` / `startInteractive()`
- `executeShell()` / `executeShellWithEnv()`
- `toGuestPath()` / `buildPRootCommandLine()` / `buildExecEnvironment()`

不再负责：

- 编译器实例创建
- PRoot 内工具链安装
- 调试器空安装逻辑
- 旧的 `ICompilerEnvironment` 兼容接口

### `ToolchainPathResolver`

现在仅根据 rootfs 真实文件布局解析工具路径：

- 优先使用 `ToolchainBinaryLocator` 动态探测
- 找不到时回退到标准路径（如 `/usr/bin/clangd`）

已移除依赖：

- `ToolchainManifestStore`
- `SymlinkConfigStore`
- `SymlinkManager`

## 已删除遗留代码

- `ICompilerEnvironment`
- `PRootCompiler`
- `ToolchainInstallResult`
- `ToolchainManifest*`
- `toolchain/Symlink*`
- `LlvmVersions`

## 调用链调整

### Guest 工具链安装

现在 `PRoot` 编译链不再依赖历史兼容层，而是通过新的 guest 安装服务显式安装：

- `PRootGuestToolchainInstaller`
- 按 rootfs 实际包管理器执行索引更新与安装：`RootfsPackageManager` 枚举为 `APK / APT / PACMAN / DNF / UNKNOWN`，命令由 `GuestSystemPackageManager` 统一拼装（重构当时只支持 Alpine 的 `apk`）。注意枚举保留 `APK` 不等于仍支持 Alpine 发行版——`0.18.29` 起唯一维护的 rootfs 是 Ubuntu 24.04（`APT`）
- 根据 `ToolchainConfig` 解析需要的 Linux 工具，并对每个工具做候选包名探测
- 安装结果通过 `ToolchainPathResolver` 动态发现

这意味着：

- `proot` 运行模式使用的是 **rootfs 内真实存在的 clang/cmake/make/clangd/lldb**
- 如果没有安装 guest 工具链，`proot` 编译不会再“假装可用”

### 编译前检查

重构时把 `CompileEnvironmentChecker` 改为直接探测可执行文件（`<compiler> --version` / `lldb --version`），
不再尝试自动安装已废弃的 PRoot 工具链。

当前代码里 `CompileEnvironmentChecker` 已不存在：编译流水线的前置校验收缩为
`core/compile` 的 `EnvironmentValidator`（只做项目根目录、buildDir 这类"便宜校验"），
更细的工具链探测下沉到各 `BuildStrategy.execute` 内部。

### LSP

`PRootClangdConnectionProvider` 不再读取 manifest，统一使用 `ToolchainPathResolver`。

### 调试

`DebugSessionService` 启动前直接检查 `lldb` 是否存在，避免依赖旧的
`isDebuggerAvailable()` 伪能力。

## 设置联动

当 Linux 环境插件关闭时：

- 编译 / LSP 设置中的 `proot` 选项不再展示
- 存储页的 Linux 系统分区隐藏
- 终端后端中的 `PROOT` 选项隐藏，并自动回退到 `HOST`
- 关于页中的 PRoot 日志入口隐藏
- 插件从关闭切换为开启时，如果 rootfs 未安装，会弹出安装引导

## 构建资源策略

重构阶段曾临时收紧 Gradle 并发以降低资源争抢。当前仓库已经恢复常规本地构建配置，事实源以根目录 `gradle.properties` 与 `build-logic/gradle.properties` 为准：

- 根目录 `gradle.properties`：`org.gradle.parallel=true`、`org.gradle.workers.max=6`
- `build-logic/gradle.properties`：`org.gradle.parallel=true`

如果后续再次遇到 PRoot 资产构建或 native 包构建资源争抢，应优先在具体维护脚本中限流，不要把全仓库 Gradle 默认构建改回单 worker。
