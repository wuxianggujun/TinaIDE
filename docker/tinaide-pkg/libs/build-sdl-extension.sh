#!/usr/bin/env bash
# Build SDL2/SDL3 companion libraries against TinaIDE's matching core package.
# Usage: ./build-sdl-extension.sh <package-id> <abi> <shared>

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/../build-common.sh"

PACKAGE_ID=${1:?Package id is required}
ARCH=${2:-arm64-v8a}
LINK_TYPE=${3:-shared}
ANDROID_API=28

if [ "$LINK_TYPE" != "shared" ]; then
    log_error "${PACKAGE_ID} is published as a shared library; requested ${LINK_TYPE}"
    exit 1
fi

SUBMODULES=()
CMAKE_OPTIONS=(-DBUILD_SHARED_LIBS=ON)

case "$PACKAGE_ID" in
    sdl2-image)
        VERSION="2.8.12"
        REPO="libsdl-org/SDL_image"
        REF="release-${VERSION}"
        COMMIT="12cb2e40330d256d9b1329647be8f366546d715c"
        CORE_ID="sdl2"
        CORE_CMAKE_PACKAGE="SDL2"
        CORE_DIR_VARIABLE="SDL2_DIR"
        LIBRARY_FILE="libSDL2_image.so"
        LICENSE_FILE="LICENSE.txt"
        CMAKE_OPTIONS+=(
            -DSDL2IMAGE_INSTALL=ON
            -DSDL2IMAGE_SAMPLES=OFF
            -DSDL2IMAGE_TESTS=OFF
            -DSDL2IMAGE_VENDORED=ON
            -DSDL2IMAGE_DEPS_SHARED=OFF
            -DSDL2IMAGE_BACKEND_STB=ON
            -DSDL2IMAGE_AVIF=OFF
            -DSDL2IMAGE_JXL=OFF
            -DSDL2IMAGE_TIF=OFF
            -DSDL2IMAGE_WEBP=OFF
        )
        ;;
    sdl2-ttf)
        VERSION="2.24.0"
        REPO="libsdl-org/SDL_ttf"
        REF="release-${VERSION}"
        COMMIT="2a891473eaf05ba1707a4b7913e6c4db7de7458a"
        CORE_ID="sdl2"
        CORE_CMAKE_PACKAGE="SDL2"
        CORE_DIR_VARIABLE="SDL2_DIR"
        LIBRARY_FILE="libSDL2_ttf.so"
        LICENSE_FILE="LICENSE.txt"
        SUBMODULES=(external/freetype)
        CMAKE_OPTIONS+=(
            -DSDL2TTF_INSTALL=ON
            -DSDL2TTF_SAMPLES=OFF
            -DSDL2TTF_VENDORED=ON
            -DSDL2TTF_HARFBUZZ=OFF
        )
        ;;
    sdl2-mixer)
        VERSION="2.8.2"
        REPO="libsdl-org/SDL_mixer"
        REF="release-${VERSION}"
        COMMIT="b208916aed9250fe434360e6c6a95f0697bb7b01"
        CORE_ID="sdl2"
        CORE_CMAKE_PACKAGE="SDL2"
        CORE_DIR_VARIABLE="SDL2_DIR"
        LIBRARY_FILE="libSDL2_mixer.so"
        LICENSE_FILE="LICENSE.txt"
        CMAKE_OPTIONS+=(
            -DSDL2MIXER_INSTALL=ON
            -DSDL2MIXER_SAMPLES=OFF
            -DSDL2MIXER_VENDORED=ON
            -DSDL2MIXER_DEPS_SHARED=OFF
            -DSDL2MIXER_FLAC=ON
            -DSDL2MIXER_FLAC_LIBFLAC=OFF
            -DSDL2MIXER_FLAC_DRFLAC=ON
            -DSDL2MIXER_GME=OFF
            -DSDL2MIXER_MOD=OFF
            -DSDL2MIXER_MP3=ON
            -DSDL2MIXER_MP3_MINIMP3=ON
            -DSDL2MIXER_MP3_MPG123=OFF
            -DSDL2MIXER_MIDI=OFF
            -DSDL2MIXER_OPUS=OFF
            -DSDL2MIXER_VORBIS=STB
            -DSDL2MIXER_WAVPACK=OFF
        )
        ;;
    sdl2-net)
        VERSION="2.4.0"
        REPO="libsdl-org/SDL_net"
        REF="release-${VERSION}"
        COMMIT="904600c6133e0435d627ec1878bfdfeac414a899"
        CORE_ID="sdl2"
        CORE_CMAKE_PACKAGE="SDL2"
        CORE_DIR_VARIABLE="SDL2_DIR"
        LIBRARY_FILE="libSDL2_net.so"
        LICENSE_FILE="LICENSE.txt"
        CMAKE_OPTIONS+=(
            -DSDL2NET_INSTALL=ON
            -DSDL2NET_SAMPLES=OFF
        )
        ;;
    sdl3-image)
        VERSION="3.4.4"
        REPO="libsdl-org/SDL_image"
        REF="release-${VERSION}"
        COMMIT="bec9134a26c7d0f31b36d6083c25296e04cabff5"
        CORE_ID="sdl3"
        CORE_CMAKE_PACKAGE="SDL3"
        CORE_DIR_VARIABLE="SDL3_DIR"
        LIBRARY_FILE="libSDL3_image.so"
        LICENSE_FILE="LICENSE.txt"
        CMAKE_OPTIONS+=(
            -DSDLIMAGE_INSTALL=ON
            -DSDLIMAGE_SAMPLES=OFF
            -DSDLIMAGE_TESTS=OFF
            -DSDLIMAGE_VENDORED=ON
            -DSDLIMAGE_DEPS_SHARED=OFF
            -DSDLIMAGE_BACKEND_STB=ON
            -DSDLIMAGE_AVIF=OFF
            -DSDLIMAGE_JXL=OFF
            -DSDLIMAGE_TIF=OFF
            -DSDLIMAGE_WEBP=OFF
            -DSDLIMAGE_PNG_LIBPNG=OFF
        )
        ;;
    sdl3-ttf)
        VERSION="3.2.2"
        REPO="libsdl-org/SDL_ttf"
        REF="release-${VERSION}"
        COMMIT="a1ce3670aec736ecbf0936c43f2f0cc53aa61e5b"
        CORE_ID="sdl3"
        CORE_CMAKE_PACKAGE="SDL3"
        CORE_DIR_VARIABLE="SDL3_DIR"
        LIBRARY_FILE="libSDL3_ttf.so"
        LICENSE_FILE="LICENSE.txt"
        SUBMODULES=(external/freetype)
        CMAKE_OPTIONS+=(
            -DSDLTTF_INSTALL=ON
            -DSDLTTF_SAMPLES=OFF
            -DSDLTTF_VENDORED=ON
            -DSDLTTF_HARFBUZZ=OFF
            -DSDLTTF_PLUTOSVG=OFF
        )
        ;;
    sdl3-mixer)
        VERSION="3.2.4"
        REPO="libsdl-org/SDL_mixer"
        REF="release-${VERSION}"
        COMMIT="72a81869b45e249e8e67102db4e98dd2441f05a1"
        CORE_ID="sdl3"
        CORE_CMAKE_PACKAGE="SDL3"
        CORE_DIR_VARIABLE="SDL3_DIR"
        LIBRARY_FILE="libSDL3_mixer.so"
        LICENSE_FILE="LICENSE.txt"
        CMAKE_OPTIONS+=(
            -DSDLMIXER_INSTALL=ON
            -DSDLMIXER_TESTS=OFF
            -DSDLMIXER_EXAMPLES=OFF
            -DSDLMIXER_VENDORED=ON
            -DSDLMIXER_DEPS_SHARED=OFF
            -DSDLMIXER_FLAC=ON
            -DSDLMIXER_FLAC_LIBFLAC=OFF
            -DSDLMIXER_FLAC_DRFLAC=ON
            -DSDLMIXER_GME=OFF
            -DSDLMIXER_MOD=OFF
            -DSDLMIXER_MP3=ON
            -DSDLMIXER_MP3_DRMP3=ON
            -DSDLMIXER_MP3_MPG123=OFF
            -DSDLMIXER_MIDI=OFF
            -DSDLMIXER_OPUS=OFF
            -DSDLMIXER_VORBIS_STB=ON
            -DSDLMIXER_VORBIS_VORBISFILE=OFF
            -DSDLMIXER_VORBIS_TREMOR=OFF
            -DSDLMIXER_WAVPACK=OFF
        )
        ;;
    sdl3-net)
        VERSION="3.2.0"
        REPO="libsdl-org/SDL_net"
        REF="release-${VERSION}"
        COMMIT="1a84a2a6b9663572f77e2eb5348d42845bac0053"
        CORE_ID="sdl3"
        CORE_CMAKE_PACKAGE="SDL3"
        CORE_DIR_VARIABLE="SDL3_DIR"
        LIBRARY_FILE="libSDL3_net.so"
        LICENSE_FILE="LICENSE.txt"
        CMAKE_OPTIONS+=(
            -DSDLNET_INSTALL=ON
            -DSDLNET_SAMPLES=OFF
        )
        ;;
    *)
        log_error "Unsupported SDL extension: ${PACKAGE_ID}"
        exit 1
        ;;
esac

setup_toolchain "$ARCH"

CORE_ARCHIVE="/output/${CORE_ID}/${ARCH}/${CORE_ID}-${ARCH}-shared.tar.xz"
if [ ! -f "$CORE_ARCHIVE" ]; then
    log_error "Missing ${CORE_ID} build for ${ARCH}: ${CORE_ARCHIVE}"
    exit 1
fi

CORE_PREFIX="/build/deps/${CORE_ID}-${ARCH}"
rm -rf "$CORE_PREFIX"
mkdir -p "$CORE_PREFIX"
tar -xf "$CORE_ARCHIVE" -C "$CORE_PREFIX"
CORE_CMAKE_DIR="${CORE_PREFIX}/lib/cmake/${CORE_CMAKE_PACKAGE}"
if [ ! -d "$CORE_CMAKE_DIR" ]; then
    log_error "Core package does not contain ${CORE_CMAKE_DIR}"
    exit 1
fi

SRC_DIR="/build/src/${PACKAGE_ID}-${VERSION}"
git_checkout_exact "$REPO" "$SRC_DIR" "$REF" "$COMMIT"
if [ "${#SUBMODULES[@]}" -gt 0 ]; then
    git_submodule_update_with_retry "$SRC_DIR" "${SUBMODULES[@]}"
fi

BUILD_DIR="/build/build/${PACKAGE_ID}-${ARCH}"
INSTALL_DIR="/build/install/${PACKAGE_ID}-${ARCH}"
rm -rf "$BUILD_DIR" "$INSTALL_DIR"
mkdir -p "$BUILD_DIR" "$INSTALL_DIR"

cmake -B "$BUILD_DIR" -S "$SRC_DIR" \
    -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="${CMAKE_TOOLCHAIN_FILE}" \
    -DCMAKE_INSTALL_PREFIX="$INSTALL_DIR" \
    -DCMAKE_PREFIX_PATH="$CORE_PREFIX" \
    -D"${CORE_DIR_VARIABLE}"="$CORE_CMAKE_DIR" \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_SHARED_LINKER_FLAGS="-Wl,-z,max-page-size=16384" \
    -DANDROID_ABI="${CMAKE_ARCH}" \
    -DANDROID_PLATFORM="android-${ANDROID_API}" \
    -DANDROID_STL=c++_static \
    "${CMAKE_OPTIONS[@]}"

cmake --build "$BUILD_DIR" --parallel "$(nproc)"
cmake --install "$BUILD_DIR"

LIBRARY_PATH="$(find "$INSTALL_DIR/lib" -maxdepth 1 -name "${LIBRARY_FILE}*" -type f | sort | head -1)"
if [ -z "$LIBRARY_PATH" ]; then
    log_error "Installed library not found: ${LIBRARY_FILE}"
    exit 1
fi
"$STRIP" --strip-unneeded "$LIBRARY_PATH"

if ! "$READELF" -dW "$LIBRARY_PATH" | grep -Fq "Shared library: [lib${CORE_CMAKE_PACKAGE}"; then
    log_error "${LIBRARY_FILE} is not dynamically linked to ${CORE_CMAKE_PACKAGE}"
    exit 1
fi
while read -r LOAD_ALIGNMENT; do
    if (( LOAD_ALIGNMENT < 0x4000 )); then
        log_error "${LIBRARY_FILE} LOAD alignment is below 16 KB: ${LOAD_ALIGNMENT}"
        exit 1
    fi
done < <("$READELF" -lW "$LIBRARY_PATH" | awk '$1 == "LOAD" { print $NF }')

if [ -f "$SRC_DIR/$LICENSE_FILE" ]; then
    cp "$SRC_DIR/$LICENSE_FILE" "$INSTALL_DIR/LICENSE.txt"
fi

cat > "$INSTALL_DIR/package.json" <<EOF
{
  "id": "${PACKAGE_ID}",
  "version": "${VERSION}",
  "platform": "android",
  "artifactType": "shared",
  "installType": "download",
  "abi": "${ARCH}",
  "dependencies": ["${CORE_ID}"]
}
EOF

OUTPUT_DIR="/output/${PACKAGE_ID}/${ARCH}"
OUTPUT_FILE="${OUTPUT_DIR}/${PACKAGE_ID}-${ARCH}-shared.tar.xz"
mkdir -p "$OUTPUT_DIR"
tar -C "$INSTALL_DIR" -cf - include lib LICENSE.txt package.json | xz -9e --threads=0 > "$OUTPUT_FILE"

log_success "${PACKAGE_ID} ${VERSION} build complete"
log_info "Output: ${OUTPUT_FILE}"
