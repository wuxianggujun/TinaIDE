#!/usr/bin/env bash
# package-for-assets-win.sh - Windows 版本（使用 PowerShell 创建 zip）
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUTPUT_DIR="${SCRIPT_DIR}/output"
ASSETS_DIR="${SCRIPT_DIR}/../../app/src/main/assets/bundled_packages"

PACKAGE_NAME=${1:-sdl3}
ABIS_INPUT=${2:-arm64-v8a}
IFS=' ' read -ra ABIS <<< "$ABIS_INPUT"

case "$PACKAGE_NAME" in
    sdl2)
        VERSION="2.32.10"
        DISPLAY_NAME="SDL2"
        DESCRIPTION="Simple DirectMedia Layer 2 Android runtime"
        INCLUDE_SUBDIR="SDL2"
        ;;
    sdl3)
        VERSION="3.5.0"
        DISPLAY_NAME="SDL3"
        DESCRIPTION="Simple DirectMedia Layer 3 Android runtime"
        INCLUDE_SUBDIR="SDL3"
        ;;
    *)
        echo "[ERROR] Windows assets packaging supports only sdl2 and sdl3: ${PACKAGE_NAME}" >&2
        exit 1
        ;;
esac

echo "[INFO] Creating ${PACKAGE_NAME} assets package..."
echo "[INFO]   Version: ${VERSION}"
echo "[INFO]   ABIs: ${ABIS[*]}"

# 创建临时目录
TEMP_DIR=$(mktemp -d)
trap 'rm -rf "$TEMP_DIR"' EXIT

# 解压各架构
for ABI in "${ABIS[@]}"; do
    TARBALL="${OUTPUT_DIR}/${PACKAGE_NAME}/${ABI}/${PACKAGE_NAME}-${ABI}-shared.tar.xz"
    
    if [ ! -f "$TARBALL" ]; then
        echo "[ERROR] Missing: $TARBALL"
        exit 1
    fi
    
    echo "[INFO] Extracting ${ABI}..."
    mkdir -p "$TEMP_DIR/$ABI"
    tar -xf "$TARBALL" -C "$TEMP_DIR/$ABI"
    
    # 移动库文件
    mkdir -p "$TEMP_DIR/lib/$ABI"
    if [ -d "$TEMP_DIR/$ABI/lib" ]; then
        find "$TEMP_DIR/$ABI/lib" -name "*.so*" -exec mv {} "$TEMP_DIR/lib/$ABI/" \;
    fi
done

# 复制头文件
if [ -d "$TEMP_DIR/${ABIS[0]}/include" ]; then
    cp -r "$TEMP_DIR/${ABIS[0]}/include" "$TEMP_DIR/"
fi

# pkg-config
mkdir -p "$TEMP_DIR/pkgconfig"
if [ -f "$TEMP_DIR/${ABIS[0]}/lib/pkgconfig/${PACKAGE_NAME}.pc" ]; then
    sed 's|libdir=.*|libdir=${prefix}/lib/${ANDROID_ABI}|' \
        "$TEMP_DIR/${ABIS[0]}/lib/pkgconfig/${PACKAGE_NAME}.pc" \
        > "$TEMP_DIR/pkgconfig/${PACKAGE_NAME}.pc"
fi

# package.json
cat > "$TEMP_DIR/package.json" <<EOF
{
  "id": "${PACKAGE_NAME}",
  "name": "${DISPLAY_NAME}",
  "version": "${VERSION}",
  "description": "${DESCRIPTION}",
  "platform": "android",
  "installType": "download",
  "category": "library",
  "homepage": "https://www.libsdl.org/",
  "license": "Zlib",
  "installedAt": $(date +%s)000,
  "files": {
    "include": "include/${INCLUDE_SUBDIR}",
    "lib": "lib",
    "pkgconfig": "pkgconfig/${PACKAGE_NAME}.pc"
  },
  "abis": [$(printf '"%s",' "${ABIS[@]}" | sed 's/,$//')]
}
EOF

# 使用 PowerShell 创建 zip
mkdir -p "$ASSETS_DIR"
TEMP_DIR_WIN=$(cygpath -w "$TEMP_DIR")
ASSETS_DIR_WIN=$(cygpath -w "$ASSETS_DIR")

powershell.exe -Command "Compress-Archive -Path '${TEMP_DIR_WIN}\*' -DestinationPath '${ASSETS_DIR_WIN}\${PACKAGE_NAME}.zip' -Force"

echo "[SUCCESS] Package created: ${ASSETS_DIR}/${PACKAGE_NAME}.zip"
ls -lh "${ASSETS_DIR}/${PACKAGE_NAME}.zip"
