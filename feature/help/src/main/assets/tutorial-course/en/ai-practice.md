# Lab 9: Learn AI collaboration with mock conversations

All user and assistant messages below are static examples bundled with the app. This lesson sends no requests, reads no keys, and makes no model calls. Complete it offline first; connecting a real service is optional.

## Return from graphics to the calculator

1. Keep using `native_lab`. Copy the earlier `console-main.cpp` contents back to `main.cpp` and `CMakeLists.console.txt` back to `CMakeLists.txt`. Back up the graphics code before replacing it.
2. If the console version uses fmt, enable vcpkg again. Select native CMake, terminal output, and target `native_lab` instead of the SDL3 host.
3. Save, build, and verify `total=30` before the link-error exercise. If backups are missing, the third hint provides a minimal recovery version without fmt; vcpkg can stay disabled for that version.

## Mock conversation: specify an acceptance test

**User — mock:** I use TinaIDE on Android with native CMake and C++17. Accept counts 0–100 and prices 0–10000 only. Suggest a minimal change, preserve target names, and list boundary inputs. Add no libraries or network requests.

**Assistant — mock:** Validate both ranges before multiplication, use the agreed error result for invalid input, and make callers check it. Test counts 0, 1, 100, -1, and 101.

Practice: compare this answer with [the functions lesson](cpp-functions.md), apply the change yourself, and run each boundary case. A plausible explanation is not test evidence.

## Mock conversation: diagnose a link error

**User — mock:** I get `undefined reference to totalPrice(int, int)`. Headers are found, but my target is `add_executable(native_lab main.cpp)`. What should I verify?

**Assistant — mock:** Check that `pricing.cpp` defines the function and add it to the target, or link the `pricing` library containing it. Do not include a `.cpp` file as a workaround. Save, rebuild, and verify output.

Practice: intentionally remove the link, then restore it. Share the first relevant error and a minimal example when asking for help, not an entire private repository.

## Mock answer with deliberate mistakes

**Assistant — mock, deliberately wrong:** Add `-lpthread`, copy desktop `/usr/lib` into the Android project, and run any `.so` in SDL mode.

Identify why this is wrong: Android uses Bionic, CMake target relationships should express dependencies, desktop binaries do not become Android libraries, and graphical hosts require matching entry points. Reject this suggestion and verify with the smallest working template.

## Configure your own real service, optionally

1. Open **RikkaHub** in the editor sidebar or its settings entry.
2. Add your provider/channel with its API address, your API key, the correct API type, and an available model. Follow the provider documentation for endpoint details; this course supplies no shared key.
3. Choose that model in RikkaHub. A real request happens only when you explicitly send a message there, and provider charges may apply. The course page remains a local example.
4. On failure check the API type, model, key permissions, and response status. Never put a key in C++ code, Git, a screenshot, a feedback attachment, or a prompt.

TinaIDE stores no second copy of the key for these lessons and never presents a mock answer as a live model response. Do not add a separate request or key-storage path just to complete the course.

## Complete the lab

Write a prompt with environment, minimal code, error, constraints, and expected result. Review a suggested patch, apply one small change, build and run it, and record actual inputs and outputs. Use execution evidence to judge the change.

## Related help

- [Learning path](../../help/en/getting-started.md)
- [Git: save reversible changes](../../help/en/git-basics.md)

<!-- tutorial-hint -->
Complete the recovery steps first. The mock conversations teach review and need no account or live AI. Edit the project files `pricing.cpp`, `main.cpp`, and CMake and actually run them before marking the exercise complete.

<!-- tutorial-hint -->
For link troubleshooting, remove `target_link_libraries(native_lab PRIVATE pricing)` temporarily. Build to observe the undefined totalPrice symbol, then restore the line. Include native Android CMake, the first error, target configuration, expected output, and the no-new-library constraint in your prompt.

<!-- tutorial-hint -->
Without console backups, keep `pricing.h` and `pricing.cpp` from [the functions lesson](cpp-functions.md) and **replace both files below completely**. This minimal version does not use fmt, so vcpkg can remain disabled.

Root `CMakeLists.txt`:

```cmake
cmake_minimum_required(VERSION 3.22)
project(native_lab LANGUAGES CXX)
add_library(pricing STATIC pricing.cpp)
target_include_directories(pricing PUBLIC "${CMAKE_CURRENT_SOURCE_DIR}")
target_compile_features(pricing PUBLIC cxx_std_17)
add_executable(native_lab main.cpp)
target_link_libraries(native_lab PRIVATE pricing)
```

Root `main.cpp`:

```cpp
#include "pricing.h"
#include <iostream>

int main() {
    const int total = totalPrice(3, 10);
    if (total < 0) { std::cerr << "invalid order\n"; return 1; }
    std::cout << "total=" << total << '\n';
    return 0;
}
```

Switch to terminal output and expect `total=30`. Removing the link line must fail the build; restoring it must produce 30 again. Record this sequence in your prompt and test notes to finish the offline exercise.
