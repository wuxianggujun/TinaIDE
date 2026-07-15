# Plugin Panels and Events

This lesson advances a Script plugin from a one-shot command to a small event-driven tool. Panels carry bounded plain text only; they do not host HTML, WebView content, or arbitrary Compose UI.

## Declare a panel

```json
{
  "contributions": {
    "panels": [
      {
        "id": "status",
        "title": "Plugin Status"
      }
    ]
  }
}
```

After the plugin is enabled, the editor exposes a Plugins bottom tab. A plugin may declare at most 16 panels, and panel IDs must be unique within that plugin.

## Panel APIs

```lua
tina.panels.setContent("status", "Plugin loaded")
tina.panels.appendContent("status", "\nBuild started")
tina.panels.clear("status")
```

Use `setContent` to replace, `appendContent` to append, and `clear` to remove the current text. The host bounds panel output. Panels are for status and short summaries, not an unbounded build-log stream.

## Listen to host events

```lua
function on_project_opened(data)
  local root = data and data.rootPath or "unknown"
  tina.panels.setContent("status", "Project: " .. root)
  tina.log.info("Project opened: " .. root)
end

function on_diagnostics_changed(data)
  local errors = data and data.errorCount or 0
  local warnings = data and data.warningCount or 0
  local summary = "Errors=" .. errors .. ", warnings=" .. warnings
  tina.panels.setContent("status", summary)
end

tina.events.on("project.opened", "on_project_opened")
tina.events.on("diagnostics.changed", "on_diagnostics_changed")
```

Good starting events include project open/close, build start/finish, editor open/close, active editor, selection, dirty and saved changes, file create/delete/rename, and diagnostics changes.

Keep callbacks short. Do not scan the full workspace on every cursor or selection change. Maintain only the needed state and refresh a summary.

## Custom events

```lua
function on_refresh(data)
  local reason = data and data.reason or "manual"
  tina.panels.appendContent("status", "\nRefresh: " .. reason)
end

tina.events.on("custom", "on_refresh")
tina.events.emit("custom", { reason = "startup" })
```

Custom payloads are bounded by serialization depth, entry count, and result size. Do not put complete files into event payloads.

## Lifecycle and cleanup

Panel content and runtime ownership are cleared when the plugin is disabled, uninstalled, quarantined, when the isolated runtime exits, or when the host ends the owner lifecycle.

Do not assume panel text survives a restart. Store durable plugin-owned state in `tina.storage` or `tina.db`, then rebuild the panel summary at startup.

## Verification

1. Hot-install and enable the plugin.
2. Open the editor and confirm the Plugins tab and `status` panel appear.
3. Open a project, save a file, and trigger diagnostics; verify summary updates.
4. Disable the plugin and confirm panel content is cleared.
5. Re-enable it and confirm initial state is rebuilt.
6. Review Plugin Logs for repeated callback errors.

## Continue learning

- [Script API and Least Privilege](plugin-script-api.md)
- [LSP Plugin Development and Troubleshooting](plugin-lsp-troubleshooting.md)
- [Plugin Testing, Recovery, and Preflight](plugin-testing-recovery.md)
- [Plugin Settings](plugins-settings.md)
