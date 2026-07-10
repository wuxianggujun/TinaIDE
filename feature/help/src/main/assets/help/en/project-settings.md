# Project Settings

The Project page combines global preferences with settings that require an active project.

## Global preferences

These appear outside targeted-project override mode:

- Auto-save: Off, 30 seconds, 60 seconds, or 5 minutes
- Automatic backup
- Default source location for new projects: Public or Private

Changing the default source location affects future projects; it does not move an existing project.

## Project-specific sections

Project overview, native dependency paths, and native build flags appear only when a project context exists. Open a project or enter Project Settings from a project-list action.

## Project overview

The page can show the project name and APK export type:

- Native Activity
- SDL3
- Terminal
- Disabled
- Not detected

Re-detect export type may be hidden when Settings was opened for a targeted project rather than the active editor session.

## Native dependency paths

Configure:

- Include directories
- Library directories
- Runtime directories

The list displays a count summary. Open the editor dialog to inspect or change each path.

Use these fields when project metadata needs extra paths. Do not duplicate paths already expressed correctly by CMake without a reason.

## Native build flags

- CFLAGS
- CXXFLAGS
- LDFLAGS
- LDLIBS
- CMake Args

The first four are normalized as whitespace-separated text. CMake Args is maintained as a list.

## Troubleshooting

### Project sections are missing

Confirm that a project is open or that Settings was launched for a specific project.

### Current project did not move

The source-location setting changes the default for newly created projects only.

### Only a count or truncated value is visible

The main list shows summaries. Open the corresponding editor to see the complete data.

## Related documentation

- [Settings overview](settings-overview.md)
- [Create a project](create-project.md)
- [Compiler settings](compiler-settings.md)
- [Build a project](build-project.md)
