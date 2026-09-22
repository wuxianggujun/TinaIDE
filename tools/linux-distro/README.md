# Linux Distro Manifest Tools

本目录用于维护 `:core:linux-distro` 的自研发行版 manifest 数据。

## 原则

- 只整理 Ubuntu Base 官方 cdimage 与校验文件等发行版官方源数据。
- 不复制外部脚本项目的 shell 脚本、插件字段结构或发行版元数据。
- `linux-distros.lock.json` 是人工审核后的锁定源数据，`manifest.json` 由脚本生成。
- 新增发行版前先确认官方 rootfs 来源、架构映射、文件大小和 SHA-256。

## 当前发行版

- Ubuntu 24.04 LTS：`AARCH64`、`ARM`、`X86_64`，官方 Ubuntu Base 当前不提供 `I686`。

## 生成 manifest

```powershell
pwsh tools/linux-distro/generate-linux-distro-manifest.ps1
```

输出文件：

```text
core/linux-distro/src/main/assets/linux-distro/manifest.json
```

## 刷新官方元数据

刷新全部已支持的官方源：

```powershell
pwsh tools/linux-distro/generate-linux-distro-manifest.ps1 -RefreshRemoteMetadata -StampNow
```

只刷新 Ubuntu Base：

```powershell
pwsh tools/linux-distro/generate-linux-distro-manifest.ps1 -RefreshUbuntuMetadata -StampNow
```

刷新命令会访问发行版官方地址：

- `https://cdimage.ubuntu.com/cdimage/ubuntu-base/releases/24.04/release/ubuntu-base-<version>-base-<arch>.tar.gz`
- `https://cdimage.ubuntu.com/cdimage/ubuntu-base/releases/24.04/release/SHA256SUMS`

刷新后必须人工审查 `linux-distros.lock.json` 与生成的 assets manifest 差异。

## 远程清单（registry 托管，更新数据无需发版）

App 启动安装链路会优先尝试从 `TinaIDE-Registry` 仓库拉取远程清单，失败时回落到内置
asset（`core/linux-distro/src/main/assets/linux-distro/manifest.json`）。远程清单额外带
`mirrors` 前缀替换规则，下载官方源失败时自动切换到可用镜像。

- 待发布文件：`tools/linux-distro/registry-manifest.v1.json`
- 发布路径：`TinaIDE-Registry` 仓库 `main` 分支的 `linux-distro/manifest.v1.json`
- 拉取端点常量：`GitHubRegistryConfig.LINUX_DISTRO_MANIFEST_PATH`

### 发布步骤

1. 确认 `registry-manifest.v1.json` 内容正确（Ubuntu 官方 URL 与镜像规则已验证）。
2. 把该文件作为 `linux-distro/manifest.v1.json` 提交到 `TinaIDE-Registry` 仓库 `main` 分支并 push。
3. 推送后即时生效：已含远程清单逻辑的 App 会在缓存过期后（默认 6h）自动拉到新数据，
   无需用户更新 App。

### 镜像规则说明

`mirrors` 是清单级前缀替换规则，一条规则覆盖该发行版全部架构：

```json
{ "matchPrefix": "https://cdimage.ubuntu.com/cdimage/", "replaceWith": "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage/" }
```

artifact 的 `url` 保持官方规范地址；installer 先试官方，失败再按规则派生镜像候选逐个回落。
新增镜像只需在远程清单 `mirrors` 数组加一行。注意 Ubuntu 镜像路径前缀与官方不同
（官方 `cdimage.ubuntu.com/cdimage/` → 镜像 `<mirror>/ubuntu-cdimage/`），已在规则中正确映射。
