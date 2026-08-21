# LSP Overview

Language Server Protocol is the foundation for code intelligence in TinaIDE. For C and C++, clangd supplies most completion, diagnostics, navigation, and refactoring data.

## Capabilities

- Code completion
- Diagnostics
- Quick Fixes
- Conditional Fix All
- Go to definition and find references
- Rename
- Signature Help
- Inlay Hints
- Semantic Tokens
- Folding ranges

## Local LSP

Analysis runs on the Android device. It is offline and simple to configure, but large projects can consume significant CPU and memory.

## Remote LSP

Analysis runs on another computer while the device handles editing and presentation. This can improve indexing and completion for large projects, but requires networking and project synchronization.

## Why compile_commands.json matters

clangd needs the real include paths, definitions, compiler arguments, and target ownership for each source file. An incomplete compilation database can reduce completion accuracy, break header resolution, and produce misleading diagnostics.

## Inspect the current C/C++ compile context

Open a C or C++ file and tap the language-service status in the editor status bar to inspect the compile context currently used by clangd. The view includes:

- local or remote clangd mode;
- the path, source, and update time of `compile_commands.json`;
- whether the current file has an exact, inferred, or missing command;
- the compiler, language standard, target, toolchain, NDK runtime, and sysroot;
- header search paths, preprocessor definitions, and full compile arguments.

If the project builds but completion, header resolution, or diagnostics are still inaccurate, compare these values with the real build first. Use **Refresh and Reload** to prepare the compile context again and reconnect clangd when needed.

Remote LSP compile commands are managed by the remote language service. TinaIDE shows the connection and workspace state locally without presenting unverified remote arguments.

## Diagnostics and Quick Fixes

The Problems panel asks the language server whether a diagnostic actually has an enabled Quick Fix. **View fixes** appears only after the server returns at least one executable fix. Diagnostics with no automated fix, refactoring-only responses, and disabled fixes do not show the action.

The absence of **View fixes** does not make the diagnostic invalid. It means that the language server currently has no automated fix, so you can still open the diagnostic and edit the code manually. For a file that is not open or whose language server is still connecting, open the diagnostic first; TinaIDE checks again when the LSP context becomes ready.

The editor's general **Code Actions** entry remains separate and can still include refactorings and source actions. The diagnostic entry is intentionally limited to fixes for that problem.

When the language server explicitly provides an enabled `source.fixAll` action for the active file, the Problems panel shows **Fix all**. The entry stays hidden when unsupported. TinaIDE requests the latest action again when selected, and previews affected files and edit counts before applying a multi-file change.

## Settings

Open **Settings → Language Server** to configure clangd behavior, completion limits, Signature Help, Inlay Hints, Semantic Tokens, Remote LSP host and port, synchronization, and connection tests.

## Data flow

    Editor input
        ↓
    Language server analyzes the project
        ↓
    Completion, diagnostics, navigation, and signatures
        ↓
    Editor renders the result

## Next steps

- [LSP settings](lsp-settings.md)
- [Code completion](code-completion.md)
- [Remote LSP](remote-lsp-guide.md)
- [Known issues](known-issues.md)
