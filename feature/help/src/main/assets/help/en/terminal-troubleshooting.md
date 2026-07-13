# Terminal and Environment Troubleshooting

Diagnose terminal problems by separating backend, shell, guest tools, Locale, and display concerns.

## Backend

- Start with **AUTO** when unsure.
- Use **PROOT** for a complete Linux package and tool environment.
- Use **HOST** for lightweight Android-host commands.

If PROOT cannot start, verify that the optional Linux environment is installed before repeatedly switching modes.

## Shell

Use bash as a stable baseline. Select zsh only after its status is ready. If zsh prevents startup, switch back to bash and verify the environment.

## Guest Dev Packages

This state checks common developer commands such as compilers, make, git, curl, cmake, and pkg-config.

When a command is missing:

1. verify that the terminal is using PROOT;
2. refresh Linux Tools health;
3. install or reinstall Guest Dev Packages;
4. restart the terminal.

## Locale and text corruption

Changing the Locale preference does not guarantee that corresponding Locale data exists in the Linux rootfs.

For broken non-ASCII output:

1. check the selected Locale;
2. check Locale Support;
3. rebuild or install Locale data;
4. verify the terminal font.

All TinaIDE text and documentation assets use UTF-8. Keep scripts and files in UTF-8 as well.

## Font and display

Adjust font size for cramped output. Try the system monospace font or a validated custom font when symbols are missing. Disable cursor blinking or change its interval if needed.

## Terminal does not open

1. Check the backend value.
2. Confirm PRoot readiness when selected.
3. Review Linux Tools health.
4. Redeploy the optional development environment if terminal, LLDB, and guest tools fail together.

## Redeploy only when appropriate

Redeployment is suitable for a damaged PRoot environment or repeated Guest Dev Package failures. It is not the first fix for one project build flag or one missing source include.

Change one variable at a time: backend, then shell, then guest tools, then zsh/Locale/font.

## Related documentation

- [Terminal settings](terminal-settings.md)
- [Linux environment and storage](linux-storage.md)
- [Terminal usage](terminal-usage.md)
- [Build a project](build-project.md)
- [Known issues](known-issues.md)
