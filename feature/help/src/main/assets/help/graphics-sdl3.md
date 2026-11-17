# 实践 8：SDL3 图形与事件

目标：从 TinaIDE 原生图形模板开始，完成绘制、输入、日志和退出检查。先完成[构建基础](cmake-practice.md)。

## 先运行模板

1. 在 **市场 → 包** 安装 SDL3 的 Android 包。这里使用内置包市场，不把它当作 vcpkg 的同名库。
2. 新建内置 **CMake 库** 项目 `native_lab`，不启用 vcpkg，把根目录文件改为下面的完整示例。如果已安装的模板插件提供 SDL3 模板，也可以先运行它；基础离线模板列表不保证包含图形模板。
3. 返回编辑器；在运行配置的 **图形选项 → 悬浮日志** 开启日志，再运行，检查窗口就绪日志。

`CMakeLists.txt`：

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

```cpp
#define SDL_MAIN_USE_CALLBACKS 1
#include <SDL3/SDL.h>
#include <SDL3/SDL_main.h>

struct AppState { SDL_Window* window = nullptr; SDL_Renderer* renderer = nullptr; };

SDL_AppResult SDL_AppInit(void** appstate, int, char**) {
    auto* state = new AppState();
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
        delete state;
    }
    SDL_Quit();
}
```

选择原生 CMake、目标 `native_lab`、SDL3 图形宿主，保存并运行；预期蓝底橙色矩形。不要继续运行原库模板的测试可执行目标。

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

raylib 使用普通 `main` 和 NativeActivity。安装 Android raylib 包，另建 CMake 库项目；把上例的 `find_package` 换为 `find_package(raylib CONFIG REQUIRED)`，链接换为 `raylib::raylib`，保留共享库目标和输出名。将 `main.cpp` 替换为：

```cpp
#include <raylib.h>

int main() {
    InitWindow(640, 480, "raylib lab");
    if (!IsWindowReady()) return 1;
    SetTargetFPS(60);
    while (!WindowShouldClose()) {
        BeginDrawing();
        ClearBackground(DARKBLUE);
        DrawRectangle(40, 40, 120, 80, ORANGE);
        EndDrawing();
    }
    CloseWindow();
    return 0;
}
```

运行配置改选 NativeActivity，构建后确认蓝底矩形。不要把这个入口替换成 SDL3 callbacks，也不要手动拼接 Android Activity。

## 验收

- 冷启动、返回编辑器后再次启动都成功。
- 点击能改变颜色；窗口可退出，IDE 仍可操作。
- 横竖屏变化后绘制可见；资源路径不依赖旧窗口尺寸。
- 故意加载不存在图片，日志说明错误，修正后能重新运行。

## 继续学习

- [AI 协作模拟练习](ai-practice.md)
- [运行配置与图形日志速查](build-project.md)
