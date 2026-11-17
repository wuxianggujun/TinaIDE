# 2026-11-18 vcpkg、市场与课程续接验收

## 当前结论与范围

本记录续接 AegisHub 迁移聊天中尚未提交的 vcpkg、市场、课程和反馈文本草稿改动。AegisHub 迁移及旧服务清理已经推送，参见 [既有迁移验收](2026-11-16_aegishub-migration.md)。不重新引入旧上传后端、旧 vcpkg 手动路径或预编译配置模式。

当前代码与双 ABI `0.19.5` Release 已完成构建和验收：9 模块、772 项单测通过，完整 Debug 功能回归通过，最终签名 Release 的 5 项黑盒界面回归通过。Release 正常开启 R8 与资源压缩，没有为了测试关闭优化或复制业务代码进测试 APK。PMS110 的无线 ADB 在覆盖安装前掉线，本次 APK 尚未装到真机；已请求重新连接。编辑器缩进/格式化及 TinaEditor 子模块的独立未提交改动保留，不纳入本次提交。

## 失败、根因与修复

| 发现 | 证据 | 修复路径 |
| --- | --- | --- |
| raylib 课程报缺少 `libc++_shared.so` | 同一专用模拟器复现失败，修复后绘制测试通过 | 测试先固定构建 sysroot，再传入其 `LD_LIBRARY_PATH` 并复用正式图形启动器；不修改生产解析器迁就测试 |
| 导出共享库中途取消会覆盖原库，首次取消会暴露半成品 | 修复前 6 项导出测试中 2 项失败；修复后 8 项全部通过 | 复用 `PackageInstallCoordinator` 的共享根写锁、唯一暂存目录及发布/回滚；取消或复制失败保留已发布目录 |
| 新课程资产被文档门禁判断为未注册 | 8 个课程文件与动态 `$id.md` 注册不匹配 | 注册显式的文件名，文档 ID 由文件名派生，保持单一事实源；43 篇中英帮助资产检查通过 |
| 更新用例把目录更新与源码准备合在一个 50 秒窗口 | 新修订实际下载 5,507,430 字节；目录更新 28.712 秒、源码准备 29.951 秒，总和超过旧截止 | 拆为独立 `update` / `sources` 阶段，每阶段仍为 50 秒，并保存下载进度、耗时与失败类型；不延长超时算通过 |
| 完整主页测试无法按图标描述定位 Material 3 导航项 | 合并语义树无匹配，未合并树存在图标；失败发生在首次导航点击 | 为同一导航项提供由 route 派生的稳定标识，保持四项导航选中、关键词及 SavedState 恢复断言 |
| Intel host GPU 下 SDL 课程启动但画面全黑 | 设备 Awake；独立 SDL 进程已加载库、输出 window ready 并持续呈现；桌面截图正常 | 不改 APK/课程，只把本任务 AVD 切换到 SwiftShader；四项中英 SDL3/raylib 绘制与退出均通过。此结果不是 Intel GPU 或 ARM64 真机图形兼容性验收 |
| Release 白盒启动器引用已被 App 的 R8 移除的入口 | 先缺少 `androidx.tracing.Trace`，补充测试依赖后又缺少 `kotlin.LazyKt`；均未进入业务用例 | 不逐项保留生产代码迎合白盒测试。新增只依赖 Android SDK 的生命周期控制器，用 accessibility 实际点击、输入、截图及 Activity 重建验证原签名 APK；失败尝试不计为通过 |
| 一个 UI 用例同时验证所有导航、搜索和重建，超过原截止 | accessibility 快照有固定等待成本 | 将四导航与市场搜索拆为独立用例；SDK 控制器仍为 50 秒、宿主每项仍为 60 秒，不延长超时 |

共享库暂存会额外复制当前 ABI 的已发布文件，其他项目与 ABI 的库不会为了腾空间被删除。相同库的版本仍需项目自行统一，不能把共享目录视为依赖版本解析器。

## 已执行验证

- `:core:packages:testDebugUnitTest`：28 个套件、157 项，failures/errors/skipped 均为 0；其中共享导出 8 项。
- 专用 `TinaIDE_Vcpkg_QA_API34`、`emulator-5580`：中文/英文的 SDL3 与 raylib 课程均提取实际帮助正文编译，使用正式独立图形宿主启动，验证蓝色背景及橙色矩形后退出。
- 两个 ABI 的辅助工具快照：64 个 Debian 包、326 个 ELF，大小、SHA-256、架构及动态依赖闭包通过。
- i18n：中英资源同步及硬编码中文检查通过。资源、课程、脚本和本记录使用 UTF-8，无 BOM。
- 文档与直接文件操作门禁通过；新共享导出只清理本次未发布暂存目录，发布/备份回滚沿用现有能力。

开发验证的摘要见 [进度证据](evidence/vcpkg-market-course-2026-11-18/progress-results.json)。这部分先行 Debug 验证不能替代最终 Release/R8 或所有 ARM64 真机行为验收。

## 隔离回归记录

首轮隔离快照为 `83051dea10893ff566c7991e4d7cb9ce73f630de`，Debug `0.19.5 / 19061`，APK SHA-256 为 `52f6b98f6833cf21e793223d7189377307315260601a11c81403ff33c14f2a24`。原 `emulator-5580` 后来被另一会话的不同 AVD 使用；身份检查阻止了安装，专用 `TinaIDE_Vcpkg_QA_API34` 改用 `emulator-5582`。其他模拟器、构建和缓存未清理。

- 单测：packages 157、network 38、compile 276、project 74、help 23、feature packages 21、settings 143、tutorial 10、wizard 30；failures/errors/skipped 均为 0。
- 设备：15 项 vcpkg 场景、官方 API 200 探测、13 项中英基础课程与错误恢复、4 项图形课程、4 项 fmt 包场景通过。共享库消费者实际输出 `managed vcpkg: 42`，取消导出保留已发布文件哈希。
- APK：44 个 x86_64 原生库、16 KiB 对齐、签名及 7 个关键随包资产与检出源码字节一致，分发许可门禁通过。
- GPU 对照：[原始比较数据](evidence/vcpkg-market-course-2026-11-18/gpu-comparison.json)、[SDL3 实际帧](evidence/vcpkg-market-course-2026-11-18/sdl3-debug.png)、[raylib 实际帧](evidence/vcpkg-market-course-2026-11-18/raylib-debug.png)。APK、课程、内存 1536 MiB 和 4 核配置保持一致，只切换图形后端。
- 新版 Debug 市场用例通过 instrumentation stdout 返回 runId、实际方向、四项导航、列表高度及有界 PNG。最终 Release 改用下述独立 SDK 黑盒控制器；两类证据不混用。原回归门禁脚本的 20 项单测通过。

包含导航标识与新版取证的 `2b8a08749` 再次独立执行 9 模块单测，772 项全部通过；与 App 构建合并执行的尝试触发了包模块 60 秒任务截止，不计为通过。拆开执行后保持同一截止，通过的 XML 均来自新一轮实际执行，见 [单测摘要](evidence/vcpkg-market-course-2026-11-18/unit-results.json)。后续生产改动只涉及市场窄屏 Tab 与字号，没有改变上述业务模块。

## 最终 Release 验收

发布代码快照为 `f52612ed82fdd823d608d16a59da224b1c68eed9`，双 ABI 构建成功，用时 29 分 50 秒。验收与复制过程中最终 APK 哈希保持不变。

| ABI | versionCode | 原生库 | SHA-256 |
| --- | --- | --- | --- |
| ARM64 | 19062 | 46 | `3c7ce7a85d0228940b26701e07b25f211e7d79eeef54d4b9dcaa352c302f6eb8` |
| x86_64 | 19061 | 44 | `20fb04e16f94c8cee66f965bf7e3bb9e153aa30ff940dea39e1b9bc778ce3511` |

两个 APK 均通过签名、ABI、16 KiB ZIP 对齐和分发许可校验。7 个关键资产及每个 APK 的 86 篇中英帮助正文与检出源码逐字节一致。最终文件保留在主仓库标准 `app/build/outputs/apk/arm64/release/` 与 `app/build/outputs/apk/x86_64/release/` 中，不外置到 AppData。

在专用 `TinaIDE_Vcpkg_QA_API34`、`emulator-5582` 上，对**已安装 APK 的 SHA-256**与目标文件核对后，独立执行 5 项 Release 黑盒场景，全部通过：

- 四个首页导航实际点击与选中验证；重建后仍保留当前导航。
- 竖屏市场：三个 Tab、搜索词交接、Activity 重建恢复、关闭搜索。
- 横屏市场：同样验证搜索与恢复；实际 PNG 为 1280×720，列表高度 280 px / density 2 = **140 dp**。
- 关闭 Wi-Fi/移动数据后打开本地课程，下一课仍留在教程，重建后课程继续显示；结束后恢复网络。
- 反馈窗口及消息、联系方式文本重建恢复；只使用合成草稿，**没有提交反馈**。

SDK 控制器只调用平台生命周期/IME API，不加载 App 业务代码副本。请求横竖方向后校验实际视口；不会把横向桌面截图当作横屏。每项有独立 runId、原 APK 哈希及保存状态创建证明；SDK 50 秒和宿主 60 秒截止均不放宽。Tab 的单行几何门禁配合人工截图检查，不冒充 Compose `TextLayoutResult` 检查。

证据见 [最终 Release 摘要](evidence/vcpkg-market-course-2026-11-18/release-results.json)、[竖屏](evidence/vcpkg-market-course-2026-11-18/release-market-portrait.png)、[横屏](evidence/vcpkg-market-course-2026-11-18/release-market-landscape.png)、[离线课程](evidence/vcpkg-market-course-2026-11-18/release-tutorial-course.png)、[合成反馈草稿](evidence/vcpkg-market-course-2026-11-18/release-feedback-draft.png)。新增黑盒门禁的 10 项 Python 单测通过。

完整 vcpkg、13 项编译课程、4 项绘制课程及 fmt 包生命周期仍引用已完成的 Debug 功能证据，**没有重新标记为 Release 白盒通过**。ARM64 真机图形、用户自选附件、取消上传和实时崩溃上传状态不在本次已通过范围内。

## 复现入口

在仓库根目录执行目标模块测试：

```powershell
./gradlew :core:packages:testDebugUnitTest --tests "*VcpkgSharedLibraryTest" --no-daemon --console=plain
py -3 -X utf8 tools/i18n/check_all.py
py -3 -X utf8 tools/checks/check_documentation.py
py -3 -X utf8 tools/checks/check_direct_file_operations.py
```

构建并安装对应 ABI 的 App 与 instrumentation APK 后，使用已安装 SDL3/raylib 的专用模拟器：

```powershell
py -3 -X utf8 tools/testing/run_editor_runtime_regression.py --serial emulator-5582 --suite graphics-course
py -3 -X utf8 tools/testing/run_editor_runtime_regression.py --serial emulator-5582 --suite vcpkg-managed --case shared
py -3 -X utf8 tools/testing/run_editor_runtime_regression.py --serial emulator-5582 --suite vcpkg-managed --case cancel-shared
```

Debug 市场布局用例（先安装匹配的 Debug App 与 test APK）：

```powershell
py -3 -X utf8 tools/testing/run_editor_runtime_regression.py --serial emulator-5582 --suite market-layout --orientation portrait
py -3 -X utf8 tools/testing/run_editor_runtime_regression.py --serial emulator-5582 --suite market-layout --orientation landscape
```

最终 Release 黑盒入口（在仓库根目录；先安装上述 x86_64 Release，SDK 控制器需同签名）：

```powershell
py -3 -X utf8 tools/testing/build_release_ui_driver.py --sdk D:/Programs/Android/Sdk --java-home D:/Programs/Java/jdk-17
py -3 -X utf8 tools/testing/run_release_ui_blackbox.py --adb D:/Programs/Android/Sdk/platform-tools/adb.exe --serial emulator-5582 --expected-avd TinaIDE_Vcpkg_QA_API34 --driver-apk app/build/reports/release-ui-blackbox/sdk-driver/lifecycle-driver.apk --target-apk app/build/outputs/apk/x86_64/release/TinaIDE-0.19.5-x86_64-release.apk --case market-landscape --output app/build/reports/release-ui-blackbox/market-landscape
```

其余独立 `--case` 为 `main-navigation`、`market-portrait`、`tutorial-course`、`feedback-draft`。脚本拒绝非模拟器和不匹配的 AVD；不会把状态修改用例直接运行在用户真机上。Java、Python、资源及证据 JSON 使用 UTF-8，无 BOM；签名密码只通过子进程环境变量传递，不进入命令行或报告。

设备执行每项有 60 秒宿主截止；`cancel-shared` 必须在真实 `shared` 导出后运行，并校验全部已发布文件的哈希。断网、首次工具准备与安装取消用例需要明确设置网络/缓存前置条件，不通过旧证据或超时判定成功。

## 真机交付状态与边界

PMS110 的预检查确认旧包为 `0.19.4 / 19052`，签名一致；但安装前原无线连接已断开（地址保留在本地日志），设备列表和 mDNS 均无此真机，因此安装未执行，应用数据未清空。重新连接后只执行 ARM64 `adb install -r`，核对 UID、首次安装时间和新版本，再做短启动检查。不卸载主 App，不提交真实反馈/日志，也不把当前状态写成已安装成功。
