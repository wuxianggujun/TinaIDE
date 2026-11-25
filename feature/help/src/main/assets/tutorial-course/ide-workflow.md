# 实践 1：从项目到运行

目标：独立完成“编辑 → 保存 → 构建 → 运行 → 核对输出”。本课程使用 Android 原生 C/C++ 工具链，不需要 Linux 环境，也不需要 AI 服务。

## 准备与操作

1. 点击本章上方 **创建／继续本章项目**。应用自动创建 `tina_course_01_ide`，准备完整的 C++17 工程；运行目标为 `native_lab`，vcpkg 默认关闭。
2. 首次使用按应用提示准备编译环境。只有环境安装完成后再运行项目。
3. 编辑器自动打开工程。根目录 `CMakeLists.txt` 和 `main.cpp` 已填好下方示例，无需新建文件或复制初始化代码。

每章使用独立工程，源码保存在应用私有项目目录，可从项目列表继续打开或导出。重复点击会继续已有工程，不覆盖修改；同名用户目录不会被覆盖，新工程会自动加后缀。

`CMakeLists.txt`：

<!-- tutorial-project-file: CMakeLists.txt -->
```cmake
cmake_minimum_required(VERSION 3.22)
project(native_lab LANGUAGES CXX)
add_executable(native_lab main.cpp)
target_compile_features(native_lab PRIVATE cxx_std_17)
```

`main.cpp`：

<!-- tutorial-project-file: main.cpp -->
```cpp
#include <iostream>

int main() {
    std::cout << "Hello, TinaIDE!\n";
    return 0;
}
```

4. 默认运行配置已选择 **原生 CMake**、终端输出和 `native_lab` 目标，直接点击运行；练习修改后记得保存。
5. 在终端找到 `Hello, TinaIDE!`。把它改为 `First change`，保存并再次运行，确认输出随之变化。

## 主动制造一次错误

删除 `return 0;` 的分号，保存后构建。在 **构建日志** 找到第一个指向 `main.cpp` 的错误，恢复分号再运行。编译失败时看到的旧终端输出不代表新程序成功。

如果源码已变但结果不变，检查保存状态、选中的运行目标、实际构建是否成功。**诊断** 中的语言服务提示不是本次编译器输出的替代品。

## 自己完成

- 输出两行不同的文字。
- 把目标名改成 `hello_lab`，同时更新运行配置；源文件名不用跟着改。
- 关闭项目再打开，确认文件仍在、输出仍正确。

完成改名练习后，把 CMake 目标名和运行配置恢复为 `native_lab`，再进入下一章。

只在实际完成以上操作后标记本课完成；阅读进度不等于编译验收。源码、Markdown 和文本文件统一保存为 UTF-8，不使用系统默认 ANSI 编码。

## 相关速查

- [编辑器速查](../help/editor-basics.md)
- [构建与运行排错](../help/build-project.md)

<!-- tutorial-hint -->
点击“创建／继续本章项目”即可打开已填好两个文件的工程。先运行基线，再练习修改。代码块“复制”仅供查看或恢复练习代码，不是创建工程的必要步骤；不会自动覆盖你已有的修改。

<!-- tutorial-hint -->
两行输出都写在 `int main()` 的花括号内、`return 0;` 前。改名练习只改 `add_executable`、`target_compile_features` 里的目标名和运行配置，不改源文件名；练完恢复 `native_lab`。

<!-- tutorial-hint -->
两行输出练习可把 `main.cpp` **完整替换**为：

```cpp
#include <iostream>

int main() {
    std::cout << "Hello, TinaIDE!\n";
    std::cout << "First change\n";
    return 0;
}
```

保存、构建、运行，应看到两行文字。仍失败时核对 CMake 源文件路径和第一个构建错误，再确认勾选完成。
