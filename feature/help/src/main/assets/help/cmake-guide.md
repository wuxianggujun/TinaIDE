# CMake 配置速查

动手学习见 [CMake 目标与库](../tutorial-course/cmake-practice.md)，以下只保留常用写法。

```cmake
cmake_minimum_required(VERSION 3.22)
project(native_lab LANGUAGES CXX)
add_executable(native_lab main.cpp)
target_compile_features(native_lab PRIVATE cxx_std_17)
```

源文件名应与磁盘一致。新增实现显式列入目标；优先目标级配置，不用全局标志影响每个依赖。

```cmake
add_library(pricing STATIC pricing.cpp)
target_include_directories(pricing PUBLIC "${CMAKE_CURRENT_SOURCE_DIR}/include")
target_link_libraries(native_lab PRIVATE pricing)
```

PRIVATE 只影响自身，PUBLIC 也传给使用者，INTERFACE 仅传给使用者。只有存在 `include/` 目录和对应头文件时才使用示例路径。

## 第三方库

在市场给当前项目添加 `fmt`，安装后：

```cmake
find_package(fmt CONFIG REQUIRED)
target_link_libraries(native_lab PRIVATE fmt::fmt)
```

完整流程见 [vcpkg 实践](vcpkg-guide.md)。工具、源码和安装路径由 TinaIDE 提供；不手填 `CMAKE_TOOLCHAIN_FILE`、旧 `TINA_VCPKG_ROOT` 或电脑 sysroot。系统线程用 `find_package(Threads REQUIRED)` 与 `Threads::Threads`，不照搬桌面 Linux 的 `-lpthread`。

## 排错

- 配置失败：先看 CMake 首个错误，确认包与目标名。
- 头文件失败：检查目标的 include 路径以及文件是否存在。
- 链接失败：确认实现加入目标、依赖已链接且 ABI 一致。
- 切换 Debug/Release：在运行配置选择；不要硬编码私有构建目录。
- SDL / raylib：以对应模板为基础，不把控制台入口与图形入口混用。

## 继续学习

- [多文件实践](../tutorial-course/cpp-functions.md)
- [CMake 实践](../tutorial-course/cmake-practice.md)
- [构建速查](build-project.md)
