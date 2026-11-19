# Lab 6: CMake targets and libraries

Use the three source files from [the functions lesson](cpp-functions.md). Build `pricing.cpp` as a library and link it into the executable:

```cmake
cmake_minimum_required(VERSION 3.22)
project(native_lab LANGUAGES CXX)
add_library(pricing STATIC pricing.cpp)
target_include_directories(pricing PUBLIC "${CMAKE_CURRENT_SOURCE_DIR}")
target_compile_features(pricing PUBLIC cxx_std_17)
add_executable(native_lab main.cpp)
target_link_libraries(native_lab PRIVATE pricing)
```

Save and run; expect `total=30`. A static library contributes code during linking. A shared library also needs a compatible runtime host and loader; an arbitrary `.so` is not an executable.

## Three stages

| Stage | Work | Common errors |
| --- | --- | --- |
| Configure | Read CMake, discover tools and dependencies, generate rules | Conflicting targets or missing package configuration |
| Build | Compile sources and link targets | Syntax errors, duplicate definitions, unresolved symbols |
| Run | Start the executable or graphical host | Wrong target, missing assets, nonzero exit |

Native CMake in TinaIDE already supplies its Android toolchain and sysroot. Do not paste desktop compiler paths or Linux library paths into this project.

`PRIVATE` affects a target; `PUBLIC` also propagates to its consumers; `INTERFACE` affects consumers only. Prefer target-specific settings over global flags. List new `.cpp` files explicitly and update the list after renaming them. Check the selected run target and build type.

## Your exercise

Remove the link to `pricing`, observe the linker error, restore it, and run again. Then add `fmt` in the next lesson. The manifest installs a dependency, `find_package` loads its CMake definitions, and `target_link_libraries` expresses actual use.

Complete the lesson when a library edit triggers rebuilding, the program runs, and recovery after a configuration error does not require deleting the project.

## Quick reference

- [CMake reference](../../help/en/cmake-guide.md)

<!-- tutorial-hint -->
Replace only the root `CMakeLists.txt`, keeping the previous `main.cpp`, `pricing.h`, and `pricing.cpp`. Do not compile `pricing.cpp` into both the library and executable.

<!-- tutorial-hint -->
The pricing target compiles the rules. The native_lab target compiles main and links pricing. Intentionally deleting the link should produce an unresolved symbol; restore the target relationship instead of including a `.cpp` file.

<!-- tutorial-hint -->
After both targets have been defined, restore this line:

```cmake
target_link_libraries(native_lab PRIVATE pricing)
```

Keep the target definitions and include directory from the complete CMake above. Save, build, and run; expect `total=30`. For a missing source file, check its location and filename case first.
