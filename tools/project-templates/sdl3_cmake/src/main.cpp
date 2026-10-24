// 入口契约：SDL3 的 <SDL3/SDL.h> 不会自动包含 SDL_main.h（SDL2 的 <SDL2/SDL.h> 会）。
// 漏掉它项目仍能编译成 libmain.so，但运行宿主 dlsym 找不到 SDL_main，表现为
// “启动一闪而过”。TinaIDE 会在运行前检测并提示；请勿删掉下面这行 include。
// 注意：SDL_main.h 是“单头文件库”，整个项目只能在这一个 .cpp 里包含它；
// 回调写法必须在包含它之前定义 SDL_MAIN_USE_CALLBACKS。
#define SDL_MAIN_USE_CALLBACKS 1
#include <SDL3/SDL.h>
#include <SDL3/SDL_main.h>

#include <cmath>

namespace {
struct AppState {
    SDL_Window* window = nullptr;
    SDL_Renderer* renderer = nullptr;
    float phase = 0.0f;
};

Uint8 pulseColor(float phase, float offset) {
    const float value = (std::sin(phase + offset) + 1.0f) * 0.5f;
    return static_cast<Uint8>(value * 255.0f);
}
} // namespace

SDL_AppResult SDL_AppInit(void** appstate, int argc, char* argv[]) {
    (void)argc;
    (void)argv;

    if (!SDL_Init(SDL_INIT_VIDEO)) {
        SDL_Log("SDL_Init failed: %s", SDL_GetError());
        return SDL_APP_FAILURE;
    }

    auto* state = new AppState();
    if (!SDL_CreateWindowAndRenderer("{{PROJECT_NAME}}", 960, 540, 0, &state->window, &state->renderer)) {
        SDL_Log("SDL_CreateWindowAndRenderer failed: %s", SDL_GetError());
        delete state;
        return SDL_APP_FAILURE;
    }

    SDL_SetWindowResizable(state->window, true);
    SDL_SetRenderVSync(state->renderer, 1);
    *appstate = state;
    // 这一行会出现在悬浮日志与“启动失败”对话框里，可用来确认日志链路。
    SDL_Log("{{PROJECT_NAME}}: SDL3 window ready");
    return SDL_APP_CONTINUE;
}

SDL_AppResult SDL_AppEvent(void* appstate, SDL_Event* event) {
    (void)appstate;

    if (event->type == SDL_EVENT_QUIT) {
        return SDL_APP_SUCCESS;
    }

    if (event->type == SDL_EVENT_KEY_DOWN && event->key.key == SDLK_ESCAPE) {
        return SDL_APP_SUCCESS;
    }

    return SDL_APP_CONTINUE;
}

SDL_AppResult SDL_AppIterate(void* appstate) {
    auto* state = static_cast<AppState*>(appstate);
    if (state == nullptr || state->renderer == nullptr) {
        return SDL_APP_FAILURE;
    }

    state->phase += 0.02f;

    // 用一个简单的动态渐变确认窗口、渲染器和事件循环都已工作。
    SDL_SetRenderDrawColor(
        state->renderer,
        pulseColor(state->phase, 0.0f),
        pulseColor(state->phase, 2.0f),
        pulseColor(state->phase, 4.0f),
        SDL_ALPHA_OPAQUE
    );
    SDL_RenderClear(state->renderer);

    SDL_FRect rect = {
        180.0f + std::sin(state->phase) * 80.0f,
        140.0f,
        220.0f,
        220.0f
    };
    SDL_SetRenderDrawColor(state->renderer, 255, 255, 255, SDL_ALPHA_OPAQUE);
    SDL_RenderFillRect(state->renderer, &rect);
    SDL_RenderPresent(state->renderer);
    return SDL_APP_CONTINUE;
}

void SDL_AppQuit(void* appstate, SDL_AppResult result) {
    (void)result;

    auto* state = static_cast<AppState*>(appstate);
    if (state != nullptr) {
        if (state->renderer != nullptr) {
            SDL_DestroyRenderer(state->renderer);
        }
        if (state->window != nullptr) {
            SDL_DestroyWindow(state->window);
        }
        delete state;
    }

    SDL_Quit();
}
