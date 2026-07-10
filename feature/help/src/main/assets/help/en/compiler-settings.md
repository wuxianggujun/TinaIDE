# Compiler Settings

The Compiler page controls optimization, parallelism, CMake behavior, formatting, toolchains, sysroots, and optional environment redeployment.

## Compilation options

- Optimization levels: O0, O1, O2, O3
- Compilation threads: 1 to 8

Use O0 while debugging. Lower the thread count if the device overheats or becomes unresponsive.

## CMake and tool modes

CMake, Make, and clang-format can use native or proot modes. The proot choice appears only when the optional Linux environment is enabled.

The default compilation host is still the native tina-toolchain with an Android sysroot. Use proot only for workflows that require the Linux guest environment.

Available build types include Debug, Release, RelWithDebInfo, and MinSizeRel. Generators include Unix Makefiles and Ninja. CMake Parallel Jobs controls CMake build concurrency separately from the global compilation-thread preference.

## Formatting

Format Style chooses the clang-format preset. Check this value before assuming the formatter is broken.

## Toolchain management

The page shows:

- active toolchain;
- active NDK Runtime;
- sysroot installation state;
- import toolchain;
- import sysroot.

The NDK Runtime affects the compiler sysroot, C++ headers, libc++_shared.so injection, APK packaging, and the headers visible to clangd.

Toolchain archives can use tar.gz, tar.xz, or tar. Import extracts, validates, installs, registers, and activates the toolchain. Sysroots are imported separately.

Changing the NDK Runtime does not change the selected run target. An NDK shared-library template may still run its test executable until you select the library target or SDL mode.

## Redeploy development environment

When the optional Linux environment is enabled, **Redeploy development environment** performs a cleanup and initialization flow. Use it for a damaged PRoot environment or when terminal, debugging, and related guest tools fail together. Do not use it to fix one incorrect project flag.

## Troubleshooting

### proot is missing from a mode list

Enable the Linux development environment first.

### A toolchain imported successfully but builds still fail

Confirm the active toolchain, installed sysroot, current NDK Runtime, and project build flags.

### Execution still shows template output

Confirm that files were saved and inspect the selected CMake target and run configuration. Build diagnostics can show target selection and the final launched artifact.

## Related documentation

- [Settings overview](settings-overview.md)
- [Project settings](project-settings.md)
- [Build a project](build-project.md)
- [Linux and storage](linux-storage.md)
