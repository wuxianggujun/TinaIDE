# 实践 9：用模拟对话学习 AI 协作

本课所有“用户 / 助手”内容都是随应用提供的静态模拟数据，不发送请求、不读取 Key、不产生模型调用。先离线完成练习；是否启用真实服务由你决定。

## 一键准备离线计价项目

1. 点击 **创建／继续本章项目**，自动准备独立的 `tina_course_09_ai`。计价源码和完整 CMake 已填好，无需恢复控制台备份，上一章图形工程不会被修改。
2. 本章不使用 fmt，vcpkg 默认关闭；默认运行配置为原生 CMake、终端输出和 `native_lab` 目标。
3. 直接运行，先确认 `total=30`，再开始下面的链接错误练习。创建及模拟对话都可离线使用，不会读取 Key 或请求模型。

## 模拟 1：把要求变成可验收任务

**用户（模拟）**：我在 Android 上用 TinaIDE、原生 CMake 和 C++17。计价函数只接受数量 0～100、单价 0～10000。请给最小修改，保留现有目标名，解释边界并列出测试输入。不要引入网络或新库。

**助手（模拟）**：先验证两个参数范围，再返回乘积；无效输入返回约定错误。测试 0、1、100、-1、101；调用方检查错误结果后才显示金额。

实践：用[函数课](cpp-functions.md)的代码核对回答。要求与代码不一致时修正规则，不因为回答看起来合理就跳过运行。

## 模拟 2：定位链接错误

**用户（模拟）**：构建出现 `undefined reference to totalPrice(int, int)`。头文件能找到。我的目标只有 `add_executable(native_lab main.cpp)`。请说明需要验证什么。

**助手（模拟）**：检查 `pricing.cpp` 是否包含实现，并加入该目标，或链接包含实现的 `pricing` 库。不要通过包含 `.cpp` 文件解决。保存并重新构建，确认链接完成和输出正确。

实践：先故意移除链接，再按建议恢复。区分编译器错误和链接器错误，反馈最早的相关错误与少量上下文，不粘贴整个私人项目。

## 模拟 3：识别不适用的建议

**助手（模拟，故意错误）**：给 Android 项目加 `-lpthread`，把电脑 `/usr/lib` 拷进项目，再把任意 `.so` 选择 SDL 模式运行。

实践：指出错误。Android 线程由 Bionic 提供，应按 CMake 目标表达；桌面 Linux 二进制不能直接当 Android 库；图形宿主要求匹配的入口。拒绝这条修改，回到已运行的最小模板验证。

## 自己配置真实 AI（可选）

1. 在编辑器侧边栏打开 **RikkaHub**，或从设置进入其设置页面。
2. 在 RikkaHub 的渠道 / Provider 设置添加你自己的服务：填写服务商提供的 API 地址、自己的 API Key、实际可用模型。不同服务的路径和接口类型以服务商文档为准，不在教程里填一把公共 Key。
3. 在 RikkaHub 选择该模型，自己明确点击发送后才发生真实请求；真实请求按服务商计费。本课程页始终只是本地示例，不会自动发送。
4. 请求失败时核对接口类型、模型、Key 权限和服务商返回码。不要把 Key 放入 C++ 源码、Git、截图、反馈附件或提示词。

TinaIDE 宿主不另存一份 Key，也不会把课程中的模拟回答伪装成在线模型响应。不要为了课程新增 API 请求或旁路密钥存储。

## 完成标准

自己写一条包含环境、最小代码、错误、约束和预期结果的提问；检查模拟建议；手工应用一个小改动并构建运行；记录输入和实际输出。AI 建议有帮助，但编译与运行结果才是验收依据。

## 相关帮助

- [学习路线](../help/getting-started.md)
- [Git 基础：保存可回退的修改](../help/git-basics.md)

<!-- tutorial-hint -->
先点击创建入口，运行本章预填的计价工程，不需要备份恢复或新建文件。模拟对话用于练习判断，不需要账号或真实 AI。练习只改本章的 `pricing.cpp`、`main.cpp` 和 CMake，不要用阅读对话代替实际运行。

<!-- tutorial-hint -->
链接排错时，临时移除 `target_link_libraries(native_lab PRIVATE pricing)`，构建确认 `totalPrice` 未定义，再恢复它。提问写清 Android 原生 CMake、实际第一条错误、目标配置、预期输出以及不能引入新库的约束。

<!-- tutorial-hint -->
本章自动复用[函数课](cpp-functions.md)的 `pricing.h` 和 `pricing.cpp`，并预填下面两个文件。这是基线参考，只有需要恢复自己改坏的练习代码时才复制；正常创建不需要手工操作文件，也不使用 fmt。

根目录 `CMakeLists.txt`：

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

根目录 `main.cpp`：

<!-- tutorial-project-file: main.cpp -->
```cpp
#include "pricing.h"
#include <iostream>

int main() {
    const int total = totalPrice(3, 10);
    if (total < 0) { std::cerr << "invalid order\n"; return 1; }
    std::cout << "total=" << total << '\n';
    return 0;
}
```

切回终端宿主，预期 `total=30`。故意移除链接行后应构建失败，恢复后再次得到 30；把这一过程写入自己的提问和验证记录，即可完成离线练习。
