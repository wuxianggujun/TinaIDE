# Lab 7: fmt dependencies and build

First finish [CMake targets and libraries](cmake-practice.md). Tap **Create / resume chapter project** to prepare the independent `tina_course_07_vcpkg` with target `native_lab`. New projects declare fmt, link it in CMake, and use it in `main.cpp`. No manual file creation or initialization paste is needed.

Creation only prepares local files and never downloads packages. The first run installs declared dependencies; missing sources or Git objects require network access. **Resuming an existing project never rewrites its sources or manifest**.

## Run the complete baseline first

`vcpkg.json`:

<!-- tutorial-project-file: vcpkg.json -->
```json
{
  "dependencies": ["fmt"]
}
```

`CMakeLists.txt`:

<!-- tutorial-project-file: CMakeLists.txt -->
```cmake
cmake_minimum_required(VERSION 3.22)
project(native_lab LANGUAGES CXX)
find_package(fmt CONFIG REQUIRED)
add_library(pricing STATIC pricing.cpp)
target_include_directories(pricing PUBLIC "${CMAKE_CURRENT_SOURCE_DIR}")
target_compile_features(pricing PUBLIC cxx_std_17)
add_executable(native_lab main.cpp)
target_link_libraries(native_lab PRIVATE pricing fmt::fmt)
```

`main.cpp`: the project reuses the pricing declarations and implementation from the [functions lab](cpp-functions.md); this stage focuses on dependencies.

<!-- tutorial-project-file: main.cpp -->
```cpp
#include "pricing.h"
#include <fmt/core.h>
#include <iostream>

int main() {
    const int total = totalPrice(3, 10);
    if (total < 0) { std::cerr << "invalid order\n"; return 1; }
    fmt::print("total={}\n", total);
    return 0;
}
```

1. Save and run `native_lab`. Wait for dependency installation, CMake configuration, and build to succeed; expect `total=30` in the terminal.
2. Use this stage's Market action to open **Market → vcpkg**, select this chapter project, and inspect fmt's manifest and installed states. The former is a declaration; the latter is an installation record for this project and ABI.
3. Build and run again. Installed dependencies should be reused and output should remain `total=30`. Project creation neither replaces installation nor records stage completion.

## Recover an existing empty manifest

Previously created chapter projects retain their files when resumed. Inspect the root `vcpkg.json`: if it has `"dependencies": []`, add fmt to **this project** in Market, or change the dependency list to `"dependencies": ["fmt"]`, save, and run again. Preserve other fields and your sources.

`All requested packages are currently installed` applies only to packages actually declared in the manifest. An empty manifest also prints it immediately, while `find_package(fmt)` still fails; the message is not proof that fmt is installed. Do not delete the project, copy desktop libraries, or hardcode install paths.

## Complete the dependency workflow yourself

- Temporarily remove fmt from the manifest, run, and observe the package-not-found configuration error. Add it back through Market and recover.
- Temporarily remove `fmt::fmt` from `target_link_libraries`, observe a header or linking error, and restore it. A manifest does not replace CMake usage requirements.
- Change the quantity to 10 and expect `total=100`; -1 must report an error. Restore 3 before choosing Verify current course stage.
- For download failures, inspect the first relevant error, cancel, and retry with the appropriate network or Git proxy settings. Keep practice sources intact.

See the [vcpkg reference](../../help/en/vcpkg-guide.md) for states, modes, and proxy details.

<!-- tutorial-hint -->
New projects prepare the manifest, CMake, and fmt output together. Run the complete baseline first; the initial run may download and install dependencies. Existing projects resume their original files, and an empty manifest needs an explicit fmt addition rather than a silent rewrite.

<!-- tutorial-hint -->
The workflow has three parts: declare fmt in `vcpkg.json`, find and link `fmt::fmt` in CMake, and include `<fmt/core.h>` and call `fmt::print` in C++. Installation alone does not prove the program builds or runs.

<!-- tutorial-hint -->
Restore fmt in the manifest and its CMake link, keeping `pricing.*`. The baseline output statement is:

```cpp
fmt::print("total={}\n", total);
```

`total` comes from `totalPrice(3, 10)` and should produce `total=30`. An existing RAII exercise can keep its `restored` variable; fix a missing package through the manifest without rewriting sources. Save and rebuild successfully before verifying.
