#include <raylib.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

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
    for (int frame = 0; ready && frame < 450 && !WindowShouldClose(); ++frame) {
        BeginDrawing();
        ClearBackground(DARKBLUE);
        DrawRectangle(20 + frame * 2, 200, 100, 100, SKYBLUE);
        DrawText("TinaIDE isolated raylib", 25, 60, 28, RAYWHITE);
        EndDrawing();
        if (frame > 30 && overlay_ready && access(overlay_ready, F_OK) == 0) break;
    }
    const char *action = getenv("TINA_PROBE_ACTION");
    fprintf(result, "action=%s\n", action ? action : "return");
    fclose(result);
    if (action && strcmp(action, "abort") == 0) abort();
    if (action && strcmp(action, "exit") == 0) _exit(42);
    CloseWindow();
    return ready ? 0 : 71;
}
