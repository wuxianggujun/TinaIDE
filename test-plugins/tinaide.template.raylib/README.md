# raylib Project Template Plugin

This plugin adds a `raylib + CMake` template to TinaIDE's New Project flow.

The template builds a shared library named `libmain.so` and renders through
NativeActivity. raylib's Android backend exports `ANativeActivity_onCreate` and
references an undefined `main`, which the IDE's NativeActivity host resolves after
loading the library, so `src/main.c` only needs a plain `main`.

## Runtime requirements

- TinaIDE 0.18.36 or newer. Older raylib/SDL binary packages are outside the
  supported runtime contract; use the current Registry package and rebuild the project.
- Use the fixed raylib 6.0 package (`package.json` with `packageRevision: 2` or newer). Revision 1
  incorrectly enabled custom frame control, leaving `EndDrawing()` without buffer
  swapping and input polling. Updating only TinaIDE does not replace an already
  installed native library; reinstall raylib from the Registry's `6.0-r2` artifacts.
- Template 1.0.1 drains `GetKeyPressed()` before drawing and exits on Android Back
  or Escape, then calls `CloseWindow()`. raylib's Android backend consumes Back
  rather than making `WindowShouldClose()` return true. Using the key queue also
  handles a short press whose DOWN and UP events arrive in the same frame.
- Existing projects are not rewritten by a template update. Apply the same exit
  handling to their main loop if needed.

This directory is a local regression fixture. Updating it does not publish a
marketplace plugin; synchronize the versioned source and artifact in
`TinaIDE-Registry` separately.

Version 1.0.1 and raylib 6.0 revision 2 were synchronized in
[Registry commit fca514d](https://github.com/wuxianggujun/TinaIDE-Registry/commit/fca514d907b519a2adb3c703fec042b791e9ad02).

## Package

PowerShell:

```powershell
Compress-Archive -Path .\* -DestinationPath ..\tinaide.template.raylib.tinaplug
```

## Install

TinaIDE -> Settings -> Plugins -> Install from file

Select `tinaide.template.raylib.tinaplug`.
