# Plugin Development Quick Start

This tutorial creates a visible TinaIDE config plugin. Start with a theme and snippet before adding scripts, permissions, or an LSP contribution.

## Prerequisites

- Install and enable **TinaIDE Plugin Starters**.
- If plugin templates are missing, check **Settings → Plugins**.
- Use the **Tina Config Plugin** starter for this tutorial.

## 1. Create the project

Open this tutorial and use the **Create plugin project** quick action. Select Tina Config Plugin, enter a name, and create the project.

The **+** button on the Projects page opens the general project wizard. If you use that path, select a template marked as a plugin template.

## 2. Edit manifest.json

Set at least:

- id
- name
- version
- apiVersion (the current stable value is 1)
- minAppVersion (only when a newer host capability is truly required)
- type
- description
- author.name
- contributions.themes
- contributions.snippets

Example:

    {
      "id": "com.example.my-first-plugin",
      "name": "My First Plugin",
      "version": "0.1.0",
      "apiVersion": 1,
      "type": "config",
      "description": "My first TinaIDE plugin.",
      "author": {
        "name": "Your Name"
      },
      "contributions": {
        "themes": ["themes/my-theme.json"],
        "snippets": ["snippets/my-snippets.json"]
      }
    }

The id may contain letters, digits, periods, underscores, and hyphens. It cannot be a path or contain two consecutive path-traversal dots.

The example omits minAppVersion so a plugin with no newer host dependency remains compatible with older IDEs. Add or raise it only for a capability that really requires a newer TinaIDE; an older IDE will not offer an incompatible plugin update.

## 3. Add a theme

Create themes/my-theme.json:

    {
      "name": "My First Theme",
      "type": "dark",
      "colors": {
        "WHOLE_BACKGROUND": "#1E1E1E",
        "TEXT_NORMAL": "#D4D4D4",
        "KEYWORD": "#C586C0",
        "STRING": "#CE9178",
        "LINE_NUMBER": "#6B7280"
      }
    }

Tap **Run** to hot-install the plugin, open **Settings → Plugins**, enter the plugin details, and select the plugin theme.

## 4. Add a snippet

Create snippets/my-snippets.json with language cpp, a short prefix such as fori, and a snippet body. Open a C++ file, type the prefix, and select the snippet from completion.

If it does not appear, verify the language, prefix, contribution path, and JSON structure.

## 5. Run and hot-install

For a plugin project, Run:

1. validates the plugin directory;
2. creates a .tinaplug package;
3. hot-installs it into TinaIDE;
4. refreshes installed-plugin state.

Normally the IDE does not need to restart. A newly installed plugin remains disabled; open its details, review permissions, and explicitly enable it.

## 6. Package and verify

Use **Package** to generate:

    dist/<manifest.id>-<manifest.version>.tinaplug

Then open **Settings → Plugins → Install plugin from file** and install the package once more.

Errors block installation. Warnings can be confirmed. Script and hybrid plugins may require a separate permission confirmation.

Installation uses staging and an atomic swap. A failed upgrade of a healthy enabled plugin restores the previous version. Package size, entry count, expanded size, per-entry size, and compression ratio are bounded before activation.

## Troubleshooting

- No plugin template: install or enable TinaIDE Plugin Starters.
- Run does not hot-install: validate the root manifest.json and required fields.
- Resource missing: verify that contribution paths are relative and included in the package.
- Theme installed but unchanged: select the plugin theme in its details or Editor settings.

## Continue learning

Advanced script and hybrid plugins may declare optionalPermissions for on-demand grants. They may also declare contributions.panels and publish bounded plain text through tina.panels.setContent, appendContent, and clear.

- [Plugin settings](plugins-settings.md)
- [Plugin Manifest and Version Compatibility](plugin-manifest-compatibility.md)
- [Script API and Least Privilege](plugin-script-api.md)
- [Plugin Panels and Events](plugin-panels-events.md)
- [LSP Plugin Development and Troubleshooting](plugin-lsp-troubleshooting.md)
- [Plugin Testing, Recovery, and Preflight](plugin-testing-recovery.md)
- [Create a project](create-project.md)
- [Build a project](build-project.md)
- [Known issues](known-issues.md)
