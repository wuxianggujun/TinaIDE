# Package Management

**Profile → My packages** lists installed packages, including available updates. A package appears when either its Android or Linux version is installed. Uninstalling refreshes this filter.
**Settings → Packages** provides the full catalog. Browse new libraries from **Marketplace → Packages**.

## vcpkg catalog

**Marketplace → vcpkg** manages dependencies for a selected CMake project. Built-in packages usually provide prebuilt artifacts; vcpkg offers more ports, but on-device installation may compile source code.
A fixed toolbar keeps project selection reachable while the catalog scrolls; the search field lives in the market top bar and is shared with the plugin and package tabs:
- Top-bar search filters by port name or description as you type. Switching tabs keeps the query; switching projects keeps the query.
- The list has a single view: ports are filtered by upstream declarations for the current architecture and static linkage, and ports without declared restrictions remain candidates. Entries already in the manifest but filtered out by compatibility still appear so they can be removed.
- The refresh icon re-reads the local catalog; **Download / update catalog** retrieves the newest metadata and preserves the old list if cancelled or unsuccessful.
- The project button selects a CMake project. Browsing works without a project; a project is requested when you add a dependency.
A hint reports how many upstream ports are hidden as incompatible with the current architecture. An empty search distinguishes "no such port" from "the port exists but declares no support for this architecture or static linking".
Cards show name, version and description plus **In manifest**, **Verified**, installed version and update-available badges. **View details** opens project status, compatibility, target architecture and the upstream `supports` declaration; both the card and the detail dialog can copy the port name.
Tap **Add to project** to pick a project; the app enables built-in vcpkg and updates `vcpkg.json`. **Remove from manifest** asks for confirmation first, then install/build again to synchronize installed state. Transitive dependencies are managed by vcpkg.
Installed badges come from the selected project and ABI, independently of manifest declarations. **Verified** marks only ports that TinaIDE exercised end to end on a device (currently `fmt`); everything else is filtered by upstream declarations alone. The footer offers two install targets: **To project** installs into the project's private workspace, while **Shared library** installs the same way and then exports the ABI's dependencies into the device-level shared directory so other projects can reuse them. Output streams into a dedicated dialog that scrolls live and can be copied; failures appear as a retryable banner above the list, and installation can be cancelled at any time.
Exporting switches the project to shared-library mode. Use **Use built-in vcpkg** before adding more dependencies; the private installed workspace is retained. Libraries and the installed index are published together as one immutable generation per ABI, so active builds do not lose the previous directory during publication. The market reads actual installed versions from the shared index. Libraries exported by an older version without an index are not assigned invented versions.
The shared store retains the current and previous generations, deferring older-directory collection until active builds leave. vcpkg's generated CMake configs resolve their prefix relatively; if a port hardcodes absolute paths, installing again in the project may be necessary.
After catalog updates or source preparation, archives and sources retain only the current revision and one recent older revision. Active builds defer collection; unrelated directories are not removed.
Settings store only disabled, project-manifest, or device-shared mode. Old manual paths and prebuilt settings are no longer read. Helper tools are bundled and verified; missing library sources and Git objects still need a connection. Select shared mode in another project to reuse exported libraries. Platform filtering does not verify every feature or transitive dependency; inspect actual build output.
For a complete new-project and existing-project example, see [Using vcpkg with CMake](vcpkg-guide.md), also available through the market's **Usage guide** action.

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
