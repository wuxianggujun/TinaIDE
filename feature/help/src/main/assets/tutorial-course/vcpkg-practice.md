# 实践 7：给现有项目接入 fmt

前置：[CMake 目标与库](cmake-practice.md)。点击 **创建／继续本章项目**，自动准备独立的 `tina_course_07_vcpkg`，运行目标为 `native_lab`。完整计价源码、下方 CMake 和空的 `vcpkg.json` 已就绪，项目清单模式已启用；不会自动添加或联网安装 fmt。

目标是自己完成“清单声明 → 安装 → CMake 链接 → 构建 → 运行”的闭环，不需要手工新建教程文件。

## 操作

1. 先创建本章项目，再从本阶段的市场入口进入 **市场 → vcpkg**，选择 `tina_course_07_vcpkg`（同名冲突时带后缀），添加 `fmt`。确认卡片显示“已在清单”。这只修改 `vcpkg.json`，尚不表示库已安装。
2. 根目录 `CMakeLists.txt` 已预填下方内容。核对 `pricing` 库、可执行目标与 fmt 链接，不填手动 toolchain 或安装路径：

<!-- tutorial-project-file: CMakeLists.txt -->
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

3. `main.cpp` 已包含 RAII 计价、写文件和读回逻辑。把最后的输出改为 `fmt::print("total={}\n", restored);`，并包含 `<fmt/core.h>`。保存后运行 `native_lab`。
4. 首次构建会按项目清单安装依赖；等待安装、配置、构建全部成功后，终端应输出 `total=30`。市场的“已安装”版本是安装状态；“已在清单”仅表示依赖声明存在。

## 排错与验收

- 如果提示找不到 fmt，检查当前项目、`vcpkg.json`、原生 CMake 模式以及构建日志中的第一条有效错误。
- 暂时从 `target_link_libraries` 删除 `fmt::fmt` 并构建，观察缺少头文件或链接符号的错误；恢复后再次运行。
- 再构建一次，确认项目能复用已安装的依赖。不要通过删掉项目、复制桌面库或手写旧安装路径修复。
- 独立完成一次添加、构建、错误恢复和运行验证后，勾选“我已完成”。

需要查包兼容、共享库、代理、目录更新或安装状态时打开[vcpkg 依赖管理速查](../help/vcpkg-guide.md)；该帮助页不重复本课的完整实操。

<!-- tutorial-hint -->
创建本章项目后，在市场选择 `tina_course_07_vcpkg` 并添加 fmt。CMake、源码、空清单和项目清单模式已自动准备；需要自己修改 `main.cpp` 使用 fmt。“已在清单”不等于完成安装，首次构建需要联网获取依赖。

<!-- tutorial-hint -->
CMake 负责 `find_package` 和链接 `fmt::fmt`；C++ 顶部包含 `<fmt/core.h>`，再把最后的输出语句替换为 `fmt::print`。保留计价、写文件和读回校验。

<!-- tutorial-hint -->
在 `main.cpp` 顶部的 include 区**添加**：

```cpp
#include <fmt/core.h>
```

把输出金额的那一行**替换**为：

```cpp
fmt::print("total={}\n", restored);
```

这里的 `restored` 来自本章已预填的 `total.txt` 读回代码。CMake 已使用正文完整版本，构建后应输出 `total=30`。缺包时重试安装并看第一条错误，创建工程或复制代码不会代替依赖安装。
