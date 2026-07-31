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

## Run configurations

Check the selected target, arguments, environment variables, and working directory when a program builds but does not run as expected.

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

Console program output opens in the separate **Terminal**, while graphical programs use **SDL**. No current component writes to the bottom-panel Run Output model, so its empty tab remains hidden.

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
