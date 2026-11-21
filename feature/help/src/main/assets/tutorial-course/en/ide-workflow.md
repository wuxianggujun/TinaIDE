# Lab 1: From project to execution

Goal: finish the edit, save, build, run, and verify cycle yourself. This course uses the native Android C/C++ toolchain. It requires neither a Linux environment nor an AI account.

## Create and run

1. Tap **Create / resume chapter project** above. The app creates `tina_course_01_ide` with complete C++17 files, target `native_lab`, and vcpkg disabled.
2. Finish the environment preparation requested by the app before building.
3. The editor opens the project automatically. The root `CMakeLists.txt` and `main.cpp` already contain the examples below; no manual file creation or initialization paste is needed.

Each chapter has its own project in app-private storage, available from Projects for reopening or export. Repeated taps resume it without overwriting edits. Unrelated directories are preserved; a name conflict adds a suffix to the new project.

`CMakeLists.txt`:

<!-- tutorial-project-file: CMakeLists.txt -->
```cmake
cmake_minimum_required(VERSION 3.22)
project(native_lab LANGUAGES CXX)
add_executable(native_lab main.cpp)
target_compile_features(native_lab PRIVATE cxx_std_17)
```

`main.cpp`:

<!-- tutorial-project-file: main.cpp -->
```cpp
#include <iostream>

int main() {
    std::cout << "Hello, TinaIDE!\n";
    return 0;
}
```

4. The default run configuration already selects native CMake, terminal output, and `native_lab`; run it directly. Save your subsequent exercise edits.
5. Find `Hello, TinaIDE!` in the terminal. Change the text to `First change`, save, and verify the new output.

## Diagnose an intentional error

Delete the semicolon after `return 0`, save, and build. Find the first relevant error in **Build log**, restore the semicolon, and run again. Old terminal output after a failed build does not prove the new program ran.

If the output remains unchanged, check saved files, the selected target, and whether this build succeeded. Language-server diagnostics may lag behind the actual compiler log.

## Your exercise

Print two lines, rename the target to `hello_lab` and update the run configuration, then reopen the project and run again. Mark the lesson complete only after checking the result. Save source and text files as UTF-8.

After the rename exercise, restore `native_lab` in CMake and the run configuration before continuing.

## Quick references

- [Editor reference](../../help/en/editor-basics.md)
- [Build troubleshooting](../../help/en/build-project.md)

<!-- tutorial-hint -->
Create / resume chapter project opens both prefilled files. Run the baseline first, then edit it. Copying a block is optional for reviewing or restoring exercise code, not a prerequisite for project creation; existing edits are never overwritten automatically.

<!-- tutorial-hint -->
Put both output statements inside `int main()`, before `return 0;`. For the rename exercise change the target in `add_executable`, `target_compile_features`, and the run configuration. Restore `native_lab` afterward.

<!-- tutorial-hint -->
For the two-line exercise, **replace all of main.cpp** with:

```cpp
#include <iostream>

int main() {
    std::cout << "Hello, TinaIDE!\n";
    std::cout << "First change\n";
    return 0;
}
```

Save, build, and run; expect two lines. If it fails, check the CMake source path and the first build error before marking the stage complete.
