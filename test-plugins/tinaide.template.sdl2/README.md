# SDL2 Project Template Plugin

This plugin adds an `SDL2 + CMake` template to TinaIDE's New Project flow.

The template targets SDL2's classic `main()` entry point and event loop (no
`SDL_MAIN_USE_CALLBACKS`), so it is detected as an SDL2 project and runs through
the `:sdl2` graphical runtime. APK export for SDL2 is not supported yet.

## Package

PowerShell:

```powershell
Compress-Archive -Path .\* -DestinationPath ..\tinaide.template.sdl2.tinaplug
```

## Install

TinaIDE -> Settings -> Plugins -> Install from file

Select `tinaide.template.sdl2.tinaplug`.
