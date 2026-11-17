# CMake configuration reference

Use [the CMake lab](cmake-practice.md) for practice. A minimal native executable is:

```cmake
cmake_minimum_required(VERSION 3.22)
project(native_lab LANGUAGES CXX)
add_executable(native_lab main.cpp)
target_compile_features(native_lab PRIVATE cxx_std_17)
```

List implementation files explicitly and keep names consistent with disk. Prefer target-specific settings:

```cmake
add_library(pricing STATIC pricing.cpp)
target_include_directories(pricing PUBLIC "${CMAKE_CURRENT_SOURCE_DIR}/include")
target_link_libraries(native_lab PRIVATE pricing)
```

PRIVATE affects the target, PUBLIC also affects consumers, and INTERFACE affects only consumers. The example include path requires an actual `include/` directory.

## Dependencies

Add `fmt` to the selected project in Market and install it, then use:

```cmake
find_package(fmt CONFIG REQUIRED)
target_link_libraries(native_lab PRIVATE fmt::fmt)
```

See [the vcpkg lab](vcpkg-guide.md). TinaIDE supplies tool and installation paths. Do not enter `CMAKE_TOOLCHAIN_FILE`, obsolete `TINA_VCPKG_ROOT`, or a desktop sysroot. For threads use `find_package(Threads REQUIRED)` and `Threads::Threads`, not a copied desktop `-lpthread` flag.

## Diagnose

Read the first configure error for missing packages or targets. Check include paths for header failures, implementation files and target linkage for unresolved symbols, and ABI compatibility for libraries. Select Debug/Release in the run configuration instead of hardcoding private build paths. Start SDL/raylib projects from their matching templates and retain the required entry points.

## Continue learning

- [Multiple files](cpp-functions.md)
- [CMake lab](cmake-practice.md)
- [Build reference](build-project.md)
