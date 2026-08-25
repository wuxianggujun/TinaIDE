#!/usr/bin/env bash
# Build raylib for Android.
# Usage: ./build-raylib.sh <abi> <shared>

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/../build-common.sh"

VERSION="6.0"
REF="6.0"
COMMIT="dbc56a87da87d973a9c5baa4e7438a9d20121d28"
ANDROID_API=28
ARCH=${1:-arm64-v8a}
LINK_TYPE=${2:-shared}

if [ "$LINK_TYPE" != "shared" ]; then
    log_error "raylib is published as a shared library; requested ${LINK_TYPE}"
    exit 1
fi

setup_toolchain "$ARCH"
SRC_DIR="/build/src/raylib-${VERSION}"
git_checkout_exact "raysan5/raylib" "$SRC_DIR" "$REF" "$COMMIT"

BUILD_DIR="/build/build/raylib-${ARCH}"
INSTALL_DIR="/build/install/raylib-${ARCH}"
rm -rf "$BUILD_DIR" "$INSTALL_DIR"

cmake -B "$BUILD_DIR" -S "$SRC_DIR" \
    -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="${CMAKE_TOOLCHAIN_FILE}" \
    -DCMAKE_INSTALL_PREFIX="$INSTALL_DIR" \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_SHARED_LINKER_FLAGS="-Wl,-z,max-page-size=16384" \
    -DANDROID_ABI="${CMAKE_ARCH}" \
    -DANDROID_PLATFORM="android-${ANDROID_API}" \
    -DANDROID_STL=c++_static \
    -DBUILD_SHARED_LIBS=ON \
    -DBUILD_EXAMPLES=OFF \
    -DPLATFORM=Android \
    -DGRAPHICS=GRAPHICS_API_OPENGL_ES2 \
    -DCUSTOMIZE_BUILD=ON \
    -DUSE_AUDIO=ON

cmake --build "$BUILD_DIR" --parallel "$(nproc)"
cmake --install "$BUILD_DIR"

LIBRARY_PATH="$(find "$INSTALL_DIR/lib" -maxdepth 1 -name 'libraylib.so*' -type f | sort | head -1)"
if [ -z "$LIBRARY_PATH" ]; then
    log_error "Installed libraylib.so was not found"
    exit 1
fi
"$STRIP" --strip-unneeded "$LIBRARY_PATH"
if ! "$READELF" -Ws "$LIBRARY_PATH" | awk '
    $7 != "UND" && $8 == "ANativeActivity_onCreate" { found = 1 }
    END { exit found ? 0 : 1 }
'; then
    log_error "libraylib.so does not export ANativeActivity_onCreate"
    exit 1
fi
if ! "$READELF" -Ws "$LIBRARY_PATH" | awk '
    $7 == "UND" && $8 == "main" { found = 1 }
    END { exit found ? 0 : 1 }
'; then
    log_error "libraylib.so no longer exposes the expected undefined main contract"
    exit 1
fi
if "$READELF" -dW "$LIBRARY_PATH" | grep -Eq 'Shared library: \[libSDL[23]'; then
    log_error "libraylib.so unexpectedly depends on SDL; NativeActivity and SDL entry contracts must stay separate"
    exit 1
fi
while read -r LOAD_ALIGNMENT; do
    if (( LOAD_ALIGNMENT < 0x4000 )); then
        log_error "libraylib.so LOAD alignment is below 16 KB: ${LOAD_ALIGNMENT}"
        exit 1
    fi
done < <("$READELF" -lW "$LIBRARY_PATH" | awk '$1 == "LOAD" { print $NF }')

cp "$SRC_DIR/LICENSE" "$INSTALL_DIR/LICENSE.txt"
cat > "$INSTALL_DIR/package.json" <<EOF
{"id":"raylib","version":"${VERSION}","platform":"android","artifactType":"shared","installType":"download","abi":"${ARCH}","dependencies":[]}
EOF

OUTPUT_DIR="/output/raylib/${ARCH}"
OUTPUT_FILE="${OUTPUT_DIR}/raylib-${ARCH}-shared.tar.xz"
mkdir -p "$OUTPUT_DIR"
tar -C "$INSTALL_DIR" -cf - include lib LICENSE.txt package.json | xz -9e --threads=0 > "$OUTPUT_FILE"
log_success "raylib ${VERSION} build complete"
