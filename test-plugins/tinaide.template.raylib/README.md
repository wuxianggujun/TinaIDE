# raylib Project Template Plugin

This plugin adds a `raylib + CMake` template to TinaIDE's New Project flow.

The template builds a shared library named `libmain.so` and renders through
NativeActivity. raylib's Android backend exports `ANativeActivity_onCreate` and
references an undefined `main`, which the IDE's NativeActivity host resolves after
loading the library, so `src/main.c` only needs a plain `main`.

## Package

PowerShell:

```powershell
Compress-Archive -Path .\* -DestinationPath ..\tinaide.template.raylib.tinaplug
```

## Install

TinaIDE -> Settings -> Plugins -> Install from file

Select `tinaide.template.raylib.tinaplug`.
