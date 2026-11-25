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

补充测试复现了底部输入 Popup 被键盘遮挡；仅添加 IME insets 未能解决，最终改为标准搜索 Dialog。三个引擎均重新完整执行并通过“拖到底部 → 搜索 → 输入框位于键盘上方 → IME 提交”断言。最终 70 项宿主单测也重新通过，加上 8 项搜索引擎测试合计 78 项。端到端使用本任务启动的只读、无界面临时模拟器；未操作用户真机上的其他应用窗口。修正版 Release 与真机验收尚未完成。

版本号脚本 `verifyReleaseVersioning` 已验证：旧码 1913 可读、同名修正版 1914 可读、后续 0.19.13 自动递增到 1915、低于历史下限的版本码被拒绝。测试只改 `build/verification/versioning` 内夹具，不改真实版本文件。

## 同版本重发的明确例外

维护者在本轮明确要求“真的修复之后重新发布 0.19.12”。本次按该最新指令执行一次同版本修正版：基础版本码 1913 → 1914，版本名仍为 0.19.12；后续版本自动递增使用历史下限与当前版本码加一的较大值。

日常仍遵守不改公开版本的默认规则；本次例外须保留首次发布提交 `cbe418767d1bdd48b76fbd59900e190deb0e43a0`、原 annotated Tag 对象 `be677f5d0b5b531d2ec86e5c48e66d21465710da` 及两个 APK 的可追溯归档，并先将验证候选通过 PR 合入 `main`，再更新发布引用。不得静默丢弃旧产物。

旧版更新检查只比较版本名，同名修正版不会自动弹出更新提示。Release 说明须明确手动下载覆盖安装。版本码提高可保证安装升级；不能宣称已修复旧客户端的更新判断。
