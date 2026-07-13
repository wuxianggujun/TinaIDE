# 自研 Linux 发行版运行时

> 最后人工核验：2026-07-10

本文记录 TinaIDE 当前 Linux rootfs 管理入口。当前实现已经收敛到
`:core:linux-distro` + `:core:proot`，不再保留旧脚本模块、灰度开关或兼容分支。

## 当前结论

- `:core:linux-distro` 是发行版 manifest、镜像规则、下载、校验、解包和注册的事实源。
- `SelfHostedLinuxDistroRuntime` 是 `core:proot` 的安装运行时；`RootfsDistroRuntime` 是设置页中性门面。
- 默认编译宿主仍是 native tina-toolchain + Android sysroot；PRoot/Linux distro 只在用户显式需要 Linux 环境时启用。
- 启动和普通列表读取不隐式访问网络；远程 manifest 刷新由明确的远程加载入口触发。
- 远程 manifest、缓存和内置 asset 形成回落链路，Registry 或网络异常不会让发行版列表失去兜底数据。
- `RootfsSourceType` 只保留 `LINUX_DISTRO`，旧来源不再兼容迁移。

## 模块边界

### `:core:linux-distro`

- `linux-distro/manifest.json`：Android assets 中的内置官方 rootfs 清单。
- `LinuxDistroManifest`：发行版、artifact 与清单级镜像替换规则的数据模型。
- `LinuxDistroManager`：下载、SHA-256 校验、解包和安装状态机。
- `LinuxDistroInstaller`：先尝试 artifact 官方 URL，再按 `mirrors` 规则派生镜像候选。
- `LinuxDistroInstallLayout`：cache、staging、installed-rootfs 布局。

### `:core:proot`

- `LinuxDistroCatalogRepository`：把内置、缓存和远程三种加载意图拆成显式入口。
- `RemoteLinuxDistroManifestSource`：远程多端点读取、6 小时缓存和失败回落。
- `SelfHostedLinuxDistroRuntime`：连接 catalog、manager 与 `RootfsProfileStore`。
- `RootfsDistroRuntime`：设置页发行版列表和安装门面；普通列表使用缓存或内置数据，不主动联网。
- `PRootBootstrap`：工作区安装页和自动引导入口。
- `PRootEnvironment`：`LinuxEnvironment` 实现、rootfs 健康检查与清理入口。

## Manifest 加载策略

当前加载意图必须显式选择：

- `loadBundledCatalog()`：只读内置 asset，适合必须完全离线的路径。
- `loadCachedOrBundledCatalog()`：先读本地缓存，失败后读内置 asset；启动与普通设置页列表使用这一入口。
- `loadRemoteOrCachedCatalog()`：用于明确需要刷新数据的路径，顺序如下：
  1. 6 小时内的新鲜缓存。
  2. TinaIDE Registry 多端点远程 manifest。
  3. 远程失败时的过期缓存。
  4. 内置 asset。

远程路径由 `GitHubRegistryConfig.linuxDistroManifestUrls()` 生成，Registry 相对路径为
`linux-distro/manifest.v1.json`。远程 JSON、schema 或网络异常只记录诊断信息并回落，不中断安装。

## 安装流程

1. 用户在设置页或工作区安装页显式请求 Linux 环境。
2. 安装入口加载远程或缓存 catalog，并创建 `SelfHostedLinuxDistroRuntime`。
3. `LinuxDistroInstaller` 依次尝试官方 URL 与 manifest 中匹配的镜像候选。
4. `LinuxDistroManager` 下载 rootfs、校验 SHA-256，并解包到 `linux-distro/installed-rootfs`。
5. `LinuxDistroRootfsBootstrapper` 进入新 rootfs，补齐 bash、curl、tar、xz、file、ca-certificates 等基础命令。
6. `LinuxDistroRootfsProfileMapper` 写入 `RootfsProfileStore`，并设置为活动 profile。
7. 工作区安装页继续安装可选的 PRoot guest toolchain、Android sysroot 和 native toolchain。

## 运行时自检

- `PRootEnvironment.checkLinuxDistroHealth()` 是当前活动 rootfs 的结构化自检入口。
- `LinuxDistroRootfsHealthChecker` 检查 rootfs 可用性、包管理器、必需/可选基础命令、`uname -m` 和 `/etc/os-release`。
- 可选 `proot` 缺失不会阻塞 `isUsable`；bash、curl、tar、xz、file、update-ca-certificates 等必需命令缺失会标记不可用。

## Manifest 维护

- 官方源数据先通过 `tools/linux-distro/linux-distros.lock.json` 锁定。
- 生成脚本：`tools/linux-distro/generate-linux-distro-manifest.ps1`。
- 内置产物：`core/linux-distro/src/main/assets/linux-distro/manifest.json`。
- 远程产物：TinaIDE Registry 的 `linux-distro/manifest.v1.json`。
- manifest 支持 Alpine Linux 3.23、Ubuntu 24.04 LTS 与清单级 `mirrors` 前缀替换规则。
- 新增发行版或 artifact 时必须记录 URL、架构、版本、大小和 SHA-256；镜像只改变下载来源，不能改变校验结果。
- 刷新官方元数据只允许走发行版官方源，不复制外部脚本项目字段结构。

## 验收标准

- 启动和普通列表路径不触发远程请求；显式刷新可使用远程 manifest。
- 新鲜缓存、远程成功、远程失败后的过期缓存和内置 asset 回落均有测试覆盖。
- 官方 URL 失败时可尝试镜像，取消操作不会继续切换镜像。
- `core:proot` 只依赖当前 Linux distro 与现有运行时模块，不恢复旧脚本运行时。
- `RootfsProfileStore` 只清理 `linux-distro/installed-rootfs` 管理目录。
- 文档始终把 native tina-toolchain + Android sysroot 描述为默认链路，把 PRoot/Linux distro 描述为可选环境。
