# 图形日志触摸诊断过滤、搜索与 0.19.12 修正版

## 根因与边界

设备日志样本的来源是 `DynamicFramerate [AnimationSpeedAware]`，正文包含 `onEventHandled: info: InputEventInfo`。此前过滤表没有覆盖厂商动态帧率组件，且移除 `[`／`@` 装饰后没有去除尾部空格。它与图形程序处于同一进程，按 PID 采集仍会收到这些信息。

共享 `GraphicalRuntimeLogPolicy` 现在默认过滤该来源的 V/D/I 普通诊断，保留 W/E/F。未知用户 Tag、SDL 原生日志和 stdout/stderr 仍按原样显示；不按正文关键词过滤。SDL2、SDL3、NativeActivity／raylib 复用同一策略。完整进程日志仍保留全部来源。

## 搜索实现

- 复用 `CodeSearchEngine`、`EditorState` 与只读 `TinaCodeViewer`，当前匹配使用编辑器选区并滚动定位。
- 不区分大小写的字面量搜索，上／下一处循环，匹配计数，无匹配状态；保留中文及 emoji 的 UTF-16 偏移，不再通过全文小写转换推导索引。
- 只搜索已保留缓冲：最多 500 行、512×1024 个 UTF-16 字符；匹配最多保留 2000 处，通过额外一项判断并显示 `2000+`。
- 搜索期间暂停尾部跟随；追加、裁剪、清空后刷新匹配，过期匹配不选择无关文本。关闭搜索后只保留关键词，重新打开时重算结果。
- 仅关键词输入使用标准 Compose Dialog，自动避让软键盘；提交／关闭输入后释放焦点并回到日志内的搜索条。正文保持只读、可复制、不弹键盘，原生日志窗与图形窗口的尺寸和位置不变。收起日志后不自动重新打开输入框。
- 新增用户文案同步中英文资源与应用内帮助，源码与文档使用 UTF-8 无 BOM。

## 已执行验证

78 项 JVM／Robolectric 回归通过，0 失败、0 跳过：

| 套件 | 数量 |
| --- | ---: |
| CodeSearchEngineTest | 8 |
| GraphicalRuntimeLogPolicyTest | 7 |
| GraphicalRuntimeLogcatTest | 10 |
| GraphicalRuntimeLogDocumentTest | 25 |
| GraphicalRuntimeLogSearchTest | 6 |
| GraphicalRuntimeOverlayTest | 18 |
| GraphicalRuntimePopupAnchorTest | 4 |

包含真实格式 Tag、连续 600 条触摸诊断、多行继承、用户同名正文、完整进程模式、Unicode、长行横向定位、缓冲裁剪、搜索收起／清空、窄窗口大字体工具栏，以及原生 PopupWindow 内打开搜索对话框后的日志位置保持和焦点释放。

宿主 Debug Kotlin／单测源码编译与 `py tools/i18n/check_all.py` 通过。测试任务使用 `tools/testing/single-worker-tests.init.gradle`，单任务超时 60 秒。执行日志位于本地 `app/build/verification/graphical-log-search-20261125/`，不提交生成物。

API 34／x86_64 Debug 的 SDL2、SDL3、NativeActivity／raylib 三个端到端用例已通过。实际原生探针每轮写入 600 条带空格 Tag 的触摸诊断，再输出普通用户日志；窗口内校验诊断不出现、同名用户正文保留，验证无匹配、上下跳转、中文／emoji 选区、IME 搜索提交与收起重开。最终三项耗时分别为 26.65s、28.62s、23.93s，均低于单项 60s 上限。

测试驱动在可获焦点的输入窗口打开时读不到底层窗口的无障碍节点，改为调用输入框的 `ACTION_IME_ENTER` 后再检查正文；没有放宽正文或选区断言。SDL 探针的日志用例使用竖屏窗口以避开模拟器方向切换时的启动卡顿，历史退出／崩溃用例保持原窗口尺寸。保留失败输出，不把重试前的用例计为通过。

补充测试复现了底部输入 Popup 被键盘遮挡；仅添加 IME insets 未能解决，最终改为标准搜索 Dialog。三个引擎均重新完整执行并通过“拖到底部 → 搜索 → 输入框位于键盘上方 → IME 提交”断言。最终 70 项宿主单测也重新通过，加上 8 项搜索引擎测试合计 78 项。端到端使用本任务启动的只读、无界面临时模拟器，验证完毕已关闭；未操作用户真机上的其他应用窗口。

版本号脚本 `verifyReleaseVersioning` 已验证：旧码 1913 可读、同名修正版 1914 可读、后续 0.19.13 自动递增到 1915、低于历史下限的版本码被拒绝。测试只改 `build/verification/versioning` 内夹具，不改真实版本文件。

## 同版本重发的明确例外

维护者在本轮明确要求“真的修复之后重新发布 0.19.12”。本次按该最新指令执行一次同版本修正版：基础版本码 1913 → 1914，版本名仍为 0.19.12；后续版本自动递增使用历史下限与当前版本码加一的较大值。

日常仍遵守不改公开版本的默认规则；本次例外须保留首次发布提交 `cbe418767d1bdd48b76fbd59900e190deb0e43a0`、原 annotated Tag 对象 `be677f5d0b5b531d2ec86e5c48e66d21465710da` 及两个 APK 的可追溯归档，并先将验证候选通过 PR 合入 `main`，再更新发布引用。不得静默丢弃旧产物。

旧版更新检查只比较版本名，同名修正版不会自动弹出更新提示。Release 说明须明确手动下载覆盖安装。版本码提高可保证安装升级；不能宣称已修复旧客户端的更新判断。

## 2026-11-26 发布与真机安装结果

- 修复提交：`f41761f59089ac0cfa46b3848a3f6766a8831a96`。PR #22 普通合并到远端 `main`，发布提交为 `98ff7c23163852e8a6576250f01239682b578a42`；核对文件树与已验证候选一致后才更新 Tag。
- 首次发布的 annotated Tag 对象保存在 `archive/0.19.12-initial`，非 Latest 归档 Release 保留两个原始 APK、原 SHA-256 和对应源码。按维护者明确要求，`v0.19.12` 使用带旧对象校验的 force-with-lease 更新至修复提交；没有静默丢弃旧产物。
- PR 回归 `45639743935` 与 main 回归 `45639931894` 均成功。修正版流水线 `45640027488` 的许可门禁、回归门禁、ARM64 与 x86_64 Release 构建均成功；正式构建分别耗时 37m13s 和 23m15s。
- 自动 `publish-release` 步骤调用 GitHub Release 更新接口失败，重跑接口也返回 **HTTP 500**；整个 run 的最终状态仍是 failure，不能写成流水线全绿。已下载这次成功构建的原始 CI artifacts，逐项验证后通过发布 API/CLI 更新中英文说明与两个同名 APK。没有另行编译冒充 CI 产物。
- 正式 Release 仍为 `v0.19.12`，Latest=true、draft=false、prerelease=false。修正版资产于北京时间 **2026-11-26** 更新；GitHub Release 的 published_at 保留首次发布的 2026-11-25 时间，这是更新已有 Release 的结果。
- 发布后 PR #24 将 `main` 普通合并回 `dev`，同步提交为 `2fc911ed9`。核对发布 SHA 是 dev 的祖先，两个分支文件树一致；本节验收记录随后仅在 dev 维护。

| 正式 CI APK | versionCode | 字节数 | SHA-256 |
| --- | ---: | ---: | --- |
| ARM64 | 19142 | 177312669 | `8190a64dccc063d420a841431c271e65d20801e38fcf2474ac6645024b488678` |
| x86_64 | 19141 | 170903662 | `d92e779c315a2168809fe1afcd0026ee48590113439911a4eee41b17cb13b22b` |

两包均通过版本／包名／ABI／非 debuggable、签名、16 KB zipalign、native libraries、distribution 和 vcpkg 校验，GitHub 发布资产的哈希与 CI 日志一致。签名 SHA-256 为 `e1ef5c24ca8349376e6f3ebf0d9b93a7c44c20d7bb9ae2144f053abb649fd45c`。Windows 工作树 CRLF 与 CI LF 的资源校验使用发布提交原始 Git blob 快照，未关闭校验或改变比较规则。

PMS110 无线真机 `192.168.2.185:40777` 已先安装本地签名包，再覆盖为上表的正式 CI ARM64 APK，设备版本确认为 **0.19.12 / 19142**，UID 仍为 10472，首次安装时间仍为 2026-11-01 13:10:17。覆盖安装成功，未清数据、未启动 App 或切换用户其他应用画面。初次安装记录脚本因新 Android 使用 `appId` 而非 `userId` 报断言失败，随后直接核验并修正了检查器；不是 APK 安装失败。

真机确认范围为正式包覆盖安装、版本与身份保留；图形交互完整回归在 API 34 模拟器完成，不扩写为 PMS110 图形 UI 已全部实测。本地产物与核验 JSON 位于 `app/build/verification/graphical-log-search-20261125/`。
