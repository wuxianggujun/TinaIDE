# Terminal Settings

The Terminal page contains theme, font, shell/backend, Linux tools, and interaction settings.

The Linux Tools group appears only when the optional Linux development environment is enabled.

## Theme and font

Terminal themes come from the active theme provider.

Font choices can include:

- bundled font, when installed;
- system monospace;
- imported custom font.

Imported fonts are validated before being saved. Font size ranges from 8sp to 32sp.

## Backend

- **AUTO** — prefer PRoot when installed, otherwise use HOST.
- **PROOT** — force the optional Linux guest environment.
- **HOST** — use the Android host environment.

When Linux support is disabled, a persisted PROOT choice is corrected to HOST. Backend changes require restarting the terminal.

## Shell

- AUTO
- sh
- bash
- zsh

AUTO detection prefers zsh, then bash, then sh. Shell changes require restarting the terminal.

If zsh is missing in PRoot, Settings can offer installation. A missing zsh on HOST may be unsupported; use bash or PRoot instead.

## Linux Tools

The health section shows:

- distribution name and package manager;
- Guest Dev Packages;
- Zsh state;
- Locale support.

Guest Dev Packages checks compiler commands, make, git, curl, cmake, and pkg-config or pkgconf. Depending on state, Settings can install or reinstall them.

Package-manager labels include APK, APT, PACMAN, DNF, and UNKNOWN.

## Interaction

Available Locale choices include:

- C.UTF-8
- zh_CN.UTF-8
- zh_TW.UTF-8
- en_US.UTF-8
- ja_JP.UTF-8

Choosing a Locale other than C.UTF-8 may require installing or rebuilding Locale data in the Linux environment.

Cursor blinking can be disabled. Its interval ranges from 100ms to 2000ms.

## Recommended defaults

For a conservative setup:

- Backend: AUTO
- Shell: AUTO or bash
- Locale: C.UTF-8

For a complete guest toolchain, enable the Linux environment, use AUTO or PROOT, and install Guest Dev Packages.

## Troubleshooting

- PROOT is missing or resets to HOST: enable and deploy the Linux environment.
- Current terminal did not change: restart it after changing backend or shell.
- Custom font import failed: verify that the selected file is a valid font.
- Non-ASCII output is broken: verify Locale data and a font that contains the characters.

## Related documentation

- [Settings overview](settings-overview.md)
- [Terminal usage](terminal-usage.md)
- [Terminal troubleshooting](terminal-troubleshooting.md)
- [Linux environment and storage](linux-storage.md)
- [Build a project](build-project.md)
