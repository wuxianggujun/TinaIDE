#!/bin/bash
# package-for-assets.sh - 将编译产物打包为 assets 格式
# 用法: ./package-for-assets.sh <库名> [架构列表] [压缩格式]
# 示例: ./package-for-assets.sh sdl3 arm64-v8a xz
#       ./package-for-assets.sh sdl3 "arm64-v8a armeabi-v7a" zstd
#
# 支持的压缩格式:
#   xz    - tar.xz (默认，最高压缩率，推荐)
#   zstd  - tar.zst (快速压缩/解压)
#   gz    - tar.gz (兼容性最好)
#   zip   - zip (Windows 友好，但压缩率较低)

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUTPUT_DIR="${SCRIPT_DIR}/output"
ASSETS_DIR="${SCRIPT_DIR}/../../app/src/main/assets/bundled_packages"

# 颜色输出
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

log_info() { echo -e "${BLUE}[INFO]${NC} $1"; }
log_success() { echo -e "${GREEN}[SUCCESS]${NC} $1"; }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
log_error() { echo -e "${RED}[ERROR]${NC} $1"; }

# 参数解析
PACKAGE_NAME=${1:-sdl3}
ABIS_INPUT=${2:-arm64-v8a}
FORMAT=${3:-xz}  # 默认使用 xz 格式（最高压缩率）
IFS=' ' read -ra ABIS <<< "$ABIS_INPUT"

# 版本映射（根据实际编译版本调整）
declare -A VERSIONS=(
    ["sdl2"]="2.32.10"
    ["sdl3"]="3.5.0"
    ["zlib"]="1.3.1"
    ["openssl"]="3.2.1"
)

VERSION=${VERSIONS[$PACKAGE_NAME]:-"unknown"}

log_info "Creating ${PACKAGE_NAME} assets package..."
log_info "  Version: ${VERSION}"
log_info "  ABIs: ${ABIS[*]}"
log_info "  Format: ${FORMAT}"

# 创建临时目录
TEMP_DIR=$(mktemp -d)
trap "rm -rf $TEMP_DIR" EXIT

# 解压各架构到统一目录
for ABI in "${ABIS[@]}"; do
    TARBALL="${OUTPUT_DIR}/${PACKAGE_NAME}/${ABI}/${PACKAGE_NAME}-${ABI}-shared.tar.xz"

    # 尝试静态库（如果动态库不存在）
    if [ ! -f "$TARBALL" ]; then
        TARBALL="${OUTPUT_DIR}/${PACKAGE_NAME}/${ABI}/${PACKAGE_NAME}-${ABI}-static.tar.xz"
    fi

    if [ ! -f "$TARBALL" ]; then
        log_error "Missing tarball: ${OUTPUT_DIR}/${PACKAGE_NAME}/${ABI}/${PACKAGE_NAME}-${ABI}-*.tar.xz"
        log_info "Run: ./build-pkg.ps1 -Library ${PACKAGE_NAME} -Arch ${ABI} -LinkType shared"
        exit 1
    fi

    log_info "Extracting ${ABI}..."

    # 解压到临时目录
    mkdir -p "$TEMP_DIR/$ABI"
    tar -xf "$TARBALL" -C "$TEMP_DIR/$ABI"

    # 移动库文件到 lib/<abi>/ 结构
    mkdir -p "$TEMP_DIR/lib/$ABI"
    if [ -d "$TEMP_DIR/$ABI/lib" ]; then
        find "$TEMP_DIR/$ABI/lib" -name "*.so*" -o -name "*.a" | while read -r libfile; do
            mv "$libfile" "$TEMP_DIR/lib/$ABI/"
        done
    fi
done

# 复制头文件（所有架构共享）
if [ -d "$TEMP_DIR/${ABIS[0]}/include" ]; then
    cp -r "$TEMP_DIR/${ABIS[0]}/include" "$TEMP_DIR/"
    log_info "Copied headers from ${ABIS[0]}"
fi

# 处理 pkg-config（需要修改为多架构）
mkdir -p "$TEMP_DIR/pkgconfig"
if [ -f "$TEMP_DIR/${ABIS[0]}/lib/pkgconfig/${PACKAGE_NAME}.pc" ]; then
    # 修改 pkg-config 文件以支持多架构
    sed 's|libdir=.*|libdir=${prefix}/lib/${ANDROID_ABI}|' \
        "$TEMP_DIR/${ABIS[0]}/lib/pkgconfig/${PACKAGE_NAME}.pc" \
        > "$TEMP_DIR/pkgconfig/${PACKAGE_NAME}.pc"
    log_info "Generated multi-arch pkg-config"
elif [ -f "$TEMP_DIR/${ABIS[0]}/pkgconfig/${PACKAGE_NAME}.pc" ]; then
    sed 's|libdir=.*|libdir=${prefix}/lib/${ANDROID_ABI}|' \
        "$TEMP_DIR/${ABIS[0]}/pkgconfig/${PACKAGE_NAME}.pc" \
        > "$TEMP_DIR/pkgconfig/${PACKAGE_NAME}.pc"
    log_info "Generated multi-arch pkg-config"
else
    log_warn "No pkg-config file found, skipping"
fi

# 创建包元数据
cat > "$TEMP_DIR/package.json" <<EOF
{
  "id": "${PACKAGE_NAME}",
  "name": "${PACKAGE_NAME^^}",
  "version": "${VERSION}",
  "description": "Precompiled ${PACKAGE_NAME} library for Android",
  "platform": "android",
  "installType": "download",
  "category": "library",
  "license": "Various",
  "installedAt": $(date +%s)000,
  "files": {
    "include": "include",
    "lib": "lib",
    "pkgconfig": "pkgconfig/${PACKAGE_NAME}.pc"
  },
  "abis": [$(printf '"%s",' "${ABIS[@]}" | sed 's/,$//')]
}
EOF

log_info "Generated package.json"

# 打包
mkdir -p "$ASSETS_DIR"
cd "$TEMP_DIR"

# 确定要打包的内容
PACK_ITEMS=""
[ -d "include" ] && PACK_ITEMS="$PACK_ITEMS include"
[ -d "lib" ] && PACK_ITEMS="$PACK_ITEMS lib"
[ -d "pkgconfig" ] && PACK_ITEMS="$PACK_ITEMS pkgconfig"
[ -f "package.json" ] && PACK_ITEMS="$PACK_ITEMS package.json"

if [ -z "$PACK_ITEMS" ]; then
    log_error "No content to package!"
    exit 1
fi

# 根据格式打包
case "$FORMAT" in
    xz)
        tar -cf - $PACK_ITEMS | xz -9e --threads=0 > "${ASSETS_DIR}/${PACKAGE_NAME}.tar.xz"
        PACKAGE_FILE="${ASSETS_DIR}/${PACKAGE_NAME}.tar.xz"
        ;;
    zstd)
        tar -cf - $PACK_ITEMS | zstd -19 --long -T0 > "${ASSETS_DIR}/${PACKAGE_NAME}.tar.zst"
        PACKAGE_FILE="${ASSETS_DIR}/${PACKAGE_NAME}.tar.zst"
        ;;
    gz)
        tar -czf "${ASSETS_DIR}/${PACKAGE_NAME}.tar.gz" $PACK_ITEMS
        PACKAGE_FILE="${ASSETS_DIR}/${PACKAGE_NAME}.tar.gz"
        ;;
    zip)
        zip -r "${ASSETS_DIR}/${PACKAGE_NAME}.zip" $PACK_ITEMS
        PACKAGE_FILE="${ASSETS_DIR}/${PACKAGE_NAME}.zip"
        ;;
    *)
        log_error "Unknown format: $FORMAT (supported: xz, zstd, gz, zip)"
        exit 1
        ;;
esac

log_success "Package created: $PACKAGE_FILE"
ls -lh "$PACKAGE_FILE"

# 显示内容
echo ""
log_info "Package contents:"
case "$FORMAT" in
    zip)
        unzip -l "$PACKAGE_FILE" | head -30
        ;;
    *)
        tar -tf "$PACKAGE_FILE" | head -30
        ;;
esac

# 清理临时文件
cd "$SCRIPT_DIR"
rm -rf "$TEMP_DIR"

log_success "Done!"
