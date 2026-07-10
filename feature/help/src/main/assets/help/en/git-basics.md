# Git Basics

Git features are distributed across the Projects page, editor Git panel, synchronization dialogs, Settings, and the terminal.

## Main workflow

1. Import or open a repository.
2. Edit files.
3. Review modified, untracked, and staged files in the Git panel.
4. Stage selected files or all files.
5. Enter a commit message and commit.
6. Fetch, pull, or push through remote synchronization.

## Authentication

### HTTPS

Configure host credentials and tokens under **Settings → Git → HTTPS**.

### SSH

Generate or import keys, choose a default key, and bind specific keys or ports to hosts under **Settings → Git → SSH**.

## When to use the terminal

Use the terminal for complex rebase or cherry-pick operations, submodules, advanced stash workflows, LFS, hooks, or an existing command-line workflow.

## Troubleshooting push or pull

1. Verify the remote URL.
2. Confirm that its protocol matches the configured HTTPS or SSH credential.
3. Check network access and remote permissions.
4. Use git status and git remote -v in the terminal for details.

## Continue learning

- [Git settings](git-settings.md)
- [Terminal usage](terminal-usage.md)
- [Build a project](build-project.md)
- [Known issues](known-issues.md)
