# Lab 2: C++ values and control flow

First finish [the IDE workflow](ide-workflow.md). Tap **Create / resume chapter project** to prepare `tina_course_02_basics` with CMake and the complete calculator below. Its run target is `native_lab`. No manual file creation or changes to the previous chapter's project are needed.

<!-- tutorial-project-file: main.cpp -->
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

<!-- tutorial-hint -->
CMake and the complete main.cpp are already prepared. Run the fixed quantity of 3 first, then edit only main.cpp to add terminal input. Return 1 immediately on parsing failure and keep the range check before calculation.

<!-- tutorial-hint -->
Apply the discount after the loop and before output. Test `total >= 100`, not the item count. Counts 9, 10, and 11 should produce 90, 90, and 100 after discount.

<!-- tutorial-hint -->
**Insert** this fragment immediately before `std::cout` inside the existing `main()`. Keep the loop and do not declare another `total`:

```cpp
if (total >= 100) {
    total -= 10;
}
```

Enter `9`, `10`, `11`, `abc`, and `-1`. Check the three discounted amounts; the last two must fail. Project creation prepares the baseline only. Input and discount remain learning tasks and are not automatically marked as passed.
