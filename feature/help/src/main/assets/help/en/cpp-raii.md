# Lab 5: Objects and resource lifetimes

Goal: encapsulate a small collection and use RAII for memory and file handles. Use one `main.cpp`:

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

Set a writable project directory as the run configuration working directory. Expect `restored=60`; the file path is relative to that directory, not a fixed public-storage path.

`private` protects state and `total() const` does not modify the object. `unique_ptr` expresses single ownership and releases its object on scope exit. A plain stack `Ledger` would be simpler here; dynamic allocation is shown to teach ownership, not as a default for every object.

RAII ties resource release to object lifetime, including early returns and exceptions. File destructors close handles; explicitly closing and checking is useful when a write failure must be reported. `std::move` allows transfer of resources; do not assume moved-from values retain their contents.

## Your exercise

Replace the pointer with a stack object without changing output. Try a nonexistent output directory, check the error, and restore the valid directory. Explain why a raw pointer does not express ownership and why two unique owners must not manage the same allocation. Add `count() const` and test an empty ledger.

## Continue learning

- [CMake targets and libraries](cmake-practice.md)
