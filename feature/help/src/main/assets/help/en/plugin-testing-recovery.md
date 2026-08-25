# Plugin Testing, Recovery, and Preflight

A plugin that installs is not necessarily stable. This lesson turns development into a repeatable acceptance loop and explains host isolation and recovery behavior.

## Layered verification

### Static preflight

Validate that `manifest.json` parses, identity and type fields are legal, compatibility fields are supported, required and optional permissions do not overlap, every referenced file is packaged, and the archive root directly contains `manifest.json`.

### Hot-install loop

1. Run the plugin project.
2. Confirm validation and packaging succeed.
3. Confirm hot-install completes.
4. Inspect version, type, and permissions in plugin details.
5. Explicitly enable the plugin.
6. Verify its contribution or runtime behavior.

A newly installed plugin is disabled by default. No immediate execution is expected.

### File-based install

Install the generated `.tinaplug` again through the system picker. This covers package size, entry count, expanded size, per-entry size, compression ratio, manifest identity, and permission-confirmation gates.

### Upgrade and rollback

Start with a healthy enabled old release and install a strictly newer release. A successful update atomically switches versions. If installation or activation fails, the healthy old release should be restored. Update preserves plugin data and enable intent; uninstall revokes grants and removes plugin-local data.

## Use Plugin Logs

Open Settings → Plugins → overflow → Plugin Logs. Log concise milestones such as load, command registration, callback failures, request status without secrets, and LSP startup, initialize, exit, and owner stop.

The log screen filters by level, plugin, and query and supports copy and export. Never log source content, keys, tokens, passwords, or complete network responses.

## Automatic quarantine

Attributable startup or callback failures, watchdog timeout, memory or result limits, isolated runtime crashes, invalid runtime contributions, and an LSP server crash after successful startup may quarantine a plugin.

The quarantined release no longer starts automatically, preventing a crash loop. Plugin details show a sanitized failure phase and time. Re-enable requires explicit user confirmation, and another attributable failure quarantines it again immediately.

Permission denial, ordinary network failure, and missing dependencies are recoverable business results and should not trigger quarantine.

## Recovery acceptance

Verify at least these cases:

1. A healthy plugin starts and executes commands.
2. A busy loop is stopped by the watchdog while the host stays usable.
3. Killing the runtime replaces its process generation.
4. A faulty plugin is quarantined while healthy plugins recover.
5. Re-enabling a still-faulty plugin quarantines it again.
6. Disable, uninstall, quarantine, and runtime failure clear commands, panels, events, and owners.
7. One failed LSP plugin does not stop another plugin's server.
8. Quarantine state remains consistent across force-stop and relaunch.

## Compatible-update acceptance

Test two host perspectives. An old IDE must see only the highest version compatible with its `apiVersion` and `minAppVersion`; a supported new IDE can see and install the newer release.

The downloaded package must still be checked for `id`, `version`, and compatibility. Registry filtering does not replace local validation.

## Final checklist

1. The real config, script, or LSP function is verified.
2. Missing optional grants degrade safely.
3. Logs contain no sensitive data.
4. Disable and uninstall leave no commands, panels, events, or LSP owners.
5. A failure does not take down the host Activity.
6. A failed update restores a healthy older release.
7. An old IDE does not offer an incompatible update.
8. Manifest, Registry metadata, and package identity agree.
9. Chinese and English names, descriptions, and tutorials match current behavior.

## Continue learning

- [Plugin Development Quick Start](plugin-quick-start.md)
- [Plugin Manifest and Version Compatibility](plugin-manifest-compatibility.md)
- [Script API and Least Privilege](plugin-script-api.md)
- [Plugin Panels and Events](plugin-panels-events.md)
- [LSP Plugin Development and Troubleshooting](plugin-lsp-troubleshooting.md)
- [Plugin Settings](plugins-settings.md)
