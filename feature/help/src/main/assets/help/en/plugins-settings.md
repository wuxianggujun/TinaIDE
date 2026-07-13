# Plugin Settings

**Settings → Plugins** manages installed plugins. Browse new plugins from the Marketplace on the home screen.

## Installed-plugin list

The list can show:

- plugin name and version;
- disabled and bundled status;
- theme-plugin status;
- LSP dependency ready or not ready.

The overflow menu provides:

- Manage plugins
- Install plugin from file
- Plugin logs

## Plugin details

Details include the enable switch, description, ID, version, type, minimum app version, author, repository, license, install directory, and a summary of contributed themes or menus.

Theme plugins expose a **Plugin theme** action. LSP plugins can expose dependency installation and readiness state.

## Install from file

Select a .tinaplug package with the system picker. Script and hybrid plugins that request user-approved permissions display a confirmation before installation. Rejecting the request also removes the temporary import.

## Manage and uninstall

Manage mode supports multi-selection, select all, and batch uninstall. Bundled plugins cannot be uninstalled. A non-bundled plugin can also be removed from its detail page.

## Plugin logs

The log screen supports:

- level and plugin filters;
- text search;
- auto-scroll;
- entry details and copy;
- export;
- clearing one plugin or all plugin logs.

Check logs when a plugin is enabled but its contribution does not work.

## Troubleshooting

1. Confirm that the plugin is enabled.
2. Select its theme if it contributes one.
3. Install required LSP tooling for an LSP plugin.
4. Review plugin logs.

## Related documentation

- [Plugin development quick start](plugin-quick-start.md)
- [Settings overview](settings-overview.md)
- [Editor settings](editor-settings.md)
- [Code completion](code-completion.md)
- [Known issues](known-issues.md)
