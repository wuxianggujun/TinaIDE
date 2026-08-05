#!/usr/bin/env bash
# Package each native ABI into an independent TinaIDE Registry artifact.
# Usage: ./package-native-library.sh <package-id> ["arm64-v8a x86_64"]

set -euo pipefail

PACKAGE_ID=${1:?Package id is required}
ABIS_INPUT=${2:-"arm64-v8a x86_64"}
IFS=' ' read -r -a ABIS <<< "$ABIS_INPUT"

ARTIFACT_TYPE="shared"
PACKAGE_REVISION=1
DEPENDENCY_ID=""
DEPENDENCY_CMAKE_PACKAGE=""
DEPENDENCY_TARGET=""
TARGET_ALIAS=""
OFFICIAL_PC_FILE=""

case "$PACKAGE_ID" in
    sdl3)
        PACKAGE_REVISION=2
        VERSION="3.5.0"
        NAME="SDL3"
        DESCRIPTION="Simple DirectMedia Layer 3 Android runtime"
        HOMEPAGE="https://www.libsdl.org/"
        LICENSE="Zlib"
        UPSTREAM_NAME="SDL"
        UPSTREAM_TAG="f600c74c80360eaaf8675da7d4ec69dc1670ac57"
        UPSTREAM_COMMIT="f600c74c80360eaaf8675da7d4ec69dc1670ac57"
        LIBRARY_FILE="libSDL3.so"
        LINK_NAME="SDL3"
        CMAKE_PACKAGE="SDL3"
        CMAKE_TARGET="SDL3::SDL3"
        TARGET_ALIAS="SDL3::SDL3-shared"
        HEADER_SUBDIR="SDL3"
        ;;
    sdl2-image)
        VERSION="2.8.12"
        NAME="SDL2_image"
        DESCRIPTION="Image loading library for SDL2 with lightweight built-in backends"
        HOMEPAGE="https://github.com/libsdl-org/SDL_image"
        LICENSE="Zlib"
        UPSTREAM_NAME="SDL_image"
        UPSTREAM_TAG="release-${VERSION}"
        UPSTREAM_COMMIT="12cb2e40330d256d9b1329647be8f366546d715c"
        LIBRARY_FILE="libSDL2_image.so"
        LINK_NAME="SDL2_image"
        CMAKE_PACKAGE="SDL2_image"
        CMAKE_TARGET="SDL2_image::SDL2_image"
        TARGET_ALIAS="SDL2_image::SDL2_image-shared"
        HEADER_SUBDIR="SDL2"
        DEPENDENCY_ID="sdl2"
        DEPENDENCY_CMAKE_PACKAGE="SDL2"
        DEPENDENCY_TARGET="SDL2::SDL2"
        OFFICIAL_PC_FILE="SDL2_image.pc"
        ;;
    sdl2-ttf)
        VERSION="2.24.0"
        NAME="SDL2_ttf"
        DESCRIPTION="TrueType font rendering library for SDL2 using vendored FreeType"
        HOMEPAGE="https://github.com/libsdl-org/SDL_ttf"
        LICENSE="Zlib"
        UPSTREAM_NAME="SDL_ttf"
        UPSTREAM_TAG="release-${VERSION}"
        UPSTREAM_COMMIT="2a891473eaf05ba1707a4b7913e6c4db7de7458a"
        LIBRARY_FILE="libSDL2_ttf.so"
        LINK_NAME="SDL2_ttf"
        CMAKE_PACKAGE="SDL2_ttf"
        CMAKE_TARGET="SDL2_ttf::SDL2_ttf"
        TARGET_ALIAS="SDL2_ttf::SDL2_ttf-shared"
        HEADER_SUBDIR="SDL2"
        DEPENDENCY_ID="sdl2"
        DEPENDENCY_CMAKE_PACKAGE="SDL2"
        DEPENDENCY_TARGET="SDL2::SDL2"
        OFFICIAL_PC_FILE="SDL2_ttf.pc"
        ;;
    sdl2-mixer)
        VERSION="2.8.2"
        NAME="SDL2_mixer"
        DESCRIPTION="Audio mixer for SDL2 with built-in WAV, Ogg, MP3, and FLAC decoders"
        HOMEPAGE="https://github.com/libsdl-org/SDL_mixer"
        LICENSE="Zlib"
        UPSTREAM_NAME="SDL_mixer"
        UPSTREAM_TAG="release-${VERSION}"
        UPSTREAM_COMMIT="b208916aed9250fe434360e6c6a95f0697bb7b01"
        LIBRARY_FILE="libSDL2_mixer.so"
        LINK_NAME="SDL2_mixer"
        CMAKE_PACKAGE="SDL2_mixer"
        CMAKE_TARGET="SDL2_mixer::SDL2_mixer"
        TARGET_ALIAS="SDL2_mixer::SDL2_mixer-shared"
        HEADER_SUBDIR="SDL2"
        DEPENDENCY_ID="sdl2"
        DEPENDENCY_CMAKE_PACKAGE="SDL2"
        DEPENDENCY_TARGET="SDL2::SDL2"
        OFFICIAL_PC_FILE="SDL2_mixer.pc"
        ;;
    sdl2-net)
        VERSION="2.4.0"
        NAME="SDL2_net"
        DESCRIPTION="Portable TCP and UDP networking library for SDL2"
        HOMEPAGE="https://github.com/libsdl-org/SDL_net"
        LICENSE="Zlib"
        UPSTREAM_NAME="SDL_net"
        UPSTREAM_TAG="release-${VERSION}"
        UPSTREAM_COMMIT="904600c6133e0435d627ec1878bfdfeac414a899"
        LIBRARY_FILE="libSDL2_net.so"
        LINK_NAME="SDL2_net"
        CMAKE_PACKAGE="SDL2_net"
        CMAKE_TARGET="SDL2_net::SDL2_net"
        TARGET_ALIAS="SDL2_net::SDL2_net-shared"
        HEADER_SUBDIR="SDL2"
        DEPENDENCY_ID="sdl2"
        DEPENDENCY_CMAKE_PACKAGE="SDL2"
        DEPENDENCY_TARGET="SDL2::SDL2"
        OFFICIAL_PC_FILE="SDL2_net.pc"
        ;;
    sdl3-image)
        PACKAGE_REVISION=2
        VERSION="3.4.4"
        NAME="SDL3_image"
        DESCRIPTION="Image loading library for SDL3 with lightweight built-in backends"
        HOMEPAGE="https://github.com/libsdl-org/SDL_image"
        LICENSE="Zlib"
        UPSTREAM_NAME="SDL_image"
        UPSTREAM_TAG="release-${VERSION}"
        UPSTREAM_COMMIT="bec9134a26c7d0f31b36d6083c25296e04cabff5"
        LIBRARY_FILE="libSDL3_image.so"
        LINK_NAME="SDL3_image"
        CMAKE_PACKAGE="SDL3_image"
        CMAKE_TARGET="SDL3_image::SDL3_image"
        TARGET_ALIAS="SDL3_image::SDL3_image-shared"
        HEADER_SUBDIR="SDL3_image"
        DEPENDENCY_ID="sdl3"
        DEPENDENCY_CMAKE_PACKAGE="SDL3"
        DEPENDENCY_TARGET="SDL3::SDL3"
        ;;
    sdl3-ttf)
        PACKAGE_REVISION=2
        VERSION="3.2.2"
        NAME="SDL3_ttf"
        DESCRIPTION="TrueType font rendering library for SDL3 using vendored FreeType"
        HOMEPAGE="https://github.com/libsdl-org/SDL_ttf"
        LICENSE="Zlib"
        UPSTREAM_NAME="SDL_ttf"
        UPSTREAM_TAG="release-${VERSION}"
        UPSTREAM_COMMIT="a1ce3670aec736ecbf0936c43f2f0cc53aa61e5b"
        LIBRARY_FILE="libSDL3_ttf.so"
        LINK_NAME="SDL3_ttf"
        CMAKE_PACKAGE="SDL3_ttf"
        CMAKE_TARGET="SDL3_ttf::SDL3_ttf"
        TARGET_ALIAS="SDL3_ttf::SDL3_ttf-shared"
        HEADER_SUBDIR="SDL3_ttf"
        DEPENDENCY_ID="sdl3"
        DEPENDENCY_CMAKE_PACKAGE="SDL3"
        DEPENDENCY_TARGET="SDL3::SDL3"
        ;;
    sdl3-mixer)
        VERSION="3.2.4"
        NAME="SDL3_mixer"
        DESCRIPTION="Audio mixer for SDL3 with built-in WAV, Ogg, MP3, and FLAC decoders"
        HOMEPAGE="https://github.com/libsdl-org/SDL_mixer"
        LICENSE="Zlib"
        UPSTREAM_NAME="SDL_mixer"
        UPSTREAM_TAG="release-${VERSION}"
        UPSTREAM_COMMIT="72a81869b45e249e8e67102db4e98dd2441f05a1"
        LIBRARY_FILE="libSDL3_mixer.so"
        LINK_NAME="SDL3_mixer"
        CMAKE_PACKAGE="SDL3_mixer"
        CMAKE_TARGET="SDL3_mixer::SDL3_mixer"
        TARGET_ALIAS="SDL3_mixer::SDL3_mixer-shared"
        HEADER_SUBDIR="SDL3_mixer"
        DEPENDENCY_ID="sdl3"
        DEPENDENCY_CMAKE_PACKAGE="SDL3"
        DEPENDENCY_TARGET="SDL3::SDL3"
        ;;
    sdl3-net)
        VERSION="3.2.0"
        NAME="SDL3_net"
        DESCRIPTION="Portable networking library for SDL3"
        HOMEPAGE="https://github.com/libsdl-org/SDL_net"
        LICENSE="Zlib"
        UPSTREAM_NAME="SDL_net"
        UPSTREAM_TAG="release-${VERSION}"
        UPSTREAM_COMMIT="1a84a2a6b9663572f77e2eb5348d42845bac0053"
        LIBRARY_FILE="libSDL3_net.so"
        LINK_NAME="SDL3_net"
        CMAKE_PACKAGE="SDL3_net"
        CMAKE_TARGET="SDL3_net::SDL3_net"
        TARGET_ALIAS="SDL3_net::SDL3_net-shared"
        HEADER_SUBDIR="SDL3_net"
        DEPENDENCY_ID="sdl3"
        DEPENDENCY_CMAKE_PACKAGE="SDL3"
        DEPENDENCY_TARGET="SDL3::SDL3"
        ;;
    raylib)
        VERSION="6.0"
        NAME="raylib"
        DESCRIPTION="Simple and easy-to-use game programming library"
        HOMEPAGE="https://www.raylib.com/"
        LICENSE="Zlib"
        UPSTREAM_NAME="raylib"
        UPSTREAM_TAG="6.0"
        UPSTREAM_COMMIT="dbc56a87da87d973a9c5baa4e7438a9d20121d28"
        LIBRARY_FILE="libraylib.so"
        LINK_NAME="raylib"
        CMAKE_PACKAGE="raylib"
        CMAKE_TARGET="raylib"
        TARGET_ALIAS="raylib::raylib"
        HEADER_SUBDIR=""
        ;;
    box2d)
        PACKAGE_REVISION=2
        VERSION="3.1.1"
        NAME="Box2D"
        DESCRIPTION="2D physics engine for games"
        HOMEPAGE="https://box2d.org/"
        LICENSE="MIT"
        UPSTREAM_NAME="Box2D"
        UPSTREAM_TAG="v${VERSION}"
        UPSTREAM_COMMIT="8c661469c9507d3ad6fbd2fea3f1aa71669c2fe3"
        LIBRARY_FILE="libbox2d.a"
        LINK_NAME="box2d"
        CMAKE_PACKAGE="box2d"
        CMAKE_TARGET="box2d::box2d"
        TARGET_ALIAS="box2d"
        HEADER_SUBDIR="box2d"
        ARTIFACT_TYPE="static"
        ;;
    *)
        echo "[ERROR] Unsupported package: ${PACKAGE_ID}" >&2
        exit 1
        ;;
esac

if [ "${#ABIS[@]}" -eq 0 ]; then
    echo "[ERROR] At least one ABI is required" >&2
    exit 1
fi

TEMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TEMP_DIR"' EXIT
PACKAGE_ROOT="${TEMP_DIR}/${PACKAGE_ID}"
mkdir -p "$PACKAGE_ROOT/include" "$PACKAGE_ROOT/lib" "$PACKAGE_ROOT/pkgconfig"

verify_raylib_runtime_contract() {
    local library_path=$1

    if ! readelf -dW "$library_path" | grep -Fq 'Library soname: [libraylib.so]'; then
        echo "[ERROR] libraylib.so has an unexpected or missing SONAME" >&2
        return 1
    fi
    if ! readelf -Ws "$library_path" | awk '
        $7 != "UND" && $8 == "ANativeActivity_onCreate" { found = 1 }
        END { exit found ? 0 : 1 }
    '; then
        echo "[ERROR] libraylib.so does not export ANativeActivity_onCreate" >&2
        return 1
    fi
    if ! readelf -Ws "$library_path" | awk '
        $7 == "UND" && $8 == "main" { found = 1 }
        END { exit found ? 0 : 1 }
    '; then
        echo "[ERROR] libraylib.so does not retain the NativeActivity undefined main contract" >&2
        return 1
    fi
    if readelf -dW "$library_path" | grep -Eq 'Shared library: \[libSDL[23]'; then
        echo "[ERROR] libraylib.so must not depend on SDL" >&2
        return 1
    fi
}

for ABI in "${ABIS[@]}"; do
    BUILD_ARCHIVE="/output/${PACKAGE_ID}/${ABI}/${PACKAGE_ID}-${ABI}-${ARTIFACT_TYPE}.tar.xz"
    EXTRACT_DIR="${TEMP_DIR}/extract-${ABI}"
    if [ ! -f "$BUILD_ARCHIVE" ]; then
        echo "[ERROR] Missing build: ${BUILD_ARCHIVE}" >&2
        exit 1
    fi

    mkdir -p "$EXTRACT_DIR" "$PACKAGE_ROOT/lib/$ABI"
    tar -xf "$BUILD_ARCHIVE" -C "$EXTRACT_DIR"

    SOURCE_LIBRARY="$(find "$EXTRACT_DIR/lib" -maxdepth 1 -name "${LIBRARY_FILE}*" \( -type f -o -type l \) | sort | head -1)"
    if [ -z "$SOURCE_LIBRARY" ]; then
        echo "[ERROR] ${BUILD_ARCHIVE} does not provide ${LIBRARY_FILE}" >&2
        exit 1
    fi
    cp -L "$SOURCE_LIBRARY" "$PACKAGE_ROOT/lib/$ABI/$LIBRARY_FILE"

    case "$ABI" in
        arm64-v8a) EXPECTED_MACHINE="AArch64" ;;
        x86_64) EXPECTED_MACHINE="Advanced Micro Devices X86-64" ;;
        *) EXPECTED_MACHINE="" ;;
    esac
    if [ "$ARTIFACT_TYPE" = "shared" ]; then
        if [ -n "$EXPECTED_MACHINE" ] && ! readelf -hW "$PACKAGE_ROOT/lib/$ABI/$LIBRARY_FILE" | grep -Fq "$EXPECTED_MACHINE"; then
            echo "[ERROR] ${LIBRARY_FILE} has the wrong architecture for ${ABI}" >&2
            exit 1
        fi
        while read -r LOAD_ALIGNMENT; do
            if (( LOAD_ALIGNMENT < 0x4000 )); then
                echo "[ERROR] ${LIBRARY_FILE} LOAD alignment is below 16 KB: ${LOAD_ALIGNMENT}" >&2
                exit 1
            fi
        done < <(readelf -lW "$PACKAGE_ROOT/lib/$ABI/$LIBRARY_FILE" | awk '$1 == "LOAD" { print $NF }')
        if [ "$PACKAGE_ID" = "raylib" ]; then
            verify_raylib_runtime_contract "$PACKAGE_ROOT/lib/$ABI/$LIBRARY_FILE"
        fi
        if [ -n "$DEPENDENCY_CMAKE_PACKAGE" ] && ! readelf -dW "$PACKAGE_ROOT/lib/$ABI/$LIBRARY_FILE" | grep -Fq "Shared library: [lib${DEPENDENCY_CMAKE_PACKAGE}"; then
            echo "[ERROR] ${LIBRARY_FILE} is not linked to ${DEPENDENCY_CMAKE_PACKAGE}" >&2
            exit 1
        fi
    elif [ -n "$EXPECTED_MACHINE" ]; then
        ARCHIVE_MACHINES="$(
            readelf -hW "$PACKAGE_ROOT/lib/$ABI/$LIBRARY_FILE" |
                sed -n 's/^[[:space:]]*Machine:[[:space:]]*//p' |
                sort -u
        )"
        if [ "$ARCHIVE_MACHINES" != "$EXPECTED_MACHINE" ]; then
            echo "[ERROR] ${LIBRARY_FILE} has unexpected object architectures for ${ABI}: ${ARCHIVE_MACHINES:-none}" >&2
            exit 1
        fi
    fi

    if [ -z "$(find "$PACKAGE_ROOT/include" -mindepth 1 -print -quit)" ] && [ -d "$EXTRACT_DIR/include" ]; then
        cp -R "$EXTRACT_DIR/include/." "$PACKAGE_ROOT/include/"
    fi
    if [ ! -f "$PACKAGE_ROOT/LICENSE.txt" ]; then
        LICENSE_SOURCE="$(find "$EXTRACT_DIR" -maxdepth 2 -iname 'LICENSE*' -type f | sort | head -1)"
        if [ -n "$LICENSE_SOURCE" ]; then
            cp "$LICENSE_SOURCE" "$PACKAGE_ROOT/LICENSE.txt"
        fi
    fi
done

if [ ! -f "$PACKAGE_ROOT/LICENSE.txt" ]; then
    echo "[ERROR] Package license was not found for ${PACKAGE_ID}" >&2
    exit 1
fi

REQUIRES_LINE=""
DEPENDENCIES_JSON=""
if [ -n "$DEPENDENCY_ID" ]; then
    REQUIRES_LINE="Requires: ${DEPENDENCY_ID}"
    DEPENDENCIES_JSON="\"${DEPENDENCY_ID}\""
fi

cat > "$PACKAGE_ROOT/pkgconfig/${PACKAGE_ID}.pc" <<EOF
prefix=\${pcfiledir}/..
includedir=\${prefix}/include

Name: ${NAME}
Description: ${DESCRIPTION}
Version: ${VERSION}
${REQUIRES_LINE}
Libs: -l${LINK_NAME}
Cflags: -I\${includedir}
EOF
if [ -n "$OFFICIAL_PC_FILE" ]; then
    cp "$PACKAGE_ROOT/pkgconfig/${PACKAGE_ID}.pc" "$PACKAGE_ROOT/pkgconfig/${OFFICIAL_PC_FILE}"
fi

CMAKE_DIR="$PACKAGE_ROOT/lib/cmake/${CMAKE_PACKAGE}"
mkdir -p "$CMAKE_DIR"
FIND_DEPENDENCY_BLOCK=""
INTERFACE_DEPENDENCY_BLOCK=""
if [ -n "$DEPENDENCY_CMAKE_PACKAGE" ]; then
    FIND_DEPENDENCY_BLOCK="include(CMakeFindDependencyMacro)
find_dependency(${DEPENDENCY_CMAKE_PACKAGE} CONFIG)"
    INTERFACE_DEPENDENCY_BLOCK="INTERFACE_LINK_LIBRARIES \"${DEPENDENCY_TARGET}\""
fi
HEADER_INCLUDE=""
if [ -n "$HEADER_SUBDIR" ]; then
    HEADER_INCLUDE=";\${_${CMAKE_PACKAGE}_ROOT}/include/${HEADER_SUBDIR}"
fi

cat > "$CMAKE_DIR/${CMAKE_PACKAGE}Config.cmake" <<EOF
${FIND_DEPENDENCY_BLOCK}
get_filename_component(_${CMAKE_PACKAGE}_ROOT "\${CMAKE_CURRENT_LIST_DIR}/../../.." ABSOLUTE)

if(NOT ANDROID_ABI)
    message(FATAL_ERROR "${NAME} Android package requires ANDROID_ABI")
endif()

set(_${CMAKE_PACKAGE}_LIBRARY "\${_${CMAKE_PACKAGE}_ROOT}/lib/\${ANDROID_ABI}/${LIBRARY_FILE}")
if(NOT EXISTS "\${_${CMAKE_PACKAGE}_LIBRARY}")
    message(FATAL_ERROR "${NAME} package does not contain ${LIBRARY_FILE} for \${ANDROID_ABI}")
endif()

if(NOT TARGET ${CMAKE_TARGET})
    add_library(${CMAKE_TARGET} ${ARTIFACT_TYPE^^} IMPORTED)
    set_target_properties(${CMAKE_TARGET} PROPERTIES
        IMPORTED_LOCATION "\${_${CMAKE_PACKAGE}_LIBRARY}"
        INTERFACE_INCLUDE_DIRECTORIES "\${_${CMAKE_PACKAGE}_ROOT}/include${HEADER_INCLUDE}"
        ${INTERFACE_DEPENDENCY_BLOCK}
    )
endif()
EOF
if [ -n "$TARGET_ALIAS" ]; then
    cat >> "$CMAKE_DIR/${CMAKE_PACKAGE}Config.cmake" <<EOF
if(NOT TARGET ${TARGET_ALIAS})
    add_library(${TARGET_ALIAS} ALIAS ${CMAKE_TARGET})
endif()
EOF
fi
cat >> "$CMAKE_DIR/${CMAKE_PACKAGE}Config.cmake" <<EOF

set(${CMAKE_PACKAGE}_FOUND TRUE)
unset(_${CMAKE_PACKAGE}_LIBRARY)
unset(_${CMAKE_PACKAGE}_ROOT)
EOF

cat > "$CMAKE_DIR/${CMAKE_PACKAGE}ConfigVersion.cmake" <<EOF
set(PACKAGE_VERSION "${VERSION}")
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
  "id": "${PACKAGE_ID}",
  "name": "${NAME}",
  "version": "${VERSION}",
  "packageRevision": ${PACKAGE_REVISION},
  "upstreamName": "${UPSTREAM_NAME}",
  "upstreamVersion": "${VERSION}",
  "upstreamTag": "${UPSTREAM_TAG}",
  "upstreamCommit": "${UPSTREAM_COMMIT}",
  "description": "${DESCRIPTION}",
  "platform": "android",
  "artifactType": "${ARTIFACT_TYPE}",
  "installType": "download",
  "category": "library",
  "homepage": "${HOMEPAGE}",
  "license": "${LICENSE}",
  "files": {
    "include": "include",
    "lib": "lib",
    "cmake": "lib/cmake/${CMAKE_PACKAGE}",
    "pkgconfig": "pkgconfig/${PACKAGE_ID}.pc"
  },
  "abis": [${abi_json}],
  "dependencies": [${DEPENDENCIES_JSON}]
}
EOF

    cat > "$package_root/BUILD-INFO.txt" <<EOF
package_id=${PACKAGE_ID}
package_version=${VERSION}
package_revision=${PACKAGE_REVISION}
artifact_type=${ARTIFACT_TYPE}
upstream_tag=${UPSTREAM_TAG}
upstream_commit=${UPSTREAM_COMMIT}
upstream_version=${VERSION}
abis=${abi_list}
dependencies=${DEPENDENCY_ID}
EOF
}

FINAL_DIR="/output/registry/${PACKAGE_ID}/${VERSION}"
mkdir -p "$FINAL_DIR"
ABI_JSON="$(printf '"%s",' "${ABIS[@]}" | sed 's/,$//')"
ABI_LIST="$(IFS=,; echo "${ABIS[*]}")"
write_package_metadata "$PACKAGE_ROOT" "$ABI_JSON" "$ABI_LIST"

LEGACY_ARCHIVE="${FINAL_DIR}/${PACKAGE_ID}.tar.xz"
tar --sort=name --mtime='@0' --owner=0 --group=0 --numeric-owner \
    -C "$PACKAGE_ROOT" -cf - . | xz -9e --threads=0 > "$LEGACY_ARCHIVE"
sha256sum "$LEGACY_ARCHIVE" > "${LEGACY_ARCHIVE}.sha256"
echo "[SUCCESS] Legacy universal Registry package created: ${LEGACY_ARCHIVE}"
cat "${LEGACY_ARCHIVE}.sha256"

for ABI in "${ABIS[@]}"; do
    ABI_PACKAGE_ROOT="${TEMP_DIR}/package-${ABI}"
    mkdir -p "$ABI_PACKAGE_ROOT/lib"
    cp -R "$PACKAGE_ROOT/include" "$ABI_PACKAGE_ROOT/include"
    cp -R "$PACKAGE_ROOT/pkgconfig" "$ABI_PACKAGE_ROOT/pkgconfig"
    cp -R "$PACKAGE_ROOT/lib/cmake" "$ABI_PACKAGE_ROOT/lib/cmake"
    cp -R "$PACKAGE_ROOT/lib/$ABI" "$ABI_PACKAGE_ROOT/lib/$ABI"
    cp "$PACKAGE_ROOT/LICENSE.txt" "$ABI_PACKAGE_ROOT/LICENSE.txt"
    write_package_metadata "$ABI_PACKAGE_ROOT" "\"$ABI\"" "$ABI"

    FINAL_ARCHIVE="${FINAL_DIR}/${PACKAGE_ID}-${ABI}.tar.xz"
    tar --sort=name --mtime='@0' --owner=0 --group=0 --numeric-owner \
        -C "$ABI_PACKAGE_ROOT" -cf - . | xz -9e --threads=0 > "$FINAL_ARCHIVE"
    sha256sum "$FINAL_ARCHIVE" > "${FINAL_ARCHIVE}.sha256"

    echo "[SUCCESS] Registry package created: ${FINAL_ARCHIVE}"
    cat "${FINAL_ARCHIVE}.sha256"
done
