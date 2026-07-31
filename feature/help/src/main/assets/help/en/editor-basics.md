# Editor Basics

TinaIDE uses a mobile workspace rather than a traditional desktop menu layout.

## Workspace layout

The workspace normally contains:

- top actions for save, run, build, and debug;
- a project file-tree drawer;
- an editor with multiple tabs;
- default bottom tabs for Diagnostics and Build Log; Outline, Symbols, Bookmarks, and Git are available on demand from the overflow menu or commands.

The terminal is a separate screen, not a permanent bottom-panel tab.

## Files and tabs

- Open files from the project tree.
- Switch between open files from the tab bar.
- Close the current tab or all tabs.
- Configure tab actions under **Settings → Keyboard** when using a hardware keyboard.

## Core editing actions

- Save or Save All
- Undo and redo
- Project search and replace
- Toggle and navigate bookmarks
- Format the current document
- Edit multiple files in tabs

## Language intelligence

When the language server is ready, the editor can provide completion, diagnostics, definition and reference navigation, symbols, outline, rename, and selected quick fixes.

If these features are missing, verify that:

1. the project has built successfully;
2. compile_commands.json exists and is current;
3. local clangd or Remote LSP is configured correctly.

## Bottom panel

The bottom panel shows **Diagnostics** and **Build Log** by default. **Outline**, **Symbols**, **Bookmarks**, and **Git** can be opened from the panel overflow menu or their commands; an opened secondary tab is temporarily added to the tab row.

- **Build Log** contains compiler and linker output. The workspace remains on this tab after a build failure.
- **Diagnostics** contains LSP reports. They can be empty or briefly older than the latest build, so use Build Log as the source of truth for build failures.
- **Outline** describes the current document, while **Symbols** searches project-wide symbols.
- Bottom-panel **Git** shows commit history. Drawer **Git** shows working-tree changes and staging controls.
- Runtime output goes to the separate **Terminal**, or to **SDL** for graphical programs. An empty Run Output tab is hidden while no component writes to it.

## Viewer types

JSON and Markdown open in the code editor by default. Large text, images, and binary files use the dedicated large-text, image, and Hex viewers.

## When to use the terminal

Use the separate terminal for custom CMake or Make commands, advanced Git operations, scripts, SSH, rsync, and remote-development tasks.

## Continue learning

- [Build a project](build-project.md)
- [Editor settings](editor-settings.md)
- [Code completion](code-completion.md)
- [Keyboard shortcuts](keyboard-shortcuts.md)
- [Git basics](git-basics.md)
