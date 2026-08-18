# SDL2 Android Java glue

These sources are vendored from SDL tag `release-2.32.10`, commit
`5d249570393f7a37e037abf22cd6012a4cc56a71`.

TinaIDE changes:

- Java package relocated from `org.libsdl.app` to `org.libsdl2.app` so SDL2 and
  SDL3 glue can coexist in one APK.
- USB permission action relocated to `org.libsdl2.app.USB_PERMISSION`.
- Version/error/back handling hooks added to `SDLActivity` for the TinaIDE host.
- SDL thread shutdown uses a bounded join to avoid blocking Android's main
  thread indefinitely.
- Missing HID JNI exports in legacy runtime packages disable optional HIDAPI
  initialization instead of terminating the SDL2 process.

The matching native `libSDL2.so` must be built by
`docker/tinaide-pkg/libs/build-sdl2.sh`; that build rewrites SDL's JNI class
paths and HID JNI export names to `org/libsdl2/app`.

See `LICENSE.txt` for the SDL zlib license.
