# 实践 2：C++ 变量与控制流

前置：完成[从项目到运行](ide-workflow.md)。沿用 `native_lab`，替换 `main.cpp`，保留 CMake 配置。

目标：用整数计价器理解类型、条件、循环、输入约束和返回码。

```cpp
#include <iostream>

int main() {
    constexpr int unitPrice = 10;
    const int count = 3;
    if (count < 0 || count > 100) {
        std::cerr << "invalid count\n";
        return 1;
    }
    int total = 0;
    for (int index = 0; index < count; ++index) {
        total += unitPrice;
    }
    std::cout << "total=" << total << '\n';
    return 0;
}
```

保存、构建并运行，预期为 `total=30`。`constexpr` 是编译期常量，`const` 表示初始化后不再修改，`int` 表示整数。`index < count` 让循环恰好执行三次；写成 `<=` 会多算一次。

## 边界练习

依次修改 `count`，每次保存并运行：

| count | 结果 |
| --- | --- |
| 0 | `total=0` |
| 1 | `total=10` |
| 100 | `total=1000` |
| -1 或 101 | 错误流输出 `invalid count`，返回码 1 |

给价格保留整数最小单位，避免一开始就用 `float` 处理金额。若扩大数量范围，应重新选择整数类型并检查乘法溢出，不能仅删除上限。

## 输入与判断

接着把 `const int count = 3;` 改为下面两句，并在终端输入数字后回车：

```cpp
int count = 0;
if (!(std::cin >> count)) { std::cerr << "expected integer\n"; return 1; }
```

保留原来的范围校验。分别输入 `3`、`-1` 和 `abc`，说明“解析失败”和“数字超范围”的差异。不要在输入失败后继续使用未验证的数据。

## 自己完成

总价至少 100 时减 10，其他情况原价。先写出 `count=9、10、11` 的预期，再实现 `if/else` 并验证。思考 `&&`、`||` 和 `==` 的含义，避免用赋值 `=` 代替比较。

## 继续学习

- [函数与多文件](cpp-functions.md)
