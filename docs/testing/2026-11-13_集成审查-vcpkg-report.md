# vcpkg 代理与取消集成审查（2026-11-13）

## 结论与范围

vcpkg 的市场入口、项目清单、Android CLI 和辅助工具均由应用管理，普通用户无需手工安装 vcpkg。本轮续接前一会话的未提交改动，修复完整归档校验未进入镜像回退、坏目录缓存误判已更新、Git 本地命令重复执行，以及批量取消摘要被清空的问题。18 个测试套件、118 项定向 JVM/Robolectric 测试通过，失败、错误、跳过均为 0；宿主和设备测试源码编译通过。

本报告仅覆盖上述链路和相邻市场回归，不是整个 IDE 无缺陷的结论。未构建/安装 APK、未执行设备或 Release/R8 验证；当前 ADB 无连接设备。历史 x86_64 fmt 安装、编译和运行证据见[内置管理验收](vcpkg-managed-2026-11-10.md)，不能替代本轮 APK 验收。

- 用户授权：续接会话 `01a1d04c-c296-7891-aa06-53b53804ffd3` 开发，原会话要求优化 vcpkg 与代理并检查逻辑错误。
- 基础提交：`46c59a2a7186e2bb9d5bfc55bdb941280956cf6c`，分支 `dev`，验证基于包含既有未提交工作的工作区。
- 工具：JDK 17.0.13、Gradle 9.6.0、Robolectric API 34、单 worker、Test task 超时 60 秒。编译时间不计入 Test task 的超时。
- 文档类型：集成审查，`flavor = null`；不涉及恶意样本、外部扫描或 IOC。
- [结构化结果与源文件/日志 SHA-256](evidence/vcpkg-proxy-2026-11-13/results.json)。

## 模块与调用链

- `core:network`：共用 `GitHubRegistryProxyConfig`、URL 候选排序、HTTPS 客户端及 `executeCancellable`。
- `core:packages`：`VcpkgCatalogSource` 获取候选，`VcpkgCatalogStore` 校验/缓存/原子发布，`VcpkgRuntimeManager` 准备 CLI/工具，`VcpkgRepository` 编辑项目清单。
- `core:compile`：沿用 native tina-toolchain 与 Android sysroot，生成 triplet，透传代理变量，安装成功后再配置 CMake。
- `feature:settings` / 市场宿主：共用代理对话框和配置，保存后刷新客户端；已开始的安装继续持有原环境。
- `feature:packages`：展示目录、依赖、安装输出及取消结果，不新增平行包管理器。

## Evidence → Finding → Path

### 证据

| ID | 观察日期 / 类型 | 来源 | 可复现摘要 |
| --- | --- | --- | --- |
| E-001 | 2026-11-13 / log | `.tmp/vcpkg-continuation-repro-boundaries.log`；SHA-256 `d52f61f1b949e8e3bece034e9adeddd9dcfef40a455de9bfa66e4fa6caab77b5` | 修复前 18 项中 4 项失败：gzip 截断、哈希不符、损坏目录缓存、本地 Git 命令重复执行 |
| E-002 | 2026-11-13 / log | `.tmp/vcpkg-continuation-batch-repro.log`；SHA-256 `2e375a202664cc393685b795f4e685d347c1afe7c82fe40e536df0825e5c9fcd` | 修复前 9 项中 3 项失败：批量安装/更新/卸载取消后 `BatchComplete` 被清空 |
| E-003 | 2026-11-13 / file + command | [结构化结果](evidence/vcpkg-proxy-2026-11-13/results.json) 中 `verification.suites` / `verification.sources` / `verification.logs` | 修复后 118 项通过；宿主和设备测试 Kotlin 编译通过；完整校验继续保留 |
| E-004 | 2026-11-13 / command | `py tools/i18n/check_all.py`、`py tools/checks/check_documentation.py`、`git diff --check` | 中英资源、35 + 35 帮助资产及差异检查通过，新增/修改源码 UTF-8 无 BOM |

E-001 / E-002 的日志保留在被搜索排除的 `.tmp` 中，未删除缓存或构建目录。其哈希和失败用例名已写入结构化结果。重新执行下文命令可验证修复后的行为；复现修复前失败需要撤回对应语义补丁，不需要连接真实镜像。

### 已验证问题与修复

| ID / 优先级 | 定位与影响 | 根因 / 触发条件 | 修复 / 测试证据 |
| --- | --- | --- | --- |
| F-001 / P1 | `VcpkgCatalogSource.download` → `VcpkgCatalogStore.obtainArchive`：坏镜像中断更新或首次准备源码 | 下载候选只验证 HTTP/gzip 文件头；完整归档和已知 SHA-256 校验在候选成功返回后执行，因此不会回退 | 校验回调进入每个候选尝试；gzip、路径/类型、SHA-256 校验成功才允许原子发布；全部候选失败保留旧列表，取消不尝试下一来源。E-001、E-003，status=validated，confidence=high |
| F-002 / P2 | `VcpkgCatalogStore.update`：损坏目录缓存长期无法修复 | `readLocal` 回落随包目录，但短路只检查缓存文件存在和 revision 相同 | 短路只接受成功解析的缓存；损坏缓存会重建。E-001、E-003，status=validated，confidence=high |
| F-003 / P2 | `VcpkgToolScripts.wrapper`：本地 Git 失败被执行两次，改变错误/副作用边界 | 任何 Git 命令都注入镜像配置并在失败时重新执行 | 精确识别带 `-C` / `-c` 等全局参数的子命令；只有 `fetch`、`clone`、`ls-remote` 重试，原参数及错误退出码保留。E-001、E-003，status=validated，confidence=high |
| F-004 / P1 | `PackageManagerViewModel.cancelInstall`：批量摘要消失、成功/未完成计数不可见 | `Main.immediate` 可在 `Job.cancel()` 内完成 finally 并发布摘要；按钮随后按更新后的类型清空 | 取消前保存任务类型，无活动任务时忽略迟到取消；保留摘要及未完成项。安装/更新/卸载及迟到取消覆盖。E-002、E-003，status=validated，confidence=high |

P1/P2 是项目迭代优先级，不把已被校验阻止的坏归档描述为成功代码执行。F-001 中路径穿越归档已验证无法写出托管目录。

### 路径

- P-001，path_type=callflow：市场更新/安装 → 最新代理配置与候选 URL → 可取消下载 → 完整校验（F-001，E-001/E-003）→ 原子发布；失败则下一候选，全失败保留旧目录，取消直接退出。
- P-002，path_type=callflow：批量操作 → 部分包完成 → 用户取消 → 记录取消前类型 → 协程清理并发布摘要（F-004，E-002/E-003）→ 保留成功数和未完成项以供重试。
- 剩余验证：真实设备 UI、安装子进程环境与系统 shell、ARM64 端到端和 Release/R8。

## 验证结果与运行命令

| 模块 | 套件数 | 测试数 | failures / errors / skipped |
| --- | ---: | ---: | --- |
| `core:network` | 3 | 13 | 0 / 0 / 0 |
| `core:packages` | 7 | 62 | 0 / 0 / 0 |
| `core:compile` | 3 | 15 | 0 / 0 / 0 |
| `feature:packages` | 4 | 21 | 0 / 0 / 0 |
| `feature:settings` | 1 | 7 | 0 / 0 / 0 |
| 合计 | 18 | 118 | 0 / 0 / 0 |

主集成构建：`BUILD SUCCESSFUL in 5m 46s`。最后将代理客户端测试使用的两个地址限定为本地回环 IP 后，单独重跑 network 13 项：`BUILD SUCCESSFUL in 1m 35s`。未使用真实服务器或凭据。

仅回归归档/缓存/Git 边界：

```bash
./gradlew :core:packages:testDebugUnitTest --tests '*VcpkgCatalogSourceTest' --tests '*VcpkgCatalogStoreTest' --tests '*VcpkgToolScriptsTest' -I tools/testing/single-worker-tests.init.gradle --configure-on-demand --offline --no-daemon --no-parallel --max-workers=1 --console=plain
```

完整定向回归与宿主编译（在仓库根目录；Windows PowerShell 使用 `gradlew.bat`，多行续行符用反引号或把命令合成一行）：

```bash
./gradlew :core:network:testDebugUnitTest --tests '*GitHubRegistryConfigTest' --tests '*GitHubRegistryProxyConfigTest' --tests '*OkHttpCancellationTest' \
  :core:packages:testDebugUnitTest --tests '*Vcpkg*Test' --tests '*PackageRegistryProtocolTest' \
  :core:compile:testDebugUnitTest --tests '*Vcpkg*Test' --tests '*BuildProcessRunnerTest' \
  :feature:packages:testDebugUnitTest --tests '*Package*Test' \
  :feature:settings:testDebugUnitTest --tests '*GitSettingsSectionSupportTest' \
  :app:compileArm64DebugKotlin :app:compileArm64DebugAndroidTestKotlin \
  -I tools/testing/single-worker-tests.init.gradle --configure-on-demand --offline \
  --no-daemon --no-parallel --max-workers=1 --console=plain
```

如果其他会话正在构建，只做静态检查，不启动本命令，也不要执行 `gradlew --stop`。离线模式要求本地已有 Gradle/Maven/Robolectric 依赖缓存，缺失时移除 `--offline` 重新执行正常依赖解析。

```bash
py tools/i18n/check_all.py
py tools/checks/check_documentation.py
git diff --check
```

复用了现有 `TarExtractor` 的归档限制、SHA 校验、原子替换、可取消 OkHttp、配置通知和批量结果状态。没有新增第三方依赖、目录重定向或数据库字段；没有修改子模块指针、版本号或提交/推送。

## 设备验收与回滚边界

1. 安装与本次源码对应的 Debug APK，在市场右上角保存预设/自定义代理，确认插件、包和 vcpkg 更新/重试使用新配置。
2. ARM64 原生 CMake 项目从空缓存安装 fmt、链接并运行，确认无需手工安装 vcpkg；断网、切换代理后取消/重试，保留实际安装记录。
3. 回归已知 baseline、静态库、API/ABI 切换、停用集成；其他 ports 的 supports 筛选不是成功构建保证。
4. 在包市场批量安装/更新/卸载中取消，结果保留成功数、失败数和未完成项，不继续启动后续包。
5. Release/R8 验证单独执行并明确接受版本/mapping 副作用，不能用本轮 Debug Kotlin 编译代替。

回滚只撤回本报告对应的语义修改，保留前一会话的其他未提交工作；不执行整文件 checkout/reset，不清除用户工具、库或项目目录。未改变缓存/清单格式，已有已校验归档与 CLI 可继续复用。
