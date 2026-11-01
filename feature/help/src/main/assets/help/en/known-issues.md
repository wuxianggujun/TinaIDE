# Known Issues

This page lists current limitations that users are likely to encounter.

## Large-project performance

Large C/C++ projects can build and index slowly on mobile hardware. Use incremental builds, consider Remote LSP, and avoid keeping many large files open at once.

## Initial environment preparation

First launch or an upgrade may require time and free storage to prepare runtime assets. Terminal and debugging features may remain unavailable until their optional environment is ready.

TinaIDE 0.18.22 and earlier may report `tar entry escapes target directory` while deploying the Android sysroot on some Redmi / HyperOS devices. This is caused by Android treating `/data/user/0` and `/data/data` as aliases for the same app data directory; it does not by itself indicate a damaged archive or insufficient storage.

Upgrade to TinaIDE 0.18.23 or later, then redeploy the development environment from Settings. If deployment still fails, export the diagnostic log and include the device model, Android version, and complete error message.

## External workspaces and SAF

Many small operations through Android Storage Access Framework can be slower than internal project paths. Keep workspaces tidy and exclude unrelated generated files.

## Device-dependent PRoot and debugging behavior

ROM behavior, SELinux policy, and device details can affect the optional PRoot terminal and LLDB chain. Validate normal native build and run before debugging, and keep logs when reporting a compatibility problem.

## Hardware-keyboard shortcuts

Software keyboards do not reliably send Ctrl, Shift, Alt, or F2 combinations. Treat editor shortcuts as a hardware-keyboard enhancement.

## System requirements

- Minimum: Android 9 / API 28
- Recommended: at least 3 GB of memory
- Keep enough free storage for projects, toolchains, and build output

## Open-source scope and RikkaHub

The public app retains plugin and package downloads, favorites, download history, and GitHub Release update notices. Private identity, commercial features, and private backend services are not distributed.

TinaIDE no longer implements a separate AI stack. Open embedded RikkaHub from the editor sidebar and configure providers, models, MCP, assistants, and API keys inside RikkaHub.

## NDK shared-library output looks like the old template

The template includes a shared library and a test executable. The default run configuration may execute the test target. Save the file, inspect the selected target, and choose SDL mode or the shared-library target when appropriate.

Syntax highlighting showing new code does not prove that the run configuration launches that code.

## raylib runs but the screen stays black

Use TinaIDE 0.18.39 or later, raylib 6.0 revision 3, and the current raylib template. Updating TinaIDE alone
does not replace an already installed library. Refresh the package market, check for updates, install raylib,
then clean and rebuild the test project. The upstream version can still read `6.0`; the package revision matters.
Do not clear the IDE data or delete project sources. Replace any old raylib library copied into the project.

Revision 1 accidentally enabled custom frame control, so `EndDrawing()` omitted normal buffer swapping and input polling.
Revision 2 could deadlock while handling Android destruction after gesture Back, leaving the old process alive.
Revision 3 fixes that event loop, including cancellation before a window is available. The host also reclaims a stuck
graphical child process after an explicit exit without terminating the IDE. Old r1/r2 libraries are rejected
during installation, launch preparation, and APK export.

Starting in 0.18.39, one request owns saving, compilation, and the queued launch. Rapid Run taps do not queue
additional windows. Use the separate Stop button to cancel a pending build or launch before starting another run.
Refreshing the market also refreshes the live registry index, and updates compare revisions of the same upstream version.

Games have no total runtime limit. The two-second guard applies only after an explicit exit; Home and ordinary
backgrounding do not arm it. A new explicit Run replaces the previous graphical session.

Updating the IDE does not modify previously exported standalone APKs. Export raylib/SDL3 projects again with
the updated IDE to include the single-instance entry point and explicit-exit guard. Home still backgrounds
the game without imposing a runtime limit.

Update the SDL3 market template to 1.0.4 or later. For an existing project with a half-screen startup frame,
pass `SDL_WINDOW_RESIZABLE` directly to `SDL_CreateWindowAndRenderer` rather than creating with `0` and then
calling `SDL_SetWindowResizable`; this avoids conflicting orientation requests during startup.

On Android, raylib's public `GetWindowHandle()` returns `NULL`; that value is not the underlying `ANativeWindow*`.
The backend uses an EGL window surface, not an offscreen pbuffer. Rendering on the dedicated `native_app_glue` thread is normal;
do not move EGL calls to the Android UI thread solely because the public handle is zero.

Verify visible animation and touch input, 20 successive runs with gesture Back, rapid Run taps, cancellation, and background recovery.
If the current package is still black, record the device, Android version, package revision, actual EGL surface size/type,
buffer-swap results, and a minimal project. Test IDE execution and an exported APK separately.
An x86_64 emulator rendering successfully does not establish ARM64 device compatibility.

## Build cleanup after timeout or cancellation

TinaIDE terminates short-lived compiler processes, closes output streams, and removes command-specific temporary directories. It does not automatically remove the project build directory, compile_commands.json, incremental cache, or successful artifacts.

Use the project cleanup action rather than deleting unknown internal directories.

## Reporting a problem

Export logs from About and include:

- device model;
- Android and TinaIDE versions;
- reproducible steps;
- the exported diagnostic log after reviewing it for sensitive information.

## Related documentation

- [About and logs](about-and-logs.md)
- [Terminal troubleshooting](terminal-troubleshooting.md)
- [Build a project](build-project.md)

Updated: 2026-11-01
