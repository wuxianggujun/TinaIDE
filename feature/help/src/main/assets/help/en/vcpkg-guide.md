# vcpkg dependency reference

TinaIDE's vcpkg integration targets Android-native CMake projects. For the complete add, link, build, and run workflow, start the continuous C++ practical course from the Tutorials tab. This page explains states, modes, and troubleshooting.

## States and boundaries

- **In manifest**: a dependency name is recorded in the selected project's `vcpkg.json`; it has not necessarily been downloaded or installed.
- **Installed**: an installation record exists for the selected project and ABI. After changing the manifest, install/build again to synchronize dependencies.
- **Verified**: TinaIDE has completed a device download, compile, and link check for the package. This does not guarantee every feature or transitive dependency.
- Editing the manifest does not create CMake usage. CMake still needs `find_package` and `target_link_libraries`. Package, config, and target names can differ; check the upstream usage.

## Integration modes

- **Built-in project vcpkg**: the manifest and installation workspace belong to the project. The app manages toolchain, tools, and paths; do not enter `CMAKE_TOOLCHAIN_FILE`, `VCPKG_ROOT`, or a desktop sysroot manually.
- **Shared device library**: compiled dependencies are exported per ABI for reuse by other projects. Each project still needs CMake package discovery and target linkage. Switch back to built-in project mode to edit dependencies.
- **Disabled integration**: the project no longer uses app-managed dependencies; adjust its CMake references accordingly.

## Catalog and compatibility

The market catalog is a set of browsable candidates, not a promise that each library has been built for the current Android ABI. Filtering uses upstream `supports` declarations and static-linking conditions; packages without declared restrictions can also appear. Features, transitive dependencies, or extra build tools may still be incompatible. Remove or adjust unavailable dependencies already in a manifest instead of mixing in Linux or Windows binaries.

Refresh rereads the local catalog. **Download / update catalog** fetches new metadata; it does not edit manifests, upgrade installed libraries, or replace APK-bundled tools. Bundled tools are verified and extracted by the app. Missing ports, Git objects, or library sources may require network access on first use.

For slow GitHub connections, configure a GitHub proxy from the top-right of Market. A basic HTTP download proxy may not support Git smart HTTP. Active tasks keep their startup configuration; cancel and retry after changing it. A failed or cancelled update keeps the previous catalog available.

## Troubleshooting reference

| Symptom | Check |
| --- | --- |
| Project is absent from the market selector | It must be in the project list and have a root `CMakeLists.txt` |
| Package config is missing after adding a manifest entry | Check the selected project, ABI, installation state, native CMake mode, and `find_package` name |
| Headers resolve but linking fails | Ensure the actual CMake target links the correct target; the manifest entry alone is insufficient |
| Package is not in the compatibility list | Check catalog declaration, ABI, static linking, and dependency features; do not substitute desktop binaries |
| Installation or catalog download fails | Read the first useful build/install error; check network and Git proxy support, then cancel and retry |
| Shared mode cannot edit the manifest | Switch to built-in project vcpkg; exported shared libraries remain available |

Keep the original project and build logs while troubleshooting. Uninstalling the app, deleting source, or manually copying dependencies is unnecessary.

## Related documents

- [CMake configuration reference](cmake-guide.md)
- [Package management](package-manager.md)
- [Build and run reference](build-project.md)
