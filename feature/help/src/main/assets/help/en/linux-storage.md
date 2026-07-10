# Linux Environment and Storage

The Storage page manages optional Linux rootfs profiles, imports, MT Manager access, and storage cleanup.

TinaIDE still uses the native tina-toolchain with an Android sysroot for its default C/C++ build and LSP path. Linux/PRoot is an optional environment for terminal, debugging, and compatible guest workflows.

## Linux system section

This section appears only when the Linux development environment is enabled. It can provide:

- install a managed Linux distribution;
- import a preset rootfs;
- import a local rootfs archive;
- import from an HTTP or HTTPS URL;
- view the active system and profile list;
- inspect import progress and rootfs path.

The managed distribution installer uses the built-in or cached catalog for normal listing and can explicitly refresh Registry metadata during installation. Remote failure falls back to cached or bundled data.

## Profiles

Each profile represents a rootfs path and detected package manager. You can activate, rename, or delete eligible profiles.

Protected profiles cannot be renamed or deleted. The active profile does not show an unnecessary Activate action.

## URL import

A URL must begin with http:// or https://. Local-file import is preferable when you need a fully offline and controlled source.

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

### URL is rejected

Include an explicit http:// or https:// scheme.

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
