# Appearance Settings

The Appearance page currently controls the application theme and the position of the debugging toolbar.

## Application theme

Available built-in values include:

- **DARK** — always use the dark theme.
- **LIGHT** — always use the light theme.
- **GRAY** — use the gray theme.
- **AUTO** — follow the system theme.

Theme changes are saved before the night-mode state is applied. Switching to or from GRAY may recreate the screen so every visual state is refreshed.

For a conservative default, use **AUTO**.

## Debug toolbar position

Available positions are:

- **Top**
- **Bottom**
- **Both**

The default is **Bottom**. This setting is easiest to notice while a debugging workflow is active.

## Troubleshooting

### The page briefly restarted after a theme change

This is expected when the Activity must be recreated, especially when entering or leaving the GRAY theme.

### Recommended default

- Theme: **AUTO**
- Debug toolbar: **BOTTOM**

## Related documentation

- [Settings overview](settings-overview.md)
- [Editor settings](editor-settings.md)
- [Editor basics](editor-basics.md)
