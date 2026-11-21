# 2026-11-21 教程一键工程

教程现在直接准备完整的本章练习工程，不要求用户逐个新建文件。基线为主工作区 `dev` 的 `128fa3afc`；本轮没有新建 worktree。功能测试使用模拟器；按用户后续要求构建 ARM64 Release 并覆盖安装到 PMS110，真机只核对安装和版本，不运行功能测试，不发布 tag。

## 如何使用

1. 首页打开教程，进入已解锁的实操章节。
2. 点击 **创建／继续本章项目**。完整 CMake、源码和默认运行配置准备后自动进入编辑器。
3. 先运行基线，再做本章编辑、边界输入和排错练习。创建不会自动确认学习进度或判定考核通过。
4. 回到教程可继续同一工程；也可从项目列表直接打开或导出。

项目按章节隔离，保存在 `ProjectPaths.getPrivateProjectsRoot()`，不依赖公共存储权限。重复创建入口只续开，不重写源码、清单和用户运行配置。手工修改、删除文件或重命名工程均保留；需要恢复基线时，可先导出并从项目列表移除旧工程，再使用创建入口。

fmt 和 Android SDL3 包仍需用户明确添加、安装；自动创建不联网下载依赖。AI 工程不依赖 fmt、不读取 Key、不请求模型。

## 工程清单

同名目录属于其他工程时，新目录自动加 `_2` 等后缀，原目录或同名文件不被覆盖。CMake 目标统一为 `native_lab`，与自动生成的目录名分开。

| 章节 | 默认目录 | 文件和模式 |
| --- | --- | --- |
| IDE 工作流 | `tina_course_01_ide` | CMake、main.cpp；原生终端 |
| C++ 基础 | `tina_course_02_basics` | CMake、main.cpp；数量固定为 3 的初始版本 |
| 函数 | `tina_course_03_functions` | CMake、main.cpp、pricing.h、pricing.cpp |
| 容器 | `tina_course_04_collections` | 完整多文件计价器，不带前章折扣答案 |
| RAII | `tina_course_05_raii` | 完整多文件计价、写文件和读回 |
| CMake | `tina_course_06_cmake` | RAII 主程序，pricing 静态库 |
| vcpkg | `tina_course_07_vcpkg` | RAII 主程序、fmt 链接、空清单和项目清单模式；需自行添加 fmt |
| SDL3 | `tina_course_08_sdl3` | CMake、完整回调入口；SDL3 图形宿主，关闭 vcpkg |
| AI 模拟 | `tina_course_09_ai` | 独立计价工程；原生终端、离线、无 fmt |

最终知识检查不创建工程。开启 SDL3 工程不会替换控制台源码，AI 章节也无需从 SDL3 工程恢复备份。

## 设计与边界

- `feature:tutorial/project/TutorialProjectBlueprints` 从现有中英文课程的命名代码块读取完整文件；前置计价代码直接复用同一资产。新增提示或示例不靠代码块顺序选中工程源码。
- `TutorialProjectRepository` 把文件计划写成内部临时 ZIP，复用 `ProjectCreationService`、`ProjectTemplateInstaller` 的安全解压、项目元数据、vcpkg 初始化和失败回滚；不维护第二套手写创建事务或重复的随包压缩文件。
- `.tina-course.json` 的课程、章节与项目 ID 必须和项目元数据匹配才能续开。损坏、超限、身份不一致、未知 schema 的标记不会让无关目录被认作教程项目。
- 新工程创建和标记写入是有限的本地事务；取消后可留下已完成的工程，但不会自动跳转，后续点击可以续开。临时 ZIP 只放入 `ProjectPaths.getCacheRoot()`，结束后删除本次创建的临时文件。
- `TutorialViewModel` 抑制重复点击，按 reader session、章节及请求 ID 验证异步结果；关闭、切章后忽略旧请求。恢复同一页面只刷新进度，保留创建中的请求与尚未消费的打开事件，避免旋转或切回前台丢失跳转。
- 宿主通过 `ProjectRunConfigBootstrapper.ensureInitialized()` 确认默认运行配置已落盘，再调用已有打开工程回调；保留用户已有配置，失败提示可重试，页面不在后台自动打开编辑器。
- 续开拒绝符号链接标记，同名失效符号链接仍算占用；另选目录，不跟随链接创建目标。
- 教材和提示保持中英文同步，界面文案走 `core:i18n`；源码、标记、ZIP 文本和文档为 UTF-8 无 BOM。

## 验证记录

- 教程模块：89 项 JVM/Robolectric 测试通过，无失败或跳过，覆盖九章文件、同名冲突、改名续开、内容和时间戳保留、并发创建、损坏标记、会话切换、恢复请求与重试。
- 运行配置：13 项测试通过，覆盖初始化、保留已有配置、存储冲突拒绝与恢复重试。
- 编辑器子模块：49 项快捷键测试通过，含 CI 原失败用例，以及缩进宽度和 Tab 显示宽度不同、反向选区、反缩进夹取和撤销重做。
- Python 设备回归脚本 39 项、维护与分发策略脚本 60 项全部通过；国际化、完整维护门禁、分发许可检查与主仓库/编辑器 `git diff --check` 通过。Windows raylib 校验显式选择 Git Bash，修复误选系统 WSL Bash 导致的超时与错误解码。
- x86_64 Debug 主 APK 与 AndroidTest APK 构建成功；主包覆盖安装到 `emulator-5580`（API 34）。安装包与设备包 SHA-256 一致：`b87cb024aef2cbda18ce755a31429c7fd74d3b5cbab1c5b084305d373c4bc98c`。APK 原生库完整性与 ELF 架构检查通过。
- `tutorial-project` 最终三例全部通过：`zh`、`en` 分别生成九章工程并真实编译运行，`navigation` 经生产门户进入与续开真实编辑器，覆盖 Activity 重建。首轮测试用阻塞监视器等待 Activity，阻止 Compose 测试时钟推进；改为 Compose `waitUntil` 后通过。一次重跑在 instrumentation attach 阶段失败、未执行测试，恢复进程后完整三例重新通过。失败记录保留，不计入通过数。
- 补充四例通过：`zh-ai-practice`、`en-ai-practice`、`en-cmake-practice`、`recover-compile-error`，均在新安装 Debug 包上执行。
- SDL3 中英文绘制复验通过：`GraphicsCourseDeviceTest` 不再读取已删除的 `help/graphics-sdl3.md`，直接复用 `TutorialProjectBlueprints` 生成当前课程源码和 CMake；删除已不存在的 raylib 课程测试分支，raylib 的独立 runtime suite 保留。测试明确验证配置、构建、生产图形宿主启动、蓝底橙色矩形和退出后 IDE 进程存活。模拟器首次缺少 SDL3 开发包，通过现有 Android 包管理器安装 `sdl3` 后复验；生产创建入口仍不自动下载安装依赖。
- 共九例课程/编辑器设备验证通过，另有一例 SDL3 依赖安装验证通过，均无跳过；报告按每次独立 runId 校验。初始缺包、旧测试路径与启动超时记录均保留，不计为成功。
- 证据保存在 `app/build/verification/tutorial-project-20261121/`：`device-passed/results.json`、`course/*/results.json`、`graphics-verified/results.json`、`graphics-sdl3-install/results.json`、`installed-apk.json`、`final-build.log`、`graphics-test-build.log`、`modules-regression.log`、`editor-shortcuts.log`。生成物不纳入 Git。
- ARM64 Release：`:app:assembleArm64Release` 构建成功（38 分 3 秒），R8 与资源压缩完成，lintVital 无错误或警告，APK 签名验证及 ARM64 原生运行库/ELF 检查通过。使用 `-Ptina.autoIncrementReleaseVersion=false` 保留 `0.19.5` / `19062`，不修改 `version.properties`；APK 不带 debuggable 标记。产物为 `app/build/outputs/apk/arm64/release/TinaIDE-0.19.5-arm64-v8a-release.apk`，SHA-256：`df1ce93b9e5608b5e78f3359cb4fb4fb48b4ba65e811881464a635b5ff4e2ddc`。
- R8 mapping 已按现有构建逻辑归档至 `app/mappings/0.19.5-nogit-20261121230831/arm64Release/`，只做本地保留；构建和校验记录见 `arm64-release-build.log`、`release-signature.log` 与 `release-verification.json`。
- 真机安装尚未完成：构建开始前 PMS110 在线，构建结束后无线 ADB 已断开，原端口 `192.168.2.185:43525` 拒绝连接，mDNS 没有发现该设备。需要恢复 USB 调试连接或提供新的无线调试 IP 与连接端口；不卸载应用，不清空数据，不运行真机功能测试。

最小模块验证命令：

```powershell
.\gradlew.bat :feature:tutorial:compileDebugKotlin :feature:tutorial:compileDebugUnitTestKotlin --no-daemon --console=plain
.\gradlew.bat :feature:tutorial:testDebugUnitTest --no-daemon --console=plain
.\gradlew.bat :core:compile:testDebugUnitTest --tests "*ProjectRunConfigBootstrapperTest" --no-daemon --console=plain
py -X utf8 tools/i18n/check_all.py
git diff --check
```

后台测试执行窗口不超过 60 秒；APK 编译单独执行。只在没有其他会话构建时启动 Gradle，所有一次性命令使用 `--no-daemon`。

模拟器可复用验收入口（每例外部 60 秒、设备测试 55 秒上限，拒绝跳过、零测试或旧报告）：

```powershell
py -X utf8 tools/testing/run_editor_runtime_regression.py --adb D:/Programs/Android/Sdk/platform-tools/adb.exe --serial emulator-5580 --suite tutorial-project --output app/build/reports/tutorial-project
```

`zh` / `en` 使用生产创建器和运行配置，在独立临时目录生成九章工程，验证符号链接冲突、跨语言保留修改与配置，并真实编译运行多文件函数工程。`navigation` 经 MainPortalActivity 的实际教程按钮进入 MainActivity，验证 Activity 重建、续开同一工程、源码字节和修改时间不变；测试恢复原课程进度与当前项目偏好。

## CI 修复与发布状态

基线主仓库 CI [Core Regression Checks / 44903002005](https://github.com/wuxianggujun/TinaIDE/actions/runs/44903002005) 因 `EditorKeyboardShortcutsTest.handleEditorShortcut_shouldIndentEveryMultiCursorLineWithTab` 失败。本轮已一并修复：缩进开始替换整段行首空白后，通用替换映射把空白内光标折叠到新缩进末尾；现在多光标缩进使用与单选区一致的字符增量映射，并夹取到本行行首。原失败用例与新增回归均通过。

编辑器修复已先提交推送到 `TinaEditor/main`：`575fde62d6e60272696a7cc537c17ececc91bff1`，远端分支已核对可达。主仓库功能提交 `94349d3aa8cb4634958e7ae0ef88598657946044` 已推送 `dev`，包含对应 gitlink。

新功能提交的远端 [Core Regression Checks / 44918032908](https://github.com/wuxianggujun/TinaIDE/actions/runs/44918032908) 已全部成功，包含编辑器/编译器/安全/教程与宿主生命周期/分屏/存储回归。

没有发布 GitHub 新版本，最新正式发布仍为 `v0.19.2`。本地签名 Release 已生成，但尚未安装到断开连接的 PMS110，也没有执行真机功能测试；两者不能写成已完成设备验收。
