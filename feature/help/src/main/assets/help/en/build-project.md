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

Only one floating control is shown initially. Tap it to open **Runtime Log** or choose **Back to Editor**. Drag the log title to move, double-tap it to maximize / restore, and drag the bottom-right grip to resize. Minimizing retains size and position and keeps capturing logs; it does not exit. Returning to the editor requires confirmation and stops the program. Touches outside the popup continue to the graphical program; resizing logs does not change its rendering size.

SDL2, SDL3 and NativeActivity/raylib share the same log filtering. Program stdout/stderr (such as `printf` and `std::cout`), native engine logs and arbitrary custom Android tags remain visible by default, including verbose/debug output. Java crashes, native fatal errors and host warnings/errors are also retained. Recognized Android window/input-method chatter and routine host initialization/lifecycle logs are hidden to keep program output readable. Enable **Show all process logs** in the log window's overflow menu to inspect those host and system diagnostics; each message includes its `[Tag]`. Both modes stay scoped to the current graphical process. Switching modes reloads recent logs and replaces the current view and selection; minimizing and reopening retain the selected mode.

Routine touch diagnostics from the vendor `DynamicFramerate` component, such as `onEventHandled`, are also hidden by default, including tags with spaces or window decorations. Filtering uses the source tag, so user messages containing the same method name remain visible. Warnings/errors from this component are retained; enable **Show all process logs** for its full diagnostics.

Menus follow their control or overflow button and adjust near display edges. Logs use TinaEditor's read-only view with pinned line numbers and no Debug bar. Lines do not wrap by default; scroll horizontally to inspect long lines, or toggle **Word Wrap** in the log window's overflow menu. Minimizing and reopening retain this choice. New output does not force the view to the bottom while you read older logs. The bounded buffer discards its oldest lines as output continues.

Choose **Search logs** in the title bar and enter a keyword to select a match. The up/down arrows cycle through matches, and the counter shows the current match and total. Search is case-insensitive and literal, with Unicode/emoji support; regular expressions are not interpreted. The keyboard's Search action dismisses query editing while keeping the results; tap the keyword to edit again. Searching pauses tail following, and minimizing preserves the query and results. Only retained logs are searched (up to 500 lines, also subject to the text-size limit). Results are capped at 2000, with `2000+` indicating additional matches. Discarded logs cannot be searched.

A compact colored priority column appears next to the line numbers, such as `I` for information, `W` for warning, and `E` for error. It reserves no breakpoint/folding controls and cannot trigger breakpoint actions; priority prefixes no longer take up body columns. Multiline messages retain their priority on each logical line, while soft-wrapped continuations do not repeat the marker. The normal code editor keeps its breakpoint and folding features.

The log body also uses priority colors: blue for information, yellow for warnings, red for errors, and a subdued color for debug output. Long-press the text, adjust the selection handles, then choose **Copy**, or use **Select All** to copy the current buffer. Copied text preserves line breaks, Unicode and emoji, without line numbers or priority labels. An active selection pauses tail following. When old lines expire, surviving selections move with their text and fully expired selections are cleared. The log body remains read-only, with no keyboard, Cut or Paste actions; only the search field opens the keyboard.

| Error | Check first |
| --- | --- |
| Syntax error | Saved file, line number, and actual source list |
| Undefined reference | Compiled implementation, target linkage, matching declaration |
| Missing package configuration | Installation for this project/ABI and CMake package name |
| Graphics exits immediately | Matching entry point, host, and runtime log |
| File access failure | Working directory, path, permissions, and error return |

## Continue learning

- [Project to execution](../../tutorial-course/en/ide-workflow.md)
- [CMake lab](../../tutorial-course/en/cmake-practice.md)
- [vcpkg lab](vcpkg-guide.md)
- [SDL3 lab](../../tutorial-course/en/graphics-sdl3.md)
