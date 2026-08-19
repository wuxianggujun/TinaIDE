# 游戏引擎插件图形运行方案

本文记录 TinaIDE 当前的原生图形运行支持。SDL2/SDL3 使用 SDL 图形宿主；raylib 等“用户共享库导出普通 `main`、图形 runtime 提供 `ANativeActivity_onCreate`”的项目使用独立 NativeActivity 宿主。两条链路共享 `.so` 构建能力和运行界面外壳，但不共享入口约定。

## 目标体验

```text
安装游戏引擎插件
  ↓
新建项目中出现插件模板
  ↓
模板生成 SDL/CMake 或 NativeActivity/CMake 项目
  ↓
点击运行
  ↓
TinaIDE 按运行配置选择 SDL2、SDL3 或 NativeActivity 宿主
  ↓
渲染界面保留统一的悬浮返回、退出确认和可选日志入口
```

Android 图形项目不能把桌面窗口创建流程直接搬到 App 中。插件应提供项目模板、构建配置和运行配置，Activity、窗口生命周期与图形表面由 TinaIDE 的 SDL 或 NativeActivity 运行宿主承载。TinaIDE 不再暴露自研 GUI API 给插件或普通项目调用。

## 统一图形运行外壳

SDL2、SDL3 与 NativeActivity Activity 都通过 `GraphicalRuntimeActivityHost` 接入同一套生命周期、stdout/stderr 重定向和返回编辑器逻辑，并把 `GraphicalRuntimeOverlay` 叠加在各自的渲染表面上方。

- 可拖拽的悬浮返回按钮始终显示，点击后先弹出退出确认，不因渲染库变化而改变操作习惯。
- `enableFloatingLog` 只控制额外的日志按钮和日志面板，不会隐藏返回按钮。
- `ContextCompileGraphicalRuntimeLauncher` 根据 `GraphicalRuntimeLaunchRequest` 分派到 `ContextCompileSdlLauncher` 或 `ContextCompileNativeActivityLauncher`；两条分支分别解析和 staging 自己的运行时依赖。
- 图形库只负责各自的入口协议和渲染，返回、日志、退出确认与回到编辑器由共享外壳负责。

## 当前支持

### 插件项目模板

插件可以通过 `contributions.projectTemplates` 声明模板 ZIP。新建项目向导会合并内置模板与插件模板。

相关实现：

- `PluginManifest.contributions.projectTemplates`
- `PluginManager.listProjectTemplateOptions()`
- `ProjectTemplateInstaller`
- `NewProjectWizardActivity.createPluginProjectIntent()`

示例：

```json
{
  "id": "friend.game.engine.starter",
  "name": "Friend Game Engine Starter",
  "version": "1.0.0",
  "minAppVersion": "0.18.20",
  "type": "config",
  "contributions": {
    "projectTemplates": [
      {
        "id": "friend-engine-sdl3",
        "name": "Friend Engine SDL3 Game",
        "description": "Create an SDL3 game project powered by Friend Engine.",
        "templatePath": "templates/friend-engine-sdl3.zip",
        "buildSystem": "cmake",
        "primaryLanguage": "CPP",
        "requiredPackages": ["sdl3"]
      }
    ]
  }
}
```

模板 ZIP 推荐结构：

```text
friend-engine-sdl3.zip
├── CMakeLists.txt
├── src/main.cpp
├── assets/
├── .tinaide/project.json
└── .tinaide/run_configs.json
```

## SDL 图形运行链路

运行配置使用 `OutputMode.SDL` 表示 SDL 图形运行。

关键实现：

- `SdlLauncher` / `LaunchDispatcher`
- `ContextCompileSdlLauncher`
- `ExternalSdlActivity`
- `ExternalSdl2Activity`
- `SdlRuntimeResolver`
- `SdlRuntimeLibraryStager`

运行前 TinaIDE 会准备构建产物：

1. 运行目标必须是 `.so` 共享库；静态库 `.a` 不能被 Android linker 直接加载。
2. TinaIDE 会递归读取主库及其依赖库的 ELF `DT_NEEDED`，识别 `libSDL2.so`、`libSDL2-2.0.so.0`、`libSDL3.so` 等 SDL2/SDL3 SONAME。
3. TinaIDE 按 ELF `DT_NEEDED` 递归解析依赖，并按“依赖先于使用方”的顺序预加载；只 staging 实际选中的项目 `.so`，不会把构建目录里的无关或过期库全部带入运行环境。
4. 不再用 `File.isFile` 或缺失依赖扫描结果提前拒绝启动；复制或 linker 实际加载失败时，由运行界面直接显示本地化错误对话框。

普通原生可执行文件仍应切换到终端模式运行；SDL 图形运行只接受可由 linker 加载的 `.so` 主库。

## raylib NativeActivity 运行链路

raylib 项目使用 `OutputMode.NATIVE_ACTIVITY`，不能使用 `OutputMode.SDL`。两者的关键差异是：

- SDL 主库导出 `SDL_main`，由 `SDLActivity` 调用。
- raylib 用户主库导出普通 `main`；`libraylib.so` 导出 `ANativeActivity_onCreate` 并引用该 `main`。
- TinaIDE 的 `libtina_native_activity_host.so` 先提供稳定的宿主 `main` 符号，再加载用户 `libmain.so`，最后把调用转发给用户的普通 `main`。
- NativeActivity 依赖闭包中一旦出现 SDL2/SDL3，运行会被拒绝并提示改用 SDL 图形运行，避免 `main` 与 `SDL_main` 入口冲突。

关键实现包括 `NativeActivityLauncher`、`ContextCompileNativeActivityLauncher`、`ExternalNativeActivity`、`NativeActivityRuntimeResolver`、`NativeActivityRuntimeStager` 和 `app/src/main/cpp/native_activity/native_activity_host.c`。

raylib CMake 目标仍必须是共享库：

```cmake
find_package(raylib CONFIG REQUIRED)
add_library(main SHARED src/main.c)
target_link_libraries(main PRIVATE raylib::raylib)
```

`requiredPackages` 是模板级 Android Registry 包 ID 列表。新建向导会在创建目录前检查这些包；缺失时停止创建并打开包管理器。依赖必须按每个模板分别声明，不能用插件级依赖代替，因为同一插件可以贡献多个依赖不同的模板。

首次使用 `requiredPackages` 的插件版本应同时设置支持该字段的 `minAppVersion`。当前最低版本为 `0.18.20`；旧宿主会继续获得插件的历史兼容版本，不会安装一个无法执行创建前依赖检查的新版本。

`src/main.c` 保持普通 `int main(void)`，不要包含 SDL 的 main 重定向头，也不要定义 `SDL_main`。旧版 run config 中误设为 `SDL` 的 raylib 项目会在 schema 7 加载时迁移到 `NATIVE_ACTIVITY`。

对应运行配置只需要选择 NativeActivity，不应再填写 SDL 主版本：

```json
{
  "schemaVersion": 7,
  "configurations": [
    {
      "id": "raylib-debug",
      "name": "raylib Debug",
      "outputMode": "NATIVE_ACTIVITY",
      "targetName": "main",
      "enableFloatingLog": true
    }
  ],
  "selectedId": "raylib-debug"
}
```

### SDL 主版本识别顺序

项目首次生成默认运行配置时，TinaIDE 会扫描 `CMakeLists.txt`、`Android.mk` 和 C/C++ 源码中的 SDL 标记，例如 `find_package(SDL2)`、`SDL2::SDL2`、`target_link_libraries(... SDL2)`、`-lSDL2`、`#include <SDL2/...>` 及对应 SDL3 写法，并把结果写入 `.tinaide/project.json` 的 `sdlVersion` 字段。通用的 `org.libsdl.app.SDLActivity` 同时被 SDL2 和 SDL3 使用，不能单独作为版本依据。

升级旧项目时，如果元数据只有历史 `apkExportType = SDL3` 而没有 `sdlVersion`，TinaIDE 会先用上述版本专属标记重新检测。这样可自动纠正旧检测逻辑因通用 `SDLActivity` 造成的 SDL2 -> SDL3 误判；无法获得更可靠证据时才保留 SDL3 兼容值。

启动已编译产物时按以下顺序决定版本：

1. 递归 ELF 依赖结果优先，编译产物是最终事实来源。
2. ELF 无法识别时，使用运行配置显式选择的 `SDL2` 或 `SDL3`。
3. 未显式选择时，使用项目元数据中的 `sdlVersion`。
4. 仍无法确定时，才按 SDL3、SDL2 的顺序尝试当前可用运行库。

显式版本与 ELF 实际依赖不一致，或依赖图同时出现 SDL2 和 SDL3 时，TinaIDE 会阻止启动并报告版本冲突。版本识别不等同于安装运行库；对应的 SDL `.so` 仍须来自包管理器或运行环境的附加库目录。

### SDL2 Android 宿主约束

SDL2 与 SDL3 的 Android Java/JNI glue 不是可互换 API。TinaIDE 分别使用：

- SDL3：`org.libsdl.app` + `ExternalSdlActivity` + `:sdl` 进程。
- SDL2：`org.libsdl2.app` + `ExternalSdl2Activity` + `:sdl2` 进程。

SDL2 glue、头文件和 `libSDL2.so` 固定来自 SDL `release-2.32.10`。包仓库
必须发布 ID 为 `sdl2` 的 Android shared 包，并使用
`docker/tinaide-pkg/libs/build-sdl2.sh` 构建；该脚本会把 native JNI 注册路径
同步重定位到 `org/libsdl2/app`。官方未重定位的 Android `libSDL2.so` 仍指向
`org/libsdl/app`，不能与 TinaIDE APK 中的 SDL3 glue 混用。

## CMake 模板要求

SDL 图形项目应生成共享库目标：

```cmake
cmake_minimum_required(VERSION 3.22)
project(friend_engine_game)

add_library(friend_engine_game SHARED
    src/main.cpp
)

find_package(SDL3 REQUIRED CONFIG)
target_link_libraries(friend_engine_game PRIVATE SDL3::SDL3)
```

运行配置示例：

```json
{
  "schemaVersion": 7,
  "configurations": [
    {
      "id": "sdl3-debug",
      "name": "SDL3 Debug",
      "outputMode": "SDL",
      "targetName": "friend_engine_game",
      "sdlVersion": "SDL3",
      "sdlOrientation": "LANDSCAPE",
      "enableFloatingLog": true
    }
  ],
  "selectedId": "sdl3-debug"
}
```

`sdlVersion` 可以是 `SDL2`、`SDL3` 或省略（自动检测）。SDL2 模板只需改为 `find_package(SDL2)`、链接 SDL2 target，并将该字段设为 `SDL2` 或交给自动检测。

当 CMake 项目选择了 `OutputMode.SDL`，TinaIDE 会优先选择共享库目标。如果项目没有共享库目标，会提示用户改为 `add_library(... SHARED ...)` 或切换到终端模式。

## APK 导出

SDL3 项目仍可走现有 APK 导出链路。插件模板可以内置 `.tinaide/project.json` 标记 SDL3 项目类型，从而复用现有 SDL3 模板和导出能力。

SDL2 已支持项目识别和 SDL 图形运行，但当前内置 APK 模板仍只有 SDL3。`sdlVersion = SDL2` 不会被错误转换为 `apkExportType = SDL3`，也不会错误显示 SDL3 APK 导出入口。

当前建议：

- 游戏引擎插件优先发布 SDL3/CMake 模板。
- 引擎 runtime 以项目源码、预编译 `.so` 或模板内 CMake 配置形式交付。
- APK 导出只采用当前设备主 ABI 的已安装包运行库，并校验每个 ELF 的真实 ABI；安装状态之外的残留包目录不会参与依赖解析。
- 如果需要独立 Android Activity/View 或 AAR 依赖管理，应先扩展插件系统和 Android Gradle App 构建系统，而不是恢复 TinaIDE 自研 GUI 协议。

## 不再支持

以下能力已移除：

- TinaIDE 自研 GUI 头文件。
- 非 SDL 图形头文件。
- 没有 SDL 或 NativeActivity 入口契约的通用 `.so` 图形宿主。
- 通过自定义渲染回调输出帧缓冲的运行协议。
- 面向该协议的开发者 GUI 预览页。

后续图形能力只在 SDL 与 NativeActivity 两条明确入口契约的运行链路上迭代。
