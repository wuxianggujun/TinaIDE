# vcpkg 集成与维护

用户从 [CMake 使用 vcpkg](../feature/help/src/main/assets/help/vcpkg-guide.md) 或 [English tutorial](../feature/help/src/main/assets/help/en/vcpkg-guide.md) 开始。教程和应用内帮助共用正文，市场提供直达入口；本文保留维护者所需的运行时、目录及兼容细节。

vcpkg 已内置于应用，用于 native CMake 项目；工具、源码和依赖安装目录由应用管理，设置页不提供目录输入。
**市场 → vcpkg** 打开即显示按当前 Android 架构筛选的离线包列表，搜索不要求先选择项目；可下载更新官方 ports 目录，再向项目添加依赖、安装或取消。搜索框位于市场顶栏，与插件、包两个 Tab 共用同一份关键词；vcpkg 内容区的固定工具栏（项目选择、刷新/更新目录）随列表常驻，底部固定区承载两种安装方式、切换为内置管理和取消。
「我的包」只管理已安装的 TinaIDE 内置/Registry 包；vcpkg 的安装状态按项目与 triplet 分开维护。
已在 API 34 x86_64 Debug 模拟器验证 fmt 从源码安装、CMake 编译、运行与重复配置，见
[自动管理验收记录](testing/vcpkg-managed-2026-11-10.md)。包含在线目录更新、自动准备工具、外部存储项目和离线重配；ARM64 真机、Release/R8 和其他 ports 尚未完成本轮验收。
本次 Android 目录筛选与旧项目兼容的验证范围见 [Android 市场回归记录](testing/vcpkg-android-catalog-2026-11-10.md)。
代理、完整归档回退、缓存修复和批量取消的本轮结果见 [2026-11-13 集成审查](testing/2026-11-13_集成审查-vcpkg-report.md)：118 项定向测试及宿主/设备测试源码编译通过；未执行新 APK 的设备验收。

## Android 包列表与安装位置

- **单一列表**：按上游 `supports` 表达式计算当前应用 ABI、Android 平台、静态库/CRT 和相同 host/target triplet；包数量和搜索使用同一筛选结果。空表达式表示上游没有声明限制，也列入候选；未知标识符或无效表达式不进入列表。目录下载更新后重新筛选。
- **已被过滤的清单条目**：`VcpkgAndroidCatalog.orphanDependencies()` 把「已声明或已安装、但不兼容/已下架」的条目补回同一列表，保证仍有卡片移除入口；它们不参与上游搜索匹配，历史实现中的独立「项目依赖」视图已移除。
- **搜索结果**：`VcpkgAndroidCatalog.search()` 返回 `VcpkgSearchResult`，除兼容条目外还带回 `incompatible`。搜索无匹配时，市场据此区分「目录里没有该库」与「库存在但声明不支持当前架构/静态链接」，后者列出库名。
- **已验证徽标**：`VcpkgVerifiedPorts` 只登记 TinaIDE 在真机上完整跑通下载、编译、链接和运行的库（目前仅 `fmt`）。其余库仅代表上游声明允许，不代表实测通过，UI 不得把两者混为一谈。
- 筛选是上游声明校验，不代表每个库、feature、传递依赖或构建工具都已在 TinaIDE 实测。当前目录不展开自定义 registry；项目的 baseline、features 和 overrides 仍交给 vcpkg 处理。

## 设备级共享依赖库

底栏提供两种安装方式，目的相同、安装位置不同：

- **安装到项目**：现有清单模式，依赖装进该项目的工作区（`ProjectPaths.getProjectVcpkgInstalledDir`）。
- **装到共享库**：先按清单模式正常安装，再由 `VcpkgSharedLibrary.export()` 在唯一暂存目录中合并当前 ABI 的 triplet 树，通过现有目录发布与回滚机制更新 `ProjectPaths.getSharedVcpkgInstalledDir()`（`<filesDir>/vcpkg/shared-installed`），最后调用 `VcpkgRepository.enableSharedLibrary()` 把项目切到 `SHARED` 模式；共享位置始终由应用计算。

约定：

- 共享目录按 ABI 分 triplet 子目录，导出时把 `arm64-tina-android` 之类的工作区 triplet 改名为共享模式使用的 `arm64-android`/`x64-android`，与 `VcpkgProjectSettings.targetTriplet()` 保持一致。
- 导出采用覆盖合并，不删除其他项目已导出的库；同名库以最后一次导出为准，版本冲突需项目自行统一。
- 导出复用 `PackageInstallCoordinator` 的共享根写锁及发布/回滚机制。取消或复制失败时只清理本次暂存目录，原库保持不变；源文件的符号链接不得越出待导出树。暂存需要额外容纳当前 ABI 已有库与新增文件，不会为了腾空间删除其他项目的依赖。
- 复用方按 vcpkg 库布局读取 `share/<port>`、`lib`、`include`，vcpkg 生成的 CMake config 用相对前缀定位，因此导出目录改名后仍可链接；如果某个库把绝对路径写进 `.pc` 或 config，复用时应改用项目清单安装。
- 共享目录不复制 `vcpkg/status`，所以市场在共享库模式下只显示「已在清单」，不显示安装版本；安装记录仍保留在原项目工作区。
- 项目切到共享库模式后 `canModify` 为 false，卡片只剩查看/复制；需要继续添加依赖时用底栏的 **使用内置 vcpkg** 切回清单模式，工作区和共享库都不会被删除。
- 共享模式单独保存为 `TINA_VCPKG_MODE=shared`，不保存目录。首次导出先完成一次清单安装。

市场界面按职责拆分，新增功能优先落到对应文件，避免继续膨胀单一 Composable：

- `VcpkgMarketContent.kt`：固定工具栏、列表/空态、底栏和对话框装配；搜索文本由 `MarketScreen` 顶栏传入。
- `VcpkgMarketViewModel.kt`：目录与项目读取、搜索防抖（`appliedQuery`）、`install()` / `installToShared()`、取消（`cancelOperation()`）、一次性 `notice`。项目切换保留搜索关键词；`onResumed()` 复用已加载目录，避免重复计算约 2900 条 `supports` 表达式。
- `VcpkgPortCard.kt` / `VcpkgPortDetailDialog.kt`：卡片徽标、复制库名、项目状态与兼容性详情。
- `VcpkgInstallLogDialog.kt`：安装输出独立对话框，自动滚动并支持复制。
- 空态和错误态复用 `core:designsystem` 的 `TinaEmptyState` / `TinaErrorState`，插件市场与 vcpkg 共用。两者按可用高度自适应：高度不足时隐藏 96 dp 图标并收紧间距，错误态改为可滚动，避免固定头部加底栏后标题、说明或重试按钮被挤出可视区域。
- 短窗口使用单行项目/刷新/更新/教程工具栏，隐藏仅作重复提示的无操作底栏；有安装、取消、切换模式或日志操作时仍显示入口。横屏门户共享原有导航项与 Spotlight 目标的侧栏，不改变页面状态持有者或缩小点击区域。

Android 原生 vcpkg CLI 在手机上读取项目的 `vcpkg.json`，使用当前 tina-toolchain、Android sysroot、API Level 和 ABI 构建依赖。

PRoot 的 Linux 库不能直接链接进 Android/Bionic 程序。启用本集成时，CMake 需选择原生运行模式。

## 自动准备本机工具

Android 原生 CLI 和辅助工具快照随对应 ABI APK 分发。维护时从 Termux 官方 HTTPS 仓库固定 git、curl、zip、unzip、pkg-config 及其依赖的版本、大小和 SHA-256，打包为 `helpers.tar`；手机首次准备会校验归档和各个 Debian 包后解压，不访问可能下架旧版本的滚动仓库，也不执行安装脚本。工具快照每个 ABI 约 21 MB，保留包内许可。应用管理 `files/vcpkg` 下的工具、ports 快照和缓存，用户无需填写这些路径；库源码与 ports 更新仍按需联网。

目录更新直接从归档读取元数据，校验全部条目的大小、路径和类型后发布索引，不提前展开完整仓库；取消或失败保留旧列表和工具。安装阶段复用已校验的归档，再准备完整源码。应用内置目录可离线浏览；更新目录不会改写项目已有 `builtin-baseline`、features 或 overrides。

市场顶部右侧提供 **GitHub 代理** 对话框，与 Git 设置共用 `GitHubRegistryProxyConfig`。支持预设、自定义 HTTPS 前缀、恢复自动选择及独立 HTTP 代理。所选前缀优先于官方地址和公共回退地址；保存后插件/包市场重建客户端并刷新列表，vcpkg 在下一次网络操作读取最新配置。进行中的安装保留启动时的环境，取消后重试可切换代理。

vcpkg 目录的 API 与归档请求均支持镜像回退；HTML 错误页、非法 revision、gzip 损坏、归档路径/类型不符及与已知 SHA-256 不符的候选均不会发布为成功缓存，而会继续尝试下一来源。历史坏归档会重新下载，损坏的目录缓存即使与随包目录 revision 相同，也会在成功更新后重建。

运行时 Git wrapper 仅对 `fetch`、`clone`、`ls-remote` 通过单次 `-c url.*.insteadOf` 使用镜像，失败回退官方地址，不修改全局 Git 配置；本地 `init`、`config` 等命令不会因镜像而重复执行。代理必须支持 Git smart HTTP。库源码通过 vcpkg 原生 `x-script` asset source 使用镜像，并保留 vcpkg 的 SHA-512 校验和原地址回退。辅助工具从随包快照校验解压，进度显示在安装输出中。

以下命令供开发者维护随包 CLI，普通用户不需要操作：

1. 准备 [microsoft/vcpkg](https://github.com/microsoft/vcpkg) 仓库，保留 `.vcpkg-root`、`scripts`、`ports`、`triplets` 及 Git 信息。
2. 查看该仓库 `scripts/vcpkg-tool-metadata.txt` 的 `VCPKG_TOOL_RELEASE_TAG`，检出同版本的 [vcpkg-tool](https://github.com/microsoft/vcpkg-tool)。
3. 在电脑使用 NDK、CMake、Ninja 构建 Android CLI。示例中两个源码仓库放在当前仓库旁边，`ANDROID_NDK_HOME` 指向已安装的 NDK：

```bash
python tools/vcpkg/build_android_tool.py \
  --source ../vcpkg-tool --ndk "$ANDROID_NDK_HOME" --abi arm64-v8a \
  --build-dir tools/vcpkg/build/arm64 --output-dir tools/vcpkg/build/arm64-package
```

通过 `tools/vcpkg/sync_runtime.py` 将产物、许可和构建信息同步到对应 ABI assets，同时生成必需的辅助工具快照；已有 CLI 仅刷新辅助工具时使用 `--refresh-helpers`。使用 `verify_runtime.py` 校验快照、包哈希与 ELF 依赖闭包。`sync_catalog.py` 同步离线目录。
CMake、Ninja、clang 复用当前工具链。本集成设置 `VCPKG_FORCE_SYSTEM_BINARIES=1`，避免下载 Linux/glibc 宿主程序。
编译并发沿用运行配置；原生子进程直接使用 Android 系统 shell，不需要手工创建 `/bin/sh` 映射。

辅助工具只使用随包快照，解压取消或失败会清理未发布的临时目录，完成标记仅在校验成功后写入。库源码按需下载，保留其许可与已有缓存。
安装前会检查 CLI 的 ELF 格式与 ABI；该检查不能证明其使用 Android/Bionic，必须使用 Android 构建产物。
依赖 Windows、桌面 GUI、额外构建系统或不支持 Android 的 port 仍会失败，完整原因进入构建日志。

## 声明项目依赖

新建向导仅为 CMake 模板提供默认关闭的 **使用内置 vcpkg** 选项。`ProjectCreationService` 在新项目创建事务内准备空 manifest 并写入 managed 参数；模板现有 manifest 原样保留，其他 CMake 参数保留，旧自定义工具/安装路径清除。准备失败按既有新建项目回滚逻辑清理本次目录，不遗留“创建成功但无法使用”的项目。

切换到非 CMake 模板会清除选项。创建过程不下载依赖，不绕过图形模板原有的内置包检查。未勾选时也可后续在市场添加库启用；无需新发布一个工具插件。

将 [`tools/vcpkg/example`](../tools/vcpkg/example) 复制为普通 CMake 项目，其 manifest 使用 `fmt`：

```json
{
  "name": "tina-vcpkg-example",
  "version-string": "1.0.0",
  "dependencies": ["fmt"]
}
```

```cmake
find_package(fmt CONFIG REQUIRED)
add_executable(tina_vcpkg_example main.cpp)
target_link_libraries(tina_vcpkg_example PRIVATE fmt::fmt)
```

在市场点击 **添加到项目** 后选择 CMake 项目，会自动启用内置 vcpkg 并更新 manifest，无需先进入设置。也可以在 **项目设置 → vcpkg 依赖** 启用或关闭内置管理。工具目录自动管理，**安装到项目** 时依赖固定安装到私有 `files/workspace/<projectId>/vcpkg_installed`，**装到共享库** 时改写到 `files/vcpkg/shared-installed/<triplet>`。市场、编译、运行与增量输入跟踪共用 `VcpkgManagedPaths.installed()` 这一路径策略；项目重命名不改变安装位置。旧自定义目录的文件不会被删除，首次使用自动管理模式会重新安装依赖。
构建会先执行 `vcpkg install`，成功后才配置 CMake；失败不会继续使用缺失的依赖。
安装使用独立进程组，取消会回收该任务的编译子进程并重新读取实际安装状态。构建成功后清理 buildtrees 和 packages staging；已安装库和源码缓存保留，便于重试。
ARM64 使用 `arm64-tina-android`，x86_64 使用 `x64-tina-android`，默认静态链接。自定义 triplet 和 chainload 文件由当前 native 构建参数生成。
配置沿用项目的 CMake 参数存储；不需要编辑额外全局配置。停用 vcpkg 后，下一次配置会清除旧 CMake toolchain 缓存。
正式项目应在 manifest 中指定自己的 `builtin-baseline`，锁定 ports 版本。

## 应用管理的路径与模式

只支持关闭、项目清单（`manifest`）、设备共享库（`shared`）三种模式。项目元数据只保存模式，不保存工具或安装路径；旧 `prebuilt`、`TINA_VCPKG_ROOT`、`TINA_VCPKG_INSTALLED_DIR` 不再参与运行时解析。保存设置时移除旧参数，不读取、迁移或自动复制旧目录。
工具统一由 `VcpkgRuntimeManager` 准备，项目安装目录按项目 ID 定位，共享目录固定由 `ProjectPaths` 提供。CMake 与增量输入跟踪必须显式接收应用计算的目录，不回退到项目内或 `<root>/installed`。
优先使用静态库；动态库还需要匹配的 libc++ 及间接依赖，运行和导出时会检查实际装载链路。
运行与导出根据产物附近的 `CMakeCache.txt` 选择 Debug 库，避免把 Release 库优先装入 Debug 程序。
增量运行会跟踪当前 ABI 已安装的头文件、CMake 配置、库文件与安装状态；同一路径替换库也按内容指纹重新配置、链接。下载缓存和 buildtrees 不进入输入扫描。
共享模式只消费已导出的库，不启动 Git 拉取；清单模式准备版本管理所需的 Git 对象并保留 baseline。市场不会从旧手动目录推断安装状态。

## 后续验证

改动后先运行所属模块定向测试，再编译宿主，并用与源码对应的 APK 复跑设备验收。
SDL JNI 修复改变了共享模板源码，必须先执行 `buildApkTemplates` 并通过 `checkApkTemplatesSync`，不能手改校验值绕过门禁。
手机验收至少覆盖：fmt 首次安装/重复构建、修改 manifest、断网失败、取消安装、切换 ABI/API、停用集成、静态库运行及动态库 APK 导出。

定向测试入口（历史执行结果及当前验证范围见设备验收记录）：

```bash
./gradlew :core:compile:testDebugUnitTest --tests '*VcpkgCMakeIntegrationTest' --tests '*BuildProcessRunnerTest' --no-daemon --console=plain
./gradlew :core:packages:testDebugUnitTest --tests '*VcpkgAndroidSupportTest' --tests '*VcpkgAndroidCatalogTest' --tests '*VcpkgRepositoryTest' --no-daemon --console=plain
./gradlew :feature:packages:testDebugUnitTest --tests '*PackageFilterStateTest' --tests '*PackageManagerViewModelTest' --no-daemon --console=plain
```
