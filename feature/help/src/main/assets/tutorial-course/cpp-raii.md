# 实践 5：对象与资源生命周期

前置：[字符串与容器](cpp-collections.md)。点击 **创建／继续本章项目**，自动准备 `tina_course_05_raii` 的完整文件，包括 `pricing.*` 和下方 `main.cpp`。本章通过 RAII 记录计价结果，不需要搬运或替换上一章工程。

<!-- tutorial-project-file: main.cpp -->
```cpp
#include "pricing.h"
#include <fstream>
#include <iostream>
#include <memory>
#include <utility>
#include <vector>

class OrderLedger {
public:
    explicit OrderLedger(std::vector<int> quantities) : quantities_(std::move(quantities)) {}
    int itemCount() const {
        if (quantities_.empty()) return -1;
        int total = 0;
        for (int count : quantities_) {
            if (count < 0 || count > 100 - total) return -1;
            total += count;
        }
        return total;
    }
private:
    std::vector<int> quantities_;
};

int main() {
    const auto ledger = std::make_unique<OrderLedger>(std::vector<int>{1, 2});
    const int total = totalPrice(ledger->itemCount(), 10);
    if (total < 0) { std::cerr << "invalid order\n"; return 1; }
    {
        std::ofstream report("total.txt");
        if (!report) { std::cerr << "cannot open report\n"; return 1; }
        report << total << '\n';
        report.close();
        if (!report) { std::cerr << "cannot write report\n"; return 1; }
    }
    std::ifstream report("total.txt");
    int restored = 0;
    if (!(report >> restored)) { std::cerr << "cannot read report\n"; return 1; }
    std::cout << "total=" << restored << '\n';
    return 0;
}
```

预期仍是 `total=30`。运行配置的工作目录应为可写项目目录；文件路径相对于该目录，不依赖固定公共存储路径。此版本仍调用上一课的 `totalPrice`，因此后面的 CMake 库化步骤可以直接复用。

`OrderLedger` 用 `private` 保护内部数量，`itemCount() const` 不修改对象。`unique_ptr` 表达唯一所有权并在离开作用域时自动释放；本例更简单的写法其实是栈对象，这里用智能指针演示所有权，并不表示每个对象都要动态分配。

对象继续保留上一章的输入约束：空列表、负数量以及累计超过 100 都返回 -1，由调用方拒绝；不能先把正负数量抵消后再检查总数。

RAII 让文件流和内存跟随对象生命周期释放，提前返回也不会泄漏。文件流析构会关闭文件；需要检查写入时，应先 `close()` 再验证状态。`std::move` 将容器资源转交给新对象，不能假定移动后源对象仍保留原内容。

## 自己完成

1. 改用栈上的 `OrderLedger`，保持 `total=30`。
2. 把文件位置改为不存在的子目录，确认程序报告错误并停止；恢复后重试。
3. 解释裸指针为什么不表达所有权，以及为何不能让两个 `unique_ptr` 管理同一对象。
4. 给 `OrderLedger` 添加 `count() const`，验证空列表计数为 0。

<!-- tutorial-hint -->
本章完整示例已填入 `main.cpp`，直接运行基线。类声明放在 `main()` 外部。运行目录必须可写，输出文件是该目录下的 `total.txt`，不是电脑上的路径。

<!-- tutorial-hint -->
栈对象练习只替换创建对象和调用方法的两行：`unique_ptr` 用 `->`，栈对象用 `.`。其余读写文件代码保留。`count()` 返回容器长度，`itemCount()` 校验并计算商品总数。

<!-- tutorial-hint -->
把 `main()` 开头创建 `ledger` 和计算 `total` 的两行**替换**为：

```cpp
const OrderLedger ledger(std::vector<int>{1, 2});
const int total = totalPrice(ledger.itemCount(), 10);
```

在 `OrderLedger` 的 `public:` 区域**添加**：

```cpp
std::size_t count() const { return quantities_.size(); }
```

文件顶部添加 `#include <cstddef>`。`ledger.count()` 应为 2，金额仍为 30；空列表的 `count()` 为 0，但不能形成有效订单。`{-1, 2}` 必须报错，不能算作 1 件。
