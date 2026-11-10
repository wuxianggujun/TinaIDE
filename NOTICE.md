# TinaIDE 第三方组件与许可证清单

TinaIDE 自有代码以 **GPL-3.0-or-later** 分发（见 `LICENSE` 与 `COPYRIGHT.md`）；
内嵌 RikkaHub 部分适用 **AGPL-3.0**，依据两份许可证第 13 条组合。
本文件列出随程序一同分发或链接的第三方组件及其原始许可证。

分发 TinaIDE 或其衍生作品时，必须保留本文件、`LICENSE`、
`docs/third-party-notices/` 下的许可证文本，以及各组件源码内的原始版权声明。

最后定点核验：2026-11-01（RikkaHub 许可、预览资源及 ARSCLib；非全依赖重新审计）

## 一、仓库内嵌源码（external/、libs/）

下列组件以 git submodule 或 vendored 源码形式存在于本仓库，参与构建并随 APK 分发。

| 组件 | 路径 | 许可证 | 说明 |
| --- | --- | --- | --- |
| termux-x11 (lorie / Xlorie) | `external/termux-x11` | GPL-3.0-or-later | Android 原生 X server；本项目改用 GPL-3.0 的直接原因 |
| PRoot (termux-proot) | `external/termux-proot` | GPL-2.0-or-later | 无 root Linux 环境；`or-later` 允许并入 GPL-3.0 作品 |
| termux terminal-emulator / terminal-view | `external/termux-terminal` | GPL-3.0-only | 来自 termux-app；部分文件为 AOSP Apache-2.0（见文件头） |
| android-rsync | `external/android-rsync` | GPL-3.0-or-later | 文件同步 |
| tina-android-tree-sitter | `external/tina-android-tree-sitter` | LGPL-2.1（顶层）；各 grammar 见子目录 | tree-sitter Android 绑定与语法 |
| RikkaHub (embedded) | `external/rikkahub` | AGPL-3.0-only（上游纯 AGPL 第 3 版，无额外限制） | AI 聊天 / Provider / MCP；许可同步与源码义务见下文 |
| xCrash | `external/xcrash` | MIT（部分文件另有许可，见 `LICENSE`） | 原生崩溃捕获，iQIYI |
| ImmersionBar | `external/immersionbar` | Apache-2.0 | 沉浸式状态栏 |
| XXPermissions | `external/xxpermissions` | Apache-2.0 | 运行时权限 |
| DeviceCompat | `external/devicecompat` | Apache-2.0 | 设备兼容适配 |
| ImmersionBar AAR | `libs/immersionbar-3.4.6.aar` | Apache-2.0 | 预编译产物，与 `external/immersionbar` 同源 |

### termux-x11 内嵌的 X.Org 组件

`external/termux-x11/lorie/src/main/cpp/` 下以 submodule 引入的上游组件，
均保留各自上游许可证（多为 MIT / X11 / HPND 风格宽松许可，与 GPL-3.0 兼容）：

`xserver`、`pixman`、`libx11`、`libxau`、`libxdmcp`、`libxfont`、`libxkbfile`、
`libxshmfence`、`libxtrans`、`libxcvt`、`libfontenc`、`libepoxy`、`xkbcomp`、
`xorgproto`、`libtirpc`（BSD-3-Clause）、`bzip2`（BSD 风格）。

各组件许可证文本位于其源码目录内（`COPYING` / `LICENSE`）。

## 二、二进制依赖（Maven）

主要运行时依赖及其许可证。完整传递依赖清单由构建产物生成。

| 组件 | 许可证 |
| --- | --- |
| AndroidX（core、lifecycle、room、compose、preference 等） | Apache-2.0 |
| Kotlin stdlib / coroutines / serialization | Apache-2.0 |
| Koin | Apache-2.0 |
| OkHttp / Okio | Apache-2.0 |
| Eclipse LSP4J | EPL-2.0 **或** EDL-1.0 双许可；本项目按 **EDL-1.0**（BSD-3-Clause 风格）分支使用，以避免 EPL-2.0 与 GPL-3.0 的兼容性争议 |
| LuaJava (party.iroiro.luajava) | Apache-2.0；内含 Lua 5.4（MIT） |
| Material Components | Apache-2.0 |
| ByteHook (com.bytedance:bytehook) | MIT；SDL 图形运行进程内 PLT/GOT hook，与 GPL-3.0 兼容 |
| ARSCLib (io.github.reandroid:ARSCLib:1.3.8) | Apache-2.0；2026-11-01 核对 Maven POM。`core:apk-builder` 构建时移除其 Android/XML 平台桩类，保留资源解析实现 |

## 三、运行时下载的组件（不随 APK 分发）

下列内容由用户在运行时按需下载，不属于本程序的分发范围，各自适用其原始许可证：

- Ubuntu 24.04 rootfs 与 apt 软件包（Canonical 及各包上游许可证）
- clangd / LLVM 工具链（Apache-2.0 with LLVM Exception）
- Android NDK sysroot 与工具链（Google NDK 许可条款）

### RikkaHub 网页预览资源（2026-10-31 定点核验）

网页预览通过 HTTPS CDN 加载固定版本，未把完整 JS 包加入 APK：

| 组件 | 版本 | 许可证 |
| --- | --- | --- |
| markdown-it | 15.0.3 | MIT |
| KaTeX（含 mhchem 扩展） | 0.19.1 | MIT |
| @vscode/markdown-it-katex | 1.1.2 | MIT |
| markdown-it-task-lists | 2.1.1 | ISC |
| highlight.js | 11.12.0 | BSD-3-Clause |
| Mermaid | 12.2.0 | MIT |
| DOMPurify | 3.4.17 | MPL-2.0 **或** Apache-2.0；本项目按 Apache-2.0 分支使用 |

DOMPurify 用于净化 Markdown 中的原始 HTML；版本与核验方法见
`external/rikkahub/embedded/RESOURCE_MAINTENANCE.md`。
此资源清单与组件自身的许可审查分别维护，RikkaHub 的后续许可同步见下节。

## 四、RikkaHub 许可同步与分发要求

**已解除原“非商业 / ≤10 用户”附加限制这一分发阻塞。**

官方作者于 2026-07-18 在
[`65a63e4ec9c649bd5404ebcbbedb55aad5741d84`](https://github.com/rikkahub/rikkahub/commit/65a63e4ec9c649bd5404ebcbbedb55aad5741d84)
改为纯 AGPL-3.0。本地 fork 于 2026-11-01 回移该提交，`external/rikkahub/LICENSE`
与官方原文一致。没有通过自行删除条款、停用门禁或移除 AI 功能来规避限制。

RikkaHub 仍由 `rikkahub-embedded` 直接链接进主程序。GPLv3 与 AGPLv3 第 13 条允许
这种组合：宿主 GPL 部分和 RikkaHub AGPL 部分各自保持原许可；适用时履行远程网络交互的源码提供义务。

分发时必须保留许可/版权/修改记录，并提供与 APK 对应的完整源码及构建脚本。
Release 页附精确提交与递归检出命令；GitHub 自动 source ZIP 不含子模块，不能单独作为完整源码。
应用内 **设置 → 关于 → 开源组件与许可证** 附 RikkaHub AGPL 全文与 fork 源码入口。

核验提交、许可哈希、作用范围与回滚规则见
[`docs/licensing/rikkahub-agpl-review.md`](docs/licensing/rikkahub-agpl-review.md)。
`tools/checks/check_distribution.py` 继续 fail closed，校验源码 LICENSE 和随 APK 展示的全文/源码链接，
不是无条件放行；不满足源码交付或设备验收条件的构建仍不能宣称已完成公开发布。

## 可选 vcpkg 工具

APK 包含按 ABI 编译的 Android 原生 vcpkg CLI 和可离线浏览的 ports 元数据目录；完整 ports 源码和配套工具按需下载。
[vcpkg](https://github.com/microsoft/vcpkg) 与 [vcpkg-tool](https://github.com/microsoft/vcpkg-tool)
采用 MIT 许可，与 GPL-3.0 兼容；构建脚本随输出保留上游 LICENSE.txt、NOTICE.txt 与对应源码提交。
Termux 辅助工具从官方仓库按固定 SHA-256 下载，保留随包许可；来源和范围见 [vcpkg 运行时许可记录](docs/licensing/vcpkg-runtime-review.md)。用户通过 ports 安装的库各有许可证，
分发这些库或导出 APK 时应按实际依赖保留对应声明与源码义务，不能把 vcpkg 的 MIT 许可视为所有库的许可。
