# Build a Project

TinaIDE supports single-file, CMake, Make, and NDK native-library workflows.

## Supported project types

- **Single file** — quickly compile one main source file.
- **CMake** — recommended for most structured C/C++ projects.
- **Make** — use an existing Makefile.
- **NDK shared library** — build Android native libraries and a verification target.

## Standard workflow

1. Save current changes.
2. Tap **Run**, **Build**, or **Debug**.
3. Wait for the build to finish.
4. Read the full compiler and linker output in **Build Log**.
5. Review clangd errors and warnings in **Diagnostics**.

After a build failure, the workspace remains on **Build Log** instead of forcing a switch to Diagnostics. LSP diagnostics can be empty or briefly older than the latest build, so treat the compiler and linker output in Build Log as authoritative.

If the editor shows new code but execution still prints template output, verify that the file was saved and that the selected run target is the intended executable or shared library.

## NDK shared-library targets

An NDK shared-library template normally contains:

- the shared library;
- a test executable that loads or exercises it.

The default terminal run may select the test executable. To run the shared-library path, select the correct run configuration or use the SDL run mode.

## CMake projects

The first build configures the project, creates or updates the build directory, and produces compile_commands.json. Completion, navigation, and diagnostics usually become more accurate after this step.

### Using vcpkg libraries

Open **Market → vcpkg** to browse and search immediately. No project or tool path is required. **Download / update catalog** retrieves the latest catalog; the existing list remains available offline.

Tap **Add to project** and select a CMake project. The app enables on-device installation and updates `vcpkg.json`. **Install dependencies and configure project** prepares Android helper tools and sources on first use. The app manages tool paths and keeps dependencies in a private workspace for each project, including projects stored in public directories.

vcpkg libraries compile on your phone, and not every port supports Android. Read the build output for specific failures. Cancelling preserves completed dependencies. For computer-built libraries, select prebuilt mode in project settings and choose a complete export containing `scripts` and `installed`.

## Run configurations

Each run configuration owns its build type. CMake configurations support Debug, Release, RelWithDebInfo, and MinSizeRel; Make and single-file configurations support Debug and Release. This lets one project keep separate Debug and Release configurations.

Project Settings paths, compiler/linker flags, and CMake arguments remain shared by every run configuration in the project. Check the selected build type, target, arguments, environment variables, and working directory when a program builds but does not run as expected.

The output mode of a shared library must match its entry contract: SDL projects export `SDL_main`, while raylib projects keep a plain `main` and use NativeActivity. Switching the mode alone never rewrites your entry point. Note that SDL3's `<SDL3/SDL.h>` does not include `SDL_main.h` automatically (SDL2's does). Without it the project still compiles, but the graphical run flashes and exits immediately. TinaIDE detects this before launch and asks you to add `#include <SDL3/SDL_main.h>`; if the program exits right after startup, the runtime screen shows its exit code and program output in a dialog.

## SDL resource directories

Place images, fonts and shaders in the project's `assets/` directory. In **Run configuration → Graphical options → Resource directory**, you can choose another directory inside the project, for example `resources` or `$ProjectDir$/resources`. Unicode and emoji paths are supported.

SDL2/SDL3 read-only relative opens first preserve existing files in the app's private storage, then search the selected resource directory, then the project root. Leaving the setting empty uses `assets/`, or the project root if `assets/` does not exist. Write, append and read/write modes never redirect into source assets. Traversal and escaping symlinks are rejected.

APK export puts the selected resource directory's contents at the APK assets root. The default exports `assets/` only; it never implicitly includes the entire project. Use `SDL_IOFromFile("font.ttf", "rb")` (SDL3) or `SDL_RWFromFile("font.ttf", "rb")` (SDL2) for the same path in the IDE and exported APK. Direct `fopen` calls in other libraries need their own absolute paths.

## Debugging

LLDB debugging can provide breakpoints, stepping, variables, and call stacks. The current debugging extension uses the optional Linux/PRoot environment. Basic compilation, execution, and local clangd still use the native toolchain by default.

## Custom terminal builds

Use the terminal for custom targets or scripts, for example:

    cmake -S . -B build
    cmake --build build

or:

    make
    make test

## Build diagnostics

For difficult incremental-build issues, enable build diagnostics under **Settings → Developer Options → Diagnostic Logs**. Logs can show save completion, target selection, build planner decisions, compile_commands.json fingerprints, and the final launched artifact. Source contents are not logged.

## Where runtime output appears

Console output opens in the separate **Terminal**. Graphical programs use SDL2, SDL3 or NativeActivity (raylib).
Enable **Graphical options → Floating log** in the selected run configuration, then run again: the log panel opens immediately for all three hosts.
It captures stdout, stderr and Android log tags from the graphical process. Drag either control to move the window; use the log button to collapse or reopen it. The program remains interactive outside the window.
Disabling floating logs keeps the return control visible. The bottom-panel Run Output tab remains hidden when empty.

The SDL3 bridge supports 3.4 / 3.5. SDL 3.2 and unknown JNI interface versions are rejected before loading.

## Process and temporary-resource cleanup

CMake, Make, and compiler processes have timeouts. Cancellation or timeout closes output streams, terminates remaining processes, and deletes the command-specific temporary directory. Persistent project build output, compile_commands.json, incremental caches, and registered artifacts are kept.

## Common problems

### The build fails but the editor shows no error

Read Build Log. Configuration and linker failures may not appear as editor diagnostics, and LSP diagnostics can be empty or briefly stale.

### Completion works but execution fails

The language server can be healthy while the run target, arguments, working directory, or linker output is wrong.

### A newly installed package has no effect

Confirm the Linux or Android platform, wait for installation to finish, reopen the file, and inspect the build log for other missing dependencies.

## Continue learning

- [Compiler settings](compiler-settings.md)
- [Project settings](project-settings.md)
- [Package management](package-manager.md)
- [CMake guide](cmake-guide.md)
- [Known issues](known-issues.md)
