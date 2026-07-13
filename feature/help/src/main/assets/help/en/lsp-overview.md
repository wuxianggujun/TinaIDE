# LSP Overview

Language Server Protocol is the foundation for code intelligence in TinaIDE. For C and C++, clangd supplies most completion, diagnostics, navigation, and refactoring data.

## Capabilities

- Code completion
- Diagnostics
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
