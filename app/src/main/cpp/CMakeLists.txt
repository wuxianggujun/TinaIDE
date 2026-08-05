cmake_minimum_required(VERSION 3.22)
project(tina_native)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
option(TINA_BUILD_PROOT_FROM_SOURCE "Build TinaIDE PRoot binaries from external/termux-proot" ON)
get_filename_component(TINA_PROJECT_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../../.." ABSOLUTE)

# ============================================================================
# 编译优化 - 减小 .so 体积
# ============================================================================
# -Os: 优化大小
# -ffunction-sections -fdata-sections: 将每个函数/数据放入独立 section
# -Wl,--gc-sections: 链接时移除未使用的 section
# -fvisibility=hidden: 隐藏不需要导出的符号
# ============================================================================
set(CMAKE_C_FLAGS_RELEASE "${CMAKE_C_FLAGS_RELEASE} -Os -ffunction-sections -fdata-sections -fvisibility=hidden")
set(CMAKE_CXX_FLAGS_RELEASE "${CMAKE_CXX_FLAGS_RELEASE} -Os -ffunction-sections -fdata-sections -fvisibility=hidden")
set(CMAKE_SHARED_LINKER_FLAGS_RELEASE "${CMAKE_SHARED_LINKER_FLAGS_RELEASE} -Wl,--gc-sections -Wl,--strip-all")

# ============================================================================
# 16KB 页面对齐 - 支持 Android 15+ 设备
# ============================================================================
# Android 15 (API 35) 开始部分设备使用 16KB 页面大小
# 必须确保 ELF LOAD 段按 16KB 边界对齐，否则无法安装
# ============================================================================
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384")

# ============================================================================
# 源文件列表
# ============================================================================
#
# PRoot 默认从 external/termux-proot 直接编译；需要预编译产物时可关闭 TINA_BUILD_PROOT_FROM_SOURCE。
# Tree-sitter 由 external/tina-android-tree-sitter 模块提供。
# 此 native 库只包含 App 侧 JNI 工具函数。
# ============================================================================

if(TINA_BUILD_PROOT_FROM_SOURCE)
    set(TINA_TERMUX_PROOT_DIR "${TINA_PROJECT_ROOT}/external/termux-proot")
    if(NOT EXISTS "${TINA_TERMUX_PROOT_DIR}/CMakeLists.txt")
        message(FATAL_ERROR "external/termux-proot is missing; run git submodule update --init --recursive")
    endif()
    add_subdirectory("${TINA_TERMUX_PROOT_DIR}" "${CMAKE_BINARY_DIR}/termux-proot")
endif()

add_library(tina_native SHARED
    # Utils 模块
    utils/jni_utils.cpp
    utils/file_utils.cpp
)

# ============================================================================
# PTY（终端伪设备）
# ============================================================================

add_library(tina_pty SHARED
    pty/pty_jni.cpp
)

add_library(tina_log_redirect SHARED
    log_redirect/log_redirect_jni.c
)

add_library(tina_native_activity_host SHARED
    native_activity/native_activity_host.c
)

target_include_directories(tina_native PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)

# ============================================================================
# 链接库
# ============================================================================

find_library(log-lib log)
target_link_libraries(tina_native PRIVATE c++_shared ${log-lib})
target_link_libraries(tina_pty PRIVATE c++_shared ${log-lib})
target_link_libraries(tina_log_redirect PRIVATE ${log-lib})
target_link_libraries(tina_native_activity_host PRIVATE android dl ${log-lib})
target_link_options(tina_native_activity_host PRIVATE "-Wl,-z,global")

# ============================================================================
# 注意：proot-loader (libproot-loader.so) 可以来自预编译 source set，
# 也可以由 external/termux-proot 直接编译。它是 PRoot 内部使用的 ELF 加载器。
# ============================================================================
