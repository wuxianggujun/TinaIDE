# 2026-11-21 市场布局与课程回归优化

本轮继续使用主工作区 `dev`，基线为 `11899d8dc`，没有新建 worktree。只构建、覆盖安装和验证 x86_64 Debug；按用户要求不执行真机测试，也没有执行 Release/R8 构建、递增版本或创建发布 tag。

## 修复依据与边界

### 市场标签：统一文字控件与段落的宽度

旧包的 `Plugins` 断言稳定复现为 `overflowWidth=true`，但进一步诊断显示：文字控件宽 82px、段落宽 184px、字形右边界 80.2px，且没有省略号或纵向溢出。因此不能把原报告直接解释为字形被截断。

`MarketTabRow` 为紧凑标签分配实际可用宽度，为横屏图标与文字组合分配有界的文字宽度；按当前字体和语言测量标签，窄窗口或大字体放不下时使用已有 Material 3 可滚动标签。保留单行、无溢出断言，不通过降低字号或删除断言放行。

原搜索、Tab 切换、导航、状态恢复和横屏列表高度检查不变。新增矩阵覆盖中英文、240/320dp、1/1.5/2 倍字体，共 12 组；每个标签仍检查行数、横纵溢出和省略状态。Locale 和字体只在测试 CompositionLocal 中覆盖，不更改用户全局设置。

### 课程考核：独立 JUnit 阶段与取消清理

原单个用例同时执行保存、正确输出、编译失败、错误输出、无限循环取消、运行超时及重试，容易挤占整个用例时限。现在拆为四个独立 `@Test`，共用生产 `CompilerViewModel`、`CompileProjectUseCase` 和隔离项目准备逻辑：

- `save`：真实未保存编辑必须落盘，五项输出检查通过，进度可重新读取。
- `reject`：编译错误不能使用旧可执行文件，错误输出不能通过，之后可重试。
- `cancel`：无限运行的进程必须被取消，构建 gate 释放，之后可重试。
- `timeout`：五秒运行截止时间必须终止进程，gate 释放，之后可重试。

每个阶段先建立真实成功基线，不用人工写入的成功标记代替执行。保留协程 50 秒、JUnit 55 秒、主机 60 秒的分层截止时间；清理使用 `NonCancellable`，不因取消而跳过编辑器、进程和诊断收尾。

### 依赖复用：不以 vcpkg 英文日志作为协议

首轮拆分复验中，`market-install` 通过，`market-reuse-run` 在约 2.3 秒的配置之后因旧英文日志断言失败。实际日志列出依赖已安装，并报告安装检查在 48.2 微秒完成；新版本的措辞不再是旧断言要求的那一句。

最终验证拆为安装和运行两个阶段，用同一个随机 fixture id 关联。运行前后检查已安装库的设备号、inode、ctime、mtime 及安装目录文件 SHA-256，并实际构建和运行 `managed vcpkg: 42`。检查覆盖配置之后以及构建/运行之后，不依赖人类可读日志措辞，也不只凭“配置成功”放行。

## 诊断与复用

- `RegressionStageTracker` 在进入阶段及执行输出期间持久化带 run id 的检查点，使用 `AtomicFile`，日志有大小限制；超时不必等到 finally 才有证据。
- 最终报告和检查点使用不同协议字段，不能把部分进度当作 JUnit 成功报告。
- 复用 `core:logging` 的 `LogTextSanitizer`，先脱敏凭据、URI 和用户路径，再写测试日志或发送诊断尾部。
- 运行器拒绝零测试、跳过、失败、超时、旧 run id、缺失检查和其他安装 fixture 的报告。
- 所有新增代码、报告和日志使用 UTF-8，无 BOM。UI 文案复用现有 `Strings` 资源，没有新增硬编码用户文案或第三方依赖。

## 验证记录

已覆盖安装最终验证包 `0.19.5 (19061)` x86_64 Debug，模拟器 `emulator-5580` / API 34。本地 APK 与设备 `base.apk` 的 SHA-256 相同，冷启动返回 `Status: ok`，最终该进程 crash buffer 未发现 fatal。安装 APK SHA-256：

```text
72cbc2b7e5b4725c54ed73f0db0c84f6b73f7d928098c72469084ac169ca175f
```

| 检查 | 本轮记录 |
| --- | --- |
| Debug App 与 AndroidTest Kotlin/打包 | 构建成功 |
| APK native ABI 与 distribution 门禁 | 通过 |
| 市场竖屏、横屏搜索与恢复 | 各 1 项通过，截图已检查 |
| 中英文/窄窗口/字体标签矩阵 | 12 组通过（1 个 JUnit 用例） |
| 中英文考核四阶段 | 共 8 项通过 |
| 依赖安装与库文件身份复用 | 安装、复用运行 2 项通过；inode/ctime/mtime/SHA-256 均保持不变，程序实际输出 `managed vcpkg: 42` |
| Python 运行器 | 37 项通过 |
| i18n、文档、静态差异 | 各自检查通过，不以编译替代测试 |

本轮最终所选设备用例共 **13 项通过，0 项失败，0 项超时**；12 组标签矩阵计作一个 JUnit 用例。统计使用最终目录中的当前 run id 报告，不计入基线与过渡候选的失败项，也不把原真机超时记录改写成真机通过。

最终结果目录为 `final-market-portrait/`、`final-market-landscape/`、`final-tab-labels/`、`verified-course-en/`、`verified-course-zh/` 和 `file-identity-market-reuse/`，统一归档在下述验证根目录；`summary.json` 汇总了用例、安装哈希和最终启动检查。

完整日志、当前 run id 的 JSON、安装核对及截图保留在被忽略的 `app/build/verification/market-course-20261121/`。基线、过渡候选和最终用例分目录，不混用失败记录与成功报告。

## 可复现命令

在 TinaIDE 仓库根目录、没有其他 Gradle 构建的情况下运行。Gradle property 在 PowerShell 中整体加引号：

```powershell
.\gradlew.bat '-Ptina.devAbi=x86_64' ':app:assembleX86_64Debug' ':app:assembleX86_64DebugAndroidTest' --no-daemon --console=plain
```

以下运行器每个所选阶段均有独立时限；旧名称 `assess-en` / `assess-zh` 展开四个阶段，`market-reuse` 展开安装和复用两个阶段：

```powershell
$adb = 'D:/Programs/Android/Sdk/platform-tools/adb.exe'
py -X utf8 tools/testing/run_editor_runtime_regression.py --adb $adb --serial emulator-5580 --suite market-layout --orientation portrait --output app/build/verification/market-course-20261121/manual-portrait
py -X utf8 tools/testing/run_editor_runtime_regression.py --adb $adb --serial emulator-5580 --suite market-layout --case market-landscape --orientation landscape --output app/build/verification/market-course-20261121/manual-landscape
py -X utf8 tools/testing/run_editor_runtime_regression.py --adb $adb --serial emulator-5580 --suite course --case assess-en --output app/build/verification/market-course-20261121/manual-en
py -X utf8 tools/testing/run_editor_runtime_regression.py --adb $adb --serial emulator-5580 --suite course --case assess-zh --output app/build/verification/market-course-20261121/manual-zh
py -X utf8 tools/testing/run_editor_runtime_regression.py --adb $adb --serial emulator-5580 --suite vcpkg-managed --case market-reuse --output app/build/verification/market-course-20261121/manual-reuse
py -X utf8 -m unittest discover -s tools/testing -p 'test_editor_runtime*.py'
py -X utf8 tools/i18n/check_all.py
git diff --check
```

单独运行 `market-reuse-run` 必须传入成功 `market-install` 结果中的 `--market-fixture-id`，否则运行器在启动设备测试前拒绝请求。不得拿旧安装记录或不同 fixture 的库作为本轮成功证明。

本轮没有改变生产编译/运行策略、编辑器内核、子模块指针或 API Key 边界；这些定向 Debug 结果不扩大为真机、Release/R8、完整图形运行或 X11/XFCE 桌面已验收。
