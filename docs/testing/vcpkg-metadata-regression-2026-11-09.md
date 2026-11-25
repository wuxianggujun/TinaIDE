# vcpkg 清单和包元数据回归（2026-11-09）

本轮继续检查 vcpkg 仓储的文件修改、读取上限、字符编码和跨页面编辑，并回归已安装包的元数据读取。
证据属于本地 JVM/Robolectric 模块测试，不是手机端 vcpkg CLI 安装、编译和运行验收。
后续 API 34 x86_64 模拟器的真实 fmt 安装、编译、运行和重复配置已通过，见
[设备验收补充](device-acceptance-2026-11-09.md)；本文模块测试结果仍保持原始验证范围。

## 失败复现与修复

1. 非法 UTF-8 原本会被 `String(bytes, UTF_8)` 替换后写回，用户文件被静默修改。严格解码失败现在直接
   拒绝修改并保留原字节；中文和 emoji 在正常修改后保持不变。
2. 重复启用已存在的依赖、移除不存在的依赖原本都会重排并写回清单；没有清单时还会创建空依赖文件。
   无实际变更时现在直接返回，保留文件字节和修改时间，避免无意义的 CMake 输入变化。
3. 单个页面的仓储锁无法保护其他页面实例。12 个独立仓储同时编辑同一清单，原实现出现冲突异常及
   Windows 文件访问争用。所有仓储实例现在共享写锁，内部编辑串行，原有外部修改检测仍保留。
4. 原实现只限制读入的清单大小，修改和格式化后可能生成超过 1 MiB 的清单，下一次自己也读不了。
   发布前校验序列化后的字节数；超限拒绝操作，原文件保持不变，临时文件清理。
5. vcpkg 清单、port 元数据和安装状态读取共用有界字节读取及严格 UTF-8 解码；已安装包复用同一实现，
   避免重复维护。读取中的真实字节数也受限，文件增长不能绕过 `length()` 检查；逐块检查取消并关闭文件。

状态文件继续只识别当前 triplet 的 `install ok installed` 主记录；按行解析避免拆分出完整段落副本，
跳过缩进续行、feature 记录及非法包名。损坏 port 单独跳过，项目清单或 status 损坏则返回现有本地化错误。
JSON 解析错误不再把任意输入片段连同异常原因写入日志。

## 验证方式

失败复现覆盖前四类行为；并发测试最初误用了虚拟时钟，改为真实时钟后仍复现产品错误。
虚拟时钟超时和一次测试辅助函数编译失败不算作产品缺陷或通过结果。

最终包模块 **69/69 通过，0 失败、0 错误、0 跳过**，构建及测试总计 3 分 15 秒。
其中包括原有包安装/卸载 50 项，以及 vcpkg/读取边界、并发编辑、取消、中文、emoji、坏编码、
文件超限、原始内容保留和状态解析用例。逐项结果与源码哈希见
[vcpkg-metadata-unit-tests.json](evidence/editor-runtime-2026-11-09/vcpkg-metadata-unit-tests.json)，
五项有效失败复现见 [vcpkg-metadata-reproductions.json](evidence/editor-runtime-2026-11-09/vcpkg-metadata-reproductions.json)。
69 项包含前轮 50 项，不与旧结果累计为独立通过数量。

CMake 相关 **39/39 通过，0 失败、0 错误、0 跳过**，构建及测试总计 5 分 32 秒。
覆盖 vcpkg 的 ABI、预编译模式、错误 CLI/冲突参数拒绝、同路径替换库后的输入指纹、
原生 CMake 命令配置及模拟进程超时退出，见
[vcpkg-cmake-unit-tests.json](evidence/editor-runtime-2026-11-09/vcpkg-cmake-unit-tests.json)。
进程用例使用可控替身，不能替代手机上真实 vcpkg 及其编译子进程的取消验收。

所有 Gradle 检查使用 `--no-daemon --no-parallel --max-workers=1`，Test task 使用
`tools/testing/single-worker-tests.init.gradle` 限制单次执行时间为 60 秒；构建时间另计。

```bash
./gradlew :core:packages:testDebugUnitTest \
  --tests '*VcpkgRepositoryTest' --tests '*PackageMetadataFilesTest' \
  --tests '*InstalledPackageMetadataReaderTest' --tests '*DownloadPackageBackendTest' \
  --tests '*LocalInstallStateStoreTest' --tests '*PackageInstallCoordinatorTest' \
  --tests '*PackageManagerIoDispatcherTest' --tests '*PackageManagerRevisionTest' \
  --tests '*BundledPackagesReadinessTest' --tests '*PackageArchivePolicyTest' \
  --tests '*ResumableDownloaderTest' --tests '*PackageDownloadIdentityTest' \
  -I tools/testing/single-worker-tests.init.gradle '-Ptina.devAbi=x86_64' \
  --no-daemon --configure-on-demand --no-parallel --console=plain --max-workers=1
```

```bash
./gradlew :core:compile:testDebugUnitTest \
  --tests '*VcpkgCMakeIntegrationTest' --tests '*VcpkgBuildInputsTest' \
  --tests '*NativeCMakeBuildExecutorConfigTest' \
  -I tools/testing/single-worker-tests.init.gradle '-Ptina.devAbi=x86_64' \
  --no-daemon --configure-on-demand --no-parallel --console=plain --max-workers=1
```

文件保持 UTF-8，无新增 UI 文案；错误提示复用既有 `vcpkg_invalid_manifest` 等资源。
测试使用临时项目、可控响应和既有 JUnit4/Truth/Robolectric，不接触手机和用户项目。
国际化检查及主仓库、编辑器子模块 `git diff --check` 均通过。本轮没有构建 App APK；
精简结果保留后清理 `.tmp/vcpkg-validation` 的六份临时日志，清理清单单独归档。

## 尚未覆盖

- 手机端 vcpkg CLI、port 构建、CMake 链接和用户程序运行的完整流程。
- ARM64 Release、真实手柄和编辑器大文件流畅度，见[整体回归报告](editor-runtime-regression-2026-11-09.md)。
- 仓储共享锁保护本进程多个页面；外部编辑器或 CLI 不受该锁控制。写入前的内容复核可检测已发生的外部变化，
  不能宣称对任意外部写入提供跨进程事务。
