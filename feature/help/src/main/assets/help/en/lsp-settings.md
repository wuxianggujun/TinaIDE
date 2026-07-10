# LSP Settings

The Language Server page is divided into clangd configuration, editor-side LSP behavior, and Remote LSP settings.

## clangd configuration

### Run mode

- **native** — the default and faster device-side mode.
- **proot** — available only when the optional Linux environment is enabled.

### Background index

Improves cross-file completion and navigation at the cost of memory and CPU.

### Clang-Tidy

Adds static-analysis diagnostics. Leave it disabled while isolating basic completion problems.

### Header insertion

Choose smart IWYU insertion or disable automatic include suggestions.

### Completion detail and placeholders

Detailed completion shows more context. Function placeholders allow Tab-based argument entry. Changes may require reopening files or restarting LSP.

## Editor behavior

- Completion limit: 10 to 200
- Case-sensitive filtering
- Signature Help
- Inlay Hints
- Semantic Tokens
- Folding ranges

Semantic Tokens and folding-range integration may be marked experimental. Keep defaults when stability is more important than new presentation features.

## Remote LSP

Available fields include:

- enabled state;
- host and port;
- remote workspace root;
- synchronization mode and method;
- rsync module and port;
- connection state and test action.

### Host and port

Enter a host name or IP address without ws://, wss://, http://, or https://. localhost and 127.0.0.1 usually point to the Android device, not the development computer.

The default port is 6789. Valid ports are 1 through 65535.

### Remote workspace root

This can be empty. When needed, use a file URI, Windows absolute path, or slash-prefixed absolute path.

### Synchronization mode

- **AUTO** — recommended starting point.
- **LIGHTWEIGHT** — emphasizes currently opened files.
- **PROJECT** — supplies full project context.

AUTO can display the detected mode and reason.

### Synchronization method

This appears only in PROJECT mode, either selected directly or detected from AUTO.

- **BUILTIN** — low-configuration choice for small or medium projects.
- **RSYNC** — incremental transfer for larger projects.
- **MANUAL** — you manage synchronization.

For RSYNC, the default module is tina-workspace and the default port is 873. A module name cannot be empty or contain spaces or slashes.

### Test connection

Remote LSP must be enabled before testing. The status can be DISCONNECTED, CONNECTING, CONNECTED, or ERROR.

## Recommended starting points

### Small local project

- native clangd
- Remote LSP disabled

### Large local project

- verify compile_commands.json;
- consider background indexing;
- lower the completion result limit if needed.

### Large remote project

1. Enable Remote LSP.
2. Enter host and port.
3. Start with AUTO synchronization.
4. Test the connection.
5. Consider PROJECT + RSYNC for larger repositories.

## Related documentation

- [LSP overview](lsp-overview.md)
- [Remote LSP](remote-lsp-guide.md)
- [rsync setup](rsync-setup.md)
- [Code completion](code-completion.md)
