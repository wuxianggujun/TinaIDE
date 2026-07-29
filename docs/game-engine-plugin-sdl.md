# 游戏引擎插件图形运行方案

本文记录 TinaIDE 当前对游戏引擎插件的 SDL 图形运行支持。当前仅保留 SDL2/SDL3 运行链路，不再提供 TinaIDE 自研 GUI 头文件、绘制协议或非 SDL 图形宿主。

## 目标体验

```text
安装游戏引擎插件
  ↓
新建项目中出现插件模板
  ↓
模板生成 SDL/CMake 项目
  ↓
点击运行
  ↓
TinaIDE 通过 SDL 运行时打开运行界面
```

Android 依赖库不能像桌面程序一样自行创建窗口。插件应提供项目模板、构建配置和运行配置，实际图形窗口由 TinaIDE 内置的 SDL 运行时承载。TinaIDE 不再暴露自研 GUI API 给插件或普通项目调用。

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
  "type": "config",
  "contributions": {
    "projectTemplates": [
      {
        "id": "friend-engine-sdl3",
        "name": "Friend Engine SDL3 Game",
        "description": "Create an SDL3 game project powered by Friend Engine.",
        "templatePath": "templates/friend-engine-sdl3.zip",
        "buildSystem": "cmake",
        "primaryLanguage": "CPP"
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

- `ExternalSdlActivity`
- `ExternalSdl2Activity`
- `SdlRuntimeResolver`
- `SdlRuntimeLibraryStager`
- `CompileUiEventObserver`
- `SdlLauncher`

运行前 TinaIDE 会准备构建产物：

1. 运行目标必须是 `.so` 共享库；静态库 `.a` 不能被 Android linker 直接加载。
2. TinaIDE 会递归读取主库及其依赖库的 ELF `DT_NEEDED`，识别 `libSDL2.so`、`libSDL2-2.0.so.0`、`libSDL3.so` 等 SDL2/SDL3 SONAME。
3. TinaIDE 按 ELF `DT_NEEDED` 递归解析依赖，并按“依赖先于使用方”的顺序预加载；只 staging 实际选中的项目 `.so`，不会把构建目录里的无关或过期库全部带入运行环境。
4. 不再用 `File.isFile` 或缺失依赖扫描结果提前拒绝启动；复制或 linker 实际加载失败时，由运行界面直接显示本地化错误对话框。

普通原生可执行文件仍应切换到终端模式运行；SDL 图形运行只接受可由 linker 加载的 `.so` 主库。

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
  "schemaVersion": 6,
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
- 通用 `.so` 图形宿主。
- 通过自定义渲染回调输出帧缓冲的运行协议。
- 面向该协议的开发者 GUI 预览页。

后续图形能力只在 SDL 运行链路上迭代。
