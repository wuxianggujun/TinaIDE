#!/bin/bash
# build.sh - TinaIDE 包构建器主入口
# 用法: ./build.sh <库名> <架构> <链接类型>
# 示例: ./build.sh openssl arm64-v8a static
#       ./build.sh all arm64-v8a static

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/build-common.sh"

# ===== 可用的库列表 =====
AVAILABLE_LIBS=(
    "zlib"
    "openssl"
    "curl"
    "libssh2"
    "libgit2"
    "pcre2"
    "sdl2"
    "sdl3"
    "sdl2-image"
    "sdl2-ttf"
    "sdl2-mixer"
    "sdl2-net"
    "sdl3-image"
    "sdl3-ttf"
    "sdl3-mixer"
    "sdl3-net"
    "raylib"
    "box2d"
)

# ===== 打印帮助 =====
print_help() {
    cat << EOF
TinaIDE Package Builder
=======================

用法: ./build.sh <库名> <架构> <链接类型>

参数:
  库名       要构建的库 (或 'all' 构建所有库)
  架构       arm64-v8a | armeabi-v7a | x86_64 | x86
  链接类型   static | shared

可用的库:
$(printf '  - %s\n' "${AVAILABLE_LIBS[@]}")

示例:
  ./build.sh zlib arm64-v8a static
  ./build.sh openssl x86_64 shared
  ./build.sh all arm64-v8a static

依赖关系:
  libssh2  -> openssl, zlib
  libgit2  -> openssl, libssh2, zlib
  curl     -> openssl (可选)
  sdl2     -> 无依赖 (独立库，Android Java/JNI bridge 已重定位)
  sdl3     -> 无依赖 (独立库)
  sdl2-*   -> sdl2 (仅 shared)
  sdl3-*   -> sdl3 (仅 shared)
  raylib   -> 无依赖 (仅 shared)
  box2d    -> 无依赖 (仅 static)

建议构建顺序:
  1. zlib
  2. openssl
  3. pcre2
  4. libssh2
  5. curl
  6. libgit2
  7. sdl2 / sdl3
  8. SDL2 / SDL3 扩展库
  9. raylib / box2d
EOF
}

# ===== 构建单个库 =====
build_lib() {
    local LIB=$1
    local ARCH=$2
    local LINK_TYPE=$3

    local SCRIPT="/build/libs/build-${LIB}.sh"

    case "$LIB" in
        sdl2-image|sdl2-ttf|sdl2-mixer|sdl2-net|sdl3-image|sdl3-ttf|sdl3-mixer|sdl3-net)
            SCRIPT="/build/libs/build-sdl-extension.sh"
            ;;
    esac

    if [ ! -f "$SCRIPT" ]; then
        log_error "Build script not found: ${SCRIPT}"
        return 1
    fi

    log_info "=========================================="
    log_info "Building ${LIB} for ${ARCH} (${LINK_TYPE})"
    log_info "=========================================="

    if [ "$SCRIPT" = "/build/libs/build-sdl-extension.sh" ]; then
        bash "$SCRIPT" "$LIB" "$ARCH" "$LINK_TYPE"
    else
        bash "$SCRIPT" "$ARCH" "$LINK_TYPE"
    fi
}

supports_link_type() {
    local LIB=$1
    local LINK_TYPE=$2

    case "$LIB" in
        sdl2-image|sdl2-ttf|sdl2-mixer|sdl2-net|sdl3-image|sdl3-ttf|sdl3-mixer|sdl3-net|raylib)
            [ "$LINK_TYPE" = "shared" ]
            ;;
        box2d)
            [ "$LINK_TYPE" = "static" ]
            ;;
        *)
            return 0
            ;;
    esac
}

# ===== 构建所有库 (按依赖顺序) =====
build_all() {
    local ARCH=$1
    local LINK_TYPE=$2

    local BUILD_ORDER=(
        "zlib"
        "openssl"
        "pcre2"
        "libssh2"
        "curl"
        "libgit2"
        "sdl2"
        "sdl3"
        "sdl2-image"
        "sdl2-ttf"
        "sdl2-mixer"
        "sdl2-net"
        "sdl3-image"
        "sdl3-ttf"
        "sdl3-mixer"
        "sdl3-net"
        "raylib"
        "box2d"
    )

    log_info "Building all libraries for ${ARCH} (${LINK_TYPE})"
    log_info "Build order: ${BUILD_ORDER[*]}"

    for LIB in "${BUILD_ORDER[@]}"; do
        if supports_link_type "$LIB" "$LINK_TYPE"; then
            build_lib "$LIB" "$ARCH" "$LINK_TYPE"
        else
            log_warn "Skipping unsupported combination: ${LIB} (${ARCH}, ${LINK_TYPE})"
        fi
    done

    log_success "All libraries built successfully!"
}

# ===== 列出输出文件 =====
list_outputs() {
    log_info "Output packages:"
    find /output -name "*.tar.xz" -o -name "*.tar.zst" 2>/dev/null | sort | while read -r f; do
        local SIZE=$(du -h "$f" | cut -f1)
        echo "  $f ($SIZE)"
    done
}

# ===== 主程序 =====
main() {
    local LIB=${1:-}
    local ARCH=${2:-arm64-v8a}
    local LINK_TYPE=${3:-static}

    # 初始化源码目录
    mkdir -p /build/src /build/build /build/install /output

    case "$LIB" in
        -h|--help|help|"")
            print_help
            exit 0
            ;;
        list)
            list_outputs
            exit 0
            ;;
        all)
            build_all "$ARCH" "$LINK_TYPE"
            ;;
        *)
            # 检查是否是已知库
            local FOUND=0
            for KNOWN in "${AVAILABLE_LIBS[@]}"; do
                if [ "$LIB" = "$KNOWN" ]; then
                    FOUND=1
                    break
                fi
            done

            if [ $FOUND -eq 0 ]; then
                log_error "Unknown library: ${LIB}"
                log_info "Available: ${AVAILABLE_LIBS[*]}"
                exit 1
            fi

            build_lib "$LIB" "$ARCH" "$LINK_TYPE"
            ;;
    esac

    echo ""
    list_outputs
}

main "$@"
