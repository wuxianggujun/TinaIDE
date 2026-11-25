# 0.18.38 Release 真机手测清单

编写日期：2026-11-01。**本页真机项目均为待测，不是已通过记录。**
本次优先验收模型选择页的 R8/Vector XML 崩溃修复、许可展示和 APK 导出，
再验收 RikkaHub 资源与 raylib；许可阻塞解除不等于设备功能已全部通过。

## 1. 安装与测试准备

- 使用 `app/build/outputs/apk/arm64/release/TinaIDE-0.18.38-arm64-v8a-release.apk`，
  版本 `0.18.38`，ARM64 versionCode `18392`（基础版本码 `1839`）。
- 首先在原来发生问题的 Xiaomi 14 Ultra / Android 16 上覆盖安装；不要先卸载或清数据。
  另用独立 Android 用户或备用设备测试全新安装。最低系统 Android 9；本包不是 x86_64 包。
- 记录系统版本、Android System WebView 版本、原安装版本、主题/语言以及测试库的修订号。
- 准备专用测试项目、旧聊天和测试渠道。不要把 API Key 放进测试样例、日志或反馈。

在仓库根目录运行 PowerShell（多设备时给 `adb` 添加 `-s` 和对应序列号）：

```powershell
$adb = 'D:\Programs\Android\Sdk\platform-tools\adb.exe'
$apk = Join-Path (Get-Location).Path 'app\build\outputs\apk\arm64\release\TinaIDE-0.18.38-arm64-v8a-release.apk'
& $adb devices
& $adb install -r $apk
```

预期 `Success`，设置页显示 `0.18.38`。如果签名不匹配，先核对安装来源，
不要用卸载正式数据掩盖升级问题。

## 2. P0：公开发布前必须通过

| 项目 | 操作 | 通过标准 |
| --- | --- | --- |
| 升级与启动 | 从 0.18.37 / 0.18.36 覆盖安装；打开旧项目、聊天、渠道，保存文件后冷启动；另做独立全新安装 | 不闪退、不丢项目/聊天/渠道配置；语言与主题保留；新安装可完成权限流程 |
| 模型选择页崩溃 | 打开 RikkaHub 渠道详情的模型选择列表，滚动到带推理、视觉、工具等能力标签的模型；选择/取消、切换渠道、切换深浅主题后重复 10 次 | 能力图标完整；不再出现 `ModelAbilityTag` / `painterResource` / `seekToStartTag` 相关 NPE，也无 `Resources.NotFoundException` |
| 许可全文和源码 | 断网打开“设置 → 关于 → 开源组件与许可证”，查看 RikkaHub 和 TinaIDE，滚动至第 13 条；联网点击项目主页 | RikkaHub 显示完整 AGPL v3，TinaIDE 显示 GPL v3；没有旧非商业/用户数限制，也不提示“无许可内容”；RikkaHub 源码入口指向 TinaIDE fork |
| AI 流式聊天 | 从编辑器侧边栏发中文与 emoji；长回复中途停止，再继续发消息；切换模型/旧会话，返回编辑器 | 流式响应、停止和继续可用；历史会话正常；没有串会话或编辑内容丢失 |
| 原生 Markdown | 在聊天气泡查看中文、表格、代码、公式，复制代码；检查 Gemma / Kimi / Qwen 图标及深浅色占位图 | 无缺资源/字体异常，中文和 emoji 不乱码，复制内容正确；未因瘦身丢掉实际使用的资源 |
| 网页视图与图表 | 消息菜单打开“网页视图渲染”；测试 Markdown、KaTeX / 化学式、Mermaid；分别测试内联图表和独立图表页并导出 PNG | 在线时渲染正常；PNG 非空且能打开；原生聊天与 WebView 两条路径都通过 |
| WebView 释放与异常输入 | 两条不同消息交替打开/关闭预览各 10 次，切后台再回来；测试错误 Mermaid、含 HTML 的样例；断网后返回聊天 | 不崩溃、无 ANR、不串旧内容；错误输入可返回/继续聊天；脚本和 iframe 不执行；断网不要求首次 CDN 预览可用 |
| C/C++ 编译与 LSP | 用默认 native toolchain 编译运行中文输出程序；故意制造编译错误再修复；测试补全、悬停、跳转和诊断 | 编译/运行正确，UTF-8 中文正常；错误能定位到源文件；不要求先安装 PRoot |
| 编辑与存储 | 中文/emoji、多行选择、撤销/重做、保存、关闭重开；同一文件分屏后关闭一侧继续编辑 | 磁盘内容正确，另一个分屏不被释放，无空白编辑器或保存崩溃 |
| APK 导出回归 | 使用当前 raylib 模板或已支持导出的 SDL 模板，按导出契约构建 `libmain.so`；修改应用名称/包名，导出、签名、安装并启动，再覆盖安装导出的新版 | Manifest/资源处理、签名和安装成功；包名/标签正确，图形程序能运行；不能只以“已导出”提示当成功 |
| raylib 真机显示 | 按下一节重装 r2 库并清理测试项目重编；检查动画、触摸、后台恢复和返回后再次运行 | 非黑屏，帧与交互有可见变化；返回退出运行进程后 IDE 仍在；连续运行/退出 5 次正常 |

任意 P0 失败都保留复现步骤并暂停公开发布。没有执行的项记为“未测”，不勾选通过。
预览样例和注入回归输入可复用
[0.18.37 清单第 3 节](release-0.18.37-manual-checklist.md#3-可以直接粘贴的预览样例)，
**但安装包和版本必须使用本页的 0.18.38**。

## 3. raylib 黑屏必须这样区分

1. 更新 APK **不会替换已安装的 raylib 库**。在包管理里重新安装 `raylib 6.0 revision 2`，
   新测试项目使用模板 `1.0.1`；旧模板不会自动重写已有源码。
2. 对测试项目执行清理后重新配置/编译，确认使用的库来自 r2，避免继续链接旧项目产物。
3. 在 IDE 内运行一次，再导出支持的 APK 单独运行。分别记录是否有画面，不能把两条路径混为一项。
4. 检查动画和触摸反馈，而不只看渲染循环日志；返回退出后再次运行 5 次，再测一次切后台恢复。
5. 仍黑屏时，按 [Native 图形回归](native-graphics-runtime.md) 采集真实 EGL surface 类型、尺寸、
   `app->window`、交换帧结果和线程 ID，同时附库修订号、设备和最小项目。

当前 Android raylib 的 `GetWindowHandle()` 固定返回 `NULL`，它不是底层 `ANativeWindow*` 的诊断入口。
后端创建的是 `eglCreateWindowSurface`，不是 pbuffer 离屏表面；`native_app_glue` 的专用渲染线程
也是正常模式，不要为了这个 `0x0` 把 EGL 绘制强行挪到 Android UI 线程。
已有 x86_64/API 34 对照曾显示 742 帧及触摸反馈，但它不能代替本次 ARM64 Release 真机验收。

## 4. P1：按准备发布的能力补测

- 文件管理：导入/导出、重命名、删除测试文件、SAF 外部工作空间、分享与接收文件。
- Git：状态/diff、提交、HTTPS / SSH 拉取推送；仅用测试仓库，不在反馈中附私钥或 token。
- 插件：安装、启停、模板创建、权限拒绝和卸载；不要把用户项目当作可删除的测试目录。
- 终端与调试：标准输入、中文输出、结束进程，native LLDB 的断点/继续/退出。
- RikkaHub：会话切换、消息编辑/重新生成、附件、图片、已有 MCP 配置的连接和取消。
- 长时间运行：聊天和编辑切换 20 分钟、切后台、旋转/分屏，记录 ANR、崩溃和明显内存增长。
- Ubuntu / X11：只在拟发布对应能力时单独验收；XFCE 真机验证仍未完成，不写成已验证功能。

## 5. 反馈与发布判定

每个问题记录：版本/Build ID、设备/系统/WebView、升级还是新装、最小操作、预期/实际、
截图或短视频、发生时间。崩溃使用本次 APK 对应的 mapping，不能使用 0.18.37 的旧 mapping。
导出日志前检查是否含项目内容、路径或凭据，只分享排查需要的片段。

默认和英文帮助、许可 JSON、新文档均为 UTF-8；测试 C/C++ 源码也保存为 UTF-8。
发布前执行分发门禁，并在 APK 旁附精确主仓库提交、递归检出子模块的方法和构建说明。
“许可门禁通过”“Release 构建通过”“真机 P0 通过”是三项独立结果。

## 6. 本次本地构建记录（不代表真机通过）

构建于 2026-11-01 12:56（Asia/Shanghai）完成，`:app:assembleArm64Release` 成功，
Gradle 耗时 17 分 41 秒。R8 与资源压缩保持开启，未用关闭优化或全包 keep 掩盖问题。

| 项目 | 实际结果 |
| --- | --- |
| 版本 / ABI | `0.18.38` / versionCode `18392` / `arm64-v8a` / minSdk `28` |
| APK 体积 | 151,961,577 字节（144.92 MiB） |
| APK SHA-256 | `4e3aa546b1431d5acedf4b650bb9e7e8a4b1fbbe73fdaf1a9b3a7a15fd02e451` |
| Build ID | `97934cd7a1-20261101123923` |
| 构建源码 | [TinaIDE `97934cd7a15bbb4fc0f6d2f7bd80f8ecc2983507`](https://github.com/wuxianggujun/TinaIDE/tree/97934cd7a15bbb4fc0f6d2f7bd80f8ecc2983507) |
| RikkaHub 子模块 | [fork `7e9862c3279509a8965b8dcb1f6b6f458be96d32`](https://github.com/wuxianggujun/rikkahub/tree/7e9862c3279509a8965b8dcb1f6b6f458be96d32) |
| 签名与对齐 | APK v2 验证通过，签名证书与 0.18.37 一致，zipalign 通过 |
| mapping SHA-256 | `9144fe9d5d71d049cff92a23ecc3785f649571d35c4a3b91d7a29ba05992b8e2` |

本机产物与排错材料如下；这些是构建生成物，不要求在新的源码检出中已经存在：

```text
app/build/outputs/apk/arm64/release/TinaIDE-0.18.38-arm64-v8a-release.apk
app/mappings/0.18.38-97934cd7a1-20261101123923/arm64Release/mapping.txt
app/build/reports/release-0.18.38/verification-summary.json
app/build/reports/release-0.18.38/assemble-arm64-release-wrapper.log
```

已完成的本地验证：

- 分发策略 20 项、raylib 包契约 9 项、embedded 资源契约 10 项、设备基线解析 5 项，共 44 项 Python 测试通过。
- `core:apk-builder`、`feature:settings`、RikkaHub embedded 的 Debug Kotlin 编译通过。
  `core:apk-builder` 单测报告为 15 项、0 失败/错误/跳过；本次执行复用了同输入的 UP-TO-DATE 结果。
- 文档、文件操作基线、embedded 资源冲突和 i18n 检查通过，修改文本为 UTF-8 无 BOM。
- **最终 APK** 的版本、ABI、最低系统、签名、对齐及 `check_distribution.py --apk` 均通过，
  确认包内包含新的许可全文，而不是仅凭源码通过检查。
- 最终 DEX 和 mapping 中没有 ARSCLib 的 6 个 Android/XML 平台桩类定义；
  `painterResource` 的向量资源路径保留 `Resources.getXml → XmlPullParser.next → XmlPullParser.getName`。
  已核对最终 mapping 与本地归档哈希一致。
- 最终 APK 包含新的深浅色 WebP、Gemma / Kimi / Qwen 图标、JetBrains Mono、emoji 和网页预览资源；
  被裁剪的旧 Google Sans Flex、banner、simple_dict 没有回流。

本机首次构建在 R8 阶段发生 JVM native memory allocation 失败，不是 Kotlin/Java 编译错误；
一次重试还遇到 Windows 嵌套 `cmd` 引号导致 JVM 参数被识别为 task。
最终通过项目原有 wrapper JAR 启动，使用 `-Xmx5g`、`MaxMetaspaceSize=768m`、G1、
`ActiveProcessorCount=4`、单 worker、`--no-parallel --no-daemon` 完成。
重试始终设置 `-Ptina.autoIncrementReleaseVersion=false`，版本仅递增一次至 0.18.38。

本 APK 对应上表的**构建源码提交**；后续追加本记录的文档提交不改变该 APK 的 Build ID。
在新目录获取精确源码时执行以下命令，不使用不含子模块的 GitHub 自动 source ZIP 代替：

```bash
git clone https://github.com/wuxianggujun/TinaIDE.git TinaIDE-0.18.38
cd TinaIDE-0.18.38
git checkout 97934cd7a15bbb4fc0f6d2f7bd80f8ecc2983507
git submodule update --init --recursive
```

**尚未完成：本次 ARM64 Release 真机安装与 P0 功能验收。**
尤其不能把静态 XML 检查或旧 x86_64 图形对照当作用户设备上模型页、raylib 已通过的证明。
