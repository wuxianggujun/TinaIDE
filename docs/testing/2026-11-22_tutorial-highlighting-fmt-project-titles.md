# 2026-11-22 教程高亮、fmt 基线与项目名称修复

代码修复、模块回归、模拟器验证及真机覆盖安装已完成。2026-11-22 22:38（Asia/Shanghai）将签名、R8 压缩后的 ARM64 Release `0.19.6 / 19072` 安装到 PMS110，手机与本地 APK 的 SHA-256 一致；未卸载、未清除数据，也未自动启动手机应用或运行真机功能测试。

## 现象与根因

| 现象 | 已核实的根因 | 修复 |
| --- | --- | --- |
| 教程 C++ / CMake 代码没有语法高亮 | 教程和帮助调用 `MarkdownViewer → SimpleCodeBlock → Text`，绕过编辑器与 Tree-sitter | 通用 Markdown 支持注入代码渲染；教程正文、提示和帮助改用编辑器只读视图 |
| 第 7 章 vcpkg 提示“全部已安装”，但 CMake 找不到 fmt | 用户提供的 `vcpkg.json` 是空依赖；旧蓝图也生成空清单，却已有 `find_package(fmt)`，安装成功只代表空集合 | 新工程同时生成 fmt 声明、链接和实际使用源码；已有工程不被自动覆盖 |
| 项目名只显示 `tin...` | 名称、来源、构建系统和语言标签在同一行，剩余宽度不足且名称被限制为单行 | 标题完整换行，标签移到独立 FlowRow，保留菜单宽度 |

用户当前空清单无需删除工程，可在市场为该项目添加 fmt，或仅将依赖列表改为：

```json
{
  "dependencies": ["fmt"]
}
```

保存后重新构建。其他清单字段和现有 RAII 源码保留；新教材中的简洁 fmt 基线不是续开时自动重写已有源码的指令。

## 模块边界与复用

- [TinaCodeViewer](../../editor-kit/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/TinaCodeViewer.kt) 在编辑器子模块中复用 `EditorRenderEngine`、布局缓存、语法配色和滚动边界。只创建渲染面，不创建输入宿主、焦点目标、编辑手势或 LSP。
- [MarkdownViewer](../../core/designsystem/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/MarkdownViewer.kt) 保持通用 AST 排版，以 composable 插槽接收代码渲染器，不反向依赖编辑器或 feature。
- [EditorMarkdownViewer](../../feature/help/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/EditorMarkdownViewer.kt) 装配独立文本快照与已有 Tree-sitter 高亮器，覆盖帮助、教程正文和分层提示；长代码可展开，复制始终返回完整围栏内容。未知语言仍可只读展示，不伪造语法高亮。
- [TutorialProjectBlueprints](../../feature/tutorial/src/main/java/com/wuxianggujun/tinaide/tutorial/project/TutorialProjectBlueprints.kt) 从同一份中英文教材读取 `vcpkg.json`、CMake 和 fmt 主程序，并复用函数课的 `pricing.*`。只增加受限的 JSON 清单标记，不开放任意 ZIP 路径。
- [ProjectListCard](../../feature/projectlist/src/main/java/com/wuxianggujun/tinaide/ui/projectlist/ProjectListCard.kt) 只调整信息布局，不改打开、重命名、导出和菜单回调。

没有新增外部库或第二套解析/高亮引擎；没有把宿主 config、designsystem 或 LSP 依赖带回编辑器内核。用户可见文案在 `core:i18n` 中英资源与随包中英文教材中维护；代码、文档和清单为 UTF-8 无 BOM。

## 回归与证据

验证根目录：`app/build/verification/tutorial-ui-fmt-20261122/`，生成物不提交 Git。

| 验证 | 当前结果 | 证据 |
| --- | --- | --- |
| fmt 修复前回归 | 新测试失败，确认蓝图没有 `vcpkg.json` | `fmt-regression-red.log` |
| 帮助模块 | 29 项通过，0 失败、错误、跳过 | `modules-junit-summary.json` |
| 项目列表模块 | 6 项通过，含长名称、标签独立行和四组宽度/字体矩阵 | 同上 |
| 教程模块 | 92 项通过，含预置 fmt、JSON 标记隐藏和旧空清单保留 | 同上 |
| 编辑器独立工程 | 新只读视图编译与 2 项定向测试通过 | `kit-compile.log`、`kit-viewer-tests-final.log` |
| Python 设备证据解析 | 提交前重跑 40 项通过，拒绝旧报告、未高亮、错误矩阵和不完整 fmt 复用证据 | `test_editor_runtime_regression.py` |
| 静态维护、国际化、差异 | 检查通过 | `check_all.py`、`check_all.py`（i18n）及 `git diff --check` |
| 模拟器真实高亮、复制与名称矩阵 | 2 项通过，0 失败、超时；C++ / CMake / JSON 均有实际语法颜色，完整复制且无编辑输入宿主；24 组名称矩阵通过 | `tutorial-ui/results.json`、本轮 metrics 与两张 PNG |
| 新建 fmt 工程安装、构建、运行和二次复用 | 中英文 2 项通过，0 失败、超时；真实生成的清单和源码未经测试修补，首次安装运行输出 `total=30`，二次复用保持 fmt 库哈希、修改时间和源码字节不变 | `fmt-course/results.json`、本轮 metrics |
| 新 ARM64 Release 与 PMS110 安装 | `0.19.6 / 19072` 构建、检查及覆盖安装通过；非 debuggable，手机 APK 哈希与本地一致 | `arm64-release-build-01.log`、`release-preflight.json`、`physical-install/installation.json` |

本轮 x86_64 Debug 主 APK 与 AndroidTest APK 构建成功（8 分 29 秒），原生库和 ELF 架构检查通过。主包 SHA-256 为 `f81841e27b216247250963b82c2784eea67da9cabb0afe1ac66cffc4f6dc7ec8`。模拟器 `tina_native_runtime_api34` / `emulator-5580`（API 34）执行最后四项设备验收；C++ / CMake / JSON 的彩色像素样本分别为 3692、6130、300。长短项目名 × 中英文 × 280/320 dp × 1/1.5/2 倍字体共 24 组均保留完整名称和独立标签行。

最初选择的 `TinaIDE_Vcpkg_QA_API34` 已安装不同签名的 Release，Debug 覆盖安装被 Android 拒绝。保留该环境和失败安装日志，改用已有 Debug 验证环境后成功；没有卸载或清空任一模拟器的应用数据。测试结束关闭本任务启动的模拟器，释放内存用于 Release/R8，不停止其他会话的 Gradle 进程。

测试排障保留失败记录：首轮整条 Gradle 命令在 60 秒内停在依赖准备，未执行测试；随后复用仓库的 `single-worker-tests.init.gradle`，将实际测试任务限制为 60 秒，构建准备单独计时。项目列表新增 Compose 测试还需启用 Android 资源以加载测试 Activity；补齐后完整模块回归通过，没有跳过布局断言。

最小模块回归：

```powershell
.\gradlew.bat -I tools/testing/single-worker-tests.init.gradle :feature:help:testDebugUnitTest :feature:projectlist:testDebugUnitTest :feature:tutorial:testDebugUnitTest --no-daemon --console=plain --configure-on-demand --max-workers=1
cd editor-kit
.\gradlew.bat -I ../tools/testing/single-worker-tests.init.gradle :core:editor-view:testDebugUnitTest --tests "*TinaCodeViewerTest" --no-daemon --console=plain --configure-on-demand --max-workers=1
```

设备验收只选择模拟器序列号：

```powershell
py -X utf8 tools/testing/run_editor_runtime_regression.py --adb D:/Programs/Android/Sdk/platform-tools/adb.exe --serial emulator-5580 --suite tutorial-ui --output app/build/verification/tutorial-ui-fmt-20261122/tutorial-ui
py -X utf8 tools/testing/run_editor_runtime_regression.py --adb D:/Programs/Android/Sdk/platform-tools/adb.exe --serial emulator-5580 --suite fmt-course --output app/build/verification/tutorial-ui-fmt-20261122/fmt-course
```

## Release 与交付边界

用户已允许版本叠加，按现有 Release 自动 patch 递增规则构建，没有关闭递增开关或固定旧版本。构建成功耗时 16 分 9 秒；`version.properties` 为 `0.19.6 / 1907`，ARM64 APK 实际版本为 `0.19.6 / 19072`。R8 mapping 存在，APK 签名、ARM64 原生库与 ELF 架构、随包许可和中英文 fmt 教材检查均通过。

| 安装项 | 实际结果 |
| --- | --- |
| 手机 | PMS110，ARM64，API 37 |
| 安装前 | `0.19.5 / 19062`，非 debuggable Release |
| 安装后 | `0.19.6 / 19072`，非 debuggable Release |
| 安装命令 | `adb install -r` 返回 `Success`；没有使用卸载、清数据或启动应用命令 |
| 候选 | `app/build/outputs/apk/arm64/release/TinaIDE-0.19.6-arm64-v8a-release.apk` |
| APK SHA-256 | 本地与手机均为 `a1df5c3fe577e684ae4c7d475d22a1f067cd2dd766daa2db1996c5809bf03b66` |
| R8 mapping | `app/build/outputs/mapping/arm64Release/mapping.txt`；SHA-256 为 `7b713e2d874e615e00c9b461eda7ce4ff6c5b7bbb09dbb492f465e34d055cc09` |

安装证据位于本轮验证根目录的 `physical-install/installation.json` 和 `install.log`；预检、签名与许可检查分别保存在 `release-preflight.json`、`release-signature.txt`、`release-native-libs.txt`、`release-distribution.txt`。四项功能设备验收使用 x86_64 Debug 模拟器，真机上的新 Release 功能仍由用户手测，不能把两种范围混写成真机功能测试通过。

编辑器子模块当前有本轮源码修改，提交推送时必须先提交推送 `editor-kit`，再更新主仓库 gitlink。本轮尚未提交推送或创建正式发布 tag；不能把主仓库旧提交号当作包含新修复的完整对应源码快照。

当前仍使用主仓库 `dev` 的唯一工作区，没有新建 worktree。预检记录了主仓库 HEAD `e6a68514aacfaec06da943a63d0dc81c5beeedcb`、编辑器子模块 HEAD `575fde62d6e60272696a7cc537c17ececc91bff1` 和工作区有未提交修改这一事实。`CHANGELOG.md` 中的本轮条目继续保留在 Unreleased，手机本地候选升级不等于已创建 GitHub Release。

## 安装后优先手测

1. 打开教程正文、分层提示和帮助，检查代码高亮、横向滚动与完整复制；点击只读代码不应弹出编辑键盘。
2. 项目列表应完整显示 `tina_course_07_vcpkg`，来源、构建系统和语言标签在下方；放大字体后名称仍能换行，更多菜单不被挤掉。
3. **已有空清单不会被升级自动覆盖**：按本文开头为现有项目添加 fmt，保存后运行；保留原源码，不删除工程。新建本章工程已预置 fmt 声明、链接和实际输出源码。
4. 检查旧项目、课程进度和设置，再验证修改后续开仍保留原文件。真机这些功能项由用户手测，本轮不自动启动应用。

教程后续优先补首次 native 工具链/sysroot 就绪清单、各章“基线与自动验收参考”的区别，以及导出备份与恢复片段指引；独立 C 入门和最小测试/Git 实操属于下一轮扩展。证据、建议和验收路径见[教程审查](2026-11-22_physical-install-and-tutorial-review.md)。
