# Debugging Guide

TinaIDE debugging uses LLDB. Native builds use the toolchain selected for that build, which must contain `bin/lldb` and `bin/lldb-server`. PRoot builds use LLDB in their selected Linux environment. Missing debugger components produce an error without switching build environments. Native debugger integration still requires device verification for breakpoints, stepping, and ptrace restrictions.

## Requirements

- The project builds successfully.
- The selected native toolchain includes LLDB components, or the selected PRoot debugging environment is ready.
- LLDB is usable on the device; the native path has not yet been verified on real hardware.

## Current capabilities

- Start and stop an LLDB session
- Continue and pause
- Step over, into, and out
- Refresh variables and call stacks while paused
- Show LLDB output in the debugging console
- Display and toggle breakpoints

A command timeout or unexpected stdout closure ends the session. Start debugging again to create a fresh session. Stop interrupts pending commands. Native pause sends SIGINT to the current target; pausing a running PRoot target remains limited by signal routing and is unverified.

## Recommended workflow

1. Build the project successfully.
2. Add a breakpoint near the suspected code.
3. Start debugging.
4. When execution stops, inspect variables and the call stack.
5. Continue or step to narrow the failure.

Debugging is most useful for unclear crashes, unexpected branches, incorrect state, and call-order problems.

## Troubleshooting

### The debugger does not start

Check the build-selected toolchain and its LLDB components, or the selected PRoot environment, and confirm the normal build succeeds first.

### A breakpoint was hit

Inspect input arguments, key intermediate values, the current stack frame, and the next control-flow decision.

### Should every problem use the debugger?

No. Build, path, toolchain, and permission failures are usually easier to diagnose from Build Log and terminal output.

## Next steps

- [Build a project](build-project.md)
- [Editor basics](editor-basics.md)
- [Known issues](known-issues.md)
