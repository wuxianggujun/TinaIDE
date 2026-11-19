# Lab 5: Objects and resource lifetimes

Prerequisite: [Strings and containers](cpp-collections.md). Keep the same `native_lab` and `pricing.*`; replace only `main.cpp` so it records the existing price calculation with RAII:

```cpp
#include "pricing.h"
#include <fstream>
#include <iostream>
#include <memory>
#include <numeric>
#include <utility>
#include <vector>

class OrderLedger {
public:
    explicit OrderLedger(std::vector<int> quantities) : quantities_(std::move(quantities)) {}
    int itemCount() const { return std::accumulate(quantities_.begin(), quantities_.end(), 0); }
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

Expect `total=30`. Set a writable project directory as the run configuration's working directory; the report path is relative to that directory. This version still calls the prior `totalPrice`, so the next CMake library lesson can reuse it directly.

`private` protects the quantities and `itemCount() const` does not modify the object. `unique_ptr` expresses single ownership and releases its object on scope exit. A stack object would be simpler here; dynamic allocation teaches ownership, not a default for every object.

RAII ties memory and file cleanup to object lifetime, including early returns. File stream destructors close handles; explicitly closing and checking is useful when a write failure must be reported. `std::move` transfers vector storage; do not assume the moved-from object retains its original values.

## Your exercise

Use a stack `OrderLedger` without changing `total=30`. Try a nonexistent output directory and check the error, then restore the valid path. Explain why a raw pointer does not express ownership and why two `unique_ptr`s must not own the same object. Add `count() const` and test an empty list.
