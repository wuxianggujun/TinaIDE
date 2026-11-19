# 实践 3：函数与多文件

目标：把计价规则从 `main` 分离出来。前置：[变量与控制流](cpp-basics.md)。创建以下三个 UTF-8 文件。

`pricing.h` 只放声明，`#pragma once` 防止同一头文件重复展开：

```cpp
#pragma once
int totalPrice(int count, int unitPrice);
```

`pricing.cpp` 放实现。值参数适合小整数；无效输入用 `-1` 表示，本课约定调用方必须检查：

```cpp
#include "pricing.h"

int totalPrice(int count, int unitPrice) {
    if (count < 0 || count > 100 || unitPrice < 0 || unitPrice > 10000) {
        return -1;
    }
    return count * unitPrice;
}
```

`main.cpp`：

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

把原来的 `add_executable` 改为：

```cmake
add_executable(native_lab main.cpp pricing.cpp)
```

构建并运行，仍应得到 `total=30`。`#include` 让编译器看到声明；把 `.cpp` 加入目标才会编译实现，二者不能互相替代。

## 排错练习

1. 临时从 CMake 删除 `pricing.cpp`：观察链接器找不到 `totalPrice` 的错误，然后恢复。
2. 把调用名改错：观察编译期“未声明”的错误，然后恢复。
3. 分别调用 `totalPrice(0, 10)`、`totalPrice(-1, 10)` 和 `totalPrice(100, 10000)`，先预测结果再运行。

不要 `#include "pricing.cpp"`，否则目标同时编译它时会重复定义。头文件中定义普通非 inline 函数也容易出现同类问题。

## 认识引用

`int&` 是已有变量的别名，修改它会修改调用者；`const std::string&` 可只读访问较大字符串，避免值拷贝。引用必须指向生命周期足够长的对象，不要返回局部变量的引用。

自己新增 `discountedTotal` 函数，保持 `main` 只负责调用和输出。函数规则变更后，重新运行边界输入。

<!-- tutorial-hint -->
本章涉及四个文件：`pricing.h` 声明、`pricing.cpp` 实现、`main.cpp` 调用，以及 `CMakeLists.txt` 的源码列表。先复制本章完整示例运行成功，再做折扣函数练习。

<!-- tutorial-hint -->
新函数先调用 `totalPrice`，先检查返回值是否为 -1，再处理折扣。这样保留前面数量、单价的范围校验。`main.cpp` 调用新函数，但不要删除旧的 `totalPrice`。

<!-- tutorial-hint -->
在 `pricing.h` 原声明后**添加**：

```cpp
int discountedTotal(int count, int unitPrice);
```

在 `pricing.cpp` 的 `totalPrice` 函数结束后**添加**：

```cpp
int discountedTotal(int count, int unitPrice) {
    const int total = totalPrice(count, unitPrice);
    if (total < 0) return -1;
    return total >= 100 ? total - 10 : total;
}
```

在 `main.cpp` 把一次 `totalPrice(3, 10)` 调用改为 `discountedTotal(10, 10)`，预期 `total=90`；数量 -1 应仍报错。若未定义，检查声明及 CMake 中的 `pricing.cpp`。
