#!/bin/bash
# build-common.sh - 通用构建辅助函数
# 提供跨架构编译的公共配置

set -e

# ===== 颜色输出 =====
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

log_info() { echo -e "${BLUE}[INFO]${NC} $1"; }
log_success() { echo -e "${GREEN}[SUCCESS]${NC} $1"; }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
log_error() { echo -e "${RED}[ERROR]${NC} $1"; }

# ===== 架构配置 =====
# 设置交叉编译环境变量
# 参数: $1 = 架构 (arm64-v8a, armeabi-v7a, x86_64, x86)
setup_toolchain() {
    local ARCH=$1
    local API=${ANDROID_API:-28}

    export TOOLCHAIN="${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/linux-x86_64"

    case "$ARCH" in
        arm64-v8a|arm64)
            export TARGET_TRIPLE="aarch64-linux-android"
            export CLANG_TARGET="aarch64-linux-android${API}"
            export CMAKE_ARCH="arm64-v8a"
            export ARCH_SHORT="arm64"
            ;;
        armeabi-v7a|arm|armv7)
            export TARGET_TRIPLE="arm-linux-androideabi"
            export CLANG_TARGET="armv7a-linux-androideabi${API}"
            export CMAKE_ARCH="armeabi-v7a"
            export ARCH_SHORT="arm"
            ;;
        x86_64)
            export TARGET_TRIPLE="x86_64-linux-android"
            export CLANG_TARGET="x86_64-linux-android${API}"
            export CMAKE_ARCH="x86_64"
            export ARCH_SHORT="x86_64"
            ;;
        x86)
            export TARGET_TRIPLE="i686-linux-android"
            export CLANG_TARGET="i686-linux-android${API}"
            export CMAKE_ARCH="x86"
            export ARCH_SHORT="x86"
            ;;
        *)
            log_error "Unknown architecture: $ARCH"
            log_info "Supported: arm64-v8a, armeabi-v7a, x86_64, x86"
            exit 1
            ;;
    esac

    # 设置编译器
    export CC="${TOOLCHAIN}/bin/${CLANG_TARGET}-clang"
    export CXX="${TOOLCHAIN}/bin/${CLANG_TARGET}-clang++"
    export AR="${TOOLCHAIN}/bin/llvm-ar"
    export AS="${TOOLCHAIN}/bin/llvm-as"
    export LD="${TOOLCHAIN}/bin/ld.lld"
    export RANLIB="${TOOLCHAIN}/bin/llvm-ranlib"
    export STRIP="${TOOLCHAIN}/bin/llvm-strip"
    export NM="${TOOLCHAIN}/bin/llvm-nm"
    export OBJCOPY="${TOOLCHAIN}/bin/llvm-objcopy"
    export OBJDUMP="${TOOLCHAIN}/bin/llvm-objdump"
    export READELF="${TOOLCHAIN}/bin/llvm-readelf"

    # 16KB 页对齐 (Android 15+ 要求)
    export CFLAGS="-O2 -fPIC"
    export CXXFLAGS="-O2 -fPIC"
    export LDFLAGS="-Wl,-z,max-page-size=16384"

    # CMake 工具链文件路径
    export CMAKE_TOOLCHAIN_FILE="${ANDROID_NDK_HOME}/build/cmake/android.toolchain.cmake"

    log_info "Toolchain configured for ${ARCH}"
    log_info "  CC=${CC}"
    log_info "  API=${API}"
}

# ===== 下载辅助函数 =====
# 带重试和镜像的下载
# 参数: $1=URL, $2=输出文件, $3=镜像URL(可选)
download_with_retry() {
    local URL=$1
    local OUTPUT=$2
    local MIRROR=${3:-}
    local MAX_RETRIES=3

    for i in $(seq 1 $MAX_RETRIES); do
        log_info "Downloading (attempt $i/$MAX_RETRIES): $URL"

        # 尝试镜像
        if [ -n "$MIRROR" ]; then
            if curl -fsSL -o "$OUTPUT" "$MIRROR" 2>/dev/null; then
                log_success "Downloaded from mirror"
                return 0
            fi
            log_warn "Mirror failed, trying primary..."
        fi

        # 尝试主 URL
        if curl -fsSL -o "$OUTPUT" "$URL"; then
            log_success "Downloaded successfully"
            return 0
        fi

        log_warn "Download failed, retrying..."
        sleep 2
    done

    log_error "Failed to download after $MAX_RETRIES attempts"
    return 1
}

# GitHub 下载 (支持 ghproxy 镜像)
download_github() {
    local REPO=$1
    local TAG=$2
    local FILE=$3
    local OUTPUT=$4

    local GITHUB_URL="https://github.com/${REPO}/releases/download/${TAG}/${FILE}"
    local MIRROR_URL="https://ghproxy.com/${GITHUB_URL}"

    download_with_retry "$GITHUB_URL" "$OUTPUT" "$MIRROR_URL"
}

# Git clone (支持镜像)
git_clone_with_mirror() {
    local REPO=$1
    local DIR=$2
    local BRANCH=${3:-}

    local GITHUB_URL="https://github.com/${REPO}.git"
    local MIRROR_URL="https://ghproxy.com/${GITHUB_URL}"

    local BRANCH_ARG=""
    [ -n "$BRANCH" ] && BRANCH_ARG="--branch $BRANCH --single-branch"

    log_info "Cloning ${REPO}..."

    if git clone --depth 1 $BRANCH_ARG "$MIRROR_URL" "$DIR" 2>/dev/null; then
        log_success "Cloned from mirror"
        return 0
    fi

    log_warn "Mirror failed, trying GitHub directly..."
    git clone --depth 1 $BRANCH_ARG "$GITHUB_URL" "$DIR"
}

# Check out an exact upstream ref and verify its commit. This keeps package
# builds reproducible while tolerating transient GitHub/mirror failures.
git_checkout_exact() {
    local REPO=$1
    local DIR=$2
    local REF=$3
    local EXPECTED_COMMIT=$4
    local GITHUB_URL="https://github.com/${REPO}.git"
    local MAX_RETRIES=5

    if [ ! -d "$DIR/.git" ]; then
        rm -rf "$DIR"
        mkdir -p "$DIR"
        git -C "$DIR" init -q
        git -C "$DIR" remote add origin "$GITHUB_URL"
    else
        git -C "$DIR" remote set-url origin "$GITHUB_URL"
    fi

    for ATTEMPT in $(seq 1 "$MAX_RETRIES"); do
        log_info "Fetching ${REPO}@${REF} (attempt ${ATTEMPT}/${MAX_RETRIES})"
        if git -C "$DIR" fetch --force --depth 1 origin "$REF" && \
           git -C "$DIR" checkout --detach --force FETCH_HEAD; then
            local ACTUAL_COMMIT
            ACTUAL_COMMIT="$(git -C "$DIR" rev-parse HEAD)"
            if [ "$ACTUAL_COMMIT" = "$EXPECTED_COMMIT" ]; then
                return 0
            fi
            log_error "Unexpected commit for ${REPO}@${REF}: ${ACTUAL_COMMIT}"
        fi

        if [ "$ATTEMPT" -lt "$MAX_RETRIES" ]; then
            log_warn "Fetch failed; retrying after 20 seconds"
            sleep 20
        fi
    done

    log_error "Failed to fetch verified source ${REPO}@${REF}"
    return 1
}

git_submodule_update_with_retry() {
    local DIR=$1
    shift
    local MAX_RETRIES=5

    git -C "$DIR" submodule sync --recursive
    for ATTEMPT in $(seq 1 "$MAX_RETRIES"); do
        if git -C "$DIR" submodule update --init --depth 1 "$@"; then
            return 0
        fi
        if [ "$ATTEMPT" -lt "$MAX_RETRIES" ]; then
            log_warn "Submodule fetch failed; retrying after 20 seconds"
            sleep 20
        fi
    done

    log_error "Failed to initialize submodules in ${DIR}: $*"
    return 1
}

# ===== 打包辅助函数 =====
# 创建极致压缩的包
# 参数: $1=输入目录, $2=输出文件名(不含扩展名), $3=压缩格式(xz/zstd)
create_package() {
    local INPUT_DIR=$1
    local OUTPUT_NAME=$2
    local FORMAT=${3:-xz}

    cd "$INPUT_DIR"

    case "$FORMAT" in
        xz)
            log_info "Creating ${OUTPUT_NAME}.tar.xz with extreme compression..."
            tar -cf - . | xz -9e --threads=0 > "${OUTPUT_NAME}.tar.xz"
            ;;
        zstd)
            log_info "Creating ${OUTPUT_NAME}.tar.zst with maximum compression..."
            tar -cf - . | zstd -19 --long -T0 > "${OUTPUT_NAME}.tar.zst"
            ;;
        gz)
            log_info "Creating ${OUTPUT_NAME}.tar.gz..."
            tar -czf "${OUTPUT_NAME}.tar.gz" .
            ;;
        *)
            log_error "Unknown format: $FORMAT"
            return 1
            ;;
    esac

    local SIZE=$(du -h "${OUTPUT_NAME}.tar.${FORMAT}" | cut -f1)
    log_success "Package created: ${OUTPUT_NAME}.tar.${FORMAT} (${SIZE})"
}

# 创建包含头文件和库的标准发布包
# 参数: $1=库名, $2=架构, $3=包含目录, $4=库目录, $5=链接类型(static/shared)
create_release_package() {
    local LIB_NAME=$1
    local ARCH=$2
    local INCLUDE_DIR=$3
    local LIB_DIR=$4
    local LINK_TYPE=$5
    local OUTPUT_DIR="/output"

    local PKG_DIR="${OUTPUT_DIR}/${LIB_NAME}/${ARCH}/${LINK_TYPE}"
    mkdir -p "${PKG_DIR}/include" "${PKG_DIR}/lib"

    # 复制头文件
    if [ -d "$INCLUDE_DIR" ]; then
        cp -r "$INCLUDE_DIR"/* "${PKG_DIR}/include/"
    fi

    # 复制库文件
    if [ -d "$LIB_DIR" ]; then
        case "$LINK_TYPE" in
            static)
                cp "$LIB_DIR"/*.a "${PKG_DIR}/lib/" 2>/dev/null || true
                ;;
            shared)
                cp "$LIB_DIR"/*.so* "${PKG_DIR}/lib/" 2>/dev/null || true
                ;;
        esac
    fi

    # 创建压缩包
    cd "${OUTPUT_DIR}/${LIB_NAME}/${ARCH}"
    create_package "${PKG_DIR}" "${LIB_NAME}-${ARCH}-${LINK_TYPE}" "xz"

    log_success "Release package: ${LIB_NAME}-${ARCH}-${LINK_TYPE}.tar.xz"
}

# ===== 验证函数 =====
verify_16kb_alignment() {
    local FILE=$1

    if ! command -v readelf &>/dev/null; then
        log_warn "readelf not found, skipping alignment check"
        return 0
    fi

    local ALIGNMENT=$(readelf -l "$FILE" 2>/dev/null | grep -E "LOAD.*0x[0-9a-f]+" | head -1 | grep -o "0x[0-9a-f]*" | tail -1)

    if [ "$ALIGNMENT" = "0x4000" ] || [ "$ALIGNMENT" = "16384" ]; then
        log_success "16KB alignment verified for $FILE"
        return 0
    else
        log_warn "File may not have 16KB alignment: $FILE (found: $ALIGNMENT)"
        return 1
    fi
}

# ===== CMake 辅助 =====
# 生成 Android CMake 构建命令
cmake_android_configure() {
    local SOURCE_DIR=$1
    local BUILD_DIR=$2
    local ARCH=$3
    local EXTRA_ARGS="${4:-}"

    setup_toolchain "$ARCH"

    cmake -B "$BUILD_DIR" -S "$SOURCE_DIR" \
        -DCMAKE_TOOLCHAIN_FILE="${CMAKE_TOOLCHAIN_FILE}" \
        -DANDROID_ABI="${CMAKE_ARCH}" \
        -DANDROID_PLATFORM="android-${ANDROID_API}" \
        -DANDROID_STL=c++_static \
        -DCMAKE_BUILD_TYPE=Release \
        $EXTRA_ARGS
}

# ===== 主入口点 =====
# 用法: source build-common.sh && setup_toolchain arm64-v8a

log_info "TinaIDE Package Builder - Common Functions Loaded"
log_info "NDK: ${ANDROID_NDK_HOME}"
log_info "API: ${ANDROID_API:-28}"
