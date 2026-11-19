# 实践 4：字符串与容器

前置：[函数与多文件](cpp-functions.md)。继续使用同一个 `native_lab`，保留 `pricing.h`、`pricing.cpp` 和当前 CMake 目标，只替换 `main.cpp`：

```cpp
#include "pricing.h"
#include <algorithm>
#include <iostream>
#include <numeric>
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
    const int itemCount = std::accumulate(quantities.begin(), quantities.end(), 0);
    const int total = totalPrice(itemCount, 10);
    if (total < 0) { std::cerr << "invalid order\n"; return 1; }
    std::cout << customer << ": items=" << itemCount << " total=" << total << '\n';
    return 0;
}
```

预期：`Tina: items=3 total=30`。`std::string` 保存顾客名，`vector` 管理多行数量；`all_of` 校验每一行，`accumulate` 汇总，再把结果交给前一课的 `totalPrice`。这章继续构建原来的多文件项目，不重置目标。

## 边界与遍历

- 把数量改为 `{0}`：总价为 `0`。
- 改为空 `{}`：返回错误，不做空输入的业务计算。
- 加入 `-1` 或 `101`：输出 `invalid count`；大量合法行的总数超过 100 时，由 `totalPrice` 拒绝订单。
- 用范围 `for` 逐行输出；想修改数量时才使用 `int&`。

`at(index)` 会检查越界，`operator[]` 不会。不要保存元素引用后调用可能触发扩容的 `push_back`，否则引用可能失效。`std::string::size()` 对 UTF-8 返回字节数，不是中文字符数；整段输出并按 UTF-8 保存，不随意按字节切片。

## 自己完成

把单价改为用户输入并检查解析失败；增加有效数量行数统计，先列出空列表、零数量和无效数量的预期，再运行验证。
