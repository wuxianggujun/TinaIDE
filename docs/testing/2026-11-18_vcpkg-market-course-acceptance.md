# 2026-11-18 vcpkg、市场与课程续接验收

## 当前结论与范围

本记录续接 AegisHub 迁移聊天中尚未提交的 vcpkg、市场、课程和反馈文本草稿改动。AegisHub 迁移及旧服务清理已经推送，参见 [既有迁移验收](2026-11-16_aegishub-migration.md)。不重新引入旧上传后端、旧 vcpkg 手动路径或预编译配置模式。

当前为开发候选：包模块 157 项测试与中英四项 SDL3/raylib 图形课程测试通过；最终提交快照、模拟器完整回归和 ARM64 Release 验证仍在进行。编辑器缩进/格式化及 TinaEditor 子模块的独立未提交改动保留，不纳入本次提交。

## 失败、根因与修复

| 发现 | 证据 | 修复路径 |
| --- | --- | --- |
| raylib 课程报缺少 `libc++_shared.so` | 同一专用模拟器复现失败，修复后绘制测试通过 | 测试先固定构建 sysroot，再传入其 `LD_LIBRARY_PATH` 并复用正式图形启动器；不修改生产解析器迁就测试 |
| 导出共享库中途取消会覆盖原库，首次取消会暴露半成品 | 修复前 6 项导出测试中 2 项失败；修复后 8 项全部通过 | 复用 `PackageInstallCoordinator` 的共享根写锁、唯一暂存目录及发布/回滚；取消或复制失败保留已发布目录 |
| 新课程资产被文档门禁判断为未注册 | 8 个课程文件与动态 `$id.md` 注册不匹配 | 注册显式的文件名，文档 ID 由文件名派生，保持单一事实源；43 篇中英帮助资产检查通过 |

共享库暂存会额外复制当前 ABI 的已发布文件，其他项目与 ABI 的库不会为了腾空间被删除。相同库的版本仍需项目自行统一，不能把共享目录视为依赖版本解析器。

## 已执行验证

- `:core:packages:testDebugUnitTest`：28 个套件、157 项，failures/errors/skipped 均为 0；其中共享导出 8 项。
- 专用 `TinaIDE_Vcpkg_QA_API34`、`emulator-5580`：中文/英文的 SDL3 与 raylib 课程均提取实际帮助正文编译，使用正式独立图形宿主启动，验证蓝色背景及橙色矩形后退出。
- 两个 ABI 的辅助工具快照：64 个 Debian 包、326 个 ELF，大小、SHA-256、架构及动态依赖闭包通过。
- i18n：中英资源同步及硬编码中文检查通过。资源、课程、脚本和本记录使用 UTF-8，无 BOM。
- 文档与直接文件操作门禁通过；新共享导出只清理本次未发布暂存目录，发布/备份回滚沿用现有能力。

开发验证的摘要见 [进度证据](evidence/vcpkg-market-course-2026-11-18/progress-results.json)。这部分先行 Debug 验证不能替代最终 Release/R8 或所有 ARM64 真机行为验收。

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
py -3 -X utf8 tools/testing/run_editor_runtime_regression.py --serial emulator-5580 --suite graphics-course
py -3 -X utf8 tools/testing/run_editor_runtime_regression.py --serial emulator-5580 --suite vcpkg-managed --case shared
py -3 -X utf8 tools/testing/run_editor_runtime_regression.py --serial emulator-5580 --suite vcpkg-managed --case cancel-shared
```

设备执行每项有 60 秒宿主截止；`cancel-shared` 必须在真实 `shared` 导出后运行，并校验全部已发布文件的哈希。断网、首次工具准备与安装取消用例需要明确设置网络/缓存前置条件，不通过旧证据或超时判定成功。

## 后续验收边界

接下来从本次候选提交独立检出，执行相关模块及模拟器回归，构建正常启用 R8 的 ARM64 Release，验证分发、原生库、签名和真机覆盖安装，再推送最终结果。未提交用户设备反馈、上传真实日志或故意制造宿主崩溃。反馈测试只验证窗口和文本字段恢复，不扩大为自选附件、取消上传及实时崩溃上传状态已经修复。
