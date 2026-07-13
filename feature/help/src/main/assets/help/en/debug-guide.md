# Debugging Guide

TinaIDE debugging is based on LLDB. The current debug-session extension starts lldb inside the optional PRoot environment. Basic compilation, execution, and clangd do not require PRoot.

## Requirements

- The project builds successfully.
- The Linux/PRoot debugging environment is enabled.
- LLDB is installed and usable in that environment.

## Current capabilities

- Start and stop an LLDB session
- Continue and pause
- Step over, into, and out
- Refresh variables and call stacks while paused
- Show LLDB output in the debugging console
- Display and toggle breakpoints

## Recommended workflow

1. Build the project successfully.
2. Add a breakpoint near the suspected code.
3. Start debugging.
4. When execution stops, inspect variables and the call stack.
5. Continue or step to narrow the failure.

Debugging is most useful for unclear crashes, unexpected branches, incorrect state, and call-order problems.

## Troubleshooting

### The debugger does not start

Check environment deployment, LLDB availability in PRoot, and the normal build first.

### A breakpoint was hit

Inspect input arguments, key intermediate values, the current stack frame, and the next control-flow decision.

### Should every problem use the debugger?

No. Build, path, toolchain, and permission failures are usually easier to diagnose from Build Log and terminal output.

## Next steps

- [Build a project](build-project.md)
- [Editor basics](editor-basics.md)
- [Known issues](known-issues.md)
