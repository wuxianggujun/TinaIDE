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
