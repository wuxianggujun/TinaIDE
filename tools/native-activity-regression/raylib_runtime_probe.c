#include <raylib.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <android/log.h>

int main(void) {
    const char *result_path = getenv("TINA_PROBE_RESULT");
    FILE *result = result_path ? fopen(result_path, "w") : NULL;
    if (!result) return 70;
    InitWindow(480, 640, "TinaIDE raylib isolation regression");
    const bool ready = IsWindowReady();
    fprintf(result, "pid=%d video=%d window=%d\n", getpid(), ready, ready);
    fflush(result);
    SetTargetFPS(30);
    const char *overlay_ready = getenv("TINA_PROBE_READY");
    const char *action = getenv("TINA_PROBE_ACTION");
    const bool test_logs = action && strcmp(action, "log-search") == 0;
    for (int frame = 0; ready && frame < (test_logs ? 1200 : 450) && !WindowShouldClose(); ++frame) {
        if (test_logs && frame % 60 == 0) {
            for (int touch = 0; touch < 600; ++touch)
                __android_log_print(ANDROID_LOG_INFO, "DynamicFramerate [AnimationSpeedAware]", "onEventHandled: info: InputEventInfo: mAction = 0, event = %d", touch);
            __android_log_print(ANDROID_LOG_INFO, "TinaLogProbe", "needle first 中文😀");
            __android_log_print(ANDROID_LOG_WARN, "TinaLogProbe", "needle second");
            __android_log_print(ANDROID_LOG_INFO, "TinaLogProbe", "user onEventHandled kept");
        }
        BeginDrawing();
        ClearBackground(DARKBLUE);
        DrawRectangle(20 + frame * 2, 200, 100, 100, SKYBLUE);
        DrawText("TinaIDE isolated raylib", 25, 60, 28, RAYWHITE);
        EndDrawing();
        if (frame > 30 && overlay_ready && access(overlay_ready, F_OK) == 0) break;
    }
    fprintf(result, "action=%s\n", action ? action : "return");
    fclose(result);
    if (action && strcmp(action, "abort") == 0) abort();
    if (action && strcmp(action, "exit") == 0) _exit(42);
    CloseWindow();
    return ready ? 0 : 71;
}
