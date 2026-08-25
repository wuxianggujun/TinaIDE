# Plugin Manifest and Version Compatibility

This lesson explains the four version fields that are commonly confused and why an older TinaIDE must not offer an incompatible plugin update.

## Four fields to keep separate

- `id` is the stable plugin identity and must not change during an upgrade.
- `version` is the plugin release version, such as `0.2.0`.
- `apiVersion` selects the plugin API contract. The current stable value is `1`; omission also means `1`.
- `minAppVersion` is the oldest TinaIDE version the plugin truly requires. Omit it when no newer host capability is needed.

```json
{
  "id": "com.example.safe-plugin",
  "name": "Safe Plugin",
  "version": "0.1.0",
  "apiVersion": 1,
  "minAppVersion": "1.4.0",
  "type": "config",
  "description": "A compatibility-aware TinaIDE plugin.",
  "author": {
    "name": "Your Name"
  }
}
```

Do not copy the sample `minAppVersion` blindly. Add or raise it only when the plugin uses a capability introduced by that host version.

## Compatible update selection

The Registry combines the current host version with each plugin manifest:

1. discard versions with an unsupported `apiVersion`;
2. discard versions whose `minAppVersion` is newer than the IDE;
3. choose the highest remaining plugin version;
4. validate the downloaded package manifest again before install, enable, and execution.

An older IDE can therefore keep using an older plugin release without being offered a newer incompatible update. After the IDE is upgraded, the Registry can expose the newer compatible release.

This does not require two packages for one release. Keep historical releases under the same plugin ID; each release has one manifest and one package, and the host selects the compatible release.

## When to raise minAppVersion

Raise it when the plugin uses a newly introduced Script API, contribution, permission, or lifecycle guarantee and cannot work correctly on an older host.

Do not raise it for documentation changes, internal bug fixes, or features that only use already-supported host APIs.

## Required and optional permissions

- `permissions` are required for normal operation.
- `optionalPermissions` enable enhancements that users grant individually in plugin details.

Do not put the same permission in both lists. The plugin should retain its basic behavior when an optional grant is absent.

```json
{
  "permissions": ["editor.read", "command.execute"],
  "optionalPermissions": ["workspace.write", "network.fetch"]
}
```

## Upgrade checklist

1. Keep `id` unchanged.
2. Make `version` strictly newer than the published release.
3. Keep `apiVersion` at the supported stable value `1`.
4. Raise `minAppVersion` only for a real host dependency.
5. Minimize required permissions.
6. Test graceful behavior without optional grants.
7. Match Registry metadata to the package `id`, `version`, and compatibility fields.
8. Verify that an old IDE cannot see the incompatible update and a supported IDE can install it.

## Common mistakes

If a new release is still visible on an old IDE, inspect both Registry compatibility metadata and the package manifest. If incompatibility is detected only after download, keep the install-time rejection and fix the Registry metadata.

Do not create a second plugin ID for older IDEs. That splits updates and user state. Retain compatible historical releases under the same stable ID.

## Continue learning

- [Plugin Development Quick Start](plugin-quick-start.md)
- [Script API and Least Privilege](plugin-script-api.md)
- [Plugin Testing, Recovery, and Preflight](plugin-testing-recovery.md)
- [Plugin Settings](plugins-settings.md)
