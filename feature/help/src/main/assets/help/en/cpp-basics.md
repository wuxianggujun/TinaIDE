# Lab 2: C++ values and control flow

First finish [the IDE workflow](ide-workflow.md). Keep its CMake file and replace `main.cpp` with this integer price calculator:

```cpp
#include <iostream>

int main() {
    constexpr int unitPrice = 10;
    const int count = 3;
    if (count < 0 || count > 100) {
        std::cerr << "invalid count\n";
        return 1;
    }
    int total = 0;
    for (int index = 0; index < count; ++index) {
        total += unitPrice;
    }
    std::cout << "total=" << total << '\n';
    return 0;
}
```

Save and run. Expect `total=30`. `constexpr` is a compile-time constant; `const` prevents later assignment. The loop executes three times because its condition is `<`, not `<=`.

## Check boundaries

| count | Expected result |
| --- | --- |
| 0 | `total=0` |
| 1 | `total=10` |
| 100 | `total=1000` |
| -1 or 101 | `invalid count` on the error stream and exit code 1 |

Represent money in integer minor units. If you increase the limits, reconsider integer size and multiplication overflow rather than just deleting validation.

Replace `const int count = 3;` with these statements to practice terminal input:

```cpp
int count = 0;
if (!(std::cin >> count)) { std::cerr << "expected integer\n"; return 1; }
```

Keep the range check. Enter `3`, `-1`, and `abc`; explain the difference between parsing failure and an out-of-range number.

## Your exercise

Subtract 10 when the total is at least 100. Predict results for counts 9, 10, and 11 before implementing `if/else`. Explain `&&`, `||`, and `==`; avoid using assignment `=` instead of comparison.

## Continue learning

- [Functions and multiple files](cpp-functions.md)
