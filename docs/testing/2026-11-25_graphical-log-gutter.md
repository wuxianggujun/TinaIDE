# 图形日志的紧凑等级侧栏

本轮日期：2026-11-25（Asia/Shanghai）。继续原 `dev` 工作区，保留既有未提交修改；不新建 worktree。

## 1. 现象与根因

用户在已安装的日志编辑器中发现行号右侧仍有一块空白侧栏。日志已使用 `TinaCodeViewer`，不创建编辑输入或 Debug 条，但 `EditorRenderer.hitZones()` 始终保留 `GutterRenderer` 的折叠区域：至少 18 dp，或一个行高。`codeFolding = false` 只关闭折叠功能，没有去掉布局宽度。

正常代码编辑器的断点标记由 `LineNumberRenderer` 绘制，可以替代对应行号；不能为了日志去全局删除断点能力。

先添加 `logLevelPrefixesDoNotConsumeBodyColumns`，旧实现实际运行 1 项失败：期望纯正文，实际仍是 `[I] info / [W] warning / [E] error`。原始失败报告单独归档，不计为通过。

## 2. 设计与落地

- 编辑器子模块增加每实例 `EditorGutterMode`：`DEFAULT` 保持原行为；`HIDDEN` 不预留额外侧栏；`LABELS` 使用单字符宽度与紧凑留白，只展示标签。行号开关独立，非默认模式不分发断点、折叠、书签或行号编辑操作。
- 复用 `GutterDecoration`，增加通用 `GutterLineLabel`；同一 `GutterRenderer` 绘制标签，`EditorRenderer` 的 hit-zone 缓存纳入显示模式。没有第二套文本渲染器或新增依赖。
- 日志实例选择 `LABELS`，正文不写等级前缀；信息、警告和错误颜色复用编辑器配色中的诊断色。只读无障碍文本保留等级信息。
- 日志 owner 按归一化后的逻辑行生成标签：CRLF／CR、多行与空消息都保留正确等级；软换行仅在首个视觉段显示标记。
- 增量追加沿用同一个 Rope／EditorState；只在旧行裁剪时重索引已有标签。待追加标签最多 500 项，单条包含大量换行也不创建无界元数据。
- 清空同步移除正文与等级，继续保留换行选择和捕获链路。保留原 500 行／512 Ki UTF-16／单消息 8192 单元上限、横向滚动、历史位置和尾部跟随。

编辑器内核只接收字符、颜色与显示模式，不依赖 App 日志类型、LSP、宿主偏好或 i18n。正常编辑器的默认配置不变；无新用户可见硬编码文案，中英帮助资产同步，所有新增文件为 UTF-8 无 BOM。

## 3. 验证与证据

本轮证据目录为 `app/build/verification/graphical-log-gutter-20261125/`。Gradle 命令使用 JDK 17、`--no-daemon`；Test task 沿用 `tools/testing/single-worker-tests.init.gradle` 的 60 秒上限。不停止共享 daemon，不删除缓存，不改标准构建目录。

- `red-document-prefix.log` 与 `red-junit/`：修复前的确定性失败。
- kit 自身 wrapper 先验证显示模式、Robolectric Native Graphics 的 Canvas 标签绘制、默认断点／行号交互和缓存；宿主随后验证日志文档与 Compose 集成。

| 验证 | 实际结果 |
| --- | --- |
| kit 侧栏、等级绘制、默认交互与行号 | 4 类、27 项通过，0 失败／错误／跳过 |
| kit 渲染缓存、换行与视觉行映射 | 4 类、43 项通过，0 失败／错误／跳过 |
| kit 只读视图、无障碍等级与共享 popup | 4 类、27 项通过，0 失败／错误／跳过 |
| App 日志文档、裁剪、多行、空消息、清空与交互边界 | 1 类、20 项通过，0 失败／错误／跳过 |
| App 悬浮窗与原生 PopupWindow 锚点 | 2 类、16 项通过，0 失败／错误／跳过 |
| 本轮定向 JVM／Robolectric 合计 | 15 类、133 项通过；不含修复前的 1 项预期失败 |
| kit 独立 `:core:editor-view:compileDebugKotlin` | 成功 |
| 宿主 `:app:compileArm64DebugKotlin` | 成功，包含文档测试共 5m 21s |
| kit `examples/consumer :consumer:compileDebugKotlin` | 成功，独立源码消费未引入宿主依赖 |
| 国际化／维护／UTF-8／补丁检查 | 通过 |

各组原始 XML 分别保存在 `kit-gutter-junit/`、`kit-render-wrap-junit/`、`kit-viewer-popup-junit/`、`app-document-junit/` 和 `app-overlay-popup-junit/`；对应 JSON 摘要按实际用例数聚合到 `test-summary.json`。不使用后一次过滤测试留下的报告替代前一次结果，不把编译成功、Native Graphics 或框架窗口验证写成真机验收。

复测时从 TinaIDE 根目录执行（Windows 使用 `gradlew.bat`，并设置 JDK 17 与 Android SDK）：

```bash
cd editor-kit
./gradlew :core:editor-view:testDebugUnitTest --tests '*EditorGutterModeTest' --tests '*GutterRendererTest' --tests '*EditorGutterInteractionTest' --tests '*LineNumberRendererTest' -I ../tools/testing/single-worker-tests.init.gradle --no-daemon --console=plain
./gradlew -p examples/consumer :consumer:compileDebugKotlin --no-daemon --console=plain
cd ..
./gradlew :app:compileArm64DebugKotlin :app:testArm64DebugUnitTest --tests '*GraphicalRuntimeLogDocumentTest' -I tools/testing/single-worker-tests.init.gradle -Ptina.buildProotFromSource=false -Ptina.devAbi=arm64 --no-daemon --console=plain --configure-on-demand
./gradlew :app:testArm64DebugUnitTest --tests '*GraphicalRuntimeOverlayTest' --tests '*GraphicalRuntimePopupAnchorTest' -I tools/testing/single-worker-tests.init.gradle -Ptina.buildProotFromSource=false -Ptina.devAbi=arm64 --no-daemon --console=plain --configure-on-demand
```

## 4. 安装与验收边界

上述定向验证结束时，手机仍是前一轮覆盖安装的 `0.19.8 / 19092`，不包含后续等级侧栏改动。该阶段的原始 `final-checks.json` 保留 `newApkBuilt = false` 与 `newApkInstalled = false`；下方单独记录随后按用户要求完成的构建安装。

2026-11-25 14:23（Asia/Shanghai）已完成 ARM64 Release `0.19.10 / 19112` 构建与 PMS110 真机覆盖安装，包含本轮等级侧栏修改。第一次 `0.19.9` 构建随会话中断停在 R8，未产出新 APK；重新构建沿用现有自动版本递增机制，耗时 18m 07s，退出码 0，没有人为回退版本。

续接证据目录：`app/build/verification/release-physical-install-20261125-gutter/`。

- `preflight.json`、`build-status-interrupted.json`、`release-build-interrupted.log` 与 `build-status.json` 分别保留安装前状态、中断构建和实际成功构建记录。
- `release-verification.json` 核验实际 APK 包名、`arm64-v8a`、非 debuggable、旧包一致的签名、ZIP 对齐、原生库与 ELF 架构、分发许可门禁以及 R8 mapping 本地归档。构建期间主仓库 26 个既有修改文件和 kit 13 个修改文件指纹保持一致；版本文件按预期递增。此前 133 项测试对应的 20 个源码／文档指纹也与本次构建前内容一致。
- 安装命令只针对 `192.168.2.185:44495` 执行 `adb install -r`，返回 `Success`。`installation.json` 记录升级前 `0.19.8 / 19092` 与升级后 `0.19.10 / 19112`；Android user 0 的 UID 均为 `10472`，首次安装时间均为 `2026-11-01 13:10:17`。
- 新 APK 与手机 `base.apk` 的 SHA-256 一致：`bf04e8a2790f3fdbfb5e64c7fd3a35c11930f1557414d67d71f32be4fd3450e2`。产物位于 `app/build/outputs/apk/arm64/release/TinaIDE-0.19.10-arm64-v8a-release.apk`。

本次没有卸载、清空用户数据、启动应用或操作模拟器；没有提交推送、打 Tag 或发布。安装与包体核验成功不代表图形交互已在真机验收。

后续候选安装后，应在真机检查：行号旁的 `I / W / E` 颜色与正文对应；长行横向滚动时等级固定；切换换行不重复标记；超过 500 行、查看历史与清空继续输出时不会等级错位；返回正常代码编辑器后断点、折叠与行号操作仍正常。
