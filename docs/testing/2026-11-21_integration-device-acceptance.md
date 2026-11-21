# 2026-11-21 集成与 PMS110 真机验收

> 下文先保留首轮集成基线；末节记录后续 X11 源码归档后的双 ABI 复验与当前发布判断。

## 范围与结论

本记录覆盖 TinaIDE 主工作区与 vcpkg-release-qa 工作区的代码整合、ARM64 Debug 安装以及本轮可复现的设备与自动化检查。测试结果严格按实际结论区分；超时和缺少 JUnit 报告的用例不计为通过。

本次安装的是 0.19.5 (19062) ARM64 Debug 包，不是 Release/R8 验收。覆盖安装保留既有应用数据。最终核验的 PMS110 上 base.apk SHA-256 与本地 APK 相同：

6df597aebd0b25850514604ce6270e251e644042fcf4185ecd89b113540ea2b2

## 真机安装与启动

- 设备：PMS110，ADB serial 192.168.2.185:35827。
- App：app/build/outputs/apk/arm64/debug/TinaIDE-0.19.5-arm64-v8a-debug.apk，SHA-256 6df597aebd0b25850514604ce6270e251e644042fcf4185ecd89b113540ea2b2。
- AndroidTest：app/build/outputs/apk/androidTest/arm64/debug/app-arm64-debug-androidTest.apk，SHA-256 9e97c1f573ba8edeeb4ae8c37b4bb55fede285529e9867f735ec5d8934070b8d。
- 本地 APK 与设备已安装 base.apk 的 SHA-256 相同；安装证书 SHA-256 为 e1ef5c24ca8349376e6f3ebf0d9b93a7c44c20d7bb9ae2144f053abb649fd45c。
- 最后再次启动 com.wuxianggujun.tinaide/.ui.MainPortalActivity，Activity 返回 Status: ok，应用进程仍运行（PID 21295）。本轮最终启动核验的 crash buffer 未发现 fatal 项。

## 已通过

- :app:assembleArm64Debug 与 AndroidTest APK 构建成功；AndroidTest 的 Kotlin 编译修复后也成功。
- 本地 APK 检查：check_apk_native_libs.py --abi arm64-v8a 通过；check_distribution.py 通过。
- 主仓库 compileDebugUnitTestKotlin / compileArm64DebugUnitTestKotlin 编译通过。
- core:config 的 EditorIndentationPreferencesTest 与 EditorFontSizePreferencesTest 分别重跑通过。
- tools/testing/run_editor_runtime_regression.py 的 Python 单元测试：22 项通过；py tools/i18n/check_all.py 通过。
- PMS110 定向设备回归 4 项通过，均退出码 0：asm、function-flow-loop、function-flow-switch、function-flow-invalid。原始结果：app/build/verification/integration-20261121/device/results.json。
- 单独的 7zip ARM64 构建/运行检查曾在设备上获得程序输出 7zip=ok 与 asm=42，退出码 0；这只证明该独立检查中的二进制运行结果，不等同于后述完整 instrumentation 用例通过。

## 未通过完整验证 / 超时

- 完整定向单测批次在 :core:config:testDebugUnitTest 遇到约一分钟的任务超时；随后两个配置偏好测试类分开运行成功。其余模块未因那次批次结果而宣称通过。
- PMS110 上 7zip instrumentation 在 60 秒限制内超时；不能计作 JUnit 通过。此前日志记录构建到达 CMake/Ninja 阶段且设备上的独立程序输出正常，但没有完整 instrumentation 成功报告。
- 新增的 market-reuse 真机用例在 60 秒限制内超时，没有可用 JUnit 报告，未计为通过。
- 中英文 course assessment 真机用例均在 60 秒限制内超时，没有可用报告，未计为通过。
- 因此本次没有宣称 market-reuse、course assessment 或 7zip instrumentation 已完整通过，也没有宣称 Release/R8 或完整设备测试套件通过。

## 本轮代码与仓库状态

- VcpkgManagedDeviceTest 的 market reuse 用例改为独立 market-reuse-run 构建目录，避免 AndroidTest 跨模块访问 internal EffectiveBuildConfigResolver。
- run_editor_runtime_regression.py 的 managed-vcpkg suite 注册 market-reuse phase。
- editor-kit 子模块缩进修复已先提交、推送，并确认提交可从远端 main 访问；主仓库依其项目规则再更新 gitlink。
- 日志、安装校验结果与设备回归结果保留在被忽略的 app/build/verification/integration-20261121/，不作为源码提交。

## 后续建议

在单独排查 instrumentation 执行时长后重跑 market-reuse、7zip 与课程用例；需要 Release/R8 或更广的回归时，按对应验收清单单独执行，勿以本次 Debug 安装结果替代。

## X11 源码归档后的双 ABI 复验与发布判断（2026-11-21 后续补充）

本节是前述首轮集成验收之后的复验，构建源码为主仓库 `ec297288713b27f2ae3b2730d2d381575ca22355`，
termux-x11 fork 为 `3dd15284b363d534817c4ad5f1e706a4cdc733f5`。两个提交均已先后推送到各自远端。
libepoxy、libx11、libxkbfile、libxtrans、pixman、xkbcomp、xserver 已作为普通源码目录提交到
termux-x11 fork；外层 external/termux-x11 继续作为主仓库的单个子模块。当前仅剩主工作区，旧 worktree 已移除。

### 构建、签名和安装

ARM64 的 Lorie native 任务与整包构建成功；x86_64 的 Lorie native、App 与 AndroidTest APK 构建成功。
两套实际安装 APK 均通过 check_apk_native_libs.py 和 check_distribution.py，设备 base.apk 的 SHA-256 与各自本地验收包一致。

| 目标 | 安装版本 | 安装包 SHA-256 | 启动 |
| --- | --- | --- | --- |
| PMS110 / ARM64 | 0.19.5 (19062) | `59fca9dac23654030b69126067c7216e4e0f976fc1073403e8903436de0b3770` | Status: ok，最终 crash buffer 未发现该进程 fatal |
| emulator-5580 / API 34 / x86_64 | 0.19.5 (19061) | `29ca7b82ede77d7174d721ec3ec37e83ff6ab2e9224f6fda514b6beee881296f` | Status: ok，最终 crash buffer 未发现该进程 fatal |

真机覆盖安装使用项目既有 SignTestArtifact 工具签署的 Debug 验证包，以匹配手机现有安装证书并保留数据；
模拟器使用其现有 Debug 证书对应的原始 Debug 构建包覆盖升级，EGL 属性为 emulation。
真机复用了既有 AndroidTest APK（本次 vendor 变更未修改 instrumentation 源码）；模拟器的 AndroidTest APK 本轮重新构建。
签名一致不代表进行了 minified Release/R8 验收。本轮未递增正式版本，未创建发布 tag。

PowerShell 构建入口（Gradle property 整体加引号）：

```powershell
.\gradlew.bat '-Ptina.devAbi=arm64' ':termux-x11-lorie:externalNativeBuildDebug' ':app:assembleArm64Debug' --no-daemon --console=plain
.\gradlew.bat '-Ptina.devAbi=x86_64' ':termux-x11-lorie:externalNativeBuildDebug' ':app:assembleX86_64Debug' ':app:assembleX86_64DebugAndroidTest' --no-daemon --console=plain
```

### 已通过与未通过的设备用例

- PMS110：ASM 与 function-flow-loop、function-flow-switch、function-flow-invalid 共 4 项通过。
- PMS110：7zip instrumentation 本轮完成并通过，实际输出为 `7zip=ok` 与 `asm=42`；本节结果补充前述首轮超时记录，不扩大到市场复用用例。
- x86_64 模拟器：上述 ASM 与 3 项函数流程回归共 4 项通过，均获得实际 JUnit 成功报告。
- x86_64 模拟器市场竖屏 UI：断言失败，报告 `Market tab Plugins must fit: lines=1, overflow=true, size=82 x 29`，位置为 MarketSearchInstrumentationTest.kt:87。该项不是超时，不能计为通过。
- PMS110 市场依赖复用 market-reuse：60 秒内未完成，没有有效成功报告。
- PMS110 中英文课程考核 assess-zh、assess-en：均在 60 秒内未完成，没有有效成功报告。

本次新增设备用例统计为 9 项通过、1 项断言失败、3 项超时；两端安装、哈希与启动检查单独统计。
超时尚未定位到确定根因，不能据此宣称业务逻辑已经失败，也不能视作验收通过。

### 发布判断

当前暂不建议正式发布。先定位并修复或验证市场标签溢出，再收敛市场依赖复用与课程考核的超时；
随后针对同一候选源码完成实际 minified Release/R8 包的关键路径验收，尤其是编辑、编译、运行/停止、
市场安装后运行复用，以及升级保留数据。X11/XFCE 完整桌面链路本轮未验证，不能把 Lorie 编译成功扩大为桌面功能已通过。
当前结果只覆盖上述定向用例，不代表所有模拟器、真机或 App 功能均已测试。

构建日志、原始 instrumentation 报告、签名与安装校验、最终启动检查、模拟器截图保留在被忽略目录
`app/build/verification/x11-vendor-20261121/`；所有本轮新增说明与记录以 UTF-8 无 BOM 写入。
