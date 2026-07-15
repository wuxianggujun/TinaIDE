# LSP Plugin Development and Troubleshooting

An LSP plugin tells TinaIDE which files belong to a language and how its language server is prepared and started.

## Minimal structure

```text
my-lsp-plugin/
├── manifest.json
├── README.md
├── pack.ps1
└── pack.sh
```

An LSP plugin usually does not need a Lua `main`. Its core configuration is in `contributions.languageServers` and `contributions.toolchains`.

## Minimal manifest

```json
{
  "id": "tinaide.lsp.mylang",
  "name": "MyLang Language Support",
  "version": "0.1.0",
  "apiVersion": 1,
  "type": "lsp",
  "description": "Language support for MyLang.",
  "author": { "name": "Your Name" },
  "contributions": {
    "languageServers": [
      {
        "id": "mylang-server",
        "name": "MyLang Server",
        "languages": ["mylang"],
        "fileExtensions": ["mlg"],
        "server": {
          "type": "stdio",
          "command": "mylang-lsp"
        },
        "capabilities": {
          "completion": true,
          "hover": true,
          "definition": true,
          "references": true,
          "documentSymbol": true
        }
      }
    ],
    "toolchains": [
      {
        "id": "mylang-lsp",
        "name": "MyLang LSP",
        "type": "npm",
        "packages": ["mylang-lsp"],
        "required": true,
        "verifyCommand": "mylang-lsp --version",
        "verifyPattern": ".+"
      }
    ]
  },
  "activationEvents": ["onLanguage:mylang"]
}
```

## Five values that must align

1. `languages` must match TinaIDE's language ID.
2. `fileExtensions` omit the dot and cover real files.
3. `server.command` must be executable in the selected environment.
4. `verifyCommand` must succeed in that same environment.
5. The language in `activationEvents` must align with `languages`.

## Verification order

First inspect dependency readiness in plugin details. Install required tooling before repeatedly toggling the plugin.

Run the verification command in the target environment:

```text
mylang-lsp --version
```

If it is missing, fix toolchains and PATH before editing LSP protocol fields. Then open a real file whose extension matches the manifest and verify that the plugin owner starts the server.

At minimum verify server startup, an initialize response, one useful capability such as completion or diagnostics, and clean server shutdown after the last owner closes.

## Fault attribution

- Missing dependencies or commands are readiness problems, not plugin crashes.
- Exit before initialize usually means a command, argument, working-directory, environment, or server problem.
- A crash after successful initialize is an attributable LSP runtime failure. The host stops that owner, records the fault, and prevents a crash loop; the plugin may be quarantined.
- If a process remains after the file closes, inspect owner lifecycle and server shutdown/exit behavior.

One plugin's failed server must not stop another plugin's language server.

## Troubleshooting checklist

1. The package passes manifest preflight.
2. The plugin is enabled.
3. Required toolchains are ready.
4. `verifyCommand` succeeds for real.
5. File extension and language ID align.
6. Plugin Logs show initialize, exit code, or owner-stop details.
7. Reopening the file starts only the expected server.
8. The host Activity remains usable after a server crash.

## Continue learning

- [Plugin Manifest and Version Compatibility](plugin-manifest-compatibility.md)
- [Plugin Panels and Events](plugin-panels-events.md)
- [Plugin Testing, Recovery, and Preflight](plugin-testing-recovery.md)
- [LSP Overview](lsp-overview.md)
- [Plugin Settings](plugins-settings.md)
