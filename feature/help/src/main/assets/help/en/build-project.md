# Build and run reference

Start with [the learning path](getting-started.md) for guided exercises. This page is a quick reference.

Save, select a run configuration, build, check success, then run. Native CMake receives the Android toolchain and sysroot from the app. Complete environment preparation first. Use single-file projects for small experiments, CMake for multiple files, or Make for an existing Makefile.

## Logs and run configuration

On failure read the first relevant error in **Build log**. Language-server diagnostics may lag. Old terminal output is not evidence that the latest build ran. Check saved files, the selected target, and the build result.

- CMake supports Debug, Release, RelWithDebInfo, and MinSizeRel.
- Console executables use terminal output. Configure arguments, environment, and working directory there.
- SDL2/SDL3 libraries need the matching host and entry point. SDL3 callbacks retain `SDL_main.h` in exactly one implementation file.
- raylib uses ordinary `main` and NativeActivity. An arbitrary `.so` does not become runnable by selecting SDL output.
- An ordinary NDK library can be tested by a separate executable; it is not an SDL application.

## vcpkg

Browse offline under **Market → vcpkg**. Adding a manifest dependency does not edit CMake: keep `find_package` and target linkage. **Install to project** prepares dependencies and configures; build and run the application afterward. Helper tools are bundled; missing library sources or Git objects need a network. Paths are application-managed in both project and shared modes; old manual paths are no longer read.

Change the GitHub/HTTP proxy in Market, then cancel and retry a failed task. Git fetches require smart HTTP support. Do not uninstall the app or copy desktop Linux libraries into an Android project as a workaround.

## Graphics and troubleshooting

Enable **Graphics options → Floating log** in the run configuration. Inspect the user program exit code and errors. Resolve assets relative to the configured working directory or asset entry, not a desktop path. Keep user-runtime failures separate from IDE crashes.

| Error | Check first |
| --- | --- |
| Syntax error | Saved file, line number, and actual source list |
| Undefined reference | Compiled implementation, target linkage, matching declaration |
| Missing package configuration | Installation for this project/ABI and CMake package name |
| Graphics exits immediately | Matching entry point, host, and runtime log |
| File access failure | Working directory, path, permissions, and error return |

## Continue learning

- [Project to execution](ide-workflow.md)
- [CMake lab](cmake-practice.md)
- [vcpkg lab](vcpkg-guide.md)
- [SDL3 lab](graphics-sdl3.md)
