#!/usr/bin/env python3
from __future__ import annotations

import json
import re
import sys
from collections import Counter
from pathlib import Path


PLUGIN_ID_PATTERN = re.compile(r"^[a-zA-Z0-9][a-zA-Z0-9._-]*$")


def load_json(path: Path) -> object:
    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle)


def as_text(value: object) -> str:
    return value.strip() if isinstance(value, str) else ""


def as_list(value: object) -> list[object]:
    if value is None:
        return []
    if isinstance(value, list):
        return value
    return [value]


def is_safe_relative_path(path_value: str) -> bool:
    if not path_value.strip():
        return False
    normalized = path_value.strip().replace("\\", "/")
    if normalized.startswith("/"):
        return False
    if "../" in normalized:
        return False
    return True


def normalize_extension(value: object) -> str | None:
    text = as_text(value).lstrip(".").lower()
    return text or None


def normalize_file_name(value: object) -> str | None:
    text = as_text(value).lower()
    return text or None


def find_duplicates(values: list[str]) -> list[str]:
    counts = Counter(value for value in values if value)
    return sorted(value for value, count in counts.items() if count > 1)


def contains_placeholder(value: str) -> bool:
    text = value.strip().lower()
    return ("{{" in text and "}}" in text) or "replace-me" in text


def main() -> int:
    plugin_root = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
    script_root = Path(__file__).resolve().parent
    rules = load_json(script_root / "validation-rules.json")

    supported_api_version = int(rules["supportedApiVersion"])
    known_host_commands = set(rules["knownHostCommands"])
    permission_aliases = dict(rules["permissionAliases"])
    editor_when_expressions = set(rules["supportedEditorWhenExpressions"])
    filetree_when_expressions = set(rules["supportedFileTreeWhenExpressions"])
    project_build_systems = {value.lower() for value in rules["supportedProjectBuildSystems"]}

    errors: list[str] = []
    warnings: list[str] = []

    def add_error(message: str) -> None:
        errors.append(message)

    def add_warning(message: str) -> None:
        warnings.append(message)

    manifest_path = plugin_root / "manifest.json"
    if not manifest_path.is_file():
        add_error("manifest.json is missing at the plugin root.")
        for message in errors:
            print(f"[ERROR] {message}")
        print("Validation failed with 1 error(s) and 0 warning(s).")
        return 1

    try:
        manifest = load_json(manifest_path)
    except json.JSONDecodeError as exc:
        add_error(f"manifest.json is not valid JSON: {exc.msg} (line {exc.lineno}, column {exc.colno}).")
        for message in errors:
            print(f"[ERROR] {message}")
        print("Validation failed with 1 error(s) and 0 warning(s).")
        return 1

    if not isinstance(manifest, dict):
        add_error("manifest.json must contain a JSON object at the root.")
        for message in errors:
            print(f"[ERROR] {message}")
        print("Validation failed with 1 error(s) and 0 warning(s).")
        return 1

    plugin_id = as_text(manifest.get("id"))
    plugin_name = as_text(manifest.get("name"))
    plugin_version = as_text(manifest.get("version"))
    plugin_type = as_text(manifest.get("type")).lower() or "config"
    contributions = manifest.get("contributions")
    contributions = contributions if isinstance(contributions, dict) else {}

    if not plugin_id:
        add_error("manifest.id is required.")
    elif contains_placeholder(plugin_id):
        add_warning(f"manifest.id still contains template placeholders: {plugin_id}")
    elif not PLUGIN_ID_PATTERN.fullmatch(plugin_id):
        add_error(f"manifest.id is invalid: {plugin_id}")
    elif ".." in plugin_id or "/" in plugin_id or "\\" in plugin_id:
        add_error(f"manifest.id must not contain path traversal or separators: {plugin_id}")

    if not plugin_name:
        add_error("manifest.name is required.")

    if not plugin_version:
        add_error("manifest.version is required.")

    api_version_value = manifest.get("apiVersion", supported_api_version)
    try:
        api_version = int(api_version_value)
    except (TypeError, ValueError):
        api_version = None
        add_error("manifest.apiVersion must be an integer.")
    if api_version is not None and api_version != supported_api_version:
        add_error(
            f"Unsupported manifest.apiVersion {api_version}. "
            f"Expected {supported_api_version}."
        )

    def canonical_permission(permission: object) -> str | None:
        return permission_aliases.get(as_text(permission))

    declared_permissions_raw = [as_text(item) for item in as_list(manifest.get("permissions"))]
    optional_permissions_raw = [as_text(item) for item in as_list(manifest.get("optionalPermissions"))]
    unknown_permissions = sorted(
        {
            permission
            for permission in declared_permissions_raw + optional_permissions_raw
            if permission and canonical_permission(permission) is None
        }
    )
    if unknown_permissions:
        add_error(
            "Unknown permission id(s): "
            + ", ".join(unknown_permissions)
        )

    declared_permissions = [value for value in (canonical_permission(item) for item in declared_permissions_raw) if value]
    optional_permissions = [value for value in (canonical_permission(item) for item in optional_permissions_raw) if value]

    duplicate_permissions = sorted(
        set(find_duplicates(declared_permissions))
        | set(find_duplicates(optional_permissions))
        | (set(declared_permissions) & set(optional_permissions))
    )
    if duplicate_permissions:
        add_warning(
            "Duplicate permission declarations detected: "
            + ", ".join(duplicate_permissions)
        )

    has_command_execute = "command.execute" in set(declared_permissions + optional_permissions)
    has_network_permission = bool(
        {"network.fetch", "network.unrestricted"} & set(declared_permissions + optional_permissions)
    )

    network_hosts = [as_text(item) for item in as_list(manifest.get("networkHosts"))]
    invalid_hosts = sorted(
        {
            host
            for host in network_hosts
            if host and ("://" in host or "/" in host)
        }
    )
    if invalid_hosts:
        add_error(
            "networkHosts must be hostnames without schemes or paths: "
            + ", ".join(invalid_hosts)
        )
    duplicate_hosts = find_duplicates([host.lower() for host in network_hosts if host])
    if duplicate_hosts:
        add_warning(
            "Duplicate networkHosts entries detected: "
            + ", ".join(duplicate_hosts)
        )
    if network_hosts and not has_network_permission:
        add_warning(
            "networkHosts is declared without network.fetch or network.unrestricted."
        )

    locales = manifest.get("locales")
    if locales is not None:
        if not isinstance(locales, dict):
            add_error("manifest.locales must be an object.")
        else:
            locale_files = locales.get("files")
            if not isinstance(locale_files, dict) or not locale_files:
                add_error("manifest.locales.files must be a non-empty object.")
            else:
                for locale_key, locale_path in locale_files.items():
                    locale_key_text = as_text(locale_key)
                    locale_path_text = as_text(locale_path)
                    if not locale_key_text:
                        add_error("manifest.locales.files contains an empty locale key.")
                    if not is_safe_relative_path(locale_path_text) or not locale_path_text.replace("\\", "/").startswith("locales/"):
                        add_error(f"manifest.locales.files['{locale_key_text}'] must point to locales/*.json: {locale_path_text or '<empty>'}")
                        continue
                    locale_file = plugin_root / locale_path_text
                    if not locale_file.is_file():
                        add_error(f"Locale file does not exist: {locale_path_text}")
                        continue
                    try:
                        locale_data = load_json(locale_file)
                    except json.JSONDecodeError as exc:
                        add_error(
                            f"Locale file is not valid JSON: {locale_path_text} "
                            f"({exc.msg}, line {exc.lineno}, column {exc.colno})."
                        )
                        continue
                    if not isinstance(locale_data, dict):
                        add_error(f"Locale file must contain a JSON object: {locale_path_text}")

    def require_safe_path(path_value: object, field_name: str, *, must_exist: bool) -> None:
        text = as_text(path_value)
        if not is_safe_relative_path(text):
            add_error(f"{field_name} must be a safe relative path: {text or '<empty>'}")
            return
        if must_exist and not (plugin_root / text).exists():
            add_error(f"{field_name} does not exist: {text}")

    if plugin_type in {"script", "hybrid"}:
        main_entry = as_text(manifest.get("main")) or "main.lua"
        require_safe_path(main_entry, "manifest.main", must_exist=True)

    for theme_path in as_list(contributions.get("themes")):
        require_safe_path(theme_path, "contributions.themes[]", must_exist=True)

    for snippet_path in as_list(contributions.get("snippets")):
        require_safe_path(snippet_path, "contributions.snippets[]", must_exist=True)

    for keybinding_path in as_list(contributions.get("keybindings")):
        require_safe_path(keybinding_path, "contributions.keybindings[]", must_exist=True)

    for index, template in enumerate(as_list(contributions.get("projectTemplates")), start=1):
        if not isinstance(template, dict):
            add_error(f"contributions.projectTemplates[{index}] must be an object.")
            continue
        template_id = as_text(template.get("id"))
        template_name = as_text(template.get("name"))
        template_path = as_text(template.get("templatePath"))
        build_system = as_text(template.get("buildSystem")).lower()
        if not template_id:
            add_error(f"contributions.projectTemplates[{index}].id is required.")
        if not template_name:
            add_error(f"contributions.projectTemplates[{index}].name is required.")
        require_safe_path(
            template_path,
            f"contributions.projectTemplates[{index}].templatePath",
            must_exist=True,
        )
        if build_system not in project_build_systems:
            add_error(
                f"contributions.projectTemplates[{index}].buildSystem is unsupported: "
                f"{build_system or '<empty>'}"
            )

    for index, apk_export in enumerate(as_list(contributions.get("apkExports")), start=1):
        if not isinstance(apk_export, dict):
            add_error(f"contributions.apkExports[{index}] must be an object.")
            continue
        export_id = as_text(apk_export.get("id"))
        export_name = as_text(apk_export.get("name"))
        template_path = as_text(apk_export.get("templatePath"))
        if not export_id:
            add_error(f"contributions.apkExports[{index}].id is required.")
        if not export_name:
            add_error(f"contributions.apkExports[{index}].name is required.")
        require_safe_path(
            template_path,
            f"contributions.apkExports[{index}].templatePath",
            must_exist=True,
        )

    supports_runtime_plugin_commands = plugin_type in {"script", "hybrid"}
    commands = as_list(contributions.get("commands"))
    declared_command_ids: list[str] = []
    for index, command in enumerate(commands, start=1):
        if not isinstance(command, dict):
            add_error(f"contributions.commands[{index}] must be an object.")
            continue
        command_id = as_text(command.get("id"))
        command_title = as_text(command.get("title"))
        if not command_id:
            add_error(f"contributions.commands[{index}].id is required.")
        else:
            declared_command_ids.append(command_id)
            if command_id not in known_host_commands and not supports_runtime_plugin_commands:
                add_warning(
                    f"Command '{command_id}' is not a supported host command for "
                    f"{plugin_type or 'config'} plugins."
                )
        if not command_title:
            add_error(f"contributions.commands[{index}].title is required.")

    duplicate_command_ids = find_duplicates(declared_command_ids)
    if duplicate_command_ids:
        add_error(
            "Duplicate command id(s) detected: "
            + ", ".join(duplicate_command_ids)
        )

    declared_command_id_set = set(declared_command_ids)
    declared_custom_command_ids = {
        command_id for command_id in declared_command_ids if command_id not in known_host_commands
    }
    custom_menu_command_ids: set[str] = set()

    def inspect_menu_items(location: str, items: object, supported_when: set[str]) -> None:
        for index, item in enumerate(as_list(items), start=1):
            if not isinstance(item, dict):
                add_error(f"contributions.menus['{location}'][{index}] must be an object.")
                continue
            command_id = as_text(item.get("command"))
            if not command_id:
                add_error(f"contributions.menus['{location}'][{index}].command is required.")
            elif command_id in known_host_commands:
                pass
            elif supports_runtime_plugin_commands:
                custom_menu_command_ids.add(command_id)
                if command_id not in declared_command_id_set:
                    add_warning(
                        f"Menu command '{command_id}' in {location} is not declared in "
                        "contributions.commands."
                    )
            else:
                add_error(
                    f"Menu command '{command_id}' in {location} is not a supported host command."
                )

            when_expr = as_text(item.get("when"))
            if when_expr and when_expr not in supported_when:
                add_warning(
                    f"Unsupported when expression '{when_expr}' in {location}."
                )

    menus = contributions.get("menus")
    menus = menus if isinstance(menus, dict) else {}
    inspect_menu_items("editor/context", menus.get("editor/context"), editor_when_expressions)
    inspect_menu_items("editor/toolbar", menus.get("editor/toolbar"), editor_when_expressions)
    inspect_menu_items("filetree/context", menus.get("filetree/context"), filetree_when_expressions)

    if supports_runtime_plugin_commands and not has_command_execute:
        custom_command_ids = sorted(declared_custom_command_ids | custom_menu_command_ids)
        if custom_command_ids:
            add_warning(
                "Custom commands are declared without command.execute permission: "
                + ", ".join(custom_command_ids)
            )

    panels = as_list(contributions.get("panels"))
    if panels and plugin_type not in {"script", "hybrid"}:
        add_error("contributions.panels is supported only for script or hybrid plugins.")
    if len(panels) > 16:
        add_error("A plugin may declare at most 16 panels.")
    panel_ids: list[str] = []
    for index, panel in enumerate(panels, start=1):
        if not isinstance(panel, dict):
            add_error(f"contributions.panels[{index}] must be an object.")
            continue
        panel_id = as_text(panel.get("id"))
        panel_title = as_text(panel.get("title"))
        if not panel_id or len(panel_id) > 128 or not PLUGIN_ID_PATTERN.fullmatch(panel_id):
            add_error(f"contributions.panels[{index}].id is invalid: {panel_id or '<empty>'}")
        else:
            panel_ids.append(panel_id)
        if not panel_title or len(panel_title) > 128:
            add_error(f"contributions.panels[{index}].title is required and must not exceed 128 characters.")
    duplicate_panel_ids = find_duplicates(panel_ids)
    if duplicate_panel_ids:
        add_error("Duplicate panel id(s) detected: " + ", ".join(duplicate_panel_ids))

    activation_events = [as_text(item) for item in as_list(manifest.get("activationEvents"))]
    if activation_events and plugin_type != "lsp":
        add_error("manifest.activationEvents is supported only for LSP plugins.")
    duplicate_activation_events = find_duplicates(activation_events)
    if duplicate_activation_events:
        add_error("Duplicate activation event(s) detected: " + ", ".join(duplicate_activation_events))
    contributed_languages = {
        as_text(language)
        for server in as_list(contributions.get("languageServers"))
        if isinstance(server, dict)
        for language in as_list(server.get("languages"))
        if as_text(language)
    }
    for event in activation_events:
        match = re.fullmatch(r"onLanguage:([a-zA-Z0-9][a-zA-Z0-9._+-]*)", event)
        if match is None:
            add_error(
                f"Unsupported activation event '{event}'. apiVersion 1 supports only onLanguage:<languageId>."
            )
        elif match.group(1) not in contributed_languages:
            add_error(
                f"Activation event language '{match.group(1)}' is not declared in contributions.languageServers."
            )

    for index, icon in enumerate(as_list(contributions.get("fileIcons")), start=1):
        if not isinstance(icon, dict):
            add_error(f"contributions.fileIcons[{index}] must be an object.")
            continue
        icon_spec = as_text(icon.get("icon"))
        if not icon_spec:
            add_warning(f"contributions.fileIcons[{index}].icon is empty.")
            continue

        extensions = [normalize_extension(value) for value in as_list(icon.get("extensions"))]
        file_names = [normalize_file_name(value) for value in as_list(icon.get("fileNames"))]
        has_matchers = any(value for value in extensions) or any(value for value in file_names)
        if not has_matchers:
            add_warning(
                f"contributions.fileIcons[{index}] should declare extensions or fileNames."
            )

        if icon_spec.lower().startswith("builtin:"):
            continue

        if not is_safe_relative_path(icon_spec):
            add_error(
                f"contributions.fileIcons[{index}].icon must be a safe relative path: {icon_spec}"
            )
            continue
        if not (plugin_root / icon_spec).is_file():
            add_error(
                f"contributions.fileIcons[{index}].icon does not exist: {icon_spec}"
            )

    for message in errors:
        print(f"[ERROR] {message}")
    for message in warnings:
        print(f"[WARN] {message}")

    if errors:
        print(
            f"Validation failed with {len(errors)} error(s) and "
            f"{len(warnings)} warning(s)."
        )
        return 1

    if warnings:
        print(f"Validation passed with {len(warnings)} warning(s).")
    else:
        print("Validation passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
