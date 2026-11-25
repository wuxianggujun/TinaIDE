# 实践 7：fmt 依赖与构建

前置：[CMake 目标与库](cmake-practice.md)。点击 **创建／继续本章项目**，自动准备独立的 `tina_course_07_vcpkg`，运行目标为 `native_lab`。新工程的清单已声明 fmt，CMake 已链接它，`main.cpp` 已实际使用它输出，不需要手工新建文件或补初始化代码。

创建只准备本地文件，不联网下载。首次运行会按清单安装依赖；缺少包源码或 Git 对象时需要网络。**继续已有工程不会重写你的源码和清单**。

## 先运行完整基线

`vcpkg.json`：

<!-- tutorial-project-file: vcpkg.json -->
```json
{
  "dependencies": ["fmt"]
}
```

`CMakeLists.txt`：

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

`main.cpp`：计价函数的声明与实现由本章工程复用[函数课](cpp-functions.md)，这里专注于依赖使用。

<!-- tutorial-project-file: main.cpp -->
```cpp
#include "pricing.h"
#include <fmt/core.h>
#include <iostream>

int main() {
    const int total = totalPrice(3, 10);
    if (total < 0) { std::cerr << "invalid order\n"; return 1; }
    fmt::print("total={}\n", total);
    return 0;
}
```

1. 保存并运行 `native_lab`，等待依赖安装、CMake 配置和构建成功，终端应输出 `total=30`。
2. 从本阶段市场入口进入 **市场 → vcpkg**，选择当前本章项目。确认 fmt“已在清单”和“已安装”的状态；前者只代表声明，后者才是当前项目与 ABI 的实际安装记录。
3. 再次构建运行，确认复用已安装依赖，仍输出 `total=30`。创建工程不会代替安装，也不会自动标记本章完成。

## 已有空清单怎么恢复

若之前已经创建过本章项目，续开会保留原文件。打开根目录 `vcpkg.json`：如果内容是 `"dependencies": []`，在市场为**这个项目**添加 fmt，或将依赖列表改为 `"dependencies": ["fmt"]` 后保存，再运行；其他字段和源码保留。

`All requested packages are currently installed` 只针对清单里实际声明的包。空清单也会立即显示这句话，随后 `find_package(fmt)` 仍然会失败；它不是 fmt 已安装的证据。不要删除项目、复制电脑上的库或手写安装路径。

## 自己完成依赖闭环

- 暂时移除清单中的 fmt，运行并观察配置阶段找不到 fmt 的错误，再通过市场添加恢复。
- 暂时从 `target_link_libraries` 删除 `fmt::fmt`，构建观察头文件或链接错误，然后恢复。清单声明不能代替 CMake 使用关系。
- 将数量改为 10，确认 `total=100`；改为 -1 应报错。完成后恢复 3，再执行“验收当前课程章节”。
- 下载失败时先看第一条有效错误，按网络或 Git 代理提示取消并重试，不清空练习源码。

完整状态、模式和代理说明见[vcpkg 依赖管理速查](../help/vcpkg-guide.md)。

<!-- tutorial-hint -->
新工程同时准备清单、CMake 和 fmt 输出源码。先运行完整基线；首次运行可能下载安装依赖。已有工程仍会续开原文件，空清单需要明确添加 fmt，不会被后台重写。

<!-- tutorial-hint -->
依赖闭环有三处：`vcpkg.json` 声明 fmt，CMake 查找并链接 `fmt::fmt`，C++ 包含 `<fmt/core.h>` 并调用 `fmt::print`。少一处都不能以“安装成功”代替实际构建运行。

<!-- tutorial-hint -->
恢复清单中的 fmt 和 CMake 中的链接行，保留 `pricing.*`。基线输出语句为：

```cpp
fmt::print("total={}\n", total);
```

`total` 由 `totalPrice(3, 10)` 得到，预期为 `total=30`。已有 RAII 练习若使用变量 `restored`，保留它即可；缺包只需补清单，不必重写源码。保存并重建成功后再验收。
