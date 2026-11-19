# Lab 7: Add fmt to the existing project

Prerequisite: [CMake targets and libraries](cmake-practice.md). Continue with the same `native_lab`; do not create another project. Complete the loop: manifest → install → CMake linkage → build → run.

## Work through it

1. Use this stage's Market button to open **Market → vcpkg**, select `native_lab`, and add `fmt`. Confirm the card says **In manifest**. This edits `vcpkg.json`; it does not mean the library is installed.
2. Replace the root `CMakeLists.txt` with the content below. Keep the existing `pricing` library and executable. Do not enter a manual toolchain or installation path:

```cmake
cmake_minimum_required(VERSION 3.22)
project(native_lab LANGUAGES CXX)
find_package(fmt CONFIG REQUIRED)
add_library(pricing STATIC pricing.cpp)
target_include_directories(pricing PUBLIC "${CMAKE_CURRENT_SOURCE_DIR}")
target_compile_features(pricing PUBLIC cxx_std_17)
add_executable(native_lab main.cpp)
target_link_libraries(native_lab PRIVATE pricing fmt::fmt)
```

3. Keep the previous pricing logic in `main.cpp`, change its output to `fmt::print("total={}\n", total);`, and include `<fmt/core.h>`. Save and run `native_lab`.
4. The first build installs the manifest dependency. After installation, configuration, and build all succeed, the terminal should print `total=30`. **Installed** is an installation state; **In manifest** only means the dependency is declared.

## Diagnose and accept

- If fmt is not found, check the selected project, `vcpkg.json`, native CMake mode, and the first useful error in Build Log.
- Temporarily remove `fmt::fmt` from `target_link_libraries` and build to observe the linker error; restore it and run again.
- Build once more to confirm the project reuses the installed dependency. Do not fix this by deleting the project, copying desktop libraries, or entering obsolete installation paths.
- Independently complete dependency addition, build, error recovery, and run verification before checking **I completed this step**.

For package compatibility, shared libraries, proxies, catalog updates, or installation states, open the [vcpkg dependency reference](../../help/en/vcpkg-guide.md). Help does not repeat this complete lab.
