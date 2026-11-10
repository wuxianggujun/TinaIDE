# 编辑器与图形运行回归（2026-11-09）

本轮针对长按菜单、查找与选区、浮层主题、双指缩放、SDL 资源目录、下载取消和图形进程隔离。
源码基线为 TinaIDE `bdd488b0f`、TinaEditor `969b6eb`。设备结果对应先完成的编辑器/图形运行修改；
后续包安装与 HID 初始化修改另以模块回归和模板构建验证，未重新安装 IDE 到模拟器。
后续 vcpkg 清单修复与包管理 69 项复跑见 [vcpkg 元数据回归](vcpkg-metadata-regression-2026-11-09.md)；
本报告的 50 项记录保留为当时快照。

后续真实 Registry 跨进程安装/卸载、vcpkg fmt 构建运行及编辑器渲染采样见
[设备验收补充](device-acceptance-2026-11-09.md)。下方“尚未完成”列表描述本报告当时状态，
当前进度以补充记录为准。

## 已完成的编辑器设备矩阵

测试专用 AVD：Android 14 / API 34，x86_64，720×1280，320 dpi，SwiftShader，禁用 Vulkan。
每组包含 10 个用例；每项由主机限制在 60 秒内，串行执行。

| 配置 | 结果 | 说明 |
| --- | --- | --- |
| 默认语言、标准字号、竖屏 | 10/10 | 初始完整矩阵 |
| 中文、1.5 倍字号、竖屏 | 10/10 | 原日志名带 `landscape-largefont`，但 Activity 实际仍为竖屏，不计作横屏证据 |
| 中文、1.5 倍字号、真正横屏 | 10/10 | Activity 显式设置方向并断言；截图宽大于高；窗口使用与宿主一致的 `adjustResize` |

用例覆盖：10 字符长按选区、首层全选、查找关闭后的选区与 IME 归属、明暗主题交叉、替换回车与撤销、
无效正则与无匹配恢复、Unicode 反向多选区、反复开关查找、四种固定行号/自动换行组合缩放、横向拖动后缩放、
零宽正则、窄编辑窗和折叠列点击。

此前 API 36 模拟器出现 SystemUI / system_server / Digital Wellbeing ANR，其后受影响的批次不计为有效通过。
已修正测试夹具的方向设置和窗口 resize 行为，不把系统旋转命令成功当成横屏 UI 已验证。

## 修复机制

- 搜索会话保存选区及文档版本，仅在未编辑且当前选区仍由搜索控制时恢复；关闭后的异步结果和旧 IME 回调失效。
- 弹层明确提供背景及文字色；宿主 Markdown 继承编辑器弹层主题，低对比度颜色合成后至少达到 4.5:1。
- 缩放预览和提交共同约束横向边界；左对齐时保持零偏移，不把行号固定留白按字号比值变成滚动偏移。
- 查找面板根据剩余高度滚动，关闭按钮独立固定；共享输入组件使用紧凑边框，并保留真实光标与输入法组合态。
- SDL 路径按标准 UTF-8 字节跨 JNI，优先所选资源目录，保留存档和写入语义；运行与导出复用目录解析。
- 下载取消直接中断阻塞网络调用，内容身份决定断点文件；安装解压在事务提交前响应取消并清理临时文件。

## 真实宿主测试补充

API 34 的真实 TinaIDETheme + MarkdownViewer 测试、紧凑输入框中间选区编辑、变量补全光标、
真实 C++ Tree-sitter 大文件测试共 4 项通过。一万行、367,779 字节 C++ fixture 在该次 Debug 模拟器运行中：
最终复跑读取并建立缓冲区 61 ms，首次高亮含界面挂载 1,729 ms；滚动和末尾新增第 10,001 行后高亮仍有效。
渲染计数只取得 21 帧，其中 18 帧被标记为慢帧，不作为帧率验收通过，更不能用于声称性能提升。

真实图形库测试额外暴露并修复了两个 JVM 测试未揭示的问题：

1. 本地 SDL 3.5 包的 HID 连接注册签名有 13 个参数，更新后的 Java 桥仅保留 14 参数版本，
   `JNI_OnLoad` 期间 abort。保留兼容签名并选择可用调用，按构建脚本锁定的 SDL 提交
   `f600c74c80360eaaf8675da7d4ec69dc1670ac57` 核对全部 66 项 JNI 注册声明和 45 项 Java 回调。
   同时补回旧文件选择与手柄轮询入口。相同 3.5 版本号的开发快照不保证接口相同，
   可选新接口改为探测实际能力后调用，保留兼容回退。
2. PopupWindow 会插入自己的窗口根，Compose 在该根上查找 lifecycle owner。仅给内部 ComposeView
   设置 owner 无效，SDL2/raylib 创建浮层时抛出 `ViewTreeLifecycleOwner not found`。
   新容器在子视图附着前给实际根节点设置 lifecycle、ViewModel、saved-state owner。

首轮图形用例缺少生产启动凭据，Activity 正确拒绝启动；补齐凭据后才发现上述缺陷。
这些失败批次保留原因，不计入通过数字。图形进程崩溃时宿主测试进程仍能记录断言和结果。

## 包安装生命周期补充

两项缺陷已先复现失败：下载后等待解压时，另一个管理器实例可提前清掉缓存；安装根包及依赖时，
底层归档的 `Completed` 事件会早于安装记录写入。对应修复为跨实例缓存锁和由管理器统一发送最终完成事件。
真实下载后端测试使用实际 ZIP、校验、解压、目录发布和安装记录，只将 HTTP 与 registry 响应替换为可控夹具。
失败断言及原始日志哈希保存在 `package-install-reproductions.json`，修复后对应测试均通过。

此外，ZIP 元数据遍历与 ZIP/TAR 每块复制均检查取消，发布目录前再次检查；在下载完成但尚未解压时取消，
也通过 `finally` 删除完整归档。取消或元数据校验失败均保留旧安装并清理暂存目录。

安装及已安装信息读取共用有界解析：`package.json` 最大 1 MiB，既检查文件长度，也检查流式读取的实际字节数，
并严格解码 UTF-8。测试覆盖中文/emoji、超限、读取期间长度变化、非法编码、坏 JSON、包 ID 不匹配及更新失败保留旧包。
Robolectric 验证启用 Android 资源，断言实际本地化错误，避免把资源缺失异常误算成正确拒绝。

## 安装状态一致性补充

以下三项均先用未修复源码复现失败，再在同一用例上验证修复：

- 本地安装的是下载包，但市场把安装类型改为脚本后，卸载会走脚本分支并失败。卸载现在以本地记录为准，
  下载包无需请求市场元数据即可删除。
- 安装已发布文件但尚未写记录时，另一页面可以完成卸载，随后安装又写回“已安装”。完整安装计划、卸载
  和内置包初始化共用操作锁，文件与状态更新保持顺序；缓存锁及目录锁仍负责各自的文件资源。
- 安装记录存在未知平台时，枚举解析直接抛出异常。读取列表和查询状态统一过滤不可解释的记录；
  写入其他有效记录时保留未知记录，不把它们悄悄覆盖为空。

额外覆盖取消排队卸载、取消当前安装后继续卸载，确认取消不会删除正在安装的包，也不会遗留操作锁。
内置包初始化也经过实际等待测试。下载包目录已缺失时，卸载允许清掉陈旧安装/更新记录，测试同时确认
保留另一平台记录及其他包文件；实际删除失败仍返回失败。
`package-state-reproductions.json` 保存三项失败断言；`package-state-unit-tests.json` 保存最终 50 项通过结果，
包括先前 40 项安装回归及扩展用例，不与旧结果累计为独立用例数。

## SDL HID 初始化补充

锁定 SDL 提交 `f600c74c80360eaaf8675da7d4ec69dc1670ac57` 的 Android Activity 原本直接初始化 HID。
宿主使用 `supportsHidapiQuery() && nativeIsHIDAPIEnabled()` 却会跳过这类没有新版查询接口的库。
修复后，旧接口或缺少查询符号的快照会尝试原有 HID 初始化；新版查询明确返回关闭时跳过；
原生 HID 桥本身未编入时捕获缺失符号并继续运行。USB/蓝牙手柄的真实设备输入尚未验收。
共享 Java 源码通过导出模板模块的 5 项 JVM 测试，覆盖旧接口、新接口开启/关闭、缺少查询符号及非接口异常。
导出模板重新构建并通过 `checkApkTemplatesSync`，SDL 探测库保留全部四个 ABI。

## 验证结果

| 验证范围 | 通过 | 证据 |
| --- | --- | --- |
| 编辑器 3 组配置 | 30/30 | `editor-*-results.json` |
| 真实宿主主题、输入框、变量光标、大文件高亮 | 4/4 | `host-device-results.json` |
| SDL3 默认/自定义资源、正常返回、abort、exit | 4/4 | `runtime-device-results.json` |
| SDL2 默认/自定义资源、正常返回、abort、exit | 4/4 | 同上 |
| raylib 正常返回、abort、exit | 3/3 | 同上 |
| 网络、下载、安装事务、资源目录与导出单测 | 33/33，0 跳过 | `module-tests.json` |
| 包安装/卸载互斥/取消/元数据/状态/断点回归 | 50/50，0 跳过 | `package-state-unit-tests.json`，覆盖此前 40 项，不与旧结果累加 |
| ZIP 限额与 TAR 底层解压单测 | 11/11，0 跳过 | `archive-io-unit-tests.json` |
| 图形宿主生命周期/退出/日志单测 | 28/28，0 跳过 | `runtime-host-unit-tests.json` |
| SDL 桥接契约和 3.5 能力兼容单测 | 6/6，0 跳过 | `sdl-compatibility-tests.json` |
| SDL HID 初始化决策及当前导出模板 | 5/5，0 跳过；四 ABI 和同步检查通过 | `sdl-hid-template.json`，不代表真实手柄输入已验收 |
| 编辑器大文档与渲染缓存单测 | 5/5，0 跳过 | `editor-large-file-unit-tests.json` |
| SDL JNI 双向描述符 | 111/111 | `sdl-jni-contract.json` |

图形用例使用真实 SDL2 2.32.10、SDL3 3.5.0、raylib 6.0 Registry 库及生产 staging/Activity。
每项确认浮层可见、用户代码 PID 与 IDE 不同、图形进程退出、IDE PID 未变；SDL 用例还验证资源内容。
测试程序实际绘制帧，浮层可见并截图后才触发退出/崩溃。早期仅创建空窗口和固定 3 秒等待的结果不作最终依据。

导出模板已重新生成，使用 AGP artifact API 获取新产物。SDL 模板包含 arm64-v8a、armeabi-v7a、x86、x86_64
四种探测库，并通过 `checkApkTemplatesSync`。所有源码与报告采用 UTF-8，JNI 文件路径使用标准 UTF-8 字节。
中英文资源及帮助已同步，`tools/i18n/check_all.py` 和主仓库/子模块 `git diff --check` 通过。

精简结果、产物哈希、PID 记录及截图位于 [evidence/editor-runtime-2026-11-09](evidence/editor-runtime-2026-11-09)。
`apks.json` 为此前设备矩阵的产物快照；HID 修复后的当前模板哈希记录在 `sdl-hid-template.json`。

## 可复现入口

`tools/testing/run_editor_runtime_regression.py` 支持 `editor`、`host`、`runtime` 三组。
设备序列号为必填参数，构建产物必须与待验证源码对应。图形运行 probe 的准备步骤见
[原生运行回归说明](../../tools/native-activity-regression/README.md)。

50 项包管理回归（项目根目录，Bash 命令；Windows 可使用 Git Bash）：

```bash
./gradlew :core:packages:testDebugUnitTest \
  --tests '*DownloadPackageBackendTest' --tests '*PackageArchivePolicyTest' \
  --tests '*InstalledPackageMetadataReaderTest' --tests '*ResumableDownloaderTest' \
  --tests '*PackageDownloadIdentityTest' --tests '*PackageInstallCoordinatorTest' \
  --tests '*LocalInstallStateStoreTest' --tests '*PackageManagerRevisionTest' \
  --tests '*PackageManagerIoDispatcherTest' --tests '*BundledPackagesReadinessTest' \
  -I tools/testing/single-worker-tests.init.gradle '-Ptina.devAbi=x86_64' \
  --no-daemon --configure-on-demand --no-parallel --console=plain --max-workers=1
```

单个 Test task 限时 60 秒，构建/资源合并耗时另计。50 项最终构建成功，用时 2 分 55 秒；
证据记录了逐项测试名、时间及相关源码哈希，便于与后续修改区分。

SDL 纯路径策略已经在 API 34 上执行 13 项原生断言，包括中文/emoji、默认与自定义目录、保存文件优先、
只读与写入模式、`..`、越界符号链接。它验证路径策略，不能单独证明 SDL 的真实 IO 调用链已跑通。

## 验证边界与资源清理

当前仍未完成的验收：

- ARM64 真机与最终 Release/R8 上的编辑、输入法、图形运行及导出回归。
- 将最新包管理修复重新打包后，在完整 IDE 中覆盖市场下载、取消、更新、卸载、重新编译运行。
- vcpkg 手机上从准备 CLI/工具链，到安装 port、CMake 链接并运行的完整流程。
- 大文件性能定位：当前只有 Debug 模拟器的有限样本，21 帧中的 18 个慢帧不能作为流畅度合格证据。
- SDL USB/蓝牙手柄真实输入；本轮 HID 修复的证据为锁定源码核对、Java 行为测试和模板构建。

设备矩阵是 Debug x86_64 模拟器测试，不替代 ARM64 真机和最终 Release 验收。网络单测使用可控响应，
不能据此宣称所有镜像或用户网络均正常。大文件观察值必须与设备、构建类型和设置一起解释，不能据单次
模拟器耗时宣称性能提升。

精简结果与关键截图已保留。专用 API 34 AVD 已通过 `avdmanager delete avd` 删除，本次删除后 C 盘可用空间
增加约 5.12 GiB；API 36 专用 AVD 在此前测试阶段已删除。

工作区本轮临时诊断、测试 APK、打包副本及模板中间产物已清理，共 905,902,525 字节（约 863.94 MiB，
按删除文件的逻辑大小统计）。16 个指定目录复查均为 0 个剩余文件，保留空目录结构。
早期宽范围清理被自动审核拒绝后，将操作缩小为明确的产物文件、诊断文件类型及逐个核对的临时文件，已全部完成。
完整路径、大小、删除状态和 AVD 空间测量见 [cleanup.json](evidence/editor-runtime-2026-11-09/cleanup.json)。
后续再移除 Debug DEX 及 HID 模板构建副本共 270,971,502 字节；各次清理记录独立保存，避免将目录复用后
再次生成的文件误当作前一次仍未清理。随包模板 APK 与精简测试证据保留。
现有 Release 包、源码、随应用分发的模板 APK、全局缓存和其他任务的 AVD 均保留。
