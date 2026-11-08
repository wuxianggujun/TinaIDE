# Package Management

**Profile → My packages** lists installed packages, including available updates. A package appears when either its Android or Linux version is installed. Uninstalling refreshes this filter.
**Settings → Packages** provides the full catalog. Browse new libraries from **Marketplace → Packages**.

## vcpkg catalog

**Marketplace → vcpkg** manages dependencies for a selected CMake project. Built-in packages usually provide prebuilt artifacts; vcpkg offers more ports, but on-device installation may compile source code.
Select a project and configure its vcpkg root and mode. Search local ports, add or remove manifest dependencies, then install and configure the project. Installed badges come from that project and ABI, independently of manifest declarations.
On-device installation requires Android-native vcpkg, git, curl, zip, unzip, pkg-config and the TinaIDE toolchain. These helper executables are not bundled in the APK.
Alternatively, import libraries exported from a computer using the Android NDK. Linux/PRoot libraries cannot replace Android libraries, and some ports do not support Android.
The new integration has not yet completed compilation or device acceptance.

## Main actions

- Search installed packages
- Check for updates
- Refresh
- Enter selection mode
- Update all when updates are available

## Linux and Android status

A package can have independent platform states:

- **Linux** — installed inside the optional Linux/PRoot environment.
- **Android** — headers, libraries, sources, or executables for the native Android toolchain.

Each platform can show Install, Installed, Installing, or Update. Installing one platform does not automatically install the other.

## Package details

Details can include category, homepage, install type, Linux package name, size, ABI, package and upstream versions, dependencies, and changelog.

Dependencies are clickable so you can follow the dependency chain.

## Install, update, and uninstall

Progress stages include Preparing, Downloading, Verifying, Extracting, Installing, Completed, and Failed.

After a successful install, reopen related source files so completion and include-path state can refresh.

Update All runs updates one at a time, preserving separate Android and Linux updates for the same package. Selection mode supports batch installation and uninstallation by platform.
Batch operations can be cancelled. Results show succeeded, failed and unfinished counts; failed or unattempted updates remain available for retry.

## Android artifact types

- **header** — exposes the package include directory.
- **source** — exposes headers, but project sources must be added explicitly to CMake, Make, or the project configuration.
- **static/shared/executable** — exposes library or executable paths and checks ABI compatibility before installation.

Header-only packages such as nlohmann-json, glm, or stb can be included after installation and file reload. Source packages such as tinyxml2, fmt, or imgui still require their source files in your build.

## Troubleshooting

### Installed package has no effect

1. Confirm the intended Linux or Android platform.
2. Confirm successful completion.
3. Reopen the source file.
4. Check Build Log for additional missing dependencies.

### A new package is not listed

This screen lists installed packages. Use Marketplace to find a new package.

### Uninstall warns about dependents

Review the dependent packages before removing a shared dependency.

## Related documentation

- [Settings overview](settings-overview.md)
- [Build a project](build-project.md)
- [Compiler settings](compiler-settings.md)
- [Terminal troubleshooting](terminal-troubleshooting.md)
- [Known issues](known-issues.md)
