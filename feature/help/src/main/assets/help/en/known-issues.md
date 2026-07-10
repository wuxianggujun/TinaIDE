# Known Issues

This page lists current limitations that users are likely to encounter.

## Large-project performance

Large C/C++ projects can build and index slowly on mobile hardware. Use incremental builds, consider Remote LSP, and avoid keeping many large files open at once.

## Initial environment preparation

First launch or an upgrade may require time and free storage to prepare runtime assets. Terminal and debugging features may remain unavailable until their optional environment is ready.

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
