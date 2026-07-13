# Code Completion

TinaIDE completion is built around clangd and LSP for C, C++, and CMake projects.

## Triggers

Completion can appear while typing identifiers, member access, scope resolution, function arguments, or include directives. Signature Help can follow function-call input.

## Related features

- Completion result limit
- Case sensitivity
- clangd completion style
- Function-argument placeholders
- Signature Help
- Inlay Hints
- Semantic Tokens

Configure them under **Settings → Language Server**.

## Improve accuracy

1. Build the project successfully at least once.
2. Keep compile_commands.json current.
3. Ensure include paths, definitions, and build arguments match the real build.

Completion may combine clangd semantic results with built-in or plugin-provided snippets.

## Local and Remote LSP

Local LSP analyzes the project on the Android device. Remote LSP moves analysis to a computer and can improve latency for large projects.

## Troubleshooting

### No completion

Check the first build, compile_commands.json, Language Server settings, and whether an unavailable Remote LSP configuration is active.

### Many inaccurate results

clangd is running, but the compilation database or include configuration is probably incomplete.

### Large projects are slow

Verify the project first, reduce the completion limit, then consider Remote LSP.

## Next steps

- [LSP settings](lsp-settings.md)
- [LSP overview](lsp-overview.md)
- [Remote LSP](remote-lsp-guide.md)
- [Build a project](build-project.md)
