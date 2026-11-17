# Using vcpkg with CMake: add, link and run

TinaIDE includes Android-native vcpkg. Select a library, declare its CMake linkage and build. The app manages tool and installation directories; no extra plugin or directory setup is required.

This guide uses `fmt` to print `TinaIDE + vcpkg: 42`. Use native CMake. Supporting tools are bundled and verified before extraction; library sources are downloaded on first use and compiled on the device.

For slow GitHub connections, tap **GitHub proxy at the top right of Market**. Choose a preset or enter your own HTTPS proxy prefix and save, without opening Settings. The selected URL is tried first for catalogs, sources, and vcpkg Git fetches, with origin fallback. Git fetches require a proxy that supports Git; an optional HTTP proxy can also provide the connection. Clear the URL for automatic selection. Active installations retain their starting configuration; cancel and retry to use new settings.

## 1. Create or select a project

For a new project:

1. Open **Projects → +** and select **CMake executable**.
2. Name the project `tina_vcpkg_example` and select C++17 or later.
3. Check **Use built-in vcpkg**, then create the project.

This option is off by default and appears only for CMake templates. It enables managed integration and creates an empty `vcpkg.json`. An existing template manifest is preserved. Project creation does not download or compile libraries.

For an existing project, confirm that its root contains `CMakeLists.txt`, then continue below. Adding the first dependency in the market enables integration automatically. This also works if you left the wizard option unchecked.

Single-file and Make projects do not use this CMake integration. Graphics templates still need their existing engine packages; enabling vcpkg does not replace the built-in packages required by a template.

## 2. Add fmt

1. Open **Market → vcpkg** and search for `fmt`.
2. Tap **Add to project** and choose your CMake project.
3. Confirm that the `fmt` card shows the **In manifest** badge.

The app updates `vcpkg.json` in the project root. A minimal manifest is shown below. If it already contains dependencies or configuration, merge the dependency entry:

```json
{
  "dependencies": ["fmt"]
}
```

**Add to project** only edits the manifest. An **Installed** version indicates an actual installation for the selected project and architecture.

## 3. Link the library in CMake

For the new project in this guide, replace the root `CMakeLists.txt` with the following content and create `main.cpp` beside it. This example compiles that root-level `main.cpp`.

```cmake
cmake_minimum_required(VERSION 3.22)
project(tina_vcpkg_example LANGUAGES CXX)
find_package(fmt CONFIG REQUIRED)
add_executable(tina_vcpkg_example main.cpp)
target_link_libraries(tina_vcpkg_example PRIVATE fmt::fmt)
target_compile_features(tina_vcpkg_example PRIVATE cxx_std_17)
```

`main.cpp`:

```cpp
#include <fmt/core.h>

int main() {
    fmt::print("TinaIDE + vcpkg: {}\n", 42);
    return 0;
}
```

For an existing project, merge `find_package(fmt CONFIG REQUIRED)` and add `fmt::fmt` to the existing target's `target_link_libraries`. Use the target name from your own `add_executable` or `add_library`. Keep your existing targets and source lists.

The app passes the vcpkg toolchain automatically. Do not enter `CMAKE_TOOLCHAIN_FILE`, `VCPKG_ROOT` or installation paths manually. A market package name, its `find_package` name and its CMake target may differ; check the library's CMake instructions when using other packages.

## 4. Install, build and run

Save both files and tap **Run** in the editor. The app installs manifest dependencies, configures CMake, builds and runs the selected target.

You can also tap **Install to project** in the market footer to inspect installation output. This step does not run your program; return to the editor to build and run. Select native CMake, terminal output, and the `tina_vcpkg_example` target in the run configuration.

Expected output:

```text
TinaIDE + vcpkg: 42
```

If it fails, inspect the first installation, configuration or linker error in **Build Log**. Cancelling installation preserves completed libraries so you can retry later.

## 5. Add, remove and update dependencies

- Add another library in the market, then update CMake linkage and source usage according to that library's documentation.
- To remove a library, tap **Remove from manifest** on its card, remove its source usage and CMake linkage, then install/build again. Packages required by other libraries may remain installed.
- **Download / update catalog** refreshes browsable ports metadata. It does not automatically rewrite project manifests, upgrade installed libraries or replace the CLI bundled with the APK.
- To reuse compiled libraries, tap **Shared library** in the footer. In another project choose **Project settings → vcpkg dependencies → Use the shared device library**. Keep its `find_package` and target linkage. Use **Use built-in vcpkg** to switch back before editing dependencies.
- Existing `builtin-baseline`, features and overrides are preserved. The market shows the current catalog; the project's manifest and registries determine the versions it uses.
- Disable integration under **Project settings → vcpkg dependencies**. Adjust the project's CMake dependencies accordingly.

The default catalog evaluates upstream `supports` declarations for the current app architecture and static linking. Libraries without declared restrictions are also candidates. This does not verify every feature, transitive dependency or additional build tool. Existing incompatible or removed packages still appear in the list so they can be removed there.

## Troubleshooting

| Symptom | Check and action |
| --- | --- |
| Project is absent from the market selector | Ensure it is in the project list and has `CMakeLists.txt` at its root; a file only in a subdirectory is insufficient |
| fmt is not found after adding it | Confirm the selected project, inspect `vcpkg.json` and installation output, and use native CMake mode |
| Headers resolve but linking fails | Link `fmt::fmt` to the target you actually run; adding a manifest entry does not edit CMake linkage |
| A package is absent | Search covers current Android candidates. Update the catalog; Linux/Windows libraries cannot be linked into Android builds |
| Installation fails offline | Browsing, lessons, and bundled helper preparation work offline. Missing ports sources, Git objects, or library sources still require a connection |
| GitHub downloads fail or stall | Switch the GitHub proxy, then cancel and retry active tasks. Git fetches need smart HTTP support, not only a file-download proxy |
| The catalog or repository archive cache is corrupt | Update the catalog or retry dependency installation. Cached data is validated when read; invalid archives are downloaded again with fallback to other sources. If all sources fail, the previous catalog remains available; no app reinstall or manual vcpkg installation is needed |
| Shared mode cannot edit dependencies | Choose **Use built-in vcpkg** to return to project manifest mode; existing shared files remain |

## Exercises and acceptance

Replace `42` with `7 * 6`, save, and verify the same output. Temporarily remove `target_link_libraries`, observe the error, restore it, and run again. Rebuild to check reuse of installed dependencies. After a complete online installation, rebuild the unchanged project offline. Finally link and run another project using the shared library before marking the lesson complete.

## Further reading

- [Create a project](create-project.md)
- [CMake guide](cmake-guide.md)
- [Package management](package-manager.md)
- [Build a project](build-project.md)
