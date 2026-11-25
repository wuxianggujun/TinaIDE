# 编辑器可靠性改进与验证入口

本次改动将发布许可、核心回归、草稿恢复、分屏和诊断任务生命周期、调试传输、设备性能采样接到现有模块。具体测试执行结果以本次任务交付说明为准。

## 模块边界与数据流

- `feature/editor`：DocumentSession 读取稳定编辑快照，独立于原文件自动保存，约每 2 秒向应用私有 no-backup 目录原子写入 UTF-8 草稿。恢复入口在 UI 加载磁盘文件之前，源文件指纹与记录不一致时进入现有冲突处理流程。
- `app`：EditorDocumentSessionCoordinator 连接恢复状态与现有编辑器宿主；EditorSplitPaneState 负责分屏有效标签、焦点和选择收敛，宿主负责实际切换。
- `core/editor-lsp`：LspDiagnosticsScheduler 负责同标签任务替换、取消与过期结果屏蔽，沿用宿主状态锁使关闭标签与发布结果互斥。
- `core/debug`：LldbDebugger 负责增量 UTF-8 解码、唯一 prompt 与命令串行化。超时、EOF 使当前会话失效，停止不会被等待中的命令阻塞；native 与 PRoot 只在进程创建边界分流。编译报告传递后端和工具链 ID，宿主不自行猜测。
- `tools/checks`、CI：PR 自动运行核心回归；发布构建依赖核心回归与许可证门禁。分发策略记录缺失、损坏、未知状态均阻断。RikkaHub 已依据 `docs/licensing/rikkahub-agpl-review.md` 标为 reviewed，并校验实际 LICENSE 哈希、随包全文及源码链接；缺失或不匹配仍阻断。普通本地 Debug 开发构建不受分发脚本限制。

## 验证与验收

### 2026-11-08：文件完整性与包管理补充回归

本轮只完成源码和静态验证，没有执行 Gradle、重新生成 APK 模板或安装手机。以下用例已补充，待允许编译时执行：

| 问题 | 回归入口与预期 |
| --- | --- |
| ASCII 前缀后 GBK 被误读 | `FileCharsetDetectorTest`：12 KB ASCII 后的中文正确识别；BOM 指定编码的非法数据拒绝读取 |
| 底层替换非法字符 | TinaEditor `RopeTextBufferTest`：加载失败不改变文本和撤销记录，GBK 保存 emoji 不覆盖原文件 |
| 跨文件编辑取消 | `WorkspaceEditTransactionTest`：写入后、切回调用线程前取消，也能回滚；并发修改导致恢复失败时报告 |
| 卸载残留更新状态 | `LocalInstallStateStoreTest`：卸载清除对应平台更新，孤立旧记录和迟到响应不恢复已安装状态 |
| 双平台更新覆盖 | `PackageManagerRevisionTest`：同一包的 Android/Linux 都进入更新列表 |
| 批量结果虚报 | `PackageManagerViewModelTest`：混合成功/失败准确统计，取消停止后续项，失败更新保留 |
| 目录分页遗漏 | `PackageManagerRevisionTest`、`PackageManagerViewModelTest`：151 项目录中末项可搜索 |
| vcpkg 换库仍复用旧程序 | `VcpkgBuildInputsTest`、`BuildPlannerTest`：同名同大小同时间戳但内容变化仍触发重新配置/构建 |

```bash
./gradlew :feature:editor:testDebugUnitTest --tests '*FileCharsetDetectorTest' --tests '*DocumentSessionTest' --no-daemon --console=plain
./gradlew :core:packages:testDebugUnitTest --tests '*LocalInstallStateStoreTest' --tests '*PackageManagerRevisionTest' --no-daemon --console=plain
./gradlew :feature:packages:testDebugUnitTest --tests '*PackageManagerViewModelTest' --tests '*PackageDialogStateTest' --no-daemon --console=plain
./gradlew :core:compile:testDebugUnitTest --tests '*VcpkgBuildInputsTest' --tests '*BuildPlannerTest' --no-daemon --console=plain
./gradlew :app:testArm64DebugUnitTest --tests '*WorkspaceEditTransactionTest' --no-daemon --console=plain
```

TinaEditor 的严格编解码在 `editor-kit/` 子模块内修改，需要在该目录运行 `./gradlew :core:text-engine:testDebugUnitTest --tests '*RopeTextBufferTest' --no-daemon --console=plain`。提交时仍遵守先提交/推送子模块，再更新主仓库 gitlink 的顺序。

此事务回滚处理协程取消和可捕获异常；不能保证进程被强制终止时跨多个文件原子恢复。源码、资源、帮助文档均以 UTF-8 保存。

### 既有验证入口

先运行 Python 与维护检查，再运行所属模块测试，最后宿主 Debug 集成。所有代码和测试用例写完后执行这些命令：

```bash
python -m unittest discover -s tools/checks -p 'test_*.py'
python tools/checks/check_all.py --include-i18n
python tools/checks/check_distribution.py
./gradlew :feature:editor:testDebugUnitTest :core:debug:testDebugUnitTest :core:editor-lsp:testDebugUnitTest :core:compile:testDebugUnitTest --no-daemon --console=plain
./gradlew :app:testArm64DebugUnitTest --tests '*EditorSplitPaneStateTest' --tests '*EditorContainerStateTest' --tests '*CompileActionsHelperTest' :app:compileArm64DebugKotlin --no-daemon --console=plain
```

同步纯 AGPL 许可的当前检出应通过许可证检查；缺失子模块、正文哈希变化或随包声明不一致应失败。此处核心回归无需运行 Release 验证，也不要改写版本或映射文件。设备测试按 [设备基线](editor-device-baseline.md) 执行；没有真机数据前，native LLDB 与 X11 不视为设备验证完成。

## 约束与回滚

草稿按文件完整路径 SHA-256 命名，不进入项目导出和系统备份。每份正文最多 10Mi 字符，JSON 记录最多 64MiB；存储/容量错误会保留上一份记录并提示手动保存。进程在最近一个检查点前退出时可能丢失该时间窗口内的修改；未提供跨文件草稿浏览器、缺失源文件自动重建或加密源码保险库。

恢复失败保留记录，成功保存/明确丢弃清理记录。格式版本不兼容时不自动转换。若撤回恢复功能，应保留 no-backup 中现有记录供后续恢复，不能作为缓存批量清理。CI 改动可独立回滚，但不得绕过许可校验；回退到旧受限许可时必须重新阻断。状态重构未改变模块依赖方向；新增 native 调试仅依赖已有 ndk 模块，没有新增第三方库。
