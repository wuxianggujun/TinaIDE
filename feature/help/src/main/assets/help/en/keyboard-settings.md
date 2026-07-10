# Keyboard Settings

The current shortcut editor focuses on a small set of frequent hardware-keyboard actions.

## Configurable actions

- **Files** — Save, Save All
- **Tabs** — Close, Close All, Next, Previous
- **Editing** — Undo, Redo
- **Bookmarks** — Toggle, Next, Previous
- Restore every shortcut to its default

The default mappings are listed in [Keyboard shortcuts](keyboard-shortcuts.md).

## Record a shortcut

1. Select an action.
2. Wait for the key-capture dialog.
3. Press the new combination.
4. Confirm the change.

Ctrl, Shift, and Alt modifiers are supported. A modifier key without a main key is rejected.

## Conflict handling

Before saving, TinaIDE checks whether another action already uses the same combination. A conflict is reported instead of silently overwriting the existing mapping.

## Limits

- A hardware keyboard is strongly recommended.
- The system covers selected high-frequency actions only.
- It uses Android key events and does not implement complex desktop-style shortcut contexts.

## Related documentation

- [Keyboard shortcuts](keyboard-shortcuts.md)
- [Editor settings](editor-settings.md)
- [Editor basics](editor-basics.md)
