#include <SDL2/SDL.h>

#include <cmath>

namespace {
Uint8 pulseColor(float phase, float offset) {
    const float value = (std::sin(phase + offset) + 1.0f) * 0.5f;
    return static_cast<Uint8>(value * 255.0f);
}
} // namespace

int main(int argc, char* argv[]) {
    (void)argc;
    (void)argv;

    if (SDL_Init(SDL_INIT_VIDEO) != 0) {
        SDL_Log("SDL_Init failed: %s", SDL_GetError());
        return 1;
    }

    SDL_Window* window = SDL_CreateWindow(
        "{{PROJECT_NAME}}",
        SDL_WINDOWPOS_CENTERED,
        SDL_WINDOWPOS_CENTERED,
        960,
        540,
        SDL_WINDOW_RESIZABLE
    );
    if (window == nullptr) {
        SDL_Log("SDL_CreateWindow failed: %s", SDL_GetError());
        SDL_Quit();
        return 1;
    }

    // SDL2 通过创建标志开启垂直同步；SDL2 没有 SDL_SetRenderVSync。
    SDL_Renderer* renderer = SDL_CreateRenderer(
        window,
        -1,
        SDL_RENDERER_ACCELERATED | SDL_RENDERER_PRESENTVSYNC
    );
    if (renderer == nullptr) {
        SDL_Log("SDL_CreateRenderer failed: %s", SDL_GetError());
        SDL_DestroyWindow(window);
        SDL_Quit();
        return 1;
    }

    bool running = true;
    float phase = 0.0f;
    while (running) {
        SDL_Event event;
        while (SDL_PollEvent(&event) != 0) {
            if (event.type == SDL_QUIT) {
                running = false;
            } else if (event.type == SDL_KEYDOWN && event.key.keysym.sym == SDLK_ESCAPE) {
                running = false;
            }
        }

        phase += 0.02f;

        // 用一个简单的动态渐变确认窗口、渲染器和事件循环都已工作。
        SDL_SetRenderDrawColor(
            renderer,
            pulseColor(phase, 0.0f),
            pulseColor(phase, 2.0f),
            pulseColor(phase, 4.0f),
            SDL_ALPHA_OPAQUE
        );
        SDL_RenderClear(renderer);

        SDL_Rect rect = {
            static_cast<int>(180.0f + std::sin(phase) * 80.0f),
            140,
            220,
            220
        };
        SDL_SetRenderDrawColor(renderer, 255, 255, 255, SDL_ALPHA_OPAQUE);
        SDL_RenderFillRect(renderer, &rect);
        SDL_RenderPresent(renderer);
    }

    SDL_DestroyRenderer(renderer);
    SDL_DestroyWindow(window);
    SDL_Quit();
    return 0;
}
