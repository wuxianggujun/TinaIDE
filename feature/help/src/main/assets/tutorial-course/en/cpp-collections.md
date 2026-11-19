# Lab 4: Strings and containers

Prerequisite: [Functions and multiple files](cpp-functions.md). Keep the same `native_lab`, `pricing.h`, `pricing.cpp`, and CMake targets. Replace only `main.cpp`:

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

Expect `Tina: items=3 total=30`. `std::string` stores the customer, `vector` stores line-item quantities, `all_of` validates each item, and `accumulate` sums them before calling the earlier `totalPrice` function. This lesson continues the existing multi-file project rather than resetting its target.

## Exercise and boundaries

- Use `{0}`: expect total `0`.
- Use `{}`: report an error instead of running a business calculation on empty input.
- Add `-1` or `101`: expect `invalid count`. More than 100 valid items are rejected by `totalPrice`.
- Print each value with a range loop. Use `int&` only when changing an element.

`at(index)` checks bounds; `operator[]` does not. A vector resize can invalidate references, so do not keep an element reference across a `push_back` that may reallocate. UTF-8 `std::string::size()` counts bytes, not displayed characters; keep files UTF-8 and do not split multibyte text at arbitrary byte offsets.

## Your exercise

Read the unit price from input and reject parse failures. Add a count of valid line items; predict and test an empty list, zero quantity, and an invalid quantity.
