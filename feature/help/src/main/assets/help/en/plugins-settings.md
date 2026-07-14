# Plugin Settings

**Settings → Plugins** manages installed plugins. Browse new plugins from the Marketplace on the home screen.

## Installed-plugin list

The list can show:

- plugin name and version;
- disabled and bundled status;
- theme-plugin status;
- LSP dependency ready or not ready.
- waiting for permission, automatically quarantined, or runtime unavailable.

The overflow menu provides:

- Manage plugins
- Install plugin from file
- Plugin logs

## Plugin details

Details include the enable switch, description, ID, version, type, minimum app version, author, repository, license, install directory, contributed themes or menus, required and optional permissions, and current grants.

Theme plugins expose a **Plugin theme** action. LSP plugins can expose dependency installation and readiness state.

New plugins are installed disabled and never execute merely because the list refreshes. Enabling a script plugin requires its permissions. A quarantined plugin shows a sanitized fault phase and time; re-enabling requires an explicit risk confirmation, and another attributable failure quarantines it again.

Optional permissions are never granted by the declaration alone. Grant or revoke each one in the **Optional permission grants** card. Future related API calls are denied immediately after revocation.

## Install from file

Select a .tinaplug package with the system picker. Imports over 64 MiB are stopped while copying. Script and hybrid plugins that request user-approved permissions display a confirmation before installation; low-risk required permissions are granted automatically in the same install transaction. Rejecting the request also removes the temporary import, and a failed installation restores the previous grants. A successful new installation remains disabled until you explicitly enable it.

Marketplace installation uses the same permission confirmation. Downloads over 64 MiB are stopped, and a package whose declared ID or version differs from the marketplace request is rejected before installation.

When an enabled script or hybrid plugin contributes panels, the editor shows a **Plugins** bottom tab. Panels render bounded plain text only and are cleared after disable, uninstall, quarantine, or runtime failure.

## Manage and uninstall

Manage mode supports multi-selection, select all, and batch uninstall. Bundled plugins cannot be uninstalled. A non-bundled plugin can also be removed from its detail page.

Uninstalling a regular plugin revokes its grants and removes its local key-value and database data. Updating a plugin preserves that data.

## Plugin logs

The log screen supports:

- level and plugin filters;
- text search;
- auto-scroll;
- entry details and copy;
- export;
- clearing one plugin or all plugin logs.

Check logs when a plugin is enabled but its contribution does not work.

Automatic quarantine applies to attributable startup/callback failures, execution limits, runtime crashes, invalid contributions, and an LSP server that crashes after a successful start. Permission denial, normal network failures, and missing Linux/toolchain dependencies do not quarantine a plugin.

## Troubleshooting

1. Confirm that the plugin is enabled.
2. Select its theme if it contributes one.
3. Install required LSP tooling for an LSP plugin.
4. Review plugin logs.
5. If the plugin is quarantined, review its fault details before choosing **Re-enable** or install a strictly newer version.

## Related documentation

- [Plugin development quick start](plugin-quick-start.md)
- [Settings overview](settings-overview.md)
- [Editor settings](editor-settings.md)
- [Code completion](code-completion.md)
- [Known issues](known-issues.md)
