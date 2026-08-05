#!/bin/bash
# Package each SDL2 ABI into an independent registry artifact.
# Usage: ./package-sdl2.sh ["arm64-v8a x86_64"]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUTPUT_DIR="${SCRIPT_DIR}/output"
SDL2_VERSION="2.32.10"
SDL2_TAG="release-${SDL2_VERSION}"
SDL2_COMMIT="5d249570393f7a37e037abf22cd6012a4cc56a71"
ABIS_INPUT=${1:-"arm64-v8a x86_64"}
IFS=' ' read -r -a ABIS <<< "$ABIS_INPUT"

if [ "${#ABIS[@]}" -eq 0 ]; then
    echo "[ERROR] At least one ABI is required" >&2
    exit 1
fi

TEMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TEMP_DIR"' EXIT

PACKAGE_ROOT="${TEMP_DIR}/sdl2"
mkdir -p "$PACKAGE_ROOT/include" "$PACKAGE_ROOT/lib" "$PACKAGE_ROOT/pkgconfig"

for ABI in "${ABIS[@]}"; do
    TARBALL="${OUTPUT_DIR}/sdl2/${ABI}/sdl2-${ABI}-shared.tar.xz"
    EXTRACT_DIR="${TEMP_DIR}/extract-${ABI}"

    if [ ! -f "$TARBALL" ]; then
        echo "[ERROR] Missing SDL2 build: ${TARBALL}" >&2
        echo "[INFO] Build it with: ./build-pkg.ps1 -Library sdl2 -Arch ${ABI} -LinkType shared" >&2
        exit 1
    fi

    mkdir -p "$EXTRACT_DIR" "$PACKAGE_ROOT/lib/$ABI"
    tar -xf "$TARBALL" -C "$EXTRACT_DIR"

    if [ ! -d "$EXTRACT_DIR/include/SDL2" ]; then
        echo "[ERROR] ${TARBALL} does not contain include/SDL2" >&2
        exit 1
    fi

    SDL_LIBRARY="${EXTRACT_DIR}/lib/libSDL2.so"
    if [ ! -f "$SDL_LIBRARY" ]; then
        echo "[ERROR] ${TARBALL} does not contain lib/libSDL2.so" >&2
        exit 1
    fi

    for BRIDGE_CLASS in SDLActivity SDLInputConnection SDLAudioManager SDLControllerManager; do
        if ! grep -a -Fq "org/libsdl2/app/${BRIDGE_CLASS}" "$SDL_LIBRARY"; then
            echo "[ERROR] ${TARBALL} is missing TinaIDE SDL2 JNI path: org/libsdl2/app/${BRIDGE_CLASS}" >&2
            exit 1
        fi
    done
    if grep -a -Fq 'org/libsdl/app/SDLActivity' "$SDL_LIBRARY"; then
        echo "[ERROR] ${TARBALL} contains the unrelocated SDL Android bridge" >&2
        exit 1
    fi

    cp -L "$SDL_LIBRARY" "$PACKAGE_ROOT/lib/$ABI/libSDL2.so"

    if [ ! -d "$PACKAGE_ROOT/include/SDL2" ]; then
        cp -R "$EXTRACT_DIR/include/SDL2" "$PACKAGE_ROOT/include/SDL2"
        cp "$EXTRACT_DIR/LICENSE.txt" "$PACKAGE_ROOT/LICENSE.txt"
    fi
done

cat > "$PACKAGE_ROOT/pkgconfig/sdl2.pc" <<'EOF'
prefix=${pcfiledir}/..
includedir=${prefix}/include

Name: sdl2
Description: Simple DirectMedia Layer 2 for Android
Version: 2.32.10
Libs: -lSDL2
Cflags: -I${includedir}/SDL2
EOF

mkdir -p "$PACKAGE_ROOT/lib/cmake/SDL2"
cat > "$PACKAGE_ROOT/lib/cmake/SDL2/SDL2Config.cmake" <<'EOF'
set(SDL2_VERSION "2.32.10")
get_filename_component(_SDL2_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../.." ABSOLUTE)

if(NOT ANDROID_ABI)
    message(FATAL_ERROR "SDL2 Android package requires ANDROID_ABI")
endif()

set(_SDL2_LIBRARY "${_SDL2_ROOT}/lib/${ANDROID_ABI}/libSDL2.so")
if(NOT EXISTS "${_SDL2_LIBRARY}")
    message(FATAL_ERROR "SDL2 package does not contain libSDL2.so for ${ANDROID_ABI}")
endif()

if(NOT TARGET SDL2::SDL2)
    add_library(SDL2::SDL2 SHARED IMPORTED)
    set_target_properties(SDL2::SDL2 PROPERTIES
        IMPORTED_LOCATION "${_SDL2_LIBRARY}"
        INTERFACE_INCLUDE_DIRECTORIES "${_SDL2_ROOT}/include;${_SDL2_ROOT}/include/SDL2"
    )
endif()

set(SDL2_FOUND TRUE)
unset(_SDL2_LIBRARY)
unset(_SDL2_ROOT)
EOF

cat > "$PACKAGE_ROOT/lib/cmake/SDL2/SDL2ConfigVersion.cmake" <<'EOF'
set(PACKAGE_VERSION "2.32.10")

if(PACKAGE_FIND_VERSION VERSION_GREATER PACKAGE_VERSION)
    set(PACKAGE_VERSION_COMPATIBLE FALSE)
else()
    set(PACKAGE_VERSION_COMPATIBLE TRUE)
    if(PACKAGE_FIND_VERSION VERSION_EQUAL PACKAGE_VERSION)
        set(PACKAGE_VERSION_EXACT TRUE)
    endif()
endif()
EOF

write_package_metadata() {
    local package_root=$1
    local abi_json=$2
    local abi_list=$3

    cat > "$package_root/package.json" <<EOF
{
  "id": "sdl2",
  "name": "SDL2",
  "version": "${SDL2_VERSION}",
  "packageRevision": 1,
  "upstreamName": "SDL",
  "upstreamVersion": "${SDL2_VERSION}",
  "upstreamTag": "${SDL2_TAG}",
  "upstreamCommit": "${SDL2_COMMIT}",
  "description": "SDL2 Android runtime for TinaIDE's relocated Java bridge",
  "platform": "android",
  "artifactType": "shared",
  "installType": "download",
  "category": "library",
  "homepage": "https://www.libsdl.org/",
  "license": "Zlib",
  "files": {
    "include": "include/SDL2",
    "lib": "lib",
    "cmake": "lib/cmake/SDL2",
    "pkgconfig": "pkgconfig/sdl2.pc"
  },
  "abis": [${abi_json}],
  "dependencies": []
}
EOF

    cat > "$package_root/BUILD-INFO.txt" <<EOF
package_id=sdl2
package_version=${SDL2_VERSION}
package_revision=1
artifact_type=shared
upstream_tag=${SDL2_TAG}
upstream_commit=${SDL2_COMMIT}
upstream_version=${SDL2_VERSION}
abis=${abi_list}
dependencies=
android_jni_package=org/libsdl2/app
EOF
}

FINAL_OUTPUT_DIR="${OUTPUT_DIR}/registry/sdl2/${SDL2_VERSION}"
mkdir -p "$FINAL_OUTPUT_DIR"
ABI_JSON="$(printf '"%s",' "${ABIS[@]}" | sed 's/,$//')"
ABI_LIST="$(IFS=,; echo "${ABIS[*]}")"
write_package_metadata "$PACKAGE_ROOT" "$ABI_JSON" "$ABI_LIST"

LEGACY_OUTPUT="${FINAL_OUTPUT_DIR}/sdl2.tar.xz"
tar --sort=name --mtime='@0' --owner=0 --group=0 --numeric-owner \
    -C "$PACKAGE_ROOT" -cf - . | xz -9e --threads=0 > "$LEGACY_OUTPUT"
sha256sum "$LEGACY_OUTPUT" > "${LEGACY_OUTPUT}.sha256"
echo "[SUCCESS] Legacy universal SDL2 Registry artifact created: ${LEGACY_OUTPUT}"
cat "${LEGACY_OUTPUT}.sha256"

for ABI in "${ABIS[@]}"; do
    ABI_PACKAGE_ROOT="${TEMP_DIR}/package-${ABI}"
    mkdir -p "$ABI_PACKAGE_ROOT/lib"
    cp -R "$PACKAGE_ROOT/include" "$ABI_PACKAGE_ROOT/include"
    cp -R "$PACKAGE_ROOT/pkgconfig" "$ABI_PACKAGE_ROOT/pkgconfig"
    cp -R "$PACKAGE_ROOT/lib/cmake" "$ABI_PACKAGE_ROOT/lib/cmake"
    cp -R "$PACKAGE_ROOT/lib/$ABI" "$ABI_PACKAGE_ROOT/lib/$ABI"
    cp "$PACKAGE_ROOT/LICENSE.txt" "$ABI_PACKAGE_ROOT/LICENSE.txt"
    write_package_metadata "$ABI_PACKAGE_ROOT" "\"$ABI\"" "$ABI"

    FINAL_OUTPUT="${FINAL_OUTPUT_DIR}/sdl2-${ABI}.tar.xz"
    tar --sort=name --mtime='@0' --owner=0 --group=0 --numeric-owner \
        -C "$ABI_PACKAGE_ROOT" -cf - . | xz -9e --threads=0 > "$FINAL_OUTPUT"
    sha256sum "$FINAL_OUTPUT" > "${FINAL_OUTPUT}.sha256"

    echo "[SUCCESS] SDL2 registry artifact created: ${FINAL_OUTPUT}"
    cat "${FINAL_OUTPUT}.sha256"
done
