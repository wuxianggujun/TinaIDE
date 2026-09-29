cmake_minimum_required(VERSION 3.22.1)

project(tina_text_engine)

add_library(tina_text_engine SHARED
    line_index_kernel_jni.cpp
    text_scan_kernel_jni.cpp
)

target_compile_features(tina_text_engine PRIVATE cxx_std_17)
target_compile_options(tina_text_engine PRIVATE -fexceptions -frtti)

# Android 15+ 要求 16 KB 页面对齐（arm64-v8a / x86_64）
target_link_options(tina_text_engine PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384"
)
