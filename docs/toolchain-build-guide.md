# TinaIDE Toolchain 构建与同步指南

> 更新日期：2026-07-03

## 目标

当前工具链产物是可直接在 Android 上运行的 ELF 可执行文件：

- `clang`
- `clang++`
- `clangd`
- `clang-format`
- `lld` / `ld.lld`
- 常用 `llvm-*`
- 可选构建工具：`cmake`、`ninja`、`make`

当前方案已经完全放弃：

- `libLLVM.so`
- `libclang-cpp.so`
- “共享库 + JNI 主入口补丁”式运行时

现在的目标是产出**独立 Android ELF 工具链包**，并通过 `current.properties` 驱动 App 侧安装与校验。

## 事实源

遇到构建细节不确定时，优先回看：

- `tools/run-toolchain-builder.ps1`
- `scripts/build-and-package-android-toolchain.sh`
- `tools/sync-tina-toolchain-assets.ps1`
- `verifyTinaToolchainAssets` 任务：由 convention plugin `tina.android.app.toolchain.assets` 注册，实现位于 `build-logic/convention/src/main/kotlin/com/wuxianggujun/tinaide/buildlogic/TinaAndroidAppToolchainAssetsPlugin.kt`（已不在 `app/build.gradle.kts` 内联定义）

## 当前产物模型

当前工具链资产分成两层：

1. `tina-toolchain`
   包含 clang / clangd / lld / llvm-*，以及可选的 cmake / ninja / make
2. `android-sysroot`
   单独作为 ABI 资产包放在 `app/src/<abi>/assets/android-sysroot/`

`current.properties` 负责声明当前 ABI 需要的：

- `version`
- `arch`
- `full` 或 `base`
- 可选 `tools`
- 可选 `sha256`

运行时仅接受 `.tar.xz` 归档。

## 准备构建容器

推荐先启动专用容器：

```powershell
pwsh ./tools/run-toolchain-builder.ps1 -Detach
```

默认约定：

- 镜像名：`tinaide-toolchain-builder:ndk-r27`
- 容器名：`tinaide-toolchain-builder`
- 工作目录：`/workspace`
- 仓库根目录会挂载到容器内的 `/workspace`

如果只想执行一次命令，也可以直接：

```powershell
pwsh ./tools/run-toolchain-builder.ps1 -Command "cd /workspace && bash scripts/build-and-package-android-toolchain.sh"
```

## 执行构建

### 最小用法

```bash
docker exec tinaide-toolchain-builder bash -lc \
  "cd /workspace && bash scripts/build-and-package-android-toolchain.sh"
```

### 常见自定义参数

```bash
docker exec tinaide-toolchain-builder bash -lc \
  "cd /workspace && \
   ARCH=aarch64 \
   API_LEVEL=28 \
   LLVM_VERSION=22.1.0-rc3 \
   TOOLCHAIN_VERSION=0.2.4 \
   bash scripts/build-and-package-android-toolchain.sh"
```

脚本当前支持的高频环境变量：

- `ARCH=aarch64|x86_64`
- `API_LEVEL=28`
- `LLVM_VERSION=22.1.0-rc3`
- `TOOLCHAIN_VERSION=0.2.4`
- `BUILD_TOOLS=0|1`
- `PACKAGE_FULL=0|1`
- `PACKAGE_BASE=0|1`
- `PACKAGE_TOOLS=0|1`
- `SYSROOT_API_LEVELS=21,23,28`
- `FORCE_RECONFIGURE=0|1`
- `NINJA_TARGETS="clang lld clangd"`
- `USE_CCACHE=0|1`
- `SKIP_STAGE_AND_PACKAGE=0|1`

## 当前构建约束

脚本会强制校验以下约束：

- `ANDROID_STL=c++_static`
- `LLVM_BUILD_LLVM_DYLIB=OFF`
- `LLVM_LINK_LLVM_DYLIB=OFF`
- `CLANG_LINK_CLANG_DYLIB=OFF`

构建后还会验证 `clangd` 依赖，确保：

- 不依赖 `libLLVM.so`
- 不依赖 `libclang-cpp.so`

如果缓存目录的配置不满足这些约束，脚本会自动重建目标构建目录。

## 构建输出

默认输出根目录：

```text
build/tina-toolchain/
```

其中最常用的是：

- `build/tina-toolchain/release/`
  当前 release 包输出目录
- `build/tina-toolchain/_tmp_sysroot_out/`
  sysroot 打包输出目录
- `build/tina-toolchain/build/`
  host / target 构建中间目录

当前 release 目录一般会包含：

- 全量包：`tinaide-toolchain-<arch>-v<ver>.tar.xz`
- 基础包：`tinaide-toolchain-base-<arch>-v<ver>.tar.xz`
- tools 包：`tinaide-toolchain-tools-<arch>-v<ver>.tar.xz`
- 对应 `sha256` 文件

应用了 LLVM patch 的重打包产物会带 `-patched` 后缀。当前仓库内 arm64 与 x86_64 资产都指向 `tinaide-toolchain-<arch>-v0.2.4-patched.tar.xz`，`current.properties` 中的 `full` / `sha256` 必须与实际文件名逐字一致。

## 同步到 App 资产目录

构建完成后，不要手工复制散文件，统一走同步脚本。只同步工具链时可跳过 sysroot：

```powershell
pwsh ./tools/sync-tina-toolchain-assets.ps1 -Abi arm64 -Clean -SkipSysroot
pwsh ./tools/sync-tina-toolchain-assets.ps1 -Abi x86_64 -Clean -SkipSysroot
```

如果本次同时更新 Android sysroot，需要显式传入 profile 与资产名；旧
`android-sysroot-<abi>-all.tar.xz` 默认包名已经移除：

```powershell
pwsh ./tools/sync-tina-toolchain-assets.ps1 `
  -Abi arm64 `
  -Clean `
  -SysrootProfileId builtin-ndk-r28c-arm64 `
  -SysrootProfileName "NDK r28c" `
  -SysrootAssetName android-sysroot-arm64-r28c.tar.xz `
  -SetDefaultSysrootProfile

pwsh ./tools/sync-tina-toolchain-assets.ps1 `
  -Abi x86_64 `
  -Clean `
  -SysrootProfileId builtin-ndk-r28c-x86_64 `
  -SysrootProfileName "NDK r28c" `
  -SysrootAssetName android-sysroot-x86_64-r28c.tar.xz `
  -SetDefaultSysrootProfile
```

同步脚本会做几件事：

1. 读取 `app/src/<abi>/assets/tina-toolchain/current.properties`
2. 按 spec 检查 `release/` 目录里是否存在对应归档
3. 复制所需 `.tar.xz` 与 `sha256`
4. 维护 `app/src/<abi>/assets/android-sysroot/profiles.json`
5. 同步指定的 `android-sysroot-<abi>-<profile>.tar.xz`
6. 把旧资产归档到 `app/.local/toolchain-archive/<abi>/`

关键约束：

- `ArchiveDir` 不能放在 `app/src` 下面，否则会被一起打包进 APK
- `current.properties` 至少要有 `version`、`arch`，以及 `full` 或 `base`
- `arch` 必须与 ABI 对应：`arm64 -> aarch64`，`x86_64 -> x86_64`
- 不跳过 sysroot 时，`SysrootAssetName` 必须是 `.tar.xz` 文件名，不能包含路径分隔符

## 构建后校验

同步完资产后，至少跑一次：

```bash
./gradlew :app:verifyTinaToolchainAssets
```

这个任务会校验：

- 必需 ABI 是否都有 `current.properties`
- spec 声明的归档是否真实存在
- `full` / `base` / `tools` 是否都是 `.tar.xz`
- `sha256` 是否包含对应条目

如果报错，优先按报错提示重新执行 `sync-tina-toolchain-assets.ps1 -Clean`。

## 运行时落点

App 安装后的运行时解压目录不在仓库里，而在应用私有目录中。

文档层面只需要记住两件事：

- 仓库侧资产来源：`app/src/<abi>/assets/tina-toolchain/` 与 `app/src/<abi>/assets/android-sysroot/`
- 运行时安装逻辑：`AndroidNativeToolchainManager` 与 `AndroidSysrootManager`

## 不要再使用的旧口径

以下说法已经过时：

- “工具链靠 `libLLVM.so` / `libclang-cpp.so` 运行”
- “直接手动往 assets 扔压缩包即可”
- “sysroot 和 toolchain 永远打在一个包里”
- “旧的 `build/custom-toolchain/` 是当前标准输出目录”

如果你在其他文档或脚本里看到这些描述，请以本指南和脚本实现为准。
