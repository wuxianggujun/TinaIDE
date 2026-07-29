#!/bin/bash
# Build SDL2 for Android with TinaIDE's relocated Java/JNI bridge.
# Usage: ./build-sdl2.sh <abi> <shared|static>

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/../build-common.sh"

SDL2_VERSION="2.32.10"
SDL2_TAG="release-${SDL2_VERSION}"
SDL2_COMMIT="5d249570393f7a37e037abf22cd6012a4cc56a71"
SDL2_REPO="libsdl-org/SDL"
SDL2_JAVA_PACKAGE_PATH="org/libsdl2/app"
ANDROID_API=28

ARCH=${1:-arm64-v8a}
LINK_TYPE=${2:-shared}

if [ "$LINK_TYPE" != "shared" ] && [ "$LINK_TYPE" != "static" ]; then
    log_error "Unsupported link type: ${LINK_TYPE} (expected shared or static)"
    exit 1
fi

log_info "Building SDL2 ${SDL2_VERSION}"
log_info "  Architecture: ${ARCH}"
log_info "  Link type: ${LINK_TYPE}"
log_info "  JNI package: ${SDL2_JAVA_PACKAGE_PATH}"

setup_toolchain "$ARCH"

SRC_DIR="/build/src/SDL-${SDL2_VERSION}"
git_checkout_exact "$SDL2_REPO" "$SRC_DIR" "$SDL2_TAG" "$SDL2_COMMIT"

# SDL2 hard-codes four Java class paths in SDL_android.c. The Java glue bundled
# in TinaIDE is relocated so SDL2 and SDL3 can coexist in one APK.
ANDROID_BRIDGE_SOURCE="${SRC_DIR}/src/core/android/SDL_android.c"
if grep -Fq 'org/libsdl/app/' "$ANDROID_BRIDGE_SOURCE"; then
    sed -i 's#org/libsdl/app/#org/libsdl2/app/#g' "$ANDROID_BRIDGE_SOURCE"
fi

if grep -Fq 'org/libsdl/app/' "$ANDROID_BRIDGE_SOURCE"; then
    log_error "SDL2 JNI source still references org/libsdl/app"
    exit 1
fi

RELOCATED_PATH_COUNT="$(grep -F -c "${SDL2_JAVA_PACKAGE_PATH}/" "$ANDROID_BRIDGE_SOURCE" || true)"
if [ "$RELOCATED_PATH_COUNT" -ne 4 ]; then
    log_error "Expected 4 relocated SDL2 JNI class paths, found ${RELOCATED_PATH_COUNT}"
    exit 1
fi

BUILD_DIR="/build/build/sdl2-${ARCH}-${LINK_TYPE}"
INSTALL_DIR="/build/install/sdl2-${ARCH}-${LINK_TYPE}"
rm -rf "$BUILD_DIR" "$INSTALL_DIR"
mkdir -p "$BUILD_DIR" "$INSTALL_DIR"

if [ "$LINK_TYPE" = "shared" ]; then
    SDL_SHARED=ON
    SDL_STATIC=OFF
else
    SDL_SHARED=OFF
    SDL_STATIC=ON
fi

log_info "Configuring SDL2..."
cmake -B "$BUILD_DIR" -S "$SRC_DIR" \
    -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="${CMAKE_TOOLCHAIN_FILE}" \
    -DCMAKE_INSTALL_PREFIX="$INSTALL_DIR" \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_SHARED_LINKER_FLAGS="-Wl,-z,max-page-size=16384" \
    -DANDROID_ABI="${CMAKE_ARCH}" \
    -DANDROID_PLATFORM="android-${ANDROID_API}" \
    -DANDROID_STL=c++_static \
    -DSDL_SHARED="$SDL_SHARED" \
    -DSDL_STATIC="$SDL_STATIC" \
    -DSDL_TEST=OFF \
    -DSDL_TESTS=OFF

log_info "Building SDL2..."
cmake --build "$BUILD_DIR" --parallel "$(nproc)"

log_info "Installing SDL2..."
cmake --install "$BUILD_DIR"

cp "$SRC_DIR/LICENSE.txt" "$INSTALL_DIR/LICENSE.txt"

if [ "$LINK_TYPE" = "shared" ]; then
    SDL_LIBRARY="$(find "$INSTALL_DIR/lib" -maxdepth 1 -name 'libSDL2.so*' -type f | sort | head -1)"
    if [ -z "$SDL_LIBRARY" ]; then
        log_error "Installed libSDL2 shared library was not found"
        exit 1
    fi

    # Keep a real canonical file; registry archives and Android extractors do
    # not need to preserve Unix symlinks for runtime discovery.
    cp -L "$SDL_LIBRARY" "$INSTALL_DIR/lib/libSDL2.so.canonical"
    mv "$INSTALL_DIR/lib/libSDL2.so.canonical" "$INSTALL_DIR/lib/libSDL2.so"
    "$STRIP" --strip-unneeded "$INSTALL_DIR/lib/libSDL2.so"

    for BRIDGE_CLASS in SDLActivity SDLInputConnection SDLAudioManager SDLControllerManager; do
        if ! grep -a -Fq "${SDL2_JAVA_PACKAGE_PATH}/${BRIDGE_CLASS}" "$INSTALL_DIR/lib/libSDL2.so"; then
            log_error "Built libSDL2.so is missing relocated JNI path: ${SDL2_JAVA_PACKAGE_PATH}/${BRIDGE_CLASS}"
            exit 1
        fi
    done
    if grep -a -Fq 'org/libsdl/app/SDLActivity' "$INSTALL_DIR/lib/libSDL2.so"; then
        log_error "Built libSDL2.so still contains the unrelocated SDL Android bridge"
        exit 1
    fi
    if ! "$READELF" -dW "$INSTALL_DIR/lib/libSDL2.so" | grep -Fq 'Library soname: [libSDL2.so]'; then
        log_error "Built SDL2 shared library has an unexpected SONAME"
        exit 1
    fi
    while read -r LOAD_ALIGNMENT; do
        if (( LOAD_ALIGNMENT < 0x4000 )); then
            log_error "Built libSDL2.so LOAD alignment is below 16 KB: ${LOAD_ALIGNMENT}"
            exit 1
        fi
    done < <("$READELF" -lW "$INSTALL_DIR/lib/libSDL2.so" | awk '$1 == "LOAD" { print $NF }')
fi

cat > "$INSTALL_DIR/package.json" <<EOF
{
  "id": "sdl2",
  "name": "SDL2",
  "version": "${SDL2_VERSION}",
  "description": "SDL2 Android runtime for TinaIDE's relocated Java bridge",
  "platform": "android",
  "installType": "download",
  "category": "library",
  "homepage": "https://www.libsdl.org/",
  "license": "Zlib",
  "installedAt": $(date +%s)000,
  "files": {
    "include": "include/SDL2",
    "lib": "lib",
    "pkgconfig": "lib/pkgconfig/sdl2.pc"
  },
  "abi": "${ARCH}",
  "dependencies": []
}
EOF

OUTPUT_DIR="/output/sdl2/${ARCH}"
OUTPUT_FILE="${OUTPUT_DIR}/sdl2-${ARCH}-${LINK_TYPE}.tar.xz"
mkdir -p "$OUTPUT_DIR"

cd "$INSTALL_DIR"
if [ "$LINK_TYPE" = "shared" ]; then
    tar -cf - include lib LICENSE.txt package.json | xz -9e --threads=0 > "$OUTPUT_FILE"
else
    tar -cf - include lib LICENSE.txt package.json | xz -9e --threads=0 > "$OUTPUT_FILE"
fi

log_success "SDL2 build complete"
log_info "Output: ${OUTPUT_FILE}"
ls -lh "$OUTPUT_FILE"
