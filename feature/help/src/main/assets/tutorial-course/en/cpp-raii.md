# Lab 5: Objects and resource lifetimes

Prerequisite: [Strings and containers](cpp-collections.md). Tap **Create / resume chapter project** to prepare all files in `tina_course_05_raii`, including pricing.* and main.cpp below. It records the price with RAII without moving or replacing the previous chapter's project.

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

Expect `total=30`. Set a writable project directory as the run configuration's working directory; the report path is relative to that directory. This version still calls the prior `totalPrice`, so the next CMake library lesson can reuse it directly.

`private` protects the quantities and `itemCount() const` does not modify the object. `unique_ptr` expresses single ownership and releases its object on scope exit. A stack object would be simpler here; dynamic allocation teaches ownership, not a default for every object.

Keep the prior validation: empty input, negative quantities, and a sum above 100 return -1 for the caller to reject. Do not validate only after negative and positive quantities cancel each other out.

RAII ties memory and file cleanup to object lifetime, including early returns. File stream destructors close handles; explicitly closing and checking is useful when a write failure must be reported. `std::move` transfers vector storage; do not assume the moved-from object retains its original values.

## Your exercise

Use a stack `OrderLedger` without changing `total=30`. Try a nonexistent output directory and check the error, then restore the valid path. Explain why a raw pointer does not express ownership and why two `unique_ptr`s must not own the same object. Add `count() const` and test an empty list.

<!-- tutorial-hint -->
The complete example is already in main.cpp; run the baseline directly. Keep the class outside main(). Use a writable working directory; total.txt is relative to it, not a desktop path.

<!-- tutorial-hint -->
For the stack exercise replace only the object creation and method call. `unique_ptr` uses `->`; a stack object uses `.`. Keep the file operations. `count()` is the number of rows; `itemCount()` validates and totals quantities.

<!-- tutorial-hint -->
**Replace** the two lines creating `ledger` and calculating `total` with:

```cpp
const OrderLedger ledger(std::vector<int>{1, 2});
const int total = totalPrice(ledger.itemCount(), 10);
```

**Add** this method under the class's `public:` section:

```cpp
std::size_t count() const { return quantities_.size(); }
```

Add `#include <cstddef>` at the top. Expect `ledger.count()` to be 2 and the amount to remain 30. An empty ledger has count 0 but is not a valid order. `{-1, 2}` must fail, not become one item.
