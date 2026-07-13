# Remote LSP Guide

Remote LSP moves language analysis from the Android device to a computer. It is most useful for large projects, heavy headers, slow local completion, or memory-constrained devices.

## Phone configuration

Open **Settings → Language Server** and configure:

- Remote LSP enabled state;
- computer host or IP address;
- port;
- synchronization mode;
- connection test.

## Typical setup

### 1. Prepare the computer

Run an accessible WebSocket/LSP proxy that can start clangd for the synchronized workspace. The TinaIDE repository does not bundle a PC proxy implementation, so use a compatible service maintained in your own environment.

### 2. Connect the device

Enable Remote LSP, enter the computer address and port, then run **Test connection**. Ensure the firewall allows the port.

### 3. Choose synchronization

- **AUTO** — let the app select a mode.
- **LIGHTWEIGHT** — reduce transfer for very large projects.
- **PROJECT** — provide full project context.

For frequent changes in a large project, consider rsync.

## Troubleshooting

1. Confirm that the host and port are correct.
2. Confirm that the proxy and clangd are running.
3. Check the firewall and network path.
4. Verify the remote workspace and compile_commands.json.
5. Confirm that the synchronization mode matches the project.

## Next steps

- [LSP settings](lsp-settings.md)
- [LSP overview](lsp-overview.md)
- [Code completion](code-completion.md)
- [rsync setup](rsync-setup.md)
