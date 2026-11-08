# 在 TinaIDE 中使用 vcpkg

项目设置新增 **vcpkg 依赖**，用于 native CMake 项目。它提供本机 manifest 安装和预编译 Android 库导入两种模式。
**市场 → vcpkg** 提供项目选择、本地 ports 搜索、manifest 依赖增删、已安装版本、安装输出与取消入口。
「我的包」只管理已安装的 TinaIDE 内置/Registry 包；vcpkg 的安装状态按项目与 triplet 分开维护。
本轮仅完成实现与静态检查，尚未编译 App、重建 SDL 模板或在手机上验证 vcpkg；不能据此宣称所有 ports 已可用。

## 选择运行方式

- **本机安装**：Android 原生 vcpkg CLI 在手机上运行，读取项目根目录的 `vcpkg.json`，使用当前 tina-toolchain、Android sysroot、API Level 和 ABI 构建依赖。
- **预编译库**：电脑使用 Android NDK 构建依赖，导出后交给手机上的 native CMake 使用，不在手机执行桌面 CLI。

PRoot 的 Linux 库不能直接链接进 Android/Bionic 程序。启用本集成时，CMake 需选择原生运行模式。

## 准备本机 CLI

1. 准备 [microsoft/vcpkg](https://github.com/microsoft/vcpkg) 仓库，保留 `.vcpkg-root`、`scripts`、`ports`、`triplets` 及 Git 信息。
2. 查看该仓库 `scripts/vcpkg-tool-metadata.txt` 的 `VCPKG_TOOL_RELEASE_TAG`，检出同版本的 [vcpkg-tool](https://github.com/microsoft/vcpkg-tool)。
3. 在电脑使用 NDK、CMake、Ninja 构建 Android CLI。示例中两个源码仓库放在当前仓库旁边，`ANDROID_NDK_HOME` 指向已安装的 NDK：

```bash
python tools/vcpkg/build_android_tool.py \
  --source ../vcpkg-tool --ndk "$ANDROID_NDK_HOME" --abi arm64-v8a \
  --build-dir tools/vcpkg/build/arm64 --output-dir tools/vcpkg/build/arm64-package
```

将产出的 `vcpkg`、许可文件和构建信息放入手机上的 vcpkg 根目录。CLI 应位于可供应用读取的私有工具目录；共享存储通常不允许装载可执行文件。
还需提供同 ABI 的 Android 原生命令 `git`、`curl`、`zip`、`unzip`、`pkg-config`，并放入当前工具链的 `bin`。
CMake、Ninja、clang 复用当前工具链。本集成设置 `VCPKG_FORCE_SYSTEM_BINARIES=1`，避免下载 Linux/glibc 宿主程序。

这些辅助程序和 vcpkg 本身目前不随 TinaIDE APK 打包。缺少它们时不能完成本机安装；电脑导入模式不需要手机 CLI。
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

打开 **项目设置 → vcpkg 依赖**，选择 **本机安装** 并填写根目录。安装目录留空时使用项目的 `vcpkg_installed`。
构建会先执行 `vcpkg install`，成功后才配置 CMake；失败不会继续使用缺失的依赖。
安装使用独立进程组，取消会回收该任务的编译子进程并重新读取实际安装状态。已成功安装的库和源码缓存由 vcpkg 保留，便于重试。
ARM64 使用 `arm64-tina-android`，x86_64 使用 `x64-tina-android`，默认静态链接。自定义 triplet 和 chainload 文件由当前 native 构建参数生成。
配置沿用项目的 CMake 参数存储；不需要编辑额外全局配置。停用 vcpkg 后，下一次配置会清除旧 CMake toolchain 缓存。
正式项目应在 manifest 中指定自己的 `builtin-baseline`，锁定 ports 版本。

## 导入电脑构建的依赖

在电脑上准备 Android NDK，使用标准 `arm64-android` 或 `x64-android` triplet 安装库，再通过 `vcpkg export --raw` 导出。
将包含 `scripts/buildsystems/vcpkg.cmake` 和 `installed/<triplet>` 的导出目录完整复制到手机，选择 **使用预编译 Android 库**，根目录指向该导出目录。
自定义安装目录可以单独填写；禁止混用 `x64-linux`、`arm64-linux` 或 Windows 库。库的 Android API 不能高于目标设备版本。
优先使用静态库；动态库还需要匹配的 libc++ 及间接依赖，运行和导出时会检查实际装载链路。
运行与导出根据产物附近的 `CMakeCache.txt` 选择 Debug 库，避免把 Release 库优先装入 Debug 程序。
增量运行会跟踪当前 ABI 已安装的头文件、CMake 配置、库文件与安装状态；同一路径替换库也按内容指纹重新配置、链接。下载缓存和 buildtrees 不进入输入扫描。
预编译导出目录没有 `ports` 时，市场仍可展示安装记录。目录浏览当前读取本地 `ports/*/vcpkg.json`，不遍历自定义 registry；项目清单中的 registry、features、overrides 仍交给 vcpkg 处理。

## 后续验证

允许编译后，先运行编辑器编码、设置导航、崩溃日志和 vcpkg 配置的定向单测，再编译宿主。
SDL JNI 修复改变了共享模板源码，必须先执行 `buildApkTemplates` 并通过 `checkApkTemplatesSync`，不能手改校验值绕过门禁。
手机验收至少覆盖：fmt 首次安装/重复构建、修改 manifest、断网失败、取消安装、切换 ABI/API、停用集成、静态库运行及动态库 APK 导出。

定向测试入口（本轮未执行）：

```bash
./gradlew :core:compile:testDebugUnitTest --tests '*VcpkgCMakeIntegrationTest' --tests '*BuildProcessRunnerTest' --no-daemon --console=plain
./gradlew :core:packages:testDebugUnitTest --tests '*VcpkgRepositoryTest' --tests '*InstalledPackagePathResolverTest' --no-daemon --console=plain
./gradlew :feature:packages:testDebugUnitTest --tests '*PackageFilterStateTest' --tests '*PackageManagerViewModelTest' --no-daemon --console=plain
```
