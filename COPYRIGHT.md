# TinaIDE 版权与许可

TinaIDE — Android 平台上的 C/C++ 集成开发环境。

Copyright (C) 2025-2026 wuxianggujun

本程序是自由软件：你可以依照自由软件基金会发布的 GNU 通用公共许可证
（GNU General Public License）第 3 版，或（按你的选择）任何更新的版本，
重新分发和／或修改本程序。

本程序的分发目的是希望它有用，但**不附带任何担保**，甚至不包含对
适销性或特定用途适用性的默示担保。详见 GNU 通用公共许可证。

你应当已随本程序收到一份 GNU 通用公共许可证副本（见根目录 `LICENSE`）。
如未收到，请参阅 <https://www.gnu.org/licenses/>。

SPDX-License-Identifier: GPL-3.0-or-later

## 许可证变更说明

在 2026-09 之前，本仓库使用自定义的 "TinaIDE Open Source License Version 1.0"。
该许可证限定开源范围仅覆盖 1.0.0 版本，并对后续版本的二进制分发施加了
"仅个人非商业使用" 的限制。

为了集成 [termux-x11](https://github.com/termux/termux-x11) 提供的 Android
原生 X server（GPL-3.0），本项目整体改用 GPL-3.0-or-later。GPL-3.0 不允许
在下游附加非商业限制，也不允许后续版本闭源，因此原自定义许可证的
第 4(a)、4(b) 条已不再适用。

被取代的旧许可证文本保留在
`docs/third-party-notices/TinaIDE-Custom-License-v1.0-superseded.txt`，
仅用于历史追溯。已按旧许可证获得 1.0.0 版本的使用者，其既有权利不受影响。

## 第三方组件

本程序集成和分发多个第三方组件，各自适用其原始许可证。完整清单见
`NOTICE.md`，以及应用内 **设置 → 关于 → 开源组件与许可证** 页面。

分发本程序或其衍生作品时，必须同时保留：

- 本文件与根目录 `LICENSE`（GPL-3.0 完整文本）
- `NOTICE.md` 及 `docs/third-party-notices/` 下的第三方许可证文本
- 各第三方组件源码内的原始版权声明
