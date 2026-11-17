# 实践 5：对象与资源生命周期

目标：用一个小类保存统计结果，体验 RAII 自动释放文件和内存。沿用 `native_lab` 的单个 `main.cpp`。

```cpp
#include <fstream>
#include <iostream>
#include <memory>
#include <numeric>
#include <utility>
#include <vector>

class Ledger {
public:
    explicit Ledger(std::vector<int> values) : values_(std::move(values)) {}
    int total() const { return std::accumulate(values_.begin(), values_.end(), 0); }
private:
    std::vector<int> values_;
};

int main() {
    const auto ledger = std::make_unique<Ledger>(std::vector<int>{10, 20, 30});
    {
        std::ofstream report("report.txt");
        if (!report) { std::cerr << "cannot open report\n"; return 1; }
        report << ledger->total() << '\n';
        report.close();
        if (!report) { std::cerr << "cannot write report\n"; return 1; }
    }
    std::ifstream report("report.txt");
    int restored = 0;
    if (!(report >> restored)) { std::cerr << "cannot read report\n"; return 1; }
    std::cout << "restored=" << restored << '\n';
    return 0;
}
```

运行配置的工作目录应为可写的项目目录。预期 `restored=60`；文件保存相对于该目录，不是固定公共存储路径。

`Ledger` 用 `private` 保护内部状态，`total() const` 不修改对象。`unique_ptr` 表达唯一所有权，在离开作用域时释放对象；本例更简单的写法其实是栈上的 `Ledger ledger(...)`，这里用智能指针演示所有权，不意味着每个对象都要动态分配。

RAII 表示资源由对象生命周期管理，异常或提前返回也能释放。文件流析构会关闭文件，但必须显式检查写入结果时，可像示例一样 `close()` 后检查状态。`std::move` 允许把容器资源交给新对象；移动后的对象仍可销毁，但不要假定原内容不变。

## 自己完成

1. 改用栈对象，保持输出不变。
2. 把文件位置改成不存在的子目录，确认输出错误并停止；恢复可写目录后重试。
3. 解释裸指针 `Ledger*` 为什么不表达所有权，为什么不能对同一地址构造两个 `unique_ptr`。
4. 给 `Ledger` 添加 `count() const`，验证空数据的总和为 0。

## 继续学习

- [CMake 目标与库](cmake-practice.md)
