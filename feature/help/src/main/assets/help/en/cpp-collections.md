# Lab 4: Strings and containers

Goal: use the standard library for a grade book without manual array allocation. Set the executable source list back to just `main.cpp` and use:

```cpp
#include <algorithm>
#include <iostream>
#include <numeric>
#include <string>
#include <vector>

int main() {
    const std::string name = "Tina";
    const std::vector<int> grades{60, 80, 100};
    if (grades.empty()) { std::cerr << "no grades\n"; return 1; }
    const bool valid = std::all_of(grades.begin(), grades.end(), [](int grade) {
        return grade >= 0 && grade <= 100;
    });
    if (!valid) { std::cerr << "invalid grade\n"; return 1; }
    const int sum = std::accumulate(grades.begin(), grades.end(), 0);
    const double average = static_cast<double>(sum) / grades.size();
    std::cout << name << ": count=" << grades.size() << " average=" << average << '\n';
    return 0;
}
```

Expect `Tina: count=3 average=80`. A vector owns its storage; iterators describe its range. The lambda validates one grade. Conversion to `double` before division preserves a fractional average.

## Exercise and boundaries

- Use `{60, 81}`: expect `70.5`.
- Use `{}`: report an error instead of dividing by zero.
- Add `101`: expect `invalid grade`.
- Print all values with a range loop. Use a reference only when you intend to change the elements.

`at(index)` checks bounds and throws on an invalid index; `operator[]` does not. A vector resize can invalidate references to elements. Do not keep a reference across `push_back` without considering reallocation.

UTF-8 `std::string::size()` counts bytes, not displayed characters. Save as UTF-8 and do not split multibyte text at arbitrary byte offsets.

Add a maximum and a passing-grade count with `max_element` and `count_if`. Predict results for one element, all failing grades, and invalid input first. Keep the empty-range check before dereferencing an iterator.

## Continue learning

- [Objects and resource lifetimes](cpp-raii.md)
