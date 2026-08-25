# TinaIDE

> Last manually reviewed: 2026-07-11
>
> A C/C++ IDE for Android devices. The default development path uses
> `native tina-toolchain + Android sysroot`; Linux distro / PRoot is optional.

[中文文档](README.md)

---

TinaIDE is an integrated development environment for phones and tablets. Current
runtime responsibilities are split into two layers:

- Default development path: bundled `native tina-toolchain`, `Android sysroot`,
  and native clangd/LSP.
- Optional Linux environment: `core:linux-distro + PRoot` for terminal, Linux
  tools, plugin LSP dependencies, and selected debugging flows.

Basic C/C++ build, run, and clangd completion no longer require PRoot.

## Features

- **Native C/C++ Toolchain**: Bundled `tina-toolchain` provides clang, clang++,
  clangd, and related runtime tools
- **Android Sysroot**: ABI-specific headers and libraries are installed and
  verified by the app
- **Intelligent Code Completion**: Native clangd + LSP diagnostics, go to
  definition, references, and hover information
- **Syntax Highlighting**: High-performance incremental syntax highlighting based on Tree-sitter
- **Code Navigation**: Go to definition, find references, hover documentation
- **Real-time Diagnostics**: Display errors and warnings in real-time while editing
- **Modern Editor**: Powered by Tina Editor (Compose + Canvas) with multi-tab editing
- **Material Design 3**: Following the latest Material Design guidelines
- **Terminal and Linux Environment**: Optional self-hosted Linux distro / PRoot
  terminal
- **LLDB Debugging**: Breakpoints, stepping, variables, and call stacks where
  the runtime environment supports it
- **Git Integration**: Clone/commit/push/pull/branches/conflict resolution with HTTPS credentials and SSH keys
- **Plugin System**: Themes, snippets, menu extensions, LSP plugins, and script
  / hybrid plugins
- **File Preview**: Built-in preview for Markdown/JSON/images/Hex and more
- **Embedded RikkaHub**: AI chat, models, providers, and MCP settings are
  handled by RikkaHub inside TinaIDE

## UI Preview

<table>
  <tr>
    <td align="center"><img src="./image/projects.jpg" alt="Projects" width="220"><br>Projects</td>
    <td align="center"><img src="./image/marketplace.jpg" alt="Marketplace" width="220"><br>Marketplace</td>
    <td align="center"><img src="./image/tutorials.jpg" alt="Tutorials" width="220"><br>Tutorials</td>
  </tr>
  <tr>
    <td align="center"><img src="./image/profile.jpg" alt="Profile" width="220"><br>Profile</td>
    <td align="center"><img src="./image/code-editor.jpg" alt="Code editor" width="220"><br>Code editor</td>
    <td align="center">More screens will be updated over time</td>
  </tr>
</table>

## Core Features

### Compiler Integration

| Feature | Description |
|---------|-------------|
| Native Toolchain | Bundled `tina-toolchain` for clang / clang++ / lld |
| Android Sysroot | ABI-specific Android headers and runtime libraries |
| Build Modes | Single-file, Make, and CMake project flows |
| Optional PRoot Path | Available only when the Linux distro / PRoot environment is installed |

### LSP Language Services

| Feature | Description |
|---------|-------------|
| Code Completion | Semantic-level intelligent completion, supporting member access, headers, macros, etc. |
| Go to Definition | Quickly jump to the definition of functions, variables, and types |
| Find References | Find all usages of a symbol in the project |
| Hover Documentation | Display type information and documentation on cursor hover |
| Real-time Diagnostics | Detect syntax and semantic errors in real-time while editing |

### Editor Features

| Feature | Description |
|---------|-------------|
| Multi-tab Editing | Open multiple files simultaneously with quick switching |
| Tree-sitter Highlighting | C/C++/CMake syntax highlighting |
| Symbol Input Bar | Quick input of programming symbols (brackets, operators, etc.) |
| Undo/Redo | Complete editing history support |
| Auto Indentation | Smart code indentation |
| Line Numbers | Configurable line number area |

### Project Management

| Feature | Description |
|---------|-------------|
| File Tree Navigation | Drawer-style project file browser |
| Project Templates | Single-file / CMake executable / CMake library templates |
| compile_commands.json | Auto-generated to provide compilation configuration for LSP |

### Bottom Panel

| Tab | Function |
|-----|----------|
| Build Log | Display compilation output and error messages |
| Log | General application logs |
| Diagnostics | LSP diagnostic list, click to jump to location |

### Debugging (LLDB)

| Feature | Description |
|---------|-------------|
| Breakpoints | Set/remove breakpoints and continue |
| Stepping | Step In / Step Over / Step Out |
| Inspection | Variables, call stack, threads, etc. |

### Git Integration

| Feature | Description |
|---------|-------------|
| Common Operations | status/diff/commit/log/checkout |
| Remote Operations | clone/fetch/pull/push (including rebase/force/tags options) |
| Credentials & Security | HTTPS credentials (encrypted by Keystore), SSH keys (passphrase supported) |
| Conflict Resolution | merge/rebase conflict workflows (continue/skip/abort) |

### Plugin System

| Feature | Description |
|---------|-------------|
| Plugin Manager | Install/uninstall/enable/disable (local directory) |
| Theme Plugins | Provide editor themes via plugins |
| Snippet Plugins | Show snippets in completion list and insert placeholders |
| Bundled Plugins | `assets/bundled_plugins/*` auto install/update on app start |
| Public Registry | Plugins, packages, and Linux distro metadata are published from `https://github.com/wuxianggujun/TinaIDE-Registry` |

TinaIDE 0.18.11 and later read the full `plugins/index.v3.json` history and
select the highest plugin version compatible with the host Plugin API and
`minAppVersion`. Older clients keep reading the conservative
`plugins/index.v2.json` view, while packages remain on `packages/index.v2.json`.
Marketplace requests do not fall back to legacy `plugins/index.json` /
`packages/index.json`; v2 and v3 reference the same immutable `.tinaplug`
artifacts. The Linux distro `linux-distro/manifest.v1.json` uses a separate
protocol and falls back through fresh cache, Registry endpoints, stale cache,
and finally the bundled asset.

### File Preview

| Type | Description |
|------|-------------|
| Markdown | Built-in Markdown viewer |
| JSON | JSON viewer |
| Images | Image preview |
| Hex | Hex viewer for binary files |

## Quick Start

### 1. Toolchain Assets

```powershell
# This repo already includes the built-in runtime/debug assets
# (sysroot, tina-toolchain metadata, and optional PRoot assets),
# so normal development usually does NOT require rebuilding them locally.
#
# If you need to rebuild (contributors/maintainers), see docs/快速开始.md
# ("维护者可选资产重建").
#
# Common entry points:
# - Refresh Linux distro manifest: pwsh ./tools/linux-distro/generate-linux-distro-manifest.ps1
# - Rebuild proot + sysroot: pwsh ./docker/proot-build/build-proot.ps1 -CopyToJniLibs -CopyToAssets
# - Sync tina-toolchain assets: pwsh ./tools/sync-tina-toolchain-assets.ps1 -Abi arm64
```

### 2. Build Application

```powershell
# Build and install arm64 Debug
pwsh ./tools/build-apk.ps1 -Install

# Build and install x86_64 Debug for emulators
pwsh ./tools/build-apk.ps1 -Abi x86 -Install

# Build Release, local signing config required
pwsh ./tools/build-apk.ps1 -Variant release
```

Advanced Gradle entry points:

```bash
./gradlew :app:assembleArm64Debug
./gradlew -Ptina.devAbi=x86_64 :app:assembleX86_64Debug
./gradlew :app:assembleDebugAllAbi
./gradlew :app:assembleReleaseAllAbi
```

### 3. Getting Started

1. Launch the app. The default setup verifies and installs `Android sysroot`
   and `native tina-toolchain`.
2. Create a new project or open an existing one
3. Write code (LSP automatically provides completion and diagnostics)
4. Click the run button to compile and execute

Install Linux distro / PRoot only when you need a Linux terminal, Linux tools,
or plugin dependencies that require a guest environment.

For detailed steps, see [Quick Start Guide](docs/快速开始.md)

### 4. Release Side Effects

Release tasks are not pure read-only checks. They may:

- increment `version.properties`
- back up R8 mapping files

Mapping files are only archived locally by the public build logic.

## Documentation

- [Quick Start](docs/快速开始.md) - Start using TinaIDE from scratch
- [Architecture Overview](docs/架构概览.md) - Understand project architecture
- [Development Guide](docs/开发指南.md) - Contribute to the project
- [Documentation Center](docs/README.md) - Complete documentation index
- [Linux Distro Runtime](docs/linux-distro-self-hosted-runtime.md) - Optional Linux environment and manifest fallback
- [Changelog](CHANGELOG.md) - Version update history

### Technical Documentation

- [GitHub Registry](docs/registry/GitHub-Registry.md)
- [ProGuard / R8 Rules](docs/proguard-rules-reference.md)
- [Toolchain Build Guide](docs/toolchain-build-guide.md)
- [Remote LSP Guide](docs/guides/Remote-LSP-Guide.md)
- [MT Data Files Provider](docs/guides/MT-Data-Files-Provider.md)

## Tech Stack

| Category | Technology |
|----------|------------|
| Languages | Kotlin, C++ |
| UI Framework | Jetpack Compose + Material 3 |
| Editor | Tina Editor (in-house, Compose + Canvas) |
| Syntax Highlighting | Tree-sitter |
| Compiler | native tina-toolchain |
| Sysroot | Android sysroot, installed per ABI |
| LSP Service | native clangd, with remote / PRoot / plugin providers |
| Build System | Gradle + CMake |
| Dependency Injection | Koin |
| Async Processing | Kotlin Coroutines + Flow |

## Supported Architectures

| Architecture | Status | Usage |
|--------------|--------|-------|
| `arm64-v8a` | ✅ Primary | Physical devices |
| `x86_64` | ✅ Supported | Emulators |

- `minSdk`: 28 (Android 9.0+)
- `targetSdk`: 36
- `compileSdk`: 37

## System Requirements

### Development Environment

- Android Studio (latest stable version)
- JDK 17+
- PowerShell 7+
- Docker Desktop only for maintainers rebuilding runtime assets

### Runtime Environment

- Android 9.0+ (API 28+)
- Recommended 3GB+ RAM
- Recommended 800MB+ available storage (including sysroot)

## Project Structure

```text
TinaIDE/
├── app/                # App shell, startup, navigation, cross-module wiring
├── core/               # Shared runtime: compile, LSP, storage, plugin, security
├── feature/            # User-facing slices: editor, settings, help, workspace
├── external/           # Local source dependencies and submodules
├── server/             # Public placeholder; backend moved to a private repo
├── docs/               # Project documentation
├── docker/             # Runtime asset build helpers
├── tools/              # Local development scripts and templates
└── build-logic/        # Gradle convention plugins
```

Architecture reminders:

- `app` handles startup, navigation, DI assembly, and cross-module coordination.
- `feature/*` owns user-facing flows.
- `core/*` owns reusable infrastructure.
- Default compile / LSP depends on native toolchain, not PRoot.
- PRoot is an optional Linux environment, not the default compiler host.

## Support

TinaIDE is being opened up fully, and future energy will focus on stable maintenance
and community collaboration. If this project helps you, voluntary support is welcome.
PRs are also welcome for features, bug fixes, and docs.

| WeChat | Alipay |
|--------|--------|
| <img src="./feature/settings/src/main/res/drawable-nodpi/donation_weixin.png" alt="WeChat donation QR code" width="220"> | <img src="./feature/settings/src/main/res/drawable-nodpi/donation_zhifubao.jpg" alt="Alipay payment QR code" width="220"> |

To quickly measure the project size while excluding third-party code and generated files:

```powershell
cloc . --exclude-dir=.git,build,.gradle,.idea,node_modules,external,temp,tmp,docs/assets
```

## Acknowledgments

- [LLVM Project](https://llvm.org/) - Compiler infrastructure
- [Tree-sitter](https://tree-sitter.github.io/) - Syntax highlighting parser
- [clangd](https://clangd.llvm.org/) - C/C++ language server

---

**Making mobile development more accessible**
