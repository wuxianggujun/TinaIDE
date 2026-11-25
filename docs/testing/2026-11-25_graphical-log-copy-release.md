# 图形日志等级正文与只读选区复制

日期：2026-11-25（Asia/Shanghai）。延续 `dev` 工作区，保留先前悬浮菜单、项目卡片与日志等级侧栏修改。

## 现象与原因

`0.19.10` 只通过 `GutterLineLabel` 绘制彩色等级，正文沿用统一前景色。`TinaCodeViewer` 只接入 Canvas 渲染与两个方向的滚动，没有长按、选区手柄或复制菜单；关闭输入宿主并不自动提供只读选择能力。

## 修复

- 编辑器提供可选的 `lineForegroundColors`，使用原有文本绘制与行缓存，逐逻辑行覆盖前景色；软换行续段继承同一颜色，无覆盖时维持原语法高亮。
- `TinaCodeViewer(selectable = true)` 复用编辑器的 UTF-16 选区边界、字体与行布局缓存、选区绘制、手柄／放大镜、复制菜单和剪贴板组件。日志启用该模式，Markdown 代码围栏保留原预览默认值。
- 只读菜单只有复制和全选，不创建输入宿主或焦点目标，也不接入剪切、粘贴、断点或 LSP 操作。无障碍选择使用正文偏移，不把侧栏标签插进可选文本。
- 复制菜单通过实际屏幕坐标定位，覆盖原生 PopupWindow 中再次创建 Compose Popup 的情况。
- 日志追加同步等级与正文颜色；旧行裁剪时重索引仍存续的选区，完全过期的选区清除。选区存在时暂停尾部跟随，手柄跟随外部裁剪后的锚点。
- 中英帮助资产、CHANGELOG 和 kit README 同步，源码与文档使用 UTF-8 无 BOM。

## 验证记录

本轮原始日志与结果位于 `app/build/verification/log-copy-release-20261125/`。编译、单元／框架窗口测试、真机交互、构建／安装和正式发布分别记录；任何一个阶段通过不代替其他阶段。

- 编辑器 5 类、41 项定向 JVM／Robolectric 测试通过，0 失败／错误／跳过，原始 XML 独立保存在 `kit-junit/`。
- App 3 类、42 项测试通过，0 失败／错误／跳过，覆盖等级正文、选区追加／裁剪／清空、反向选区、尾部跟随，以及真实创建的原生 PopupWindow 中全选／复制。原始 XML 保存在 `app-junit/`。
- 本轮合计 83 项，早期失败记录单独保留，不加入通过数。手柄用例显式执行 Canvas 绘制以获取真实行高；Robolectric 缺少系统 PixelCopy Surface，该用例关闭平台放大镜，真机配置保留。
- 宿主 Debug 源码编译、kit 独立消费者 `:consumer:compileDebugKotlin`、国际化与维护检查通过。没有新增第三方依赖或修改模块构建脚本。

最小回归命令：

```bash
cd editor-kit
./gradlew :core:editor-view:testDebugUnitTest --tests '*TinaCodeViewerTest' --tests '*GutterRendererTest' --tests '*EditorGutter*Test' --tests '*EditorPopupComposeSmokeTest' -I ../tools/testing/single-worker-tests.init.gradle --no-daemon --console=plain
cd ..
./gradlew :app:testArm64DebugUnitTest --tests '*GraphicalRuntimeLogDocumentTest' --tests '*GraphicalRuntimePopupAnchorTest' --tests '*GraphicalRuntimeOverlayTest' -I tools/testing/single-worker-tests.init.gradle -Ptina.devAbi=arm64 -Ptina.buildProotFromSource=false --no-daemon --console=plain
```

Windows 使用 `gradlew.bat`。单元测试任务设 60 秒超时；编译与 R8 构建不套用该超时。真机操作仅限 TinaIDE，不关闭、调整其他应用的小窗口，也不改变系统显示大小、方向或字体。

## Release 构建与真机安装

本地 ARM64 Release 构建成功，耗时 19m 02s。版本按现有机制推进为 `0.19.11 / 19122`，于 2026-11-25 15:40 覆盖安装到 PMS110（Android user 0）。安装前后 UID 均为 `10472`，首次安装时间均为 `2026-11-01 13:10:17`；没有卸载或清数据。

本地 APK 与设备 `base.apk` 的 SHA-256 均为 `94e3ecfd276517886820ca5331526997dde718cebfb1a6129ad8372a712cca83`。签名与旧包一致，非 debuggable；原生 ABI／ELF、ZIP 对齐、vcpkg 随包工具与分发许可检查均通过。mapping 归档与本地 APK 对应，构建配置时的 build ID 为 `0a4772104b-20261125152100`；构建期间后续提交仅固定已验证的同一源码与文档，没有修改运行代码。

真机冷启动返回 `Status: ok`、`LaunchState: COLD`。用户另一个应用的小窗口覆盖主要操作区域，因此停止进一步 UI 点按，没有关闭、拖动或缩放该窗口。日志配色、手柄与复制的真机端到端交互未完成，不能把 83 项 JVM／Robolectric 和启动检查写成该项已验收。

## 源码与发布追踪

- 先推送 TinaEditor `1db3d3404f2c16ec9cef033092a626890d1202e2`，再提交并推送 TinaIDE `6d7c9c20c6fa19e6cfac7a04f6a9116c3e044c92`，最后推送 `v0.19.11` Tag。
- 同一提交的 [分支全量回归](https://github.com/wuxianggujun/TinaIDE/actions/runs/45567776172) 通过。
- [正式发布工作流](https://github.com/wuxianggujun/TinaIDE/actions/runs/45570634010) 第一次因 Robolectric 下载 `android-all-instrumented:14-robolectric-10818077-i6` 遭 Maven HTTP 429 失败；失败 XML 与日志单独归档，未跳过检查，随后重跑失败任务。
- 第二次运行全部通过：分发许可、全量回归、ARM64／x86_64 Release 构建、包体验证与发布。`build-debug` 按 Tag 发布条件跳过，不属于 Release 验证项。
- [v0.19.11 正式 Release](https://github.com/wuxianggujun/TinaIDE/releases/tag/v0.19.11) 于 2026-11-25 17:03（Asia/Shanghai）发布为 Latest，非草稿、非预发布。两个 APK、CHANGELOG 内容与对应源码获取说明均已核验。

| 公开产物 | 字节数 | GitHub 记录的 SHA-256 |
| --- | ---: | --- |
| `TinaIDE-0.19.11-arm64-v8a.apk` | 177311417 | `2c15efcda2879c106b7003faac68158ed3f5da886e66052d1439f288f4b47467` |
| `TinaIDE-0.19.11-x86_64.apk` | 170971222 | `24cd566c1221275a8dda082400ac63d1096d9e7ce8f4780538e5ba7324dd1133` |

手机安装的是上节记录的本地签名构建；公开 Release 为同一运行源码的 CI 构建，build ID／环境不同，APK 哈希不相同。没有把公开资产的哈希误记为手机安装包哈希，也没有为发布再次覆盖手机。最终公开状态证据在 `publication-verification.json`、`published-release.json` 与 `release-ci-complete.json`。
