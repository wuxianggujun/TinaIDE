# 2026-11-21 集成与 PMS110 真机验收

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
