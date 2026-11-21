# 实践 8：SDL3 图形与事件

目标：从 TinaIDE 原生图形模板开始，完成绘制、输入、日志和退出检查。先完成[构建基础](cmake-practice.md)。

## 先运行模板

1. 点击 **创建／继续本章项目**，自动准备独立的 `tina_course_08_sdl3`、完整示例及 SDL3 运行配置。vcpkg 默认关闭，上一章控制台工程保持不变，无需手工备份或替换文件。
2. 点击本阶段的市场入口，在 **市场 → 包** 安装 SDL3 的 Android 包。它来自内置包市场，不是 vcpkg 的同名库。创建工程不会联网安装库；未装包时需先完成安装才能构建。
3. 从项目列表重新打开本章工程，核对下方已预填的两个文件。
4. 在运行配置的 **图形选项 → 悬浮日志** 开启日志，再运行，检查窗口就绪日志。

`CMakeLists.txt`：

<!-- tutorial-project-file: CMakeLists.txt -->
```cmake
cmake_minimum_required(VERSION 3.22)
project(native_lab LANGUAGES CXX)
find_package(SDL3 CONFIG REQUIRED)
add_library(native_lab SHARED main.cpp)
target_compile_features(native_lab PRIVATE cxx_std_17)
target_link_libraries(native_lab PRIVATE SDL3::SDL3)
set_target_properties(native_lab PROPERTIES OUTPUT_NAME "main")
```

`main.cpp`：

<!-- tutorial-project-file: main.cpp -->
```cpp
#define SDL_MAIN_USE_CALLBACKS 1
#include <SDL3/SDL.h>
#include <SDL3/SDL_main.h>
#include <memory>

struct AppState { SDL_Window* window = nullptr; SDL_Renderer* renderer = nullptr; };
static std::unique_ptr<AppState> appStateStorage;

SDL_AppResult SDL_AppInit(void** appstate, int, char**) {
    appStateStorage = std::make_unique<AppState>();
    auto* state = appStateStorage.get();
    *appstate = state;
    if (!SDL_Init(SDL_INIT_VIDEO)) return SDL_APP_FAILURE;
    if (!SDL_CreateWindowAndRenderer("native_lab", 640, 480, SDL_WINDOW_RESIZABLE,
                                    &state->window, &state->renderer)) return SDL_APP_FAILURE;
    SDL_SetRenderVSync(state->renderer, 1);
    SDL_Log("native_lab: SDL3 window ready");
    return SDL_APP_CONTINUE;
}

SDL_AppResult SDL_AppEvent(void*, SDL_Event* event) {
    return event->type == SDL_EVENT_QUIT ? SDL_APP_SUCCESS : SDL_APP_CONTINUE;
}

SDL_AppResult SDL_AppIterate(void* appstate) {
    auto* state = static_cast<AppState*>(appstate);
    SDL_SetRenderDrawColor(state->renderer, 15, 55, 100, SDL_ALPHA_OPAQUE);
    SDL_RenderClear(state->renderer);
    SDL_FRect marker{40.0f, 40.0f, 120.0f, 80.0f};
    SDL_SetRenderDrawColor(state->renderer, 255, 80, 40, SDL_ALPHA_OPAQUE);
    SDL_RenderFillRect(state->renderer, &marker);
    SDL_RenderPresent(state->renderer);
    return SDL_APP_CONTINUE;
}

void SDL_AppQuit(void* appstate, SDL_AppResult) {
    auto* state = static_cast<AppState*>(appstate);
    if (state) {
        SDL_DestroyRenderer(state->renderer);
        SDL_DestroyWindow(state->window);
    }
    appStateStorage.reset();
    SDL_Quit();
}
```

默认配置已选择原生 CMake、目标 `native_lab` 和 SDL3 图形宿主，装好包后直接运行；预期蓝底橙色矩形。不要把运行宿主切回终端来启动此共享库。

模板应保留以下入口顺序，只在一个 `.cpp` 中包含 `SDL_main.h`：

```cpp
#define SDL_MAIN_USE_CALLBACKS 1
#include <SDL3/SDL.h>
#include <SDL3/SDL_main.h>
```

SDL3 的 `SDL.h` 不自动包含 `SDL_main.h`。缺失它时即使 `.so` 能构建，也不代表宿主能启动它。不要把普通控制台程序只切换为 SDL 模式。

## 完成一次绘制修改

完整示例已经画出了矩形。在 `SDL_AppIterate` 内找到现有的矩形绘制代码，将它替换为下面的片段：

```cpp
SDL_FRect marker{80.0f, 100.0f, 160.0f, 100.0f};
SDL_SetRenderDrawColor(state->renderer, 60, 220, 120, SDL_ALPHA_OPAQUE);
SDL_RenderFillRect(state->renderer, &marker);
```

这是函数内部替换片段，不是完整程序；不要重复声明 `marker`。保存并运行，预期矩形变绿、变大并向右下移动。不要在每帧创建窗口、纹理或重新读大文件；创建放在初始化，释放放在 `SDL_AppQuit`。

## 处理触摸并控制颜色

在 `AppState` 中加 `bool touched = false;`。在 `SDL_AppEvent` 中将 `appstate` 转为 `AppState*`，收到 `SDL_EVENT_FINGER_DOWN` 时翻转 `touched`；不要删除已有退出事件处理。在绘制前根据它选择不同颜色，点击屏幕确认变化。

本课不假设桌面鼠标事件一定等于 Android 触摸事件。需要坐标时检查 SDL3 对该事件的坐标单位，再换算到渲染尺寸。

## 资源与其他图形库

把图片放入项目资源目录后，按项目运行配置设置资源入口，不写电脑绝对路径。纹理创建失败时检查错误与日志，先停止绘制该纹理。SDL2 的事件和初始化返回值与 SDL3 不同，不能直接混用代码。

raylib 使用普通 `main` 和 NativeActivity，与 SDL3 的回调入口不同。本课程继续使用 SDL3；其他图形宿主的选择见运行配置速查。

## 验收

- 冷启动、返回编辑器后再次启动都成功。
- 点击能改变颜色；窗口可退出，IDE 仍可操作。
- 横竖屏变化后绘制可见；资源路径不依赖旧窗口尺寸。
- 本示例不加载图片；图片加载排错属于可选扩展，不作为完成本章的要求。

## 运行参考

- [运行配置与图形日志速查](../help/build-project.md)

<!-- tutorial-hint -->
创建入口自动生成独立 SDL3 工程，无需控制台备份或手工新建文件。装好市场的 Android SDL3 包后运行预填的完整示例；后续练习只改本章 `main.cpp`，不会影响其他章节。

<!-- tutorial-hint -->
触摸练习分三处：`AppState` 保存开关，`SDL_AppEvent` 接收触摸并切换它，`SDL_AppIterate` 根据开关选矩形颜色。保留退出事件和窗口释放；改变颜色应发生在画矩形之前。

<!-- tutorial-hint -->
在 `main.cpp` 中**分别替换**原来的 `AppState`、`SDL_AppEvent` 和 `SDL_AppIterate`，保留初始化与退出函数，不追加第二份同名定义：

```cpp
struct AppState {
    SDL_Window* window = nullptr;
    SDL_Renderer* renderer = nullptr;
    bool touched = false;
};
```

```cpp
SDL_AppResult SDL_AppEvent(void* appstate, SDL_Event* event) {
    if (event->type == SDL_EVENT_QUIT) return SDL_APP_SUCCESS;
    auto* state = static_cast<AppState*>(appstate);
    if (event->type == SDL_EVENT_FINGER_DOWN) state->touched = !state->touched;
    return SDL_APP_CONTINUE;
}
```

```cpp
SDL_AppResult SDL_AppIterate(void* appstate) {
    auto* state = static_cast<AppState*>(appstate);
    SDL_SetRenderDrawColor(state->renderer, 15, 55, 100, SDL_ALPHA_OPAQUE);
    SDL_RenderClear(state->renderer);
    SDL_FRect marker{80.0f, 100.0f, 160.0f, 100.0f};
    if (state->touched) {
        SDL_SetRenderDrawColor(state->renderer, 60, 220, 120, SDL_ALPHA_OPAQUE);
    } else {
        SDL_SetRenderDrawColor(state->renderer, 255, 80, 40, SDL_ALPHA_OPAQUE);
    }
    SDL_RenderFillRect(state->renderer, &marker);
    SDL_RenderPresent(state->renderer);
    return SDL_APP_CONTINUE;
}
```

每次点击屏幕，矩形应在橙色和绿色之间切换；退出再启动仍正常，才能标记本章完成。
