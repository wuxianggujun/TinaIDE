# Lab 3: Functions and multiple files

Goal: move pricing rules out of `main`. Create three UTF-8 files in the project root.

`pricing.h` declares the function:

```cpp
#pragma once
int totalPrice(int count, int unitPrice);
```

`pricing.cpp` implements it. This lesson uses `-1` for invalid inputs, so callers must check the result:

```cpp
#include "pricing.h"

int totalPrice(int count, int unitPrice) {
    if (count < 0 || count > 100 || unitPrice < 0 || unitPrice > 10000) {
        return -1;
    }
    return count * unitPrice;
}
```

`main.cpp`:

```cpp
#include "pricing.h"
#include <iostream>

int main() {
    const int total = totalPrice(3, 10);
    if (total < 0) { std::cerr << "invalid order\n"; return 1; }
    std::cout << "total=" << total << '\n';
    return 0;
}
```

Update the existing executable target:

```cmake
add_executable(native_lab main.cpp pricing.cpp)
```

Run and expect `total=30`. Including a header makes declarations visible. Adding the implementation to a target makes the compiler build it; both are needed.

## Diagnose errors

Remove `pricing.cpp` from CMake temporarily and observe a linker error. Restore it, misspell the call, and observe a compiler error. Restore the name and test `(0, 10)`, `(-1, 10)`, and `(100, 10000)`.

Do not include a `.cpp` file as a workaround. Compiling it separately as well produces duplicate definitions. Ordinary non-inline function definitions in shared headers can cause the same problem.

## References

An `int&` aliases an existing integer and can modify it. `const std::string&` accesses a larger string without copying it. The referenced object must remain alive; never return a reference to a local variable.

Add a separate discount function yourself. Keep `main` responsible for input/output and rerun boundary cases after changing the rules.

## Continue learning

- [Strings and containers](cpp-collections.md)
- [CMake targets](cmake-practice.md)
