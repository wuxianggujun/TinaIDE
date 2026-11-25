# 实践 6：CMake 目标与库

目标：把[函数课](cpp-functions.md)的计价代码编成独立库，再让主程序链接它。点击 **创建／继续本章项目**，打开 `tina_course_06_cmake`；应用已准备 `pricing.h`、`pricing.cpp`、RAII 版 `main.cpp` 和下方完整 CMake。

<!-- tutorial-project-file: CMakeLists.txt -->
```cmake
cmake_minimum_required(VERSION 3.22)
project(native_lab LANGUAGES CXX)
add_library(pricing STATIC pricing.cpp)
target_include_directories(pricing PUBLIC "${CMAKE_CURRENT_SOURCE_DIR}")
target_compile_features(pricing PUBLIC cxx_std_17)
add_executable(native_lab main.cpp)
target_link_libraries(native_lab PRIVATE pricing)
```

保存并运行，仍应输出 `total=30`。`STATIC` 产生静态库，链接时把所需代码加入程序；`SHARED` 产生动态库，还需要运行宿主能找到并加载它。普通共享库本身没有可直接运行的程序入口。

## 理解三个阶段

| 阶段 | 做什么 | 常见错误 |
| --- | --- | --- |
| 配置 configure | 读取 CMake，找编译器和依赖，生成构建规则 | 目标名冲突、找不到包配置 |
| 构建 build | 编译每个源文件，然后链接 | 语法错误、重复定义、未解析符号 |
| 运行 run | 启动目标程序或图形宿主 | 选错目标、缺少资源、退出码非零 |

TinaIDE 的 **原生 CMake** 已提供 Android 工具链与 sysroot，不要把电脑上的编译器路径、Linux 库或 NDK 路径粘贴进 CMake。

## 目标范围

`PRIVATE` 只影响当前目标；`PUBLIC` 同时传给使用者；`INTERFACE` 只传给使用者。头文件对外可见，所以 `pricing` 的 include 路径用 PUBLIC；主程序的链接关系用 PRIVATE。优先使用 `target_*`，避免全局 `include_directories` 和全局编译标志影响所有库。

新增 `.cpp` 时显式加入对应目标。不靠通配符“猜到”新文件，重命名后也同步更新。Debug/Release 使用各自的构建配置；切换目标后核对运行配置。

## 依赖练习

临时删除 `target_link_libraries`，观察链接失败；恢复后运行。然后按下一课在市场添加 `fmt`：清单负责安装库，`find_package` 找到 CMake 配置，`target_link_libraries` 表达实际使用关系，三者各有职责。

验收：库代码修改会重新编译；主程序仍能运行；错误后恢复配置无需删除整个项目。

## 相关速查

- [CMake 参数速查](../help/cmake-guide.md)

<!-- tutorial-hint -->
本章项目的 CMake 已完成库化，其他三个源码文件也已准备，不需要复制上一章文件。先核对目标关系并运行，再做删除链接行的排错练习。不要把 `pricing.cpp` 同时加进库目标和可执行目标。

<!-- tutorial-hint -->
目标分工是 `pricing` 编译计价实现，`native_lab` 编译 main 并链接 pricing。故意删除链接行后应看到未解析符号；修复是恢复目标关系，不是把 `.cpp` 包含进头文件。

<!-- tutorial-hint -->
在两个目标都已定义之后，恢复下面这行：

```cmake
target_link_libraries(native_lab PRIVATE pricing)
```

目标定义及头文件目录仍使用正文中的完整 CMake。保存、构建、运行应恢复 `total=30`。若提示找不到源码，先检查文件是否在根目录及文件名大小写。
