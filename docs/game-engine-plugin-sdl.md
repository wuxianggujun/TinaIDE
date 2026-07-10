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
- `SdlRuntimeResolver`
- `SdlRuntimeLibraryStager`
- `CompileUiEventObserver`
- `SdlLauncher`

运行前 TinaIDE 会准备构建产物：

1. 运行目标必须是 `.so` 共享库；静态库 `.a` 不能被 Android linker 直接加载。
2. TinaIDE 会尽力从动态依赖识别 SDL2/SDL3；若 SDL 被静态链接或扫描不到版本，则优先尝试已安装的 SDL3，再尝试 SDL2。
3. TinaIDE 按 ELF `DT_NEEDED` 递归解析依赖，并按“依赖先于使用方”的顺序预加载；只 staging 实际选中的项目 `.so`，不会把构建目录里的无关或过期库全部带入运行环境。
4. 不再用 `File.isFile` 或缺失依赖扫描结果提前拒绝启动；复制或 linker 实际加载失败时，由运行界面直接显示本地化错误对话框。

普通原生可执行文件仍应切换到终端模式运行；SDL 图形运行只接受可由 linker 加载的 `.so` 主库。

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
  "selected": "sdl3-debug",
  "configs": [
    {
      "id": "sdl3-debug",
      "name": "SDL3 Debug",
      "outputMode": "SDL",
      "targetName": "friend_engine_game",
      "sdlOrientation": "LANDSCAPE",
      "enableFloatingLog": true
    }
  ]
}
```

当 CMake 项目选择了 `OutputMode.SDL`，TinaIDE 会优先选择共享库目标。如果项目没有共享库目标，会提示用户改为 `add_library(... SHARED ...)` 或切换到终端模式。

## APK 导出

SDL3 项目仍可走现有 APK 导出链路。插件模板可以内置 `.tinaide/project.json` 标记 SDL3 项目类型，从而复用现有 SDL3 模板和导出能力。

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
