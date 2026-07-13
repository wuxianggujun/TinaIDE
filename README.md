# TinaIDE

[![爱发电](https://img.shields.io/badge/%E7%88%B1%E5%8F%91%E7%94%B5-%E6%94%AF%E6%8C%81%E5%BC%80%E6%BA%90-946ce6?style=flat-square)](https://ifdian.net/a/wuxianggujun)

> 最后人工核验：2026-07-11
>
> 运行在 Android 设备上的 C/C++ IDE，默认使用 `native tina-toolchain + Android sysroot`；可选提供自研 Linux distro / PRoot 环境。

[English](README_EN.md)

---

TinaIDE 是面向手机和平板的移动端 IDE。当前版本的核心变化是运行时被拆成了两层：

- 默认开发链路：内置 `native tina-toolchain`、`Android sysroot`、native clangd/LSP
- 可选 Linux 环境：基于 `core:linux-distro + PRoot` 的终端与 Linux 工具链路

这意味着：

- 基础编译、运行、clangd 补全不再依赖 PRoot
- PRoot 主要服务终端、Linux 工具、部分插件 / 调试扩展能力

## 特性

- 原生 C/C++ 工具链：内置 `tina-toolchain`，提供 clang / clang++ / clangd 等能力
- Android sysroot：按 ABI 安装与校验运行时必需的头文件和库
- 智能补全：native clangd + LSP，支持诊断、跳转定义、查找引用、悬浮信息
- 自研编辑器：Tina Editor（Compose + Canvas），支持多标签、增量高亮、会话管理
- Tree-sitter 高亮：支持 C/C++/CMake 及扩展语言
- 终端与 Linux 环境：可选 Linux distro / PRoot 终端
- Git 集成：状态、提交、分支、拉取、推送、冲突处理、SSH / HTTPS 凭据
- 文件预览：Markdown、图片、Hex、Diff、大文件文本，JSON 等文本类文件可走文本预览
- 内嵌 RikkaHub：AI 聊天、模型、渠道和 MCP 设置由 RikkaHub 维护
- 插件系统：主题、代码片段、LSP / 菜单扩展

## 插件、包与 Linux distro Registry

插件市场与依赖包市场的发布内容已经独立到公开仓库：

```text
https://github.com/wuxianggujun/TinaIDE-Registry
```

该仓库承载 `plugins/index.v2.json`、`packages/index.v2.json`、
`linux-distro/manifest.v1.json`、单项详情文件、官方插件包、依赖包文件和对应构建脚本。
当前 Android 主干只读取市场 v2 索引，不再回退旧的 `plugins/index.json` /
`packages/index.json`；如需服务旧客户端，应在 Registry 仓库显式生成 v1 兼容产物。
Linux distro manifest 使用独立协议，并按“新鲜缓存 → Registry 多端点 → 过期缓存 →
内置 asset”回落。Android 主仓库只保留客户端、内置兜底资产和文档口径。

## 界面预览

<table>
  <tr>
    <td align="center"><img src="./image/projects.jpg" alt="项目页" width="220"><br>项目页</td>
    <td align="center"><img src="./image/marketplace.jpg" alt="市场" width="220"><br>市场</td>
    <td align="center"><img src="./image/tutorials.jpg" alt="教程" width="220"><br>教程</td>
  </tr>
  <tr>
    <td align="center"><img src="./image/profile.jpg" alt="我的" width="220"><br>我的</td>
    <td align="center"><img src="./image/code-editor.jpg" alt="代码编辑器" width="220"><br>代码编辑器</td>
    <td align="center">更多界面持续更新</td>
  </tr>
</table>

## 快速开始

### 1. 开发环境

- Android Studio 最新稳定版
- JDK 17+
- Git
- PowerShell 7+（推荐）
- Docker Desktop（可选，仅维护者重建运行资产时需要）

### 2. 构建 APK

推荐使用仓库脚本：

```powershell
# 默认构建 arm64 Debug 并安装
pwsh ./tools/build-apk.ps1 -Install

# 模拟器构建（x86_64）
pwsh ./tools/build-apk.ps1 -Abi x86 -Install

# Release 构建
pwsh ./tools/build-apk.ps1 -Variant release

# 所有 ABI 的 Release
pwsh ./tools/build-apk.ps1 -Variant release -AllAbi
```

高级用法可直接调用 Gradle：

```bash
./gradlew :app:assembleArm64Debug
./gradlew :app:installArm64Debug
./gradlew -Ptina.devAbi=x86_64 :app:assembleX86_64Debug
./gradlew -Ptina.devAbi=x86_64 :app:installX86_64Debug
./gradlew :app:assembleDebugAllAbi
./gradlew :app:assembleReleaseAllAbi
```

### 3. 首次启动

首次启动时，应用会校验并安装默认运行资产：

1. `Android sysroot`
2. `native tina-toolchain`

完成后即可创建项目并编译 / 运行。

如需 Linux 终端环境，再从设置或工作区入口安装：

1. `Linux distro rootfs`
2. `PRoot guest toolchain`

详细说明见 [docs/快速开始.md](docs/快速开始.md)。

## 当前运行时模型

### 默认开发链路

用于基础编译、运行、LSP：

- `core:ndk` 管理 native toolchain 与 Android sysroot
- `core:compile` 负责编译策略与运行流程
- `core:lsp` 提供 native / PRoot / remote clangd 连接

### 可选 Linux 环境

用于终端与 Linux 工具：

- `core:linux-distro` 提供发行版 manifest、下载校验与安装描述
- `core:proot` 通过 `SelfHostedLinuxDistroRuntime` 管理 rootfs 安装与 PRoot 生命周期
- `feature:terminal` 提供终端 UI 与会话管理

### 维护者可选资产重建

只有在维护运行资产时才需要：

```powershell
# 重建 proot 运行时 / sysroot
pwsh ./docker/proot-build/build-proot.ps1 -CopyToJniLibs -CopyToAssets
pwsh ./docker/proot-build/build-proot.ps1 -BuildSysroot

# 默认从 external/termux-proot 源码直接编译 PRoot
.\gradlew.bat :app:assembleArm64Debug

# 如需临时使用 Docker 预编译产物
.\gradlew.bat :app:assembleArm64Debug '-Ptina.buildProotFromSource=false'

# 同步 tina-toolchain / sysroot 资产到 app/src/<abi>/assets
pwsh ./tools/sync-tina-toolchain-assets.ps1 -Abi arm64

# 刷新自研 Linux 发行版 manifest（可选，维护发行版元数据时使用）
pwsh ./tools/linux-distro/generate-linux-distro-manifest.ps1
```

当前 `tina-toolchain` 资产由 `app/src/<abi>/assets/tina-toolchain/current.properties` 描述，构建时会执行 `verifyTinaToolchainAssets` 校验。

## Release 注意事项

Release 任务不只是“生成 APK”，还可能触发以下副作用：

- 自动递增 `version.properties`
- 备份 R8 mapping 文件
- mapping 文件仅由公开构建逻辑做本地归档

另外，脚本方式的 Release 构建会同时检查本地签名配置：

- `keystore.properties`（可从 `keystore.properties.example` 复制）
- `keystore/release.jks`（或 `storeFile` 指向的本地 keystore）

## 文档

- [快速开始](docs/快速开始.md)
- [架构概览](docs/架构概览.md)
- [开发指南](docs/开发指南.md)
- [文档中心](docs/README.md)
- [Linux distro 运行时](docs/linux-distro-self-hosted-runtime.md)
- [GitHub Registry](docs/registry/GitHub-Registry.md)
- [更新日志](CHANGELOG.md)

## 技术栈

| 类别 | 技术 |
|------|------|
| 语言 | Kotlin, C++ |
| UI | Jetpack Compose, Material 3 |
| 编辑器 | Tina Editor（Compose + Canvas） |
| 高亮 | Tree-sitter |
| 编译 | native tina-toolchain |
| Sysroot | Android sysroot（按 ABI 安装） |
| LSP | native clangd，支持 remote / PRoot provider |
| 构建 | Gradle + CMake |
| 依赖注入 | Koin |
| 并发 | Kotlin Coroutines + Flow |
| 可选 Linux 环境 | PRoot + self-hosted Linux distro rootfs |

## 支持架构

| 架构 | 状态 | 用途 |
|------|------|------|
| `arm64-v8a` | ✅ 主支持 | 真机 |
| `x86_64` | ✅ 支持 | 模拟器 |

- `minSdk`: 28
- `targetSdk`: 36
- `compileSdk`: 37

## 项目结构

```text
TinaIDE/
├── app/                # 应用壳、导航、启动流程、跨模块装配
├── core/               # 编译、LSP、工具链、Git、插件、存储等复用能力
├── feature/            # 编辑器、终端、设置、帮助、预览、工作区等用户功能
├── external/           # 本地源码依赖与子模块
├── server/             # 公开占位说明；后端已迁入私有仓库
├── docs/               # 项目文档
├── docker/             # 运行资产构建脚本
├── tools/              # 本地开发脚本与模板
└── build-logic/        # Gradle 约定插件
```

## 架构要点

- `app` 负责启动流程、导航和跨模块编排
- `feature/*` 负责用户可见功能切片
- `core/*` 负责复用能力与运行时基础设施
- 默认编译 / LSP 依赖 native toolchain，不要求 PRoot
- PRoot 是可选 Linux 环境，而不是默认编译宿主

## 系统要求

### 开发环境

- Android Studio 最新稳定版
- JDK 17+
- PowerShell 7+（推荐）
- Docker Desktop（仅维护者需要）

### 运行环境

- Android 9.0+（API 28+）
- 推荐 3GB+ RAM
- 推荐预留至少 800MB 可用空间

## 支持项目

TinaIDE 已决定彻底开源，后续精力会优先放在稳定维护和社区协作上。
如果这个项目对你有帮助，欢迎通过赞赏支持持续维护。
如果更习惯在线赞助，也可以通过 [爱发电支持无相孤君继续开源](https://ifdian.net/a/wuxianggujun)。
也欢迎直接提交 PR，一起补功能、修 bug、改文档。

| 微信赞赏码 | 支付宝收款码 |
|------------|--------------|
| <img src="./feature/settings/src/main/res/drawable-nodpi/donation_weixin.png" alt="微信赞赏码" width="220"> | <img src="./feature/settings/src/main/res/drawable-nodpi/donation_zhifubao.jpg" alt="支付宝收款码" width="220"> |

你也可以用下面的命令快速统计当前项目源码规模，默认排除第三方库和生成物：

```powershell
cloc . --exclude-dir=.git,build,.gradle,.idea,node_modules,external,temp,tmp,docs/assets
```

## 致谢

- [LLVM Project](https://llvm.org/)
- [clangd](https://clangd.llvm.org/)
- [Tree-sitter](https://tree-sitter.github.io/)

---

更多设计与实现细节请从 [docs/README.md](docs/README.md) 进入。
