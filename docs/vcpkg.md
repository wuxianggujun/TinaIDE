# 在 TinaIDE 中使用 vcpkg

项目设置新增 **vcpkg 依赖**，用于 native CMake 项目。它提供本机 manifest 安装和预编译 Android 库导入两种模式。
**市场 → vcpkg** 打开即可浏览离线包列表，搜索不要求先选择项目或填写路径；可下载更新官方 ports 目录，再向项目添加依赖、安装或取消。
「我的包」只管理已安装的 TinaIDE 内置/Registry 包；vcpkg 的安装状态按项目与 triplet 分开维护。
已在 API 34 x86_64 Debug 模拟器验证 fmt 从源码安装、CMake 编译、运行与重复配置，见
[自动管理验收记录](testing/vcpkg-managed-2026-11-10.md)。包含在线目录更新、自动准备工具、外部存储项目和离线重配；ARM64 真机、Release/R8 和其他 ports 尚未完成本轮验收。

## 选择运行方式

- **本机安装**：Android 原生 vcpkg CLI 在手机上运行，读取项目根目录的 `vcpkg.json`，使用当前 tina-toolchain、Android sysroot、API Level 和 ABI 构建依赖。
- **预编译库**：电脑使用 Android NDK 构建依赖，导出后交给手机上的 native CMake 使用，不在手机执行桌面 CLI。

PRoot 的 Linux 库不能直接链接进 Android/Bionic 程序。启用本集成时，CMake 需选择原生运行模式。

## 自动准备本机工具

Android 原生 CLI 随对应 ABI APK 分发。首次安装自动从 Termux 官方 HTTPS 仓库下载 git、curl、zip、unzip、pkg-config 及其依赖，逐包验证固定 SHA-256；不执行 Debian 安装脚本。应用管理 `files/vcpkg` 下的工具、ports 快照和缓存，用户无需填写这些路径。

目录更新直接从归档读取元数据，校验全部条目的大小、路径和类型后发布索引，不提前展开完整仓库；取消或失败保留旧列表和工具。安装阶段复用已校验的归档，再准备完整源码。应用内置目录可离线浏览；更新目录不会改写项目已有 `builtin-baseline`、features 或 overrides。

以下命令供开发者维护随包 CLI，普通用户不需要操作：

1. 准备 [microsoft/vcpkg](https://github.com/microsoft/vcpkg) 仓库，保留 `.vcpkg-root`、`scripts`、`ports`、`triplets` 及 Git 信息。
2. 查看该仓库 `scripts/vcpkg-tool-metadata.txt` 的 `VCPKG_TOOL_RELEASE_TAG`，检出同版本的 [vcpkg-tool](https://github.com/microsoft/vcpkg-tool)。
3. 在电脑使用 NDK、CMake、Ninja 构建 Android CLI。示例中两个源码仓库放在当前仓库旁边，`ANDROID_NDK_HOME` 指向已安装的 NDK：

```bash
python tools/vcpkg/build_android_tool.py \
  --source ../vcpkg-tool --ndk "$ANDROID_NDK_HOME" --abi arm64-v8a \
  --build-dir tools/vcpkg/build/arm64 --output-dir tools/vcpkg/build/arm64-package
```

通过 `tools/vcpkg/sync_runtime.py` 将产物、许可和构建信息同步到对应 ABI assets，并记录辅助工具下载清单；使用 `verify_runtime.py` 验证真实下载包。`sync_catalog.py` 同步离线目录。
CMake、Ninja、clang 复用当前工具链。本集成设置 `VCPKG_FORCE_SYSTEM_BINARIES=1`，避免下载 Linux/glibc 宿主程序。
编译并发沿用运行配置；原生子进程直接使用 Android 系统 shell，不需要手工创建 `/bin/sh` 映射。

辅助程序按需下载并保留其许可，取消后保留断点和已校验缓存，成功后移除临时下载。电脑导入模式不需要手机 CLI。
安装前会检查 CLI 的 ELF 格式与 ABI；该检查不能证明其使用 Android/Bionic，必须使用 Android 构建产物。
依赖 Windows、桌面 GUI、额外构建系统或不支持 Android 的 port 仍会失败，完整原因进入构建日志。

## 声明项目依赖

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

在市场点击 **添加到项目** 后选择 CMake 项目，会自动启用本机模式并更新 manifest；也可以在 **项目设置 → vcpkg 依赖** 选择 **本机安装**。工具目录自动管理，依赖固定安装到私有 `files/workspace/<projectId>/vcpkg_installed`，不再提供路径输入框。市场、编译、运行与增量输入跟踪共用此路径；项目重命名不改变安装位置。旧自定义目录的文件不会被删除，首次使用自动管理模式会重新安装依赖。
构建会先执行 `vcpkg install`，成功后才配置 CMake；失败不会继续使用缺失的依赖。
安装使用独立进程组，取消会回收该任务的编译子进程并重新读取实际安装状态。构建成功后清理 buildtrees 和 packages staging；已安装库和源码缓存保留，便于重试。
ARM64 使用 `arm64-tina-android`，x86_64 使用 `x64-tina-android`，默认静态链接。自定义 triplet 和 chainload 文件由当前 native 构建参数生成。
配置沿用项目的 CMake 参数存储；不需要编辑额外全局配置。停用 vcpkg 后，下一次配置会清除旧 CMake toolchain 缓存。
正式项目应在 manifest 中指定自己的 `builtin-baseline`，锁定 ports 版本。

## 导入电脑构建的依赖

在电脑上准备 Android NDK，使用标准 `arm64-android` 或 `x64-android` triplet 安装库，再通过 `vcpkg export --raw` 导出。
将包含 `scripts/buildsystems/vcpkg.cmake` 和 `installed/<triplet>` 的导出目录完整复制到手机，选择 **使用预编译 Android 库**，根目录指向该导出目录。
设置中的导出目录是预编译库的导入来源，不是本机安装目录；不再单独提供安装目录输入。禁止混用 `x64-linux`、`arm64-linux` 或 Windows 库。库的 Android API 不能高于目标设备版本。
优先使用静态库；动态库还需要匹配的 libc++ 及间接依赖，运行和导出时会检查实际装载链路。
运行与导出根据产物附近的 `CMakeCache.txt` 选择 Debug 库，避免把 Release 库优先装入 Debug 程序。
增量运行会跟踪当前 ABI 已安装的头文件、CMake 配置、库文件与安装状态；同一路径替换库也按内容指纹重新配置、链接。下载缓存和 buildtrees 不进入输入扫描。
预编译导出目录没有 `ports` 时，市场仍可展示安装记录和独立的全局目录。目录浏览不遍历自定义 registry；项目清单中的 registry、features、overrides 仍交给 vcpkg 处理。自动模式会准备版本管理所需的 Git 对象，保留清单中的 baseline。

## 后续验证

改动后先运行所属模块定向测试，再编译宿主，并用与源码对应的 APK 复跑设备验收。
SDL JNI 修复改变了共享模板源码，必须先执行 `buildApkTemplates` 并通过 `checkApkTemplatesSync`，不能手改校验值绕过门禁。
手机验收至少覆盖：fmt 首次安装/重复构建、修改 manifest、断网失败、取消安装、切换 ABI/API、停用集成、静态库运行及动态库 APK 导出。

定向测试入口（历史执行结果及当前验证范围见设备验收记录）：

```bash
./gradlew :core:compile:testDebugUnitTest --tests '*VcpkgCMakeIntegrationTest' --tests '*BuildProcessRunnerTest' --no-daemon --console=plain
./gradlew :core:packages:testDebugUnitTest --tests '*VcpkgRepositoryTest' --tests '*InstalledPackagePathResolverTest' --no-daemon --console=plain
./gradlew :feature:packages:testDebugUnitTest --tests '*PackageFilterStateTest' --tests '*PackageManagerViewModelTest' --no-daemon --console=plain
```
