# Lab 4: Strings and containers

Prerequisite: [Functions and multiple files](cpp-functions.md). Tap **Create / resume chapter project** to open the independent `tina_course_04_collections`. The app prepares pricing.h, pricing.cpp, CMake, and main.cpp below without overwriting the previous chapter's discount exercise.

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

Expect `Tina: items=3 total=30`. `std::string` stores the customer, `vector` stores quantities, and `all_of` validates each item. The range loop checks the order limit before adding, preventing overflow before validation. This project already includes the earlier totalPrice implementation.

## Exercise and boundaries

- Use `{0}`: expect total `0`.
- Use `{}`: report an error instead of running a business calculation on empty input.
- Add `-1` or `101`: expect `invalid count`. A sum over 100 is rejected before addition.
- Print each value with a range loop. Use `int&` only when changing an element.

`at(index)` checks bounds; `operator[]` does not. A vector resize can invalidate references, so do not keep an element reference across a `push_back` that may reallocate. UTF-8 `std::string::size()` counts bytes, not displayed characters; keep files UTF-8 and do not split multibyte text at arbitrary byte offsets.

## Your exercise

Read the unit price from input and reject parse failures. Add a count of valid line items; predict and test an empty list, zero quantity, and an invalid quantity.

<!-- tutorial-hint -->
Run the prefilled main.cpp first; no files need to be copied from the earlier chapter. Start with quantities `{1, 2}`. Do not change price, discount, and target name at once. User-entered price is the next exercise.

<!-- tutorial-hint -->
Replace 10 in `totalPrice(itemCount, 10)` with a parsed variable. Stop on parse failure; `totalPrice` already rejects prices outside 0–10000. `quantities.size()` counts rows, while `itemCount` counts all items.

<!-- tutorial-hint -->
**Replace** `const int total = totalPrice(itemCount, 10);` inside main with:

```cpp
int unitPrice = 0;
if (!(std::cin >> unitPrice)) {
    std::cerr << "expected integer\n";
    return 1;
}
const int total = totalPrice(itemCount, unitPrice);
```

Keep the following `total < 0` check. After that check, you may add:

```cpp
std::cout << "rows=" << quantities.size() << '\n';
```

Enter 10: expect two rows, 3 items, and total 30. `abc` must fail; quantities `{100, 1}` must exceed the order limit.
