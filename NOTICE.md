# TinaIDE 第三方组件与许可证清单

TinaIDE 整体以 **GPL-3.0-or-later** 分发（见 `LICENSE` 与 `COPYRIGHT.md`）。
本文件列出随程序一同分发或链接的第三方组件及其原始许可证。

分发 TinaIDE 或其衍生作品时，必须保留本文件、`LICENSE`、
`docs/third-party-notices/` 下的许可证文本，以及各组件源码内的原始版权声明。

最后人工核验：2026-09-03

## 一、仓库内嵌源码（external/、libs/）

下列组件以 git submodule 或 vendored 源码形式存在于本仓库，参与构建并随 APK 分发。

| 组件 | 路径 | 许可证 | 说明 |
| --- | --- | --- | --- |
| termux-x11 (lorie / Xlorie) | `external/termux-x11` | GPL-3.0-or-later | Android 原生 X server；本项目改用 GPL-3.0 的直接原因 |
| PRoot (termux-proot) | `external/termux-proot` | GPL-2.0-or-later | 无 root Linux 环境；`or-later` 允许并入 GPL-3.0 作品 |
| termux terminal-emulator / terminal-view | `external/termux-terminal` | GPL-3.0-only | 来自 termux-app；部分文件为 AOSP Apache-2.0（见文件头） |
| android-rsync | `external/android-rsync` | GPL-3.0-or-later | 文件同步 |
| tina-android-tree-sitter | `external/tina-android-tree-sitter` | LGPL-2.1（顶层）；各 grammar 见子目录 | tree-sitter Android 绑定与语法 |
| RikkaHub (embedded) | `external/rikkahub` | **待确认，见下文"未解决事项"** | AI 聊天 / Provider / MCP |
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

## 三、运行时下载的组件（不随 APK 分发）

下列内容由用户在运行时按需下载，不属于本程序的分发范围，各自适用其原始许可证：

- Ubuntu 24.04 rootfs 与 apt 软件包（Canonical 及各包上游许可证）
- clangd / LLVM 工具链（Apache-2.0 with LLVM Exception）
- Android NDK sysroot 与工具链（Google NDK 许可条款）

## 四、未解决事项

**RikkaHub 许可证冲突（阻塞 GPL-3.0 合法分发）**

`external/rikkahub/LICENSE` 采用 "Segmented Dual Licensing"，在 AGPL-3.0 之外
附加了非商业使用与 ≤10 用户的限制。GPL-3.0 第 7 条禁止在下游施加
"further restrictions"，因此该组件当前无法与 GPL-3.0 的 TinaIDE 合并分发。

该组件通过 `app/build.gradle.kts` 的
`implementation("me.rerere.rikkahub:rikkahub-embedded")` 直接链接进主程序。

在冲突解决前，包含 RikkaHub 的构建产物**不得对外分发**。可选路径：

1. 联系 RikkaHub 作者（re_dev@qq.com）取得 GPL-3.0 兼容的授权例外
2. 将 RikkaHub 拆为可选组件，默认构建不包含
3. 移除该组件，AI 聊天改由其他 GPL 兼容实现替代
