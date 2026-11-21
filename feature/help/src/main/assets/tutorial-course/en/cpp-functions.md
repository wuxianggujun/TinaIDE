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

## Follow the same function through the code

Find `totalPrice` in Outline, inspect its signature, and navigate to the implementation. With the cursor on its name, show incoming calls. On `discountedTotal`, show outgoing calls and find the call to `totalPrice`. Wait for the language server or inspect its error if queries are unavailable.

Place the cursor inside `totalPrice` and open Current function flowchart. The validation branches should lead to `return -1` and the multiplication return. Tap a return node to check its source location. Change the maximum count from 100 to 90, regenerate, and observe the condition; restore 100 and rerun the boundary inputs afterward. The [editor reference](../../help/en/editor-basics.md) describes the controls; this exercise continues the same pricing project.

<!-- tutorial-hint -->
Four files matter: declarations in `pricing.h`, implementation in `pricing.cpp`, calls in `main.cpp`, and the source list in `CMakeLists.txt`. Run the complete examples first, then add the discount exercise.

<!-- tutorial-hint -->
Call `totalPrice` first and reject -1 before applying the discount. This preserves the quantity and price validation. Call the new function from main without deleting `totalPrice`.

<!-- tutorial-hint -->
**Append** this declaration to `pricing.h`:

```cpp
int discountedTotal(int count, int unitPrice);
```

**Append** this function after `totalPrice` in `pricing.cpp`:

```cpp
int discountedTotal(int count, int unitPrice) {
    const int total = totalPrice(count, unitPrice);
    if (total < 0) return -1;
    return total >= 100 ? total - 10 : total;
}
```

Replace one `totalPrice(3, 10)` call in main with `discountedTotal(10, 10)`. Expect `total=90`; count -1 must still fail. For an undefined function, check the declaration and the CMake source list.
