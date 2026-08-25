#!/usr/bin/env bash
# Build Box2D for Android.
# Usage: ./build-box2d.sh <abi> <static>

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/../build-common.sh"

VERSION="3.1.1"
REF="v${VERSION}"
COMMIT="8c661469c9507d3ad6fbd2fea3f1aa71669c2fe3"
ANDROID_API=28
ARCH=${1:-arm64-v8a}
LINK_TYPE=${2:-static}

if [ "$LINK_TYPE" != "static" ]; then
    log_error "Box2D is published as a static library; requested ${LINK_TYPE}"
    exit 1
fi

setup_toolchain "$ARCH"
SRC_DIR="/build/src/box2d-${VERSION}"
git_checkout_exact "erincatto/box2d" "$SRC_DIR" "$REF" "$COMMIT"

BUILD_DIR="/build/build/box2d-${ARCH}"
INSTALL_DIR="/build/install/box2d-${ARCH}"
rm -rf "$BUILD_DIR" "$INSTALL_DIR"

cmake -B "$BUILD_DIR" -S "$SRC_DIR" \
    -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="${CMAKE_TOOLCHAIN_FILE}" \
    -DCMAKE_INSTALL_PREFIX="$INSTALL_DIR" \
    -DCMAKE_BUILD_TYPE=Release \
    -DANDROID_ABI="${CMAKE_ARCH}" \
    -DANDROID_PLATFORM="android-${ANDROID_API}" \
    -DANDROID_STL=c++_static \
    -DBUILD_SHARED_LIBS=OFF \
    -DBOX2D_SAMPLES=OFF \
    -DBOX2D_UNIT_TESTS=OFF \
    -DBOX2D_BENCHMARKS=OFF \
    -DBOX2D_DOCS=OFF

cmake --build "$BUILD_DIR" --parallel "$(nproc)"
cmake --install "$BUILD_DIR"

if [ ! -f "$INSTALL_DIR/lib/libbox2d.a" ]; then
    log_error "Installed libbox2d.a was not found"
    exit 1
fi
cp "$SRC_DIR/LICENSE" "$INSTALL_DIR/LICENSE.txt"
cat > "$INSTALL_DIR/package.json" <<EOF
{"id":"box2d","version":"${VERSION}","platform":"android","artifactType":"static","installType":"download","abi":"${ARCH}","dependencies":[]}
EOF

OUTPUT_DIR="/output/box2d/${ARCH}"
OUTPUT_FILE="${OUTPUT_DIR}/box2d-${ARCH}-static.tar.xz"
mkdir -p "$OUTPUT_DIR"
tar -C "$INSTALL_DIR" -cf - include lib LICENSE.txt package.json | xz -9e --threads=0 > "$OUTPUT_FILE"
log_success "Box2D ${VERSION} build complete"
