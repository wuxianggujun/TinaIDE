# 实践 4：字符串与容器

前置：[函数与多文件](cpp-functions.md)。点击 **创建／继续本章项目**，打开独立的 `tina_course_04_collections`。应用已准备 `pricing.h`、`pricing.cpp`、CMake 和下方 `main.cpp`；上一章的折扣练习不会被覆盖。

<!-- tutorial-project-file: main.cpp -->
```cpp
#include "pricing.h"
#include <algorithm>
#include <iostream>
#include <string>
#include <vector>

int main() {
    const std::string customer = "Tina";
    const std::vector<int> quantities{1, 2};
    if (quantities.empty()) { std::cerr << "no items\n"; return 1; }
    const bool valid = std::all_of(quantities.begin(), quantities.end(), [](int count) {
        return count >= 0 && count <= 100;
    });
    if (!valid) { std::cerr << "invalid count\n"; return 1; }
    int itemCount = 0;
    for (int count : quantities) {
        if (count > 100 - itemCount) { std::cerr << "invalid order\n"; return 1; }
        itemCount += count;
    }
    const int total = totalPrice(itemCount, 10);
    if (total < 0) { std::cerr << "invalid order\n"; return 1; }
    std::cout << customer << ": items=" << itemCount << " total=" << total << '\n';
    return 0;
}
```

预期：`Tina: items=3 total=30`。`std::string` 保存顾客名，`vector` 管理多行数量；`all_of` 校验每一行，范围循环在累加前检查订单上限，避免先溢出再检查。最后复用前一课的 `totalPrice`，本章工程已包含对应实现。

## 边界与遍历

- 把数量改为 `{0}`：总价为 `0`。
- 改为空 `{}`：返回错误，不做空输入的业务计算。
- 加入 `-1` 或 `101`：输出 `invalid count`；合法行的总数超过 100 时，在累加前拒绝订单。
- 用范围 `for` 逐行输出；想修改数量时才使用 `int&`。

`at(index)` 会检查越界，`operator[]` 不会。不要保存元素引用后调用可能触发扩容的 `push_back`，否则引用可能失效。`std::string::size()` 对 UTF-8 返回字节数，不是中文字符数；整段输出并按 UTF-8 保存，不随意按字节切片。

## 自己完成

把单价改为用户输入并检查解析失败；增加有效数量行数统计，先列出空列表、零数量和无效数量的预期，再运行验证。

<!-- tutorial-hint -->
点击创建入口后先运行预填的 `main.cpp`，无需复制前一章文件。数量集合先用 `{1, 2}`，不要同时修改单价、折扣和目标名。单价输入属于本章的下一步练习。

<!-- tutorial-hint -->
把 `totalPrice(itemCount, 10)` 中的 10 换成经过解析的变量。解析失败应立即停止；`totalPrice` 已负责拒绝负单价和超过 10000 的单价。行数用 `quantities.size()`，商品总数用 `itemCount`，二者不同。

<!-- tutorial-hint -->
在 `main()` 中将原来的 `const int total = totalPrice(itemCount, 10);` **替换**为：

```cpp
int unitPrice = 0;
if (!(std::cin >> unitPrice)) {
    std::cerr << "expected integer\n";
    return 1;
}
const int total = totalPrice(itemCount, unitPrice);
```

保留后面的 `total < 0` 检查；检查通过后可添加：

```cpp
std::cout << "rows=" << quantities.size() << '\n';
```

输入 10，预期两行数量、总数 3、总价 30。输入 `abc` 应停止；`{100, 1}` 应报订单超限。
