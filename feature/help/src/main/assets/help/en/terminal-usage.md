# Terminal Usage

The TinaIDE terminal is a separate multi-session screen. It can start in a project directory and use different backends.

## Typical uses

- Run custom CMake or Make commands.
- Use advanced Git commands.
- Run scripts.
- Connect to remote hosts.
- Use SSH or rsync with Remote LSP.

## Sessions

You can create, switch, close, and restart sessions. Terminal state is associated with project paths so different projects can keep separate sessions.

## Backends

- **HOST** — Android host environment.
- **PROOT** — optional Linux environment for package managers and guest tools.

Use PROOT when a task requires the Linux guest environment. The default TinaIDE compilation chain remains the native toolchain with an Android sysroot.

## Shell and input

Bash is the safest baseline. Zsh is optional and should be selected after installation.

The extra-key row supplies Ctrl, Alt, Esc, and other command-line keys. A hardware keyboard provides reliable combinations such as Ctrl+C, Ctrl+L, and Tab.

## Examples

Build:

    cmake -S . -B build
    cmake --build build

Git:

    git status
    git add .
    git commit -m "update"

Remote work:

    ssh user@host
    rsync -av ./ user@host:/path/

## Troubleshooting

### Startup failure

Check environment deployment, selected backend, and whether the optional PRoot assets are ready.

### Text input is unreliable

Try a compatible input method or hardware keyboard. For long commands, compose text in the editor and paste it.

### A command works in one project only

Compare working directories, environment variables, build directories, and selected backends.

## Next steps

- [Terminal settings](terminal-settings.md)
- [Terminal troubleshooting](terminal-troubleshooting.md)
- [Build a project](build-project.md)
- [Git basics](git-basics.md)
- [Remote LSP](remote-lsp-guide.md)
