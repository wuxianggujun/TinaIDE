# Editor Basics

TinaIDE uses a mobile workspace rather than a traditional desktop menu layout.

## Workspace layout

The workspace normally contains:

- top actions for save, run, build, and debug;
- a project file-tree drawer;
- an editor with multiple tabs;
- bottom panels for build output, diagnostics, outline, symbols, bookmarks, and Git.

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

## Viewer types

JSON, large text, images, and binary files can open in specialized viewers instead of the normal code editor.

## When to use the terminal

Use the separate terminal for custom CMake or Make commands, advanced Git operations, scripts, SSH, rsync, and remote-development tasks.

## Continue learning

- [Build a project](build-project.md)
- [Editor settings](editor-settings.md)
- [Code completion](code-completion.md)
- [Keyboard shortcuts](keyboard-shortcuts.md)
- [Git basics](git-basics.md)
