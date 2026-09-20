cmake_minimum_required(VERSION 3.16)
project({{PROJECT_NAME}} VERSION 1.0.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD {{CPP_STANDARD}})
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_EXPORT_COMPILE_COMMANDS ON)

find_package(SDL2 CONFIG REQUIRED)

add_library({{PROJECT_NAME}} SHARED
    src/main.cpp
)

target_link_libraries({{PROJECT_NAME}} PRIVATE SDL2::SDL2)

# SDL 图形运行链默认消费共享库，输出名固定为 libmain.so 便于直接运行和后续打包。
set_target_properties({{PROJECT_NAME}} PROPERTIES
    OUTPUT_NAME "main"
)
