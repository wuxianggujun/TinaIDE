# Settings Overview

Settings is grouped into development, experience, and other sections.

## Development

### Editor

Fonts, editor theme, line numbers, wrapping, rainbow brackets, folding, whitespace, indentation, Tab behavior, and hardware acceleration.

### Language Server

clangd mode, indexing, Clang-Tidy, completion presentation, Signature Help, Inlay Hints, Semantic Tokens, Remote LSP host and port, synchronization, rsync, and connection tests.

See [LSP settings](lsp-settings.md).

### Compiler

Optimization, build concurrency, CMake/Make/clang-format modes, generator, formatting style, active toolchain, NDK Runtime, sysroot, imports, and optional environment redeployment. Build type belongs to the selected project run configuration.

### Project

Auto-save and backup, default source location, APK export type, native include/library/runtime paths, compiler/linker flags, and CMake arguments.

### Storage

Optional Linux rootfs installation and imports, profiles, import status, rootfs path, MT access, and storage cleanup.

See [Linux environment and storage](linux-storage.md).

### Git

HTTPS credentials and SSH key generation, import, default key, key list, and host bindings.

### Plugins and packages

Plugins manages installed extensions, local .tinaplug imports, permissions, themes, LSP dependencies, logs, and uninstall. Browse new plugins from Marketplace.

Packages manages installed Linux and Android package states, updates, details, dependencies, and platform-specific uninstall. Browse new packages from Marketplace.

## Experience

### Terminal

Terminal theme, font, backend, shell, Linux-tool health, package manager, guest development packages, Zsh, Locale support, cursor behavior, and interaction Locale.

See [Terminal settings](terminal-settings.md).

### RikkaHub AI

Embedded RikkaHub manages models, providers, MCP, assistants, chat preferences, and its own API keys. TinaIDE owns only the entry point, embedded container, and lifecycle; it does not duplicate RikkaHub credentials in the host repository.

For model or provider problems, check RikkaHub settings. For an embedded page that fails to mount, export TinaIDE logs and diagnose the host integration.

### Appearance

Application theme and debug-toolbar position.

### Keyboard

Hardware-keyboard shortcuts for saving, tabs, undo/redo, and bookmarks.

## Other

### Help Center

Browse categories, search, and read localized Markdown help.

### Developer Options

When enabled for the current build, tap the version information five times within three seconds on About to unlock diagnostic and test tools. Change these options only when you understand their effect.

### About

Version, GitHub, logs, PRoot logs, crash-report preference, cleanup, donation options, and open-source licenses.

## Where to start

- Completion, navigation, diagnostics → Language Server
- Build, toolchain, formatting → Compiler and Project
- rootfs, environment, storage → Storage
- Terminal, shell, guest tools → Terminal
- Git authentication → Git
- AI models and providers → RikkaHub
- Logs and licenses → About

## Related documentation

- [Getting started](getting-started.md)
- [Editor settings](editor-settings.md)
- [Compiler settings](compiler-settings.md)
- [Project settings](project-settings.md)
- [Git settings](git-settings.md)
- [Plugin settings](plugins-settings.md)
- [Package management](package-manager.md)
- [Appearance settings](appearance-settings.md)
- [Keyboard settings](keyboard-settings.md)
- [About and logs](about-and-logs.md)
