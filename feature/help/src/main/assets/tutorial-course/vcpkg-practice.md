# 实践 7：给现有项目接入 fmt

前置：[CMake 目标与库](cmake-practice.md)。继续使用同一个 `native_lab`，不新建项目。目标是完成“清单声明 → 安装 → CMake 链接 → 构建 → 运行”的闭环。

## 操作

1. 点击本阶段的市场入口进入 **市场 → vcpkg**，选择 `native_lab`，添加 `fmt`。确认卡片显示“已在清单”。这只修改 `vcpkg.json`，尚不表示库已安装。
2. 将根目录 `CMakeLists.txt` 改为下方内容。保留 `pricing` 库和可执行目标，不填手动 toolchain 或安装路径：

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

3. 在 `main.cpp` 中保留上一课的计价逻辑，把输出改为 `fmt::print("total={}\n", total);`，并包含 `<fmt/core.h>`。保存后运行 `native_lab`。
4. 首次构建会按项目清单安装依赖；等待安装、配置、构建全部成功后，终端应输出 `total=30`。市场的“已安装”版本是安装状态；“已在清单”仅表示依赖声明存在。

## 排错与验收

- 如果提示找不到 fmt，检查当前项目、`vcpkg.json`、原生 CMake 模式以及构建日志中的第一条有效错误。
- 暂时从 `target_link_libraries` 删除 `fmt::fmt` 并构建，观察链接错误；恢复后再次运行。
- 再构建一次，确认项目能复用已安装的依赖。不要通过删掉项目、复制桌面库或手写旧安装路径修复。
- 独立完成一次添加、构建、错误恢复和运行验证后，勾选“我已完成”。

需要查包兼容、共享库、代理、目录更新或安装状态时打开[vcpkg 依赖管理速查](../help/vcpkg-guide.md)；该帮助页不重复本课的完整实操。
