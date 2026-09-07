#!/system/bin/sh
# TinaIDE PRoot 启动脚本

# 说明：
# - 本脚本只负责“在 Android 上可靠地拉起 proot”，尽量避免与 Java 侧策略冲突。
# - 关键兼容性开关（如 PROOT_NO_SECCOMP）优先由 Java 注入；脚本不再擅自覆盖，避免出现“怎么配都不生效”。

SCRIPT_TAG="init-proot.sh"

ensure_log_paths() {
    if [ "${__LOG_INIT:-0}" = "1" ]; then
        return 0
    fi
    __LOG_INIT=1

    # 优先使用 Java 注入的 PROOT_LOG_DIR（建议指向 app 私有目录）
    local base_dir
    base_dir="${PROOT_LOG_DIR:-}"
    if [ -z "$base_dir" ]; then
        if [ -n "${PROOT_TMP_DIR:-}" ]; then
            base_dir="$PROOT_TMP_DIR/logs"
        else
            base_dir="/data/local/tmp/tinaide-proot-logs"
        fi
    fi

    LOG_DIR="$base_dir"
    mkdir -p "$LOG_DIR" 2>/dev/null || true

    SESSION_LOG_FILE="${PROOT_SESSION_LOG_FILE:-$LOG_DIR/session_$(date '+%Y-%m-%d_%H%M%S').log}"
    ERROR_LOG_FILE="${PROOT_ERROR_LOG_FILE:-$LOG_DIR/error_$(date '+%Y-%m-%d').log}"

    # 确保文件存在，避免部分系统上 append 重定向失败
    touch "$SESSION_LOG_FILE" 2>/dev/null || true
    touch "$ERROR_LOG_FILE" 2>/dev/null || true
}

log_line() {
    # $1: level, $2: message
    ensure_log_paths
    local level="$1"
    shift
    local msg="$*"
    local ts
    ts=$(date '+%Y-%m-%d %H:%M:%S')

    local line="[$ts] [$level] $msg"
    echo "$line" >> "$SESSION_LOG_FILE" 2>/dev/null || true

    if [ "$level" = "ERROR" ] || [ "$level" = "STDERR" ]; then
        echo "$line" >> "$ERROR_LOG_FILE" 2>/dev/null || true
    fi

    # 默认不污染 stdout；仅在 debug 或错误时输出到 stderr
    if [ "$PROOT_DEBUG" = "1" ] || [ "$level" = "ERROR" ]; then
        echo "[$SCRIPT_TAG] $line" >&2
    fi
}

log_info() {
    log_line "INFO" "$*"
}

log_warn() {
    log_line "WARN" "$*"
}

log_error() {
    log_line "ERROR" "$*"
}

log_cmd() {
    log_line "CMD" "$*"
}

log_debug() {
    if [ "$PROOT_DEBUG" = "1" ]; then
        log_line "DEBUG" "$*"
    fi
}

die() {
    log_error "$*"
    echo "ERROR: $*" >&2
    exit 1
}

debug() {
    log_debug "$*"
}

# realpath 在不同 ROM 上可能不存在
resolve_path() {
    realpath "$1" 2>/dev/null || echo "$1"
}

# 环境变量由 Java 代码设置：
# - NATIVE_LIB_DIR: native library 目录
# - ROOTFS_PATH: rootfs 路径
# - PROOT_BIN: proot 可执行路径（可选，未设置则回退到 $NATIVE_LIB_DIR/libproot.so）
# - LINKER: 动态链接器路径（/system/bin/linker64）
# - PROOT_TMP_DIR: proot 临时目录（可选，脚本会尽力创建）
# - LD_LIBRARY_PATH: 库搜索路径（脚本不消费，但会在 debug 打印）
# - PROOT_LOADER: proot loader 路径（脚本不消费，但会在 debug 打印）
# - PROOT_LOADER32: proot 32位 loader 路径（脚本不消费，但会在 debug 打印）
# - TINA_PROOT__LD_PRELOAD_DIRECT: direct 模式使用的 tina-exec preload（可选）
# - TINA_PROOT__LD_PRELOAD_LINKER: linker 模式使用的 tina-exec preload（可选）
# - TINA_APP__* / TINA_ROOTFS / TINA_PREFIX / TINA_EXEC__*:
#   tina-exec 运行时所需基础环境（由 Java 侧透传）
# - WORK_DIR: 工作目录（guest 路径，如 /workspace）
# - WORKSPACE_HOST_DIR: workspace 的 host 路径（可选）
# - PROJECTS_HOST_DIR: projects 的 host 路径（可选）

# 检查必要的环境变量
if [ -z "$NATIVE_LIB_DIR" ]; then
    die "NATIVE_LIB_DIR not set"
fi

if [ -z "$ROOTFS_PATH" ]; then
    die "ROOTFS_PATH not set"
fi

if [ ! -d "$ROOTFS_PATH" ]; then
    die "ROOTFS_PATH is not a directory: $ROOTFS_PATH"
fi

# proot 二进制路径
# - 优先使用 Java 侧注入的 PROOT_BIN（通常是 app 私有目录下的"普通可执行文件"）
# - 回退到 nativeLibraryDir 下的 libproot.so
if [ -n "$PROOT_BIN" ]; then
    : # keep
else
    PROOT_BIN="$NATIVE_LIB_DIR/libproot.so"
fi

if [ ! -f "$PROOT_BIN" ]; then
    die "proot not found at $PROOT_BIN"
fi

log_info "start"
log_debug "PROOT_LOG_DIR=${PROOT_LOG_DIR:-<unset>}"
log_debug "PROOT_SESSION_LOG_FILE=${PROOT_SESSION_LOG_FILE:-<unset>}"
log_debug "PROOT_ERROR_LOG_FILE=${PROOT_ERROR_LOG_FILE:-<unset>}"

# =============================================================================
# PRoot guest 运行兼容性环境变量
# =============================================================================

# 1. 禁用 libgcrypt FIPS 模式
#    修复 "error reading /proc/sys/crypto/fips_enabled" 错误
if [ -z "${LIBGCRYPT_FORCE_FIPS_MODE+x}" ]; then
    export LIBGCRYPT_FORCE_FIPS_MODE=0
fi

# 2. PRoot seccomp 加速开关
#    注意：不要在此处默认 export PROOT_NO_SECCOMP。
#    - 若脚本强行设置，将导致 Java 侧无法“启用 seccomp”（因为 env 一旦存在就很难在子进程里再取消）。
#    - TinaIDE 当前策略：在需要兼容的 Android 版本/设备上，由 Java 注入 PROOT_NO_SECCOMP=1。

# 3. 禁用异步 IO
#    某些文件系统操作在异步模式下可能出问题
if [ -z "${PROOT_ASYNCHRONOUS_IO+x}" ]; then
    export PROOT_ASYNCHRONOUS_IO=0
fi

# 4. 清理 Android 特有的环境变量，避免干扰 guest 环境
unset ANDROID_ART_ROOT
unset ANDROID_DATA
unset ANDROID_I18N_ROOT
unset ANDROID_ROOT
unset ANDROID_RUNTIME_ROOT
unset ANDROID_TZDATA_ROOT

# 5. 设置时区（如果未设置）
if [ -z "$TZ" ]; then
    # 尝试从 Android 系统获取时区
    android_tz=$(getprop persist.sys.timezone 2>/dev/null)
    if [ -n "$android_tz" ]; then
        export TZ="$android_tz"
    else
        export TZ="UTC"
    fi
fi

# -----------------------------------------------------------------------------
# 启动方式选择（兼容不同 ROM/SELinux/noexec 行为）
#
# 经验结论：
# - 直接执行 nativeLibraryDir 下的 ELF 在部分 ROM 上会报：`not executable: 64-bit ELF file`（ENOEXEC）。
# - 通过 bionic linker 启动时，不要添加 GNU ld.so 风格的 `--` 分隔符；
#   Android linker 不消费该参数，反而会把它传给 proot，导致 proot 报 unknown option '--'。
# - x86_64 模拟器上 linker64 行为可能与 ARM 设备不一致，导致输出 linker 帮助信息而非执行 proot。
#
# 策略调整（2024-01 修订）：
# - 默认优先使用 direct 模式（直接执行 proot）
# - 如果 direct 失败（如 ENOEXEC），再回退到 linker 模式
# - 允许通过 `PROOT_LAUNCH_MODE=direct|linker|auto` 强制覆盖
# - auto 模式会自动探测最佳启动方式
	# -----------------------------------------------------------------------------
LAUNCH_MODE="${PROOT_LAUNCH_MODE:-auto}"

# 确保 proot 临时目录存在（供 proot 内部使用）
if [ -n "$PROOT_TMP_DIR" ]; then
    mkdir -p "$PROOT_TMP_DIR" 2>/dev/null || true
    log_debug "PROOT_TMP_DIR=$PROOT_TMP_DIR"
fi

# =============================================================================
# 确保 DNS 解析正常
# 创建 resolv.conf
# =============================================================================
setup_dns() {
    local resolv_conf="$ROOTFS_PATH/etc/resolv.conf"
    local resolv_dir="$ROOTFS_PATH/etc"
    
    # 确保 /etc 目录存在
    [ -d "$resolv_dir" ] || mkdir -p "$resolv_dir" 2>/dev/null || true
    
    # 如果 resolv.conf 不存在或为空，创建默认配置
    if [ ! -s "$resolv_conf" ]; then
        # 尝试从 Android 系统复制 DNS 配置
        if [ -r "/system/etc/resolv.conf" ]; then
            cat /system/etc/resolv.conf > "$resolv_conf" 2>/dev/null || true
        fi
        
        # 如果仍然为空，使用默认的公共 DNS
        if [ ! -s "$resolv_conf" ]; then
            cat > "$resolv_conf" 2>/dev/null << 'EOF' || true
# Generated by TinaIDE init-proot.sh
nameserver 8.8.8.8
nameserver 8.8.4.4
nameserver 1.1.1.1
EOF
        fi
    fi
}

# 确保 hosts 文件存在
setup_hosts() {
    local hosts_file="$ROOTFS_PATH/etc/hosts"
    
    if [ ! -s "$hosts_file" ]; then
        cat > "$hosts_file" 2>/dev/null << 'EOF' || true
# Generated by TinaIDE init-proot.sh
127.0.0.1   localhost
::1         localhost ip6-localhost ip6-loopback
EOF
    fi
}

# 执行网络设置
log_info "setup dns and hosts"
setup_dns
setup_hosts

# =============================================================================
# 构建 proot 参数
# =============================================================================
add_bind() {
    # $1: host path, $2: guest path (optional)
    if [ -n "$2" ]; then
        ARGS="$ARGS -b $1:$2"
    else
        ARGS="$ARGS -b $1"
    fi
}

bind_dir_if_accessible() {
    # $1: dir, $2: guest path (optional)
    local dir="$1"
    local guest="$2"
    if [ -d "$dir" ] && [ -x "$dir" ]; then
        dir=$(resolve_path "$dir")
        add_bind "$dir" "$guest"
    else
        debug "skip bind dir (not accessible): $dir"
    fi
}

# 尝试绑定存储目录（放宽检查条件：只要目录存在就尝试绑定）
# Android scoped storage 下，即使没有 -x 权限，PRoot 仍可能可以访问
bind_storage_dir() {
    local dir="$1"
    local guest="$2"
    if [ -e "$dir" ]; then
        dir=$(resolve_path "$dir")
        add_bind "$dir" "$guest"
        debug "bind storage dir: $dir -> ${guest:-$dir}"
    else
        debug "skip storage dir (not exists): $dir"
    fi
}

ARGS="-w ${WORK_DIR:-/}"
ARGS="$ARGS -r $ROOTFS_PATH"
ARGS="$ARGS -0"

# --link2symlink: 将硬链接转换为符号链接（Android 文件系统限制）
ARGS="$ARGS --link2symlink"

# --kill-on-exit: 退出时杀死所有子进程
ARGS="$ARGS --kill-on-exit"

# --sysvipc: 在 proot 内部实现 System V IPC（shmget/semget/msgget）。
# Android 内核不暴露这些 syscall，而 dbus 与 X11 客户端库会用到；
# 缺少时表现为桌面组件启动即失败。注意：每个 proot 实例是独立 IPC namespace。
ARGS="$ARGS --sysvipc"

# 绑定设备与进程目录
bind_dir_if_accessible "/dev"
# /dev/urandom -> /dev/random（某些程序依赖 /dev/random 但 Android 上不一定可用）
if [ -e "/dev/urandom" ]; then
    add_bind "/dev/urandom" "/dev/random"
fi
bind_dir_if_accessible "/proc"
bind_dir_if_accessible "/sys"

# /dev/shm：Android 的 /dev 里没有 shm，而 X11 的 MIT-SHM、dbus 和多数桌面工具都要它。
# 缺失时 GTK/Qt 通常能退回 socket 传输，但 XFCE 的部分组件会直接报错。
# 复用 <rootfs>/tmp 是刻意的：guest 的 /tmp 与它同 inode（见 X11SocketLayout），
# 桌面进程在两个路径下看到的是同一份共享内存文件。
SHM_DIR="$ROOTFS_PATH/tmp"
mkdir -p "$SHM_DIR" 2>/dev/null || true
if [ -d "$SHM_DIR" ]; then
    add_bind "$SHM_DIR" "/dev/shm"
fi

# 绑定存储目录（让用户在 PRoot 环境内可访问外部存储）
# 使用 bind_storage_dir：即使没有 -x 权限也尝试绑定
bind_storage_dir "/sdcard"
bind_storage_dir "/storage"
# 常见的内部存储路径别名
bind_storage_dir "/storage/emulated/0"

# 绑定系统路径（linker、系统库等；部分工具链/程序依赖这些路径）
for sys_path in /apex /system /vendor /product; do
    bind_dir_if_accessible "$sys_path"
done

# 绑定工作空间目录（host 路径 -> guest 路径）
# 这样在 PRoot 内部访问 /workspace 时，实际访问的是 Android 上的 workspace 目录
if [ -n "$WORKSPACE_HOST_DIR" ] && [ -d "$WORKSPACE_HOST_DIR" ]; then
    bind_dir_if_accessible "$WORKSPACE_HOST_DIR" "/workspace"
fi

# 绑定 projects 目录
if [ -n "$PROJECTS_HOST_DIR" ] && [ -d "$PROJECTS_HOST_DIR" ]; then
    bind_dir_if_accessible "$PROJECTS_HOST_DIR" "/projects"
fi

# 绑定 /proc/self/fd 到 /dev/fd
# 注意：只绑定目录，不单独绑定 stdin/stdout/stderr
if [ -d "/proc/self/fd" ]; then
    add_bind "/proc/self/fd" "/dev/fd"
fi

# 设置 kernel release（用于兼容性，欺骗检测内核版本的程序）
# 使用短选项 `-k`，避免 linker 误解析
if [ -n "$KERNEL_RELEASE" ]; then
    ARGS="$ARGS -k $KERNEL_RELEASE"
else
    # 默认使用 5.4.0，这是一个常见的稳定版本
    ARGS="$ARGS -k 5.4.0"
fi

# 调试日志（仅当 PROOT_DEBUG=1 时输出）
if [ "$PROOT_DEBUG" = "1" ]; then
    debug "PROOT_BIN=$PROOT_BIN"
    debug "ROOTFS_PATH=$ROOTFS_PATH"
    debug "WORK_DIR=$WORK_DIR"
    debug "LINKER=$LINKER"
    debug "LAUNCH_MODE=$LAUNCH_MODE"
    debug "PROOT_NO_SECCOMP=${PROOT_NO_SECCOMP:-<unset>}"
    debug "PROOT_ASYNCHRONOUS_IO=${PROOT_ASYNCHRONOUS_IO:-<unset>}"
    debug "PROOT_TMP_DIR=${PROOT_TMP_DIR:-<unset>}"
    debug "LD_LIBRARY_PATH=${LD_LIBRARY_PATH:-<unset>}"
    debug "PROOT_LOADER=${PROOT_LOADER:-<unset>}"
    debug "PROOT_LOADER32=${PROOT_LOADER32:-<unset>}"
    debug "TINA_PROOT__LD_PRELOAD_DIRECT=${TINA_PROOT__LD_PRELOAD_DIRECT:-<unset>}"
    debug "TINA_PROOT__LD_PRELOAD_LINKER=${TINA_PROOT__LD_PRELOAD_LINKER:-<unset>}"
    debug "ARGS=$ARGS"
    debug "Command: $*"
fi

# -----------------------------------------------------------------------------
# 按启动模式设置 tina-exec 的 LD_PRELOAD。
# 说明：
# - 这里才真正 export LD_PRELOAD，避免影响外层 /system/bin/sh
# - direct/linker 需要加载不同的 preload so
# -----------------------------------------------------------------------------
apply_tina_exec_ld_preload_for_mode() {
    local mode="$1"
    local ld_preload=""

    case "$mode" in
        direct)
            ld_preload="${TINA_PROOT__LD_PRELOAD_DIRECT:-}"
            ;;
        linker)
            ld_preload="${TINA_PROOT__LD_PRELOAD_LINKER:-}"
            ;;
        *)
            ld_preload=""
            ;;
    esac

    if [ -n "$ld_preload" ]; then
        export LD_PRELOAD="$ld_preload"
        debug "Applied tina-exec LD_PRELOAD for mode=$mode"
    else
        unset LD_PRELOAD
        debug "No tina-exec LD_PRELOAD configured for mode=$mode"
    fi
}

# -----------------------------------------------------------------------------
# 探测函数：测试特定启动方式是否可用
# -----------------------------------------------------------------------------
test_launch_mode() {
    local mode="$1"
    local test_output
    local test_exit_code=0
    
    case "$mode" in
        direct)
            # 测试 direct 模式：执行 proot --version（快速命令）
            apply_tina_exec_ld_preload_for_mode "direct"
            test_output=$("$PROOT_BIN" --version 2>&1) || test_exit_code=$?
            ;;
        linker)
            # 测试 linker 模式
            if [ -z "$LINKER" ] || [ ! -x "$LINKER" ]; then
                return 1
            fi
            apply_tina_exec_ld_preload_for_mode "linker"
            test_output=$("$LINKER" "$PROOT_BIN" --version 2>&1) || test_exit_code=$?
            ;;
        *)
            return 1
            ;;
    esac
    
    # 检查输出是否包含 proot 版本信息（而非 linker 帮助信息）
    case "$test_output" in
        *"proot"*|*"PRoot"*|*"5."*|*"4."*)
            # 看起来是 proot 的正常输出
            return 0
            ;;
        *"helper program for dynamic executables"*|*"linker"*|*"LINKER"*)
            # linker 输出了帮助信息，说明没有正确执行 proot
            return 1
            ;;
        *"not executable"*|*"Permission denied"*|*"cannot execute"*)
            # 执行失败
            return 1
            ;;
        *)
            # 其他情况，如果有输出且没有明显错误，假设成功
            if [ -n "$test_output" ] && [ "${test_exit_code:-0}" -eq 0 ]; then
                return 0
            fi
            return 1
            ;;
    esac
}

# -----------------------------------------------------------------------------
# auto 模式：自动探测最佳启动方式
# -----------------------------------------------------------------------------
if [ "$LAUNCH_MODE" = "auto" ]; then
    debug "auto mode: testing launch methods..."
    
    # 优先尝试 direct 模式（更可靠，尤其在 x86_64 模拟器上）
    if test_launch_mode "direct"; then
        LAUNCH_MODE="direct"
        log_info "auto mode: direct works"
    elif test_launch_mode "linker"; then
        LAUNCH_MODE="linker"
        log_info "auto mode: linker works"
    else
        # 两种都失败，默认使用 direct（让错误信息更明确）
        LAUNCH_MODE="direct"
        log_warn "auto mode: both failed, fallback to direct"
    fi
fi

# 启动 proot
case "$LAUNCH_MODE" in
    direct)
        apply_tina_exec_ld_preload_for_mode "direct"
        log_cmd "$PROOT_BIN $ARGS $*"
        debug "Launching (direct): $PROOT_BIN $ARGS $*"
        exec "$PROOT_BIN" $ARGS "$@"
        ;;
    linker)
        apply_tina_exec_ld_preload_for_mode "linker"
        log_cmd "$LINKER $PROOT_BIN $ARGS $*"
        debug "Launching (linker): $LINKER $PROOT_BIN $ARGS $*"
        if [ -z "$LINKER" ] || [ ! -x "$LINKER" ]; then
            die "LINKER not set or not executable: $LINKER"
        fi
        exec "$LINKER" "$PROOT_BIN" $ARGS "$@"
        ;;
    *)
        die "invalid PROOT_LAUNCH_MODE=$LAUNCH_MODE (expected: direct|linker|auto)"
        ;;
esac
