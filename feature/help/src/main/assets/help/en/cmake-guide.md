# CMake Configuration Guide

CMake generates build systems for C and C++ projects. TinaIDE uses it for structured builds and dependency information.

## Minimal project

    cmake_minimum_required(VERSION 3.10)
    project(MyProject)

    set(CMAKE_CXX_STANDARD 17)
    set(CMAKE_CXX_STANDARD_REQUIRED ON)
    set(CMAKE_EXPORT_COMPILE_COMMANDS ON)

    add_executable(myapp main.cpp)

TinaIDE supports common C++ standards including C++11, C++14, C++17, and C++20.

## Source files

List source files explicitly:

    add_executable(myapp
        src/main.cpp
        src/config.cpp
        src/logger.cpp
    )

Explicit lists are easier to review and do not require CMake to discover newly added files through a glob.

## Include directories

    target_include_directories(myapp PRIVATE
        include
        src
    )

Use PRIVATE for one target, PUBLIC when a library and its consumers need the path, and INTERFACE when only consumers need it.

## Libraries

Create and link a static library:

    add_library(utils STATIC
        src/utils.cpp
        src/logger.cpp
    )
    target_include_directories(utils PUBLIC include)

    add_executable(myapp src/main.cpp)
    target_link_libraries(myapp PRIVATE utils)

Use find_package and imported targets when a package provides them:

    find_package(Threads REQUIRED)
    target_link_libraries(myapp PRIVATE Threads::Threads)

## Compile options and definitions

Prefer target-specific settings:

    target_compile_options(myapp PRIVATE
        -Wall
        -Wextra
        -Wpedantic
    )

    target_compile_definitions(myapp PRIVATE
        APP_VERSION="1.0.0"
    )

## Build types

Common choices are Debug, Release, RelWithDebInfo, and MinSizeRel. Use Debug while diagnosing code and Release for optimized output.

## Multiple directories

A larger project can keep a CMakeLists.txt in each module:

    add_subdirectory(lib)
    add_subdirectory(src)

Keep target ownership and dependencies explicit in the corresponding subdirectory.

## Compilation database

Enable:

    set(CMAKE_EXPORT_COMPILE_COMMANDS ON)

TinaIDE keeps compile_commands.json in the project build directory and connects it to clangd. Executables may be copied to a private runtime directory when Android execution requires it.

## Conditional configuration

    if(ANDROID)
        target_compile_definitions(myapp PRIVATE PLATFORM_ANDROID)
    elseif(UNIX)
        target_compile_definitions(myapp PRIVATE PLATFORM_UNIX)
    endif()

Generator expressions can apply options to individual configurations without changing global flags.

## Install rules

    install(TARGETS myapp
        RUNTIME DESTINATION bin
    )

    install(DIRECTORY include/
        DESTINATION include
    )

## Tests

    enable_testing()
    add_executable(test_utils test/test_utils.cpp)
    target_link_libraries(test_utils PRIVATE utils)
    add_test(NAME test_utils COMMAND test_utils)

Run:

    cmake --build build
    ctest --test-dir build

## Troubleshooting

- Configuration failure: check CMake syntax, required packages, and the CMake version.
- Missing header: inspect target_include_directories.
- Link failure: inspect target_link_libraries and library order.
- Inaccurate completion: rebuild and confirm compile_commands.json is current.

## Recommended practices

1. Prefer target_* commands to global flags.
2. Express dependencies through targets.
3. List source files explicitly.
4. Split large projects into subdirectories.
5. Export the compilation database.

## Next steps

- [Build a project](build-project.md)
- [Create a project](create-project.md)
- [Official CMake documentation](https://cmake.org/documentation/)
