# SDL3 Project Template Plugin

This plugin adds an `SDL3 + CMake` template to TinaIDE's New Project flow.

Version 1.0.4 creates the window with `SDL_WINDOW_RESIZABLE` instead of changing
the resize policy after creation. This avoids a landscape/portrait round trip
and partially black startup frames on Android. Updating the plugin does not
rewrite existing projects; apply the same flag change to their window creation
call and remove the later `SDL_SetWindowResizable` call when using this sample.

## Package

PowerShell:

```powershell
Compress-Archive -Path .\* -DestinationPath ..\tinaide.template.sdl3.tinaplug
```

## Install

TinaIDE -> Settings -> Plugins -> Install from file

Select `tinaide.template.sdl3.tinaplug`.
