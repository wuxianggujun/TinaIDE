#if SDL_PROBE_MAJOR == 3
#include <SDL3/SDL.h>
#else
#include <SDL2/SDL.h>
#endif
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <unistd.h>
#include <android/log.h>

// Built only by build_runtime_probe.py; loaded through the production SDL Activities.
static bool read_asset(const char *name, const char *expected) {
    char bytes[128] = {};
#if SDL_PROBE_MAJOR == 3
    SDL_IOStream *stream = SDL_IOFromFile(name, "rb");
    if (!stream) return false;
    const auto size = SDL_ReadIO(stream, bytes, sizeof(bytes) - 1);
    SDL_CloseIO(stream);
#else
    SDL_RWops *stream = SDL_RWFromFile(name, "rb");
    if (!stream) return false;
    const auto size = SDL_RWread(stream, bytes, 1, sizeof(bytes) - 1);
    SDL_RWclose(stream);
#endif
    return size == strlen(expected) && strcmp(bytes, expected) == 0;
}

extern "C" __attribute__((visibility("default"))) int SDL_main(int, char **) {
    const char *result_path = getenv("TINA_PROBE_RESULT");
    if (!result_path) return 70;
    FILE *result = fopen(result_path, "w");
    if (!result) return 71;
    const char *action = getenv("TINA_PROBE_ACTION");
    const bool test_logs = action && strcmp(action, "log-search") == 0;
    const bool ascii = read_asset("tina-probe-resource.txt", "configured");
    const bool unicode = read_asset("资源😀.txt", "UTF-8 中文😀");
    const bool fallback = read_asset("tina-probe-root.txt", "root");
    fprintf(result, "pid=%d major=%d ascii=%d unicode=%d root=%d\n", getpid(), SDL_PROBE_MAJOR, ascii, unicode, fallback);
    fflush(result);
#if SDL_PROBE_MAJOR == 3
    const bool initialized = SDL_Init(SDL_INIT_VIDEO);
    SDL_Window *window = initialized ? SDL_CreateWindow("TinaIDE runtime regression", test_logs ? 240 : 320, test_logs ? 320 : 240, 0) : nullptr;
#else
    const bool initialized = SDL_Init(SDL_INIT_VIDEO) == 0;
    SDL_Window *window = initialized ? SDL_CreateWindow("TinaIDE runtime regression", 0, 0, test_logs ? 240 : 320, test_logs ? 320 : 240, 0) : nullptr;
#endif
    SDL_Renderer *renderer = nullptr;
    if (window) {
#if SDL_PROBE_MAJOR == 3
        renderer = SDL_CreateRenderer(window, nullptr);
#else
        renderer = SDL_CreateRenderer(window, -1, 0);
#endif
    }
    fprintf(result, "video=%d window=%d\n", initialized, window != nullptr);
    fprintf(result, "renderer=%d\n", renderer != nullptr);
    fflush(result);
    const char *overlay_ready = getenv("TINA_PROBE_READY");
    for (int i = 0; i < (test_logs ? 2000 : 750); ++i) {
        SDL_Event event;
        while (SDL_PollEvent(&event)) {}
        if (test_logs && i % 100 == 0) {
            for (int touch = 0; touch < 600; ++touch)
                __android_log_print(ANDROID_LOG_INFO, "DynamicFramerate [AnimationSpeedAware]", "onEventHandled: info: InputEventInfo: mAction = 0, event = %d", touch);
            __android_log_print(ANDROID_LOG_INFO, "TinaLogProbe", "needle first 中文😀");
            __android_log_print(ANDROID_LOG_WARN, "TinaLogProbe", "needle second");
            __android_log_print(ANDROID_LOG_INFO, "TinaLogProbe", "user onEventHandled kept");
        }
        if (renderer) {
            SDL_SetRenderDrawColor(renderer, 15, 55 + i % 80, 100, 255);
            SDL_RenderClear(renderer);
            SDL_RenderPresent(renderer);
        }
        SDL_Delay(20);
        if (i > 30 && overlay_ready && access(overlay_ready, F_OK) == 0) break;
    }
    fprintf(result, "action=%s\n", action ? action : "return");
    fclose(result);
    if (action && strcmp(action, "abort") == 0) abort();
    if (action && strcmp(action, "exit") == 0) _exit(42);
    if (renderer) SDL_DestroyRenderer(renderer);
    if (window) SDL_DestroyWindow(window);
    SDL_Quit();
    return ascii && unicode && fallback && window ? 0 : 72;
}
