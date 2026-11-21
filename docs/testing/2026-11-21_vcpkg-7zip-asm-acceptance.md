# 2026-11-21 vcpkg 7zip 汇编修复与真机验收

PMS110（Android 17 / API 37、arm64-v8a）已安装包含修复的 TinaIDE 0.19.5 Debug（19062）。
7zip 26.6 的 ARM64 Debug/Release 源码构建、依赖安装，以及应用内的 CMake 链接、LZMA 压缩/解压与汇编运行均已通过。
本记录不覆盖 Release/R8、其他真机或其他 ports。

已核对手机 `base.apk` 与本地验收 APK 的 SHA-256 一致：
`1e1167bd03ec4414da031e39ba324c118908c6e9f2906b60a4ecb869855f80e8`。

## 现象与根因

用户日志停在 `Installing 3/3 7zip` 附近。重新连接真机后，实际失败日志显示构建已到第 270/287 步：

```text
FAILED: CMakeFiles/7zip.dir/Asm/arm64/LzmaDecOpt.S.o
/system/bin/sh -DZ7_EXTERNAL_CODECS ... -c .../Asm/arm64/LzmaDecOpt.S
/system/bin/sh: /system/bin/sh: -D: unknown option
ninja: build stopped: subcommand failed.
```

`Compiler found: /system/bin/sh` 本身是正常的 Android 编译器启动方式；其后必须带 clang 包装脚本。
原实现只为 C/C++ 配置该脚本，CMake 推断 ASM 编译器时保留了 `sh` 路径，却丢失 `COMPILER_ARG1`。
vcpkg 生成的 chainload 文件也只透传 C/C++，因此 ARM64 7zip 启用 ASM 后把编译参数直接交给了 shell。

修复复用现有 native 编译器、sysroot 和执行参数：

- `NativeCMakeBuildExecutor` 显式提供 `CMAKE_ASM_COMPILER`、`COMPILER_ARG1`、`COMPILER_TARGET` 和 `FLAGS`。
- `VcpkgCMakeIntegration` 把通用 ASM 加入现有工具链参数白名单；不引入 NASM/MASM 路径。
- 安装失败与异常进入最多 250 条的可复制日志；日志满额后仍随新增内容滚动。

## 验证结果与边界

| 检查 | 结果 | 证据与范围 |
| --- | --- | --- |
| 核心编译定向单测 | 48 项通过，无跳过 | 续接时核验已落盘的 7 项进程、33 项配置、8 项 vcpkg 工具链测试报告 |
| 安装日志 ViewModel 单测 | 3 项通过，无跳过 | 最终错误保留、异常日志、状态刷新失败不丢失原安装日志 |
| x86_64 候选设备测试 | 2 项通过 | 普通 ASM 与空 manifest 的 vcpkg chainload；该候选早于可选 7zip 压缩夹具 |
| 最终 ARM64 Debug 与 AndroidTest | 构建成功 | `app/build/vcpkg-asm-arm64-build.log` |
| 最终 ARM64 真机基础测试 | 2 项通过 | `app/build/vcpkg-asm-chainload-device-arm64.log`，耗时 2.198 秒 |
| 7zip 完整依赖安装 | 成功 | 二进制缓存未命中、源码归档命中缓存；Debug/Release 均构建并安装，CLI 总耗时 20.11 秒 |
| APK 内 7zip 链接与运行 | 1 项通过 | 已安装依赖上复跑生产 CMake 执行器，耗时 1.204 秒，输出见下文 |
| 覆盖安装与启动 | 成功 | 使用与现有 App 相同的签名，`install -r -t` 保留数据；门户冷启动成功 |
| 国际化与编码 | 通过 | i18n 检查通过；本轮相关源码使用 UTF-8 无 BOM，夹具文件显式使用 `Charsets.UTF_8` |

首次把完整 7zip 构建放进短仪器测试时触发了 60 秒外层上限，未计为通过。
随后单独调用应用生成的 triplet/chainload 完成实际 vcpkg 安装，再复跑 APK 内回归。
因此，完整源码构建的证据来自 CLI 安装记录；APK 内最终通过的回归使用已安装依赖。
短测试保留 50 秒协程上限与 60 秒外层保护，不证明冷构建一定能在短测窗口完成。

最终程序通过 `find_package(7zip CONFIG REQUIRED)` 和 `7zip::7zip` 链接真实库，
调用 `LzmaCompress` / `LzmaUncompress` 并逐字节检查解压结果，同时调用独立汇编函数：

```text
7zip=ok
asm=42
```

测试只写独立的 `native-asm-acceptance` 与 `native-asm-vcpkg-acceptance` 夹具目录及其工作区。
本轮未重新执行用户原 `vcpkgx` 项目的安装操作，也未改写该项目源码。

## 复跑入口

测试源码：[NativeCMakeAssemblyDeviceTest](../../app/src/androidTest/java/com/wuxianggujun/tinaide/core/compile/NativeCMakeAssemblyDeviceTest.kt)。
先准备对应 ABI 的 native toolchain、Android sysroot 和 vcpkg 工具；ARM64 构建命令：

```bash
./gradlew -Ptina.devAbi=arm64 :app:assembleArm64Debug :app:assembleArm64DebugAndroidTest --no-daemon --console=plain
```

应用包与测试包必须使用相同证书；覆盖已有安装时还须匹配原证书，不能以卸载代替保留数据安装。
以下命令在已选定 `ANDROID_SERIAL`、并安装匹配签名的两个 APK 后执行：

```bash
adb -s "$ANDROID_SERIAL" shell am instrument -w -r \
  -e class com.wuxianggujun.tinaide.core.compile.NativeCMakeAssemblyDeviceTest \
  com.wuxianggujun.tinaide.test/androidx.test.runner.AndroidJUnitRunner
```

默认使用空依赖 manifest，覆盖普通 CMake 和 vcpkg chainload 两条汇编路径。
显式启用真实 7zip 夹具时，先完成该独立项目的依赖准备，再运行：

```bash
adb -s "$ANDROID_SERIAL" shell am instrument -w -r \
  -e class 'com.wuxianggujun.tinaide.core.compile.NativeCMakeAssemblyDeviceTest#managedVcpkgChainloadBuildsAndRunsAssembly' \
  -e vcpkgAssemblyPort 7zip \
  com.wuxianggujun.tinaide.test/androidx.test.runner.AndroidJUnitRunner
```

每次后台仪器测试外层仍需限制为 60 秒。断言同时检查退出码、`asm=42` 与 7zip 模式下的 `7zip=ok`。
夹具目录保留 UTF-8 的 `acceptance-build.log` 和 `acceptance-output.txt`。

## 本地证据位置

以下文件位于本次工作区的忽略目录 `app/build/`，不作为源码提交；复跑时需重新收集：

- `vcpkg-7zip-before-install-arm64-tina-android-dbg-out.log`：修复前真机的实际汇编错误。
- `vcpkg-7zip-install-arm64.log` / `.json`：生成的 triplet 下，完整 7zip Debug/Release 构建与安装成功。
- `vcpkg-asm-chainload-device-arm64.log`：最终包的两项基础仪器测试。
- `vcpkg-7zip-device-arm64-retry.log`：最终包内的 7zip 回归。
- `vcpkg-7zip-apk-acceptance-output.txt`：`7zip=ok` 与 `asm=42`。
- `vcpkg-7zip-apk-acceptance-build.log`：最终 APK 的配置与链接输出。
