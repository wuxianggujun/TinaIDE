# Linux Environment and Storage

The Storage page manages optional Linux rootfs profiles, imports, MT Manager access, and storage cleanup.

TinaIDE still uses the native tina-toolchain with an Android sysroot for its default C/C++ build and LSP path. Linux/PRoot is an optional environment for terminal, debugging, and compatible guest workflows.

## Linux system section

This section appears only when the Linux development environment is enabled. It can provide:

- install a preset Linux distribution;
- view the active system and profile list;
- run a health check on the active rootfs;
- install and open the graphical desktop (only once a Linux system is installed);
- inspect install progress and rootfs path.

The managed distribution installer uses the built-in or cached catalog for normal listing and can explicitly refresh Registry metadata during installation. Remote failure falls back to cached or bundled data. The built-in catalog currently ships Ubuntu 24.04 LTS only.

## Graphical desktop

Once a Linux system is installed, three more items appear:

- **Graphical desktop** — whether the desktop packages are ready; tap to re-check.
- **Install desktop packages** — runs `apt-get` in the guest to install XFCE, `xkb-data`, DBus, PulseAudio, FCITX, and Mesa.
- **Open graphical desktop** — starts the X server, launches XFCE in the guest, then opens the desktop window.

Notes:

- The X server runs in a separate background process with an ongoing notification. Closing the desktop window does not end the guest session; opening it again reconnects to the same desktop.
- Startup fails closed when packages are missing, and the message names what is absent. This is deliberate: launching a desktop without a running X server only produces hard-to-diagnose "cannot open display" errors.
- Installing desktop packages downloads a large number of packages. Use Wi-Fi.

**This path has not yet been verified end to end on a real device.** Please attach logs when reporting problems.

## Profiles

Each profile represents a rootfs path and detected package manager. You can activate, rename, or delete eligible profiles.

Protected profiles cannot be renamed or deleted. The active profile does not show an unnecessary Activate action.

## Health check

**Health status** reports a structured check of the active rootfs and can be re-run by tapping it. It covers rootfs availability, the package manager, required and optional bootstrap commands, the architecture, and `/etc/os-release`. Missing required commands mark the rootfs unusable; missing optional ones are reported without blocking use.

## MT Manager access

MT access is enabled by default and is limited to TinaIDE-owned data, Android/data, Android/obb, and user_de_data paths. It does not grant access to every application or the whole device. Disable it when it is not needed.

## Storage cleanup

The cleanup screen can scan and clean:

- build intermediates;
- PRoot cache;
- download cache;
- export cache;
- application logs;
- installation logs.

Category details support expandable directories and selected-item cleanup. Top-level directories use three-state selection.

**Clean all** targets caches, intermediate output, and logs. Confirmation text states that source code, project configuration, and fonts are not removed. Export logs before cleanup when you are still diagnosing a problem.

Some files may fail to delete while other selected items are cleaned successfully. Review the released-space result and any failure message.

## Troubleshooting

### Linux section is missing

Check whether the Linux development environment is enabled and whether the current state has refreshed.

### Desktop entries are missing

The three desktop items appear only when a Linux system is already installed and has an active profile. Install a distribution first.

### Opening the desktop fails

Check the **Graphical desktop** status first; if it is not ready, run **Install desktop packages**. The failure message names the missing commands. For X server failures, check the logs on the About and logs screen.

### A profile cannot be removed

It is likely active or protected.

### Storage is low

Open Storage Cleanup, review the largest categories, export needed logs, then clean logs, export cache, or build intermediates before changing rootfs data.

## Related documentation

- [Settings overview](settings-overview.md)
- [Terminal settings](terminal-settings.md)
- [Terminal troubleshooting](terminal-troubleshooting.md)
- [About and logs](about-and-logs.md)
- [Known issues](known-issues.md)
