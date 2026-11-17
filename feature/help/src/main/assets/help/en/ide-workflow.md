# Lab 1: From project to execution

Goal: finish the edit, save, build, run, and verify cycle yourself. This course uses the native Android C/C++ toolchain. It requires neither a Linux environment nor an AI account.

## Create and run

1. Open **Projects → + → New project**, choose **CMake executable**, name it `native_lab`, and select C++17. Leave vcpkg off for now.
2. Finish the environment preparation requested by the app before building.
3. Use the following root-level files. If the template initially uses `src/main.cpp`, update its source list to match this example.

`CMakeLists.txt`:

```cmake
cmake_minimum_required(VERSION 3.22)
project(native_lab LANGUAGES CXX)
add_executable(native_lab main.cpp)
target_compile_features(native_lab PRIVATE cxx_std_17)
```

`main.cpp`:

```cpp
#include <iostream>

int main() {
    std::cout << "Hello, TinaIDE!\n";
    return 0;
}
```

4. Save both files. Select native CMake, terminal output, and target `native_lab` in the run configuration; run it.
5. Find `Hello, TinaIDE!` in the terminal. Change the text to `First change`, save, and verify the new output.

## Diagnose an intentional error

Delete the semicolon after `return 0`, save, and build. Find the first relevant error in **Build log**, restore the semicolon, and run again. Old terminal output after a failed build does not prove the new program ran.

If the output remains unchanged, check saved files, the selected target, and whether this build succeeded. Language-server diagnostics may lag behind the actual compiler log.

## Your exercise

Print two lines, rename the target to `hello_lab` and update the run configuration, then reopen the project and run again. Mark the lesson complete only after checking the result. Save source and text files as UTF-8.

## Continue learning

- [C++ values and control flow](cpp-basics.md)
- [Editor reference](editor-basics.md)
- [Build troubleshooting](build-project.md)
