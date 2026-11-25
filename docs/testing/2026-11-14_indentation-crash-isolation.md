# 缩进一致性与崩溃来源隔离回归

> 2026-11-14：源码修复、102 项定向测试、最终签名 Release 构建及无线真机覆盖安装完成。已核对手机 APK 哈希、版本和正常启动。额外设备测试在测试框架初始化阶段缺类退出，7 项用例尚未执行，不算设备行为回归通过。

## 根因与修复边界

| 优先级 | 问题与触发条件 | 修复与验收规则 |
| --- | --- | --- |
| P1 | 编辑器宽度与 LLVM/Google 等格式化预设默认宽度各自取值，换行 4 列而格式化 2 列 | 无项目配置时，共用编辑行为的缩进、Tab 和空格策略；预设仍负责括号、空格、换行等其他排版 |
| P1 | Tab 显示宽度同时被当成缩进步长，旧偏好的整数类型还可能使读取失败 | 独立 `editor_indent_size`，兼容旧值；首次改变 Tab 宽度前保存旧缩进，避免迁移覆盖用户选择 |
| P1 | 项目配置、继承配置或续行宽度未同步到编辑器 | 由实际 Native/PRoot 后端 `--dump-config` 解析最终配置；文件打开、切换、恢复和配置变动走可取消的刷新路径；配置解析失败时阻止格式化 |
| P1 | 格式化期间文件移动，原标签文本不变，旧结果可能仍回写 | 同时检查标签、源文件身份和原文本，条件替换失败时不覆盖用户缓冲 |
| P1 | 用户进程崩溃沿用宿主操作，旧启动器 `CLEAR_TASK` 可能清掉 IDE 任务栈 | 崩溃页使用独立任务 affinity，不清宿主任务；实际进程身份决定来源，用户程序只有返回编辑器和关闭操作 |
| P1 | 进程身份缺失时，历史 `app` 标签仍可授予宿主重启/上传权限 | 未确认宿主身份时降级为组件来源；只有精确的宿主进程身份可启用宿主操作 |
| P2 | 默认展开完整堆栈、长说明和隐私卡，界面信息过载 | 默认只显示短标题、一句提示和可选信号标识；日志、复制、分享及宿主上传状态按需展开；新事件重新折叠 |

### 配置优先级

1. 源文件所在目录及祖先目录的 `.clang-format` / `_clang-format` 优先，编辑器与格式化同步使用实际解析结果。
2. 无项目配置时，所选风格作为排版基础，缩进与 Tab 策略使用编辑行为偏好。
3. `IndentWidth`、`TabWidth`、`ContinuationIndentWidth` 是独立的列数，不能互相替代。

“缩进宽度 4”是一层缩进的步长，不是强制把所有已有行的前导空白改成 4。普通换行保留当前行原有缩进；完整规范化由格式化完成。项目显式设置 2 列时，两条路径都采用 2 列，这是项目规则，不是默认值失效。

### 复用与模块边界

- `core/config` 负责偏好与兼容迁移；`core/compile` 负责有效格式化策略和后端查询。
- `editor-kit/editor-view` 仅使用注入的 `EditorConfig` 和共享 visual-column helper，不反向依赖宿主偏好或 i18n。
- 宿主复用 `Prefs.editorSettingsFlow`、FileManager watcher、既有条件替换和 `CrashLogPrivacyClassifier`。
- 崩溃页复用 `TinaIDETheme`、`TinaPrimaryButton` 和中英文资源；不新增第三方依赖。
- 不强改用户项目配置，不自动重跑崩溃程序，不清用户数据，不提交或推送子模块指针。

## 已完成验证

| 范围 | 套件 | 用例 | failures / errors / skipped |
| --- | ---: | ---: | --- |
| `core/config` Debug 单测 | 2 | 7 | 0 / 0 / 0 |
| `core/compile` Debug 单测 | 3 | 15 | 0 / 0 / 0 |
| `editor-kit/editor-view` 独立构建单测 | 4 | 67 | 0 / 0 / 0 |
| `app` Release 定向单测 | 5 | 13 | 0 / 0 / 0 |
| 合计（不重复计算旧宿主单测） | 14 | 102 | 0 / 0 / 0 |

宿主 Release 单测包括来源判定 6 项、Intent/manifest 3 项、上传状态 2 项、配置 watcher 筛选 1 项和源文件/文本条件回写 1 项。结果来自各模块明确的 `build/test-results` XML；最终宿主测试执行设置了 60 秒任务超时，依赖编译不冒充测试执行。

其他检查：

- 中英文资源及帮助说明同步；i18n 检查、定向 `git diff --check` 通过。
- Windows clang-format 18.1.6 实际执行无项目配置的 LLVM + 4 列策略通过。
- 最终 ARM64 APK 的必需 native 库、ELF 架构和许可随包门禁通过。
- 主 APK 与验收 APK 的签名有效且签名者一致。
- 最终构建执行了主 APK 和 AndroidTest APK 的 R8，主 APK 保留资源压缩；Release 构建成功，未递增版本。
- 所有新增源码、文档和中英文 XML 使用 UTF-8 无 BOM；构建日志指定 UTF-8。

### 最终产物

- 主 APK：`app/build/outputs/apk/arm64/release/TinaIDE-0.19.2-arm64-v8a-release.apk`
- 版本：`0.19.2`，ARM64 `versionCode=19032`。
- Build ID：`46c59a2a71-20261114082348`。
- SHA-256：`c86e68f12a96f00e3b80aa836436c4fbaa8d367546280a716acf7da7ec2fc385`。
- 验收 APK：`app/build/outputs/apk/androidTest/arm64/release/app-arm64-release-androidTest.apk`。
- 验收包名：`com.wuxianggujun.tinaide.release.test`。
- 最终构建日志：`app/indentation-crash-final-release.log`；R8 mapping 由现有插件本地归档。

## 真机状态与剩余验收

目标设备为 PMS110 / arm64-v8a，初始 IDE 为 0.19.1（19022）。首轮 0.19.2 修复包执行 `adb install -r` 返回 `Success`；当时验收 APK 安装返回设备错误 `-99`，随后无线 ADB 断开。

本次通过 mDNS 发现真机的新无线端口，并再次覆盖安装最终补充包。安装返回 `Success`；手机 `base.apk` 的 SHA-256 与上面的最终产物一致，确认不是只核对相同版本号的首轮包。版本为 0.19.2（19032），安装 flags 不含 `DEBUGGABLE`。`MainPortalActivity` 冷启动返回 `Status: ok`；移除临时验收包后再次启动通过，并观察 5 秒，宿主 PID 保持 31115。

验收 APK 此次安装成功，但 `am instrument` 在任何测试用例启动前返回 `shortMsg=Process crashed.`。AndroidRuntime 诊断为 `AndroidJUnitRunner.onCreate` 中缺少 `androidx.tracing.Trace`，Release/R8 测试类路径仍需处理。普通 IDE 启动路径已通过，不能把测试框架初始化失败说成用户程序崩溃，也不能把 shell 返回码 0 或 `INSTRUMENTATION_CODE: 0` 视为测试通过。

测试前确认验收包未安装；本次新装的 `com.wuxianggujun.tinaide.release.test` 已卸载，正式 IDE 保留。无线端口变动后重新发现并连接了同一真机；没有卸载 IDE、清数据或操作模拟器。以下 7 项设备用例仍未执行：

| 设备套件 | 用例 | 实际验证目标 |
| --- | ---: | --- |
| `FormattingIndentationInstrumentationTest` | 1 | 真实 Native formatter 与 EditorState 的全局 4 列、Tab、项目 2/Tab 8/续行 4；仅使用 cache 夹具，结束恢复偏好 |
| `CrashTaskIsolationInstrumentationTest` | 2 | 用生产启动器和模拟报告验证用户来源及缺失来源；返回和系统 Back 不清宿主任务、不改变宿主 PID；获取默认折叠界面截图 |
| `CrashScreenUiInstrumentationTest` | 4 | 用户/宿主/未知组件按钮区分、日志折叠和同一报告的新事件重置 |

设备测试中的崩溃报告是模拟来源，不实际触发 SIGSEGV。即使这些测试通过，也不能据此声称真实 native 信号崩溃后的完整恢复、所有字体大小/窗口尺寸布局或 X11 桌面已验收。

### 修复验收类路径后的执行入口

在仓库根目录使用 Git Bash 构建，不通过 PowerShell 向 `.bat` 传递未引用的带点 `-P` 参数：

```bash
JAVA_HOME='D:/Programs/Java/jdk-17' ./gradlew \
  :app:assembleArm64Release :app:assembleArm64ReleaseAndroidTest \
  -Ptina.instrumentationBuildType=release \
  -Ptina.autoIncrementReleaseVersion=false --no-daemon --console=plain --max-workers=4
```

安装及测试必须从 `adb devices -l` / `adb mdns services` 中确认真机的当前序列号，并在每个命令显式使用 `-s`。覆盖安装主 APK；安装验收 APK 时加 `-t`，不使用卸载/清数据绕过错误。须确认完整 JUnit 结果和实际用例数量后才报告通过；本次 0 项执行，不能声称 `OK (7 tests)`。测试通过后再拉取 `crash_screenshot`、核对宿主 PID/任务和新布局，最后启动 `MainPortalActivity` 做正常启动验证。只清理本次新装的验收包，主 IDE 保留。

无线安装、设备 APK 哈希、正常启动、测试框架失败以及临时包清理的记录位于 `app/build/verification/indentation-crash/`。

如需回滚，只回滚本次修复补丁，保留工作区其他未提交改动；不回退或重置用户项目和偏好数据。
