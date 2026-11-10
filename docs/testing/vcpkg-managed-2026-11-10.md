# vcpkg 自动管理验收：2026-11-10

## 修复范围

- 市场列表从项目配置中解耦。无项目、无工具目录时仍可展示并搜索随包的 2,912 个 ports。
- `VcpkgCatalogStore` 负责目录快照、下载和原子发布，复用现有 HTTPS 客户端、取消机制、严格 UTF-8 解码和归档边界检查。目录从压缩包流式读取；完整源码在安装时才展开，并在校验全部条目后使用系统 tar 加速。更新失败和取消不替换已有列表。
- `VcpkgRuntimeManager` 负责 ABI 原生 CLI 与 helper 下载。CLI 来自可追溯源码构建；helper 从官方 Termux 仓库按 SHA-256 下载，解压数据而不执行安装脚本。
- 本机模式不再暴露工具或依赖安装路径。依赖固定在 `files/workspace/<projectId>/vcpkg_installed`，市场、构建、运行/导出和增量输入收集共用路径策略。项目重命名保持目录，不同项目相互隔离。
- Debian 包的绝对链接转换为归档内相对链接，仍拒绝目录穿越。配置 Git 版本信息供 baseline 使用；构建成功后清理 buildtrees 与 packages staging。
- 原生 git/curl 使用与目录下载一致的显式或系统代理；代理和证书变量继续传入 port 构建子进程。
- 全部新增用户文案同步中英文，文本以 UTF-8 无 BOM 保存；应用帮助、开发文档和许可声明同步更新。

## 已完成的静态与模块验证

37 项定向单测通过，无失败或跳过：归档读取与路径 7 项、目录仓储 6 项、manifest/安装状态 15 项、CMake 集成 7 项、增量依赖 2 项。精确类名和结果在 [module-tests.json](evidence/vcpkg-managed-2026-11-10/module-tests.json)。

两种 ABI 各验证 32 个真实下载包和 163 个 ELF 文件：校验大小、SHA-256、归档路径、ABI 与动态库依赖闭包。证据在 `evidence/vcpkg-managed-2026-11-10/verify-runtime*.json`。

国际化检查、分发许可门禁、Python 执行器单测和差异空白检查已通过。没有添加新的反射/序列化依赖或宽泛 R8 keep 规则。

## 设备验收入口

```bash
python tools/testing/run_editor_runtime_regression.py \
  --adb D:/Programs/Android/Sdk/platform-tools/adb.exe --serial emulator-5556 \
  --suite vcpkg-managed --output .tmp/vcpkg-managed/device
```

分阶段覆盖 catalog、market-ui、update、runtime、configure、build、run、repeat、external。每项宿主上限 60 秒；非 UI 阶段验证本次 runId 和完整报告，拒绝旧结果。具体设备结果将在同目录证据中保存。

## 模拟器结果

API 34 x86_64，720×1280，280 dpi。最终 APK 的 SHA-256、大小、包内资产检查与执行记录都在 [证据目录](evidence/vcpkg-managed-2026-11-10/)。

| 场景 | 结果 | 总耗时 |
| --- | --- | ---: |
| 无网络、无项目，读取随包目录 | 通过，2,912 个包 | 23.14s |
| 更新后的市场搜索 UI | 通过，有截图，2,914 个包 | 13.97s |
| 在线更新及准备完整源码 | 通过，提交 `e529425f2f43d773d0a59517c8d89367d68bb7fd` | 41.81s |
| 自动工具准备，复用先前取消下载 | 通过 | 31.10s |
| fmt 12.2.1 首次安装和配置 | 通过，包含真实下载、Debug/Release 构建 | 37.91s |
| 项目编译 | 通过 | 5.07s |
| 运行 | 通过，输出 `managed vcpkg: 42` | 2.76s |
| 重复配置 | 通过 | 5.20s |
| 外部存储项目 | 通过，依赖实际安装在私有工作区 | 17.52s |
| 已是最新目录时再次更新 | 通过 | 5.76s |
| 断网后的缓存读取 | 通过 | 3.42s |
| 断网后的重复配置 | 通过 | 5.12s |

断网更新按预期报网络错误；缓存 SHA-256 前后相同，2,914 个条目不变。此前使用逐文件 Kotlin 解压完整仓库的版本超出 60s，已据此改为流式目录读取和延迟源码准备；最终版本没有放宽单项超时。工具首次下载在 50s 测试上限取消，保存已校验包；复测自动完成并清空临时下载。

首次 UI 截图曾被模拟器 System UI 的 ANR 弹窗遮挡；恢复系统界面后重新测试并目视检查最终截图，未把被遮挡截图作为 UI 验收。最终截图：[市场列表](evidence/vcpkg-managed-2026-11-10/vcpkg-no-project-search.png)。

成功后设备上的 buildtrees 约 672 KiB、packages staging 约 20 KiB，辅助下载缓存仅剩 8 KiB 空目录。本任务模拟器已关闭；ARM64 CLI 的临时构建对象通过 CMake clean 回收。保留 ABI 资产、对应源码来源、APK 与必要验收证据。

真机连接在本轮重新检查时不在线；先前安装的 0.19.1 Release 不包含本轮新改动，不能作为本轮自动管理功能的真机验收。
