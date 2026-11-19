# Lab 8: SDL3 graphics and events

Goal: draw, handle input, read logs, and exit safely using the native template. First finish [CMake basics](cmake-practice.md).

## Run the template first

1. Use this stage's Market action to install the Android SDL3 package under **Market → Packages**. This is a built-in package, not a similarly named vcpkg port.
2. In the same `native_lab`, copy `main.cpp` to `console-main.cpp` and `CMakeLists.txt` to `CMakeLists.console.txt` for the next lesson. Do not add these backups to a build target.
3. Disable vcpkg integration in project settings so the graphics build no longer uses its toolchain. Return to this existing project; do not create another one.
4. Replace the root `CMakeLists.txt` and `main.cpp` with the examples below. Keep `pricing.*`, `vcpkg.json`, and the backups, but this graphics target will neither compile nor install them.
5. Enable **Graphics options → Floating log** in the run configuration and check the window-ready log on the next run.

`CMakeLists.txt`:

```cmake
cmake_minimum_required(VERSION 3.22)
project(native_lab LANGUAGES CXX)
find_package(SDL3 CONFIG REQUIRED)
add_library(native_lab SHARED main.cpp)
target_compile_features(native_lab PRIVATE cxx_std_17)
target_link_libraries(native_lab PRIVATE SDL3::SDL3)
set_target_properties(native_lab PROPERTIES OUTPUT_NAME "main")
```

`main.cpp`:

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

Select native CMake, target `native_lab`, and SDL3 output. Save and run; expect an orange rectangle on blue. Do not keep running the original library template test executable.

Keep this order in exactly one implementation file:

```cpp
#define SDL_MAIN_USE_CALLBACKS 1
#include <SDL3/SDL.h>
#include <SDL3/SDL_main.h>
```

SDL3 does not include `SDL_main.h` through `SDL.h`. A successful shared-library build without the required entry point does not prove it can run. Merely selecting SDL mode does not convert a console program.

## Change the drawing

The complete example already draws a rectangle. Find its existing drawing code inside `SDL_AppIterate` and replace that code with:

```cpp
SDL_FRect marker{80.0f, 100.0f, 160.0f, 100.0f};
SDL_SetRenderDrawColor(state->renderer, 60, 220, 120, SDL_ALPHA_OPAQUE);
SDL_RenderFillRect(state->renderer, &marker);
```

This replaces a function-body fragment; do not declare `marker` twice. Save and verify that the rectangle turns green, grows, and moves down and right. Create windows and textures during initialization and release them in `SDL_AppQuit`, not on every frame.

## Touch exercise

Add `bool touched = false;` to `AppState`. Convert `appstate` to `AppState*` in `SDL_AppEvent`, toggle the value on `SDL_EVENT_FINGER_DOWN`, and choose a different drawing color based on it. Preserve the existing quit handling. Tap and verify the change.

Do not assume desktop mouse events are identical to Android touch. Check the coordinate units of the particular SDL3 event before converting to renderer coordinates.

## Assets and other libraries

Use project assets and the configured runtime asset location, not a desktop absolute path. Check texture-loading failures and stop drawing invalid textures. SDL2 APIs and initialization return values differ from SDL3.

raylib uses ordinary `main` and NativeActivity, unlike the SDL3 callback entry point. Continue with SDL3 in this course; see the runtime reference for other graphical hosts.

## Acceptance

Check cold start, stop and restart, touch, returning to the editor, and rotation. The IDE must remain usable after the program exits. This example loads no images; image-loading troubleshooting is an optional extension, not required for completion.

## Runtime reference

- [Runtime and graphics log reference](../../help/en/build-project.md)

<!-- tutorial-hint -->
Save the console backups before running the complete SDL3 example. Edit the root `main.cpp` and `CMakeLists.txt`. Install the Android SDL3 package from Market and choose the SDL3 host. No automatic project creation is required.

<!-- tutorial-hint -->
The touch exercise has three parts: store a flag in AppState, toggle it in SDL_AppEvent, and select a rectangle color in SDL_AppIterate. Keep quit handling and resource release. Choose the color before drawing the rectangle.

<!-- tutorial-hint -->
**Replace each existing definition** of `AppState`, `SDL_AppEvent`, and `SDL_AppIterate` with the matching code below. Keep initialization and quit functions; do not append duplicate definitions:

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

Each tap should switch the rectangle between orange and green. Check exit and restart before marking the stage complete.
