#!/usr/bin/env bash
# Build the exact SDL3 snapshot currently published by TinaIDE Registry.
# Usage: ./build-sdl3.sh <abi> <shared|static>

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/../build-common.sh"

SDL3_VERSION="3.5.0"
SDL3_REF="f600c74c80360eaaf8675da7d4ec69dc1670ac57"
SDL3_COMMIT="f600c74c80360eaaf8675da7d4ec69dc1670ac57"
ANDROID_API=28

ARCH=${1:-arm64-v8a}
LINK_TYPE=${2:-shared}

if [ "$LINK_TYPE" != "shared" ] && [ "$LINK_TYPE" != "static" ]; then
    log_error "Unsupported link type: ${LINK_TYPE}"
    exit 1
fi

setup_toolchain "$ARCH"

SRC_DIR="/build/src/SDL3-${SDL3_VERSION}"
git_checkout_exact "libsdl-org/SDL" "$SRC_DIR" "$SDL3_REF" "$SDL3_COMMIT"

BUILD_DIR="/build/build/sdl3-${ARCH}-${LINK_TYPE}"
INSTALL_DIR="/build/install/sdl3-${ARCH}-${LINK_TYPE}"
rm -rf "$BUILD_DIR" "$INSTALL_DIR"
mkdir -p "$BUILD_DIR" "$INSTALL_DIR"

if [ "$LINK_TYPE" = "shared" ]; then
    SDL_SHARED=ON
    SDL_STATIC=OFF
else
    SDL_SHARED=OFF
    SDL_STATIC=ON
fi

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
    -DSDL_TESTS=OFF \
    -DSDL_TEST_LIBRARY=OFF

cmake --build "$BUILD_DIR" --parallel "$(nproc)"
cmake --install "$BUILD_DIR"
cp "$SRC_DIR/LICENSE.txt" "$INSTALL_DIR/LICENSE.txt"

if [ "$LINK_TYPE" = "shared" ]; then
    SDL_LIBRARY="$(find "$INSTALL_DIR/lib" -maxdepth 1 -name 'libSDL3.so*' -type f | sort | head -1)"
    if [ -z "$SDL_LIBRARY" ]; then
        log_error "Installed libSDL3 shared library was not found"
        exit 1
    fi
    "$STRIP" --strip-unneeded "$SDL_LIBRARY"
    if ! grep -a -Fq 'org/libsdl/app/SDLActivity' "$SDL_LIBRARY"; then
        log_error "Built libSDL3 does not contain the expected Android JNI bridge"
        exit 1
    fi
    while read -r LOAD_ALIGNMENT; do
        if (( LOAD_ALIGNMENT < 0x4000 )); then
            log_error "Built libSDL3 LOAD alignment is below 16 KB: ${LOAD_ALIGNMENT}"
            exit 1
        fi
    done < <("$READELF" -lW "$SDL_LIBRARY" | awk '$1 == "LOAD" { print $NF }')
fi

cat > "$INSTALL_DIR/package.json" <<EOF
{
  "id": "sdl3",
  "name": "SDL3",
  "version": "${SDL3_VERSION}",
  "platform": "android",
  "artifactType": "${LINK_TYPE}",
  "installType": "download",
  "category": "library",
  "homepage": "https://www.libsdl.org/",
  "license": "Zlib",
  "abi": "${ARCH}",
  "dependencies": []
}
EOF

OUTPUT_DIR="/output/sdl3/${ARCH}"
OUTPUT_FILE="${OUTPUT_DIR}/sdl3-${ARCH}-${LINK_TYPE}.tar.xz"
mkdir -p "$OUTPUT_DIR"
tar -C "$INSTALL_DIR" -cf - include lib LICENSE.txt package.json | xz -9e --threads=0 > "$OUTPUT_FILE"

log_success "SDL3 ${SDL3_VERSION} build complete"
log_info "Output: ${OUTPUT_FILE}"
