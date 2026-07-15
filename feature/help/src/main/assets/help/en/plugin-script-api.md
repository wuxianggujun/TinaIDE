# Script API and Least Privilege

This lesson builds a command that can be clicked, edits the active editor, and leaves useful logs. The goal is a verifiable least-privilege loop, not using every API at once.

## Choose the focused starter

Start with **Tina Script Command Plugin**. It already demonstrates command declarations, menu binding, editor access, and logging.

Lua runs in an isolated process with execution-time, memory, result-size, and dangerous-API limits. Do not depend on `io`, `debug`, `loadfile`, `dofile`, native `loadlib`, or Java reflection. Use restricted `require("module.name")` for modules inside the plugin directory.

## Minimal manifest

```json
{
  "id": "com.example.insert-header",
  "name": "Insert Header",
  "version": "0.1.0",
  "apiVersion": 1,
  "type": "script",
  "description": "Insert a comment header into the active editor.",
  "author": { "name": "Your Name" },
  "main": "main.lua",
  "contributions": {
    "commands": [
      {
        "id": "com.example.insert-header.run",
        "title": "Insert Header"
      }
    ],
    "menus": {
      "editor/context": [
        {
          "command": "com.example.insert-header.run",
          "group": "5_editor"
        }
      ]
    }
  },
  "permissions": [
    "editor.read",
    "editor.write",
    "command.execute"
  ]
}
```

`contributions.commands` supplies metadata only. Lua must register the callback. Both command registration and host-command execution require `command.execute`.

## Minimal main.lua

```lua
local command_id = "com.example.insert-header.run"

function insert_header()
  local editor = tina.editor.getActiveEditor()
  if editor == nil then
    tina.log.warn("No active editor")
    return
  end

  local file_name = editor.fileName or "unknown"
  local header = "// Generated for " .. file_name .. "\n"
  local ok = tina.editor.insertText(header, 0, 0)
  if ok then
    tina.log.info("Inserted header into " .. file_name)
  else
    tina.log.warn("insertText returned false")
  end
end

local ok, err = tina.commands.register(
  command_id,
  "insert_header",
  "Insert Header"
)

if not ok then
  tina.log.warn("Register failed: " .. tostring(err))
end
```

## Pick APIs by purpose

- `tina.ui` shows bounded user messages and requires `ui.notification`.
- `tina.log` writes plugin-scoped diagnostic logs.
- `tina.editor.getActiveEditor()` requires `editor.read`.
- `tina.editor.getSelection()` requires `editor.selection`.
- `insertText` and `replaceSelection` require `editor.write`.
- `tina.commands.register` and `execute` require `command.execute`.
- `tina.workspace` performs allowlisted project-root file operations.
- `tina.diagnostics` reads the current diagnostic snapshot.
- `tina.fs` is retained for compatibility; new plugins should prefer `tina.workspace`.
- `tina.network` is limited by permission, host allowlist, timeout, and response size.
- `tina.storage` and `tina.db` store bounded plugin-owned data.

Command IDs in the manifest, menu, and Lua registration must match exactly. Begin with read-only workspace access and request `workspace.write` only for a real write feature.

Network access should declare `network.fetch` and the required host allowlist. Never log tokens, passwords, or user source code.

## Optional permissions

Put enhancements in `optionalPermissions` and handle denial as a normal result:

```lua
local files, err = tina.workspace.findFiles("*.lua", 5)
if files == nil then
  tina.log.warn("Workspace scan unavailable: " .. tostring(err))
  return
end
```

`findFiles` sorts `/`-separated workspace-relative paths before applying the result limit. As long as the scan cap is not reached, the same tree produces the same first N items across creation orders and host file systems. If a large workspace reaches the 50,000-entry scan cap, the result represents only the scanned set and is not a complete index.

A permission denial is not a runtime crash and does not trigger quarantine.

## Verification loop

1. Run the plugin project to hot-install it.
2. Open Settings → Plugins, review it, and enable it.
3. Open a source file and invoke the editor command.
4. Confirm that the comment is inserted.
5. Review registration and execution in Plugin Logs.
6. Revoke an optional grant and verify that the basic command still works.
7. Trigger a recoverable error and verify that the host stays usable.

## Continue learning

- [Plugin Manifest and Version Compatibility](plugin-manifest-compatibility.md)
- [Plugin Panels and Events](plugin-panels-events.md)
- [Plugin Testing, Recovery, and Preflight](plugin-testing-recovery.md)
- [Plugin Settings](plugins-settings.md)
