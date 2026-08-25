# Maintenance checks

This directory contains lightweight repository checks for maintainers.

## Run all stable checks

Run:

```powershell
py tools/checks/check_all.py
```

By default, this only runs low-noise checks that are expected to pass locally.

Optional i18n check:

```powershell
py tools/checks/check_all.py --include-i18n
```

## Documentation consistency

Run:

```powershell
py tools/checks/check_documentation.py
```

The check validates:

- local Markdown links across `docs/`, both root READMEs, and App help assets;
- inline repository paths in current fact documents, plus known source paths left behind after module moves;
- one-to-one registration between `HelpRepository`, default help assets, and `help/en/*.md`;
- `minSdk`, `targetSdk`, and `compileSdk` facts in both root READMEs and the documentation status against `app/build.gradle.kts`;
- Registry paths for plugin, package, and Linux distro metadata across both root READMEs and current Registry documentation;
- the `CHANGELOG.md` `Unreleased` section and current preface paths.

The check is deliberately limited to current documentation. Historical design notes and old Changelog entries may retain paths that only make sense in their original version.

## Direct file operations

Run:

```powershell
py tools/checks/check_direct_file_operations.py
```

The check scans production Kotlin/Java sources under:

- `app/src/main`
- `feature/*/src/main`
- `core/*/src/main`

It tracks direct calls to:

- `deleteRecursively(...)`
- `renameTo(...)`
- `.delete()`

The baseline is stored in:

```text
tools/checks/direct_file_operations_allowlist.txt
```

If a new direct delete/rename call is needed, prefer a project-aware API first. For user project files, use `IFileOperations` so the file tree, editor tabs, AI tools, and plugin events stay synchronized.

Only update the baseline when the direct operation is intentional and the reason is clear.

## Embedded resource collisions

Run:

```powershell
py tools/checks/check_embedded_resource_collisions.py
```

The check compares TinaIDE host resources under `app/`, `core/`, and `feature/`
against embedded RikkaHub resources under `external/rikkahub/*/src/main/res`.
Unexpected shared resource names can make Android resource merging replace host
values such as the launcher label.

If a host resource is owned by TinaIDE, prefer a `tina` or `tinaide` prefix.
Only allowlist deliberate host-level overrides such as launcher icons or
FileProvider XML.
