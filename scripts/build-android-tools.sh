#!/usr/bin/env bash
set -euo pipefail

# Build Android-native build tools (cmake/ninja/make) and install into a toolchain dir.
# Intended to run inside docker/toolchain-builder container.

ARCH="aarch64"
API="28"
HOST_TOOLCHAIN=""
INSTALL_DIR=""
REBUILD_TOOLS="${REBUILD_TOOLS:-0}" # 1=force rebuild even if cache exists
STRIP_TOOLS_BINARIES="${STRIP_TOOLS_BINARIES:-0}" # 1=strip built tool binaries
APPLY_CMAKE_ANDROID_EXEC_PATCH="${APPLY_CMAKE_ANDROID_EXEC_PATCH:-1}" # 1=patch CMake subprocess exec() sources

usage() {
  cat <<EOF
Usage: $0 --arch aarch64|x86_64 --api 28 --host-toolchain /path/to/host-toolchain --install-dir /path/to/toolchain

Env:
  ANDROID_TOOLS_ROOT=/workspace/build/android-tools
  APPLY_CMAKE_ANDROID_EXEC_PATCH=0|1
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --arch) ARCH="$2"; shift 2 ;;
    --api) API="$2"; shift 2 ;;
    --host-toolchain) HOST_TOOLCHAIN="$2"; shift 2 ;;
    --install-dir) INSTALL_DIR="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "[ERROR] unknown arg: $1" >&2; usage; exit 2 ;;
  esac
done

die() { echo "[ERROR] $*" >&2; exit 1; }
log() { echo "[INFO] $*"; }
require_cmd() { command -v "$1" >/dev/null 2>&1 || die "missing command: $1"; }

require_cmd curl
require_cmd tar
require_cmd xz
require_cmd cmake
require_cmd ninja
require_cmd make
require_cmd python3
require_cmd readelf

[[ -n "${HOST_TOOLCHAIN}" ]] || die "--host-toolchain required"
[[ -x "${HOST_TOOLCHAIN}/bin/clang" ]] || die "host clang not found: ${HOST_TOOLCHAIN}/bin/clang"
[[ -n "${INSTALL_DIR}" ]] || die "--install-dir required"
mkdir -p "${INSTALL_DIR}/bin"

ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-}"
[[ -n "${ANDROID_NDK_HOME}" ]] || die "ANDROID_NDK_HOME is not set"

case "${ARCH}" in
  aarch64) TARGET_TRIPLE="aarch64-linux-android"; ANDROID_ABI="arm64-v8a" ;;
  x86_64) TARGET_TRIPLE="x86_64-linux-android"; ANDROID_ABI="x86_64" ;;
  *) die "unsupported arch: ${ARCH}" ;;
esac

SYSROOT="${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/linux-x86_64/sysroot"

ROOT="${ANDROID_TOOLS_ROOT:-/workspace/build/android-tools}"
SRC="${ROOT}/src"
BUILD="${ROOT}/build-${ARCH}-api${API}"
mkdir -p "${SRC}" "${BUILD}"

export CC="${HOST_TOOLCHAIN}/bin/clang --target=${TARGET_TRIPLE}${API} --sysroot=${SYSROOT}"
export CXX="${HOST_TOOLCHAIN}/bin/clang++ --target=${TARGET_TRIPLE}${API} --sysroot=${SYSROOT} -static-libstdc++"
export AR="${HOST_TOOLCHAIN}/bin/llvm-ar"
export RANLIB="${HOST_TOOLCHAIN}/bin/llvm-ranlib"
export STRIP="${HOST_TOOLCHAIN}/bin/llvm-strip"
export LDFLAGS="-fuse-ld=lld -rtlib=compiler-rt -static-libstdc++"

log "ARCH=${ARCH} API=${API} SYSROOT=${SYSROOT}"
log "ANDROID_TOOLS_ROOT=${ROOT}"
log "STRIP_TOOLS_BINARIES=${STRIP_TOOLS_BINARIES}"
log "APPLY_CMAKE_ANDROID_EXEC_PATCH=${APPLY_CMAKE_ANDROID_EXEC_PATCH}"

download() {
  local url="$1"
  local out="$2"
  local tmp="${out}.part"

  mkdir -p "$(dirname "${out}")"
  curl -L --fail \
    --retry 10 --retry-all-errors --retry-delay 2 \
    --connect-timeout 20 --speed-time 30 --speed-limit 1024 \
    -C - -o "${tmp}" "${url}"
  mv -f "${tmp}" "${out}"
}

build_ninja() {
  if [[ -x "${INSTALL_DIR}/bin/ninja" ]]; then
    log "== Build ninja 1.11.1 == (skip, already installed)"
    return 0
  fi

  local version="1.11.1"
  local url="https://github.com/ninja-build/ninja/archive/refs/tags/v${version}.tar.gz"
  local tarball="${SRC}/ninja-${version}.tar.gz"
  local srcdir="${SRC}/ninja-${version}"
  local bdir="${BUILD}/ninja"

  log "== Build ninja ${version} =="
  if [[ ! -d "${srcdir}" ]]; then
    download "${url}" "${tarball}"
    tar -xzf "${tarball}" -C "${SRC}"
  fi

  if [[ "${REBUILD_TOOLS}" == "1" ]]; then
    rm -rf "${bdir}"
  fi
  mkdir -p "${bdir}"

  if [[ ! -f "${bdir}/build.ninja" ]]; then
    cmake -G Ninja -S "${srcdir}" -B "${bdir}" \
      -DCMAKE_BUILD_TYPE=Release \
      -DCMAKE_C_COMPILER="${HOST_TOOLCHAIN}/bin/clang" \
      -DCMAKE_C_COMPILER_TARGET="${TARGET_TRIPLE}${API}" \
      -DCMAKE_SYSROOT="${SYSROOT}" \
      -DCMAKE_EXE_LINKER_FLAGS="${LDFLAGS}"
  fi

  if [[ ! -x "${bdir}/ninja" ]]; then
    ninja -C "${bdir}" -j"$(nproc)"
  fi

  cp -a "${bdir}/ninja" "${INSTALL_DIR}/bin/ninja"
  if [[ "${STRIP_TOOLS_BINARIES}" != "0" ]]; then
    "${STRIP}" --strip-all "${INSTALL_DIR}/bin/ninja" 2>/dev/null || true
  fi
}

build_make() {
  if [[ -x "${INSTALL_DIR}/bin/make" ]]; then
    log "== Build make == (skip, already installed)"
    return 0
  fi

  local version="4.4.1"
  local tarball="${SRC}/make-${version}.tar.gz"
  local srcdir="${SRC}/make-${version}"
  local bdir="${BUILD}/make"

  log "== Build make ${version} =="
  # 需要“发布包”而不是“源码快照”：
  # 快照通常缺少 configure/Makefile.in，会导致后续 configure 失败。
  if [[ ! -x "${srcdir}/configure" ]]; then
    rm -rf "${srcdir}"

    local source_url extracted_root extracted_dir
    for source_url in \
      "https://ftp.gnu.org/gnu/make/make-${version}.tar.gz" \
      "https://ftpmirror.gnu.org/make/make-${version}.tar.gz" \
      "https://codeload.github.com/Distrotech/make/tar.gz/refs/heads/distrotech-make"; do
      log "Try make source: ${source_url}"
      if download "${source_url}" "${tarball}"; then
        # 不能用 head -n 1（set -o pipefail 下会触发 SIGPIPE 误报失败）
        extracted_root="$(tar -tzf "${tarball}" | sed -n '1s#/.*##p')"
        [[ -n "${extracted_root}" ]] || { rm -f "${tarball}"; continue; }
        extracted_dir="${SRC}/${extracted_root}"
        rm -rf "${extracted_dir}"
        tar -xzf "${tarball}" -C "${SRC}"
        if [[ -x "${extracted_dir}/configure" ]]; then
          if [[ "${extracted_dir}" != "${srcdir}" ]]; then
            mv "${extracted_dir}" "${srcdir}"
          fi
          break
        fi
        rm -rf "${extracted_dir}"
      fi
      rm -f "${tarball}"
    done
  fi
  [[ -x "${srcdir}/configure" ]] || die "make source missing configure: ${srcdir}"

  # Android/Bionic does not provide confstr/_CS_PATH, but GNU make uses it as a fallback.
  local job_c="${srcdir}/src/job.c"
  if [[ ! -f "${job_c}" ]]; then
    job_c="${srcdir}/job.c"
  fi
  if [[ -f "${job_c}" ]] && ! grep -q "__ANDROID__" "${job_c}"; then
    python3 - "${job_c}" <<'PY'
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
text = path.read_text(encoding="utf-8")
old = """    /* execvp() will use a default PATH if none is set; emulate that.  */
    if (p == NULL)
      {
        size_t l = confstr (_CS_PATH, NULL, 0);
        if (l)
          {
            char *dp = alloca (l);
            confstr (_CS_PATH, dp, l);
            p = dp;
          }
      }
"""
new = """    /* execvp() will use a default PATH if none is set; emulate that.  */
    if (p == NULL)
      {
#if defined(__ANDROID__)
        p = "/system/bin:/system/xbin";
#else
        size_t l = confstr (_CS_PATH, NULL, 0);
        if (l)
          {
            char *dp = alloca (l);
            confstr (_CS_PATH, dp, l);
            p = dp;
          }
#endif
      }
"""
if old not in text:
    print("warning: make job.c patch skipped (expected block not found)", file=sys.stderr)
    raise SystemExit(0)
path.write_text(text.replace(old, new, 1), encoding="utf-8")
PY
  fi

  if [[ "${REBUILD_TOOLS}" == "1" ]]; then
    rm -rf "${bdir}"
  fi

  if [[ ! -x "${bdir}/install/bin/make" ]]; then
    mkdir -p "${bdir}"
    pushd "${bdir}" >/dev/null
    "${srcdir}/configure" \
      --host="${TARGET_TRIPLE}" \
      --prefix="${bdir}/install" \
      CC="${CC}" \
      CXX="${CXX}" \
      LDFLAGS="${LDFLAGS}" \
      --disable-nls
    make -j"$(nproc)"
    make install
    popd >/dev/null
  fi

  cp -a "${bdir}/install/bin/make" "${INSTALL_DIR}/bin/make"
  if [[ "${STRIP_TOOLS_BINARIES}" != "0" ]]; then
    "${STRIP}" --strip-all "${INSTALL_DIR}/bin/make" 2>/dev/null || true
  fi
}

patch_cmlibuv_android_sources() {
  local libuv_cmake="$1"
  python3 - "$libuv_cmake" <<'PY'
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
lines = path.read_text(encoding="utf-8").splitlines()

def find_block(start_line: str):
    try:
        start = next(i for i, l in enumerate(lines) if l.strip() == start_line)
    except StopIteration:
        return None
    # Find the first matching endif() after start (no nesting here).
    end = next((i for i in range(start + 1, len(lines)) if lines[i].strip() == "endif()"), None)
    if end is None:
        raise SystemExit(f"missing endif() for block: {start_line}")
    return start, end

linux = find_block('if(CMAKE_SYSTEM_NAME STREQUAL "Linux")')
if linux is None:
    raise SystemExit("libuv CMakeLists: Linux block not found")

android = find_block('if(CMAKE_SYSTEM_NAME STREQUAL "Android")')
android_block = [
    'if(CMAKE_SYSTEM_NAME STREQUAL "Android")',
    '  # Android uses the Linux kernel; reuse libuv\'s Linux backend.',
    '  # Do not link -lrt: Android provides clock_gettime in libc.',
    '  list(APPEND uv_libraries dl)',
    '  list(APPEND uv_headers',
    '    include/uv/linux.h',
    '    )',
    '  list(APPEND uv_defines _GNU_SOURCE)',
    '  list(APPEND uv_sources',
    '    src/unix/epoll.c',
    '    src/unix/linux-core.c',
    '    src/unix/linux-inotify.c',
    '    src/unix/linux-syscalls.c',
    '    src/unix/linux-syscalls.h',
    '    src/unix/pthread-fixes.c',
    '    src/unix/procfs-exepath.c',
    '    src/unix/proctitle.c',
    '    src/unix/sysinfo-loadavg.c',
    '    src/unix/sysinfo-memory.c',
    '    )',
    'endif()',
]

changed = False
if android is None:
    # Insert after Linux block.
    _, linux_end = linux
    insert_at = linux_end + 1
    lines[insert_at:insert_at] = [""] + android_block + [""]
    changed = True
else:
    # Ensure pthread-fixes.c is present inside the Android uv_sources list.
    start, end = android
    block = lines[start : end + 1]
    if not any(l.strip() == "src/unix/pthread-fixes.c" for l in block):
        try:
            needle_i = next(i for i, l in enumerate(block) if l.strip() == "src/unix/linux-syscalls.h")
        except StopIteration:
            raise SystemExit("Android libuv block found but linux-syscalls.h line missing")
        block.insert(needle_i + 1, "    src/unix/pthread-fixes.c")
        lines[start : end + 1] = block
        changed = True

if changed:
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")
PY
}

patch_cmake_android_exec_sources() {
  local cm_system_tools="$1"
  [[ -f "${cm_system_tools}" ]] || die "cmSystemTools.cxx not found: ${cm_system_tools}"

  python3 - "${cm_system_tools}" <<'PY'
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
text = path.read_text(encoding="utf-8")

if "TinaIDE Android linker64 exec shim" in text:
    raise SystemExit(0)

anchor_namespace = "namespace {\n"
if anchor_namespace not in text:
    raise SystemExit("cmSystemTools.cxx: namespace anchor not found")

helper = """
#if defined(__ANDROID__)
// TinaIDE Android linker64 exec shim:
// On modern Android, direct exec of app-private binaries may fail with EACCES.
// Wrap absolute-path subprocess commands via system linker64.
static bool cmIsAndroidSystemPath(std::string const& path)
{
  return cmHasPrefix(path, "/system/") || cmHasPrefix(path, "/apex/");
}

static bool cmIsShebangScript(std::string const& path)
{
  cmsys::ifstream fin(path.c_str(), std::ios::in | std::ios::binary);
  if (!fin) {
    return false;
  }
  char sig[2] = { 0, 0 };
  fin.read(sig, 2);
  return fin.gcount() == 2 && sig[0] == '#' && sig[1] == '!';
}

static bool cmIsAndroidLinkerBinary(std::string const& path)
{
  auto const fileName = cmSystemTools::GetFilenameName(path);
  return fileName == "linker64" || fileName == "linker";
}

static bool cmShouldWrapWithAndroidLinker64(std::vector<std::string> const& cmd)
{
  if (cmd.empty()) {
    return false;
  }
  std::string const& program = cmd.front();
  if (program.empty() || program[0] != '/') {
    return false;
  }
  if (cmIsAndroidSystemPath(program)) {
    return false;
  }
  if (cmIsAndroidLinkerBinary(program)) {
    return false;
  }
  if (cmSystemTools::GetFilenameName(program) == "sh") {
    return false;
  }
  if (cmIsShebangScript(program)) {
    return false;
  }
  return true;
}

static std::string cmResolveAndroidLinker64()
{
  std::vector<std::string> const candidates = {
    "/system/bin/linker64",
    "/apex/com.android.runtime/bin/linker64",
    "/system/bin/linker"
  };
  for (auto const& linker : candidates) {
    if (cmSystemTools::FileExists(linker) && access(linker.c_str(), X_OK) == 0) {
      return linker;
    }
  }
  return "/system/bin/linker64";
}

static std::vector<std::string> cmWrapCommandWithAndroidLinker64(
  std::vector<std::string> const& cmd)
{
  if (!cmShouldWrapWithAndroidLinker64(cmd)) {
    return cmd;
  }
  std::vector<std::string> wrapped;
  wrapped.reserve(cmd.size() + 1);
  wrapped.push_back(cmResolveAndroidLinker64());
  wrapped.insert(wrapped.end(), cmd.begin(), cmd.end());
  return wrapped;
}
#endif
"""

text = text.replace(anchor_namespace, anchor_namespace + helper + "\n", 1)

needle_uv = """  cmUVProcessChainBuilder builder;
  builder.SetExternalStream(cmUVProcessChainBuilder::Stream_INPUT, stdin)
    .AddCommand(command);
"""

replacement_uv = """#if defined(__ANDROID__)
  auto launchCommand = cmWrapCommandWithAndroidLinker64(command);
#else
  auto const& launchCommand = command;
#endif
  cmUVProcessChainBuilder builder;
  builder.SetExternalStream(cmUVProcessChainBuilder::Stream_INPUT, stdin)
    .AddCommand(launchCommand);
"""

needle_cmsys = """  std::vector<const char*> argv;
  argv.reserve(command.size() + 1);
  for (std::string const& cmd : command) {
    argv.push_back(cmd.c_str());
  }
"""

replacement_cmsys = """#if defined(__ANDROID__)
  auto launchCommand = cmWrapCommandWithAndroidLinker64(command);
#else
  auto const& launchCommand = command;
#endif
  std::vector<const char*> argv;
  argv.reserve(launchCommand.size() + 1);
  for (std::string const& cmd : launchCommand) {
    argv.push_back(cmd.c_str());
  }
"""

if needle_uv in text:
    text = text.replace(needle_uv, replacement_uv, 1)
elif needle_cmsys in text:
    text = text.replace(needle_cmsys, replacement_cmsys, 1)
else:
    raise SystemExit("cmSystemTools.cxx: RunSingleCommand command block not found")

path.write_text(text, encoding="utf-8")
PY
}

build_cmake() {
  if [[ -x "${INSTALL_DIR}/bin/cmake" ]] && compgen -G "${INSTALL_DIR}/share/cmake-*" >/dev/null; then
    log "== Build cmake 3.28.3 == (skip, already installed)"
    return 0
  fi

  local version="3.28.3"
  local url="https://cmake.org/files/v3.28/cmake-${version}.tar.gz"
  local tarball="${SRC}/cmake-${version}.tar.gz"
  local srcdir="${SRC}/cmake-${version}"
  local bdir="${BUILD}/cmake"

  log "== Build cmake ${version} =="
  if [[ ! -d "${srcdir}" ]]; then
    download "${url}" "${tarball}"
    tar -xzf "${tarball}" -C "${SRC}"
  fi

  local libarchive_android_inc="${srcdir}/Utilities/cmlibarchive/contrib/android/include"
  if [[ ! -f "${libarchive_android_inc}/android_lf.h" ]]; then
    mkdir -p "${libarchive_android_inc}"
    cat > "${libarchive_android_inc}/android_lf.h" <<'EOF'
#pragma once

// Large file support shim for Android (libarchive).
// On 64-bit Android (LP64) off_t is already 64-bit and the *64 APIs are aliases.
// On 32-bit Android, map common calls to their *64 variants.

#if !defined(__LP64__)
#define open open64
#define lseek lseek64
#define fstat fstat64
#define stat stat64
#define lstat lstat64
#define pread pread64
#define pwrite pwrite64
#endif
EOF
  fi

  # Ensure cmlibuv builds on Android by injecting Android source list + pthread fixes.
  patch_cmlibuv_android_sources "${srcdir}/Utilities/cmlibuv/CMakeLists.txt"
  if [[ "${APPLY_CMAKE_ANDROID_EXEC_PATCH}" != "0" ]]; then
    # Wrap CMake internal subprocess exec() with linker64 on Android.
    patch_cmake_android_exec_sources "${srcdir}/Source/cmSystemTools.cxx"
  else
    log "Skip CMake Android exec source patch."
  fi

  # cmAffinity uses pthread_getaffinity_np on Linux, but Android's pthread headers don't provide it.
  local cm_affinity="${srcdir}/Source/cmAffinity.cxx"
  if [[ -f "${cm_affinity}" ]]; then
    python3 - "${cm_affinity}" <<'PY'
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
text = path.read_text(encoding="utf-8")
text2 = text.replace(
    "#  elif defined(__linux__) || defined(__FreeBSD__)",
    "#  elif (defined(__linux__) && !defined(__ANDROID__)) || defined(__FreeBSD__)",
)
path.write_text(text2, encoding="utf-8")
PY
  fi

  # cmlibuv process.c calls pthread_setaffinity_np on Linux paths, but Android NDK
  # headers do not always expose this API. Exclude Android from that block.
  local uv_process="${srcdir}/Utilities/cmlibuv/src/unix/process.c"
  if [[ -f "${uv_process}" ]]; then
    python3 - "${uv_process}" <<'PY'
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
text = path.read_text(encoding="utf-8")
text2 = text.replace(
    "#if defined(__linux__) || defined(__FreeBSD__)",
    "#if (defined(__linux__) && !defined(__ANDROID__)) || defined(__FreeBSD__)",
)
path.write_text(text2, encoding="utf-8")
PY
  fi

  local ndk_toolchain="${ANDROID_NDK_HOME}/build/cmake/android.toolchain.cmake"
  [[ -f "${ndk_toolchain}" ]] || die "NDK CMake toolchain not found: ${ndk_toolchain}"

  if [[ "${REBUILD_TOOLS}" == "1" ]]; then
    rm -rf "${bdir}"
  fi
  mkdir -p "${bdir}"

  if [[ ! -f "${bdir}/build.ninja" ]]; then
    cmake -G Ninja -S "${srcdir}" -B "${bdir}" \
      -DCMAKE_BUILD_TYPE=Release \
      -DCMAKE_INSTALL_PREFIX="${bdir}/install" \
      -DCMAKE_TOOLCHAIN_FILE="${ndk_toolchain}" \
      -DANDROID_ABI="${ANDROID_ABI}" \
      -DANDROID_PLATFORM="android-${API}" \
      -DANDROID_STL="c++_static" \
      -DBUILD_TESTING=OFF \
      -DBUILD_CursesDialog=OFF \
      -DBUILD_QtDialog=OFF
  fi

  if [[ ! -x "${bdir}/bin/cmake" ]]; then
    ninja -C "${bdir}" -j"$(nproc)"
  fi
  cmake --install "${bdir}" --prefix "${INSTALL_DIR}"

  if [[ "${STRIP_TOOLS_BINARIES}" != "0" ]]; then
    # cmake --install emits ctest/cpack alongside cmake; stripping only cmake
    # left ~360MB of debug_info in the x86_64 package (ctest 200MB, cpack 185MB).
    for tool in cmake ctest cpack; do
      [[ -f "${INSTALL_DIR}/bin/${tool}" ]] || continue
      "${STRIP}" --strip-all "${INSTALL_DIR}/bin/${tool}" 2>/dev/null || true
    done
  fi

  compgen -G "${INSTALL_DIR}/share/cmake-*" >/dev/null || die "cmake share/cmake-* missing in install dir: ${INSTALL_DIR}/share"
}

build_pkgconf() {
  if [[ -x "${INSTALL_DIR}/bin/pkgconf" ]] && [[ -e "${INSTALL_DIR}/bin/pkg-config" ]]; then
    log "== Build pkgconf 2.2.0 == (skip, already installed)"
    return 0
  fi

  local version="2.2.0"
  local url="https://distfiles.ariadne.space/pkgconf/pkgconf-${version}.tar.xz"
  local tarball="${SRC}/pkgconf-${version}.tar.xz"
  local srcdir="${SRC}/pkgconf-${version}"
  local bdir="${BUILD}/pkgconf"

  log "== Build pkgconf ${version} =="
  if [[ ! -d "${srcdir}" ]]; then
    download "${url}" "${tarball}"
    tar -xJf "${tarball}" -C "${SRC}"
  fi

  if [[ "${REBUILD_TOOLS}" == "1" ]]; then
    rm -rf "${bdir}"
  fi

  if [[ ! -f "${bdir}/Makefile" ]]; then
    mkdir -p "${bdir}"
    pushd "${bdir}" >/dev/null
    "${srcdir}/configure" \
      --host="${TARGET_TRIPLE}" \
      --prefix="${bdir}/install" \
      --disable-shared \
      --enable-static
    popd >/dev/null
  fi

  if [[ ! -x "${bdir}/pkgconf" ]]; then
    pushd "${bdir}" >/dev/null
    make -j"$(nproc)"
    popd >/dev/null
  fi

  pushd "${bdir}" >/dev/null
  make install prefix="${INSTALL_DIR}"
  popd >/dev/null

  if [[ "${STRIP_TOOLS_BINARIES}" != "0" ]]; then
    "${STRIP}" --strip-all "${INSTALL_DIR}/bin/pkgconf" 2>/dev/null || true
  fi
  ln -sf pkgconf "${INSTALL_DIR}/bin/pkg-config"

  if readelf -d "${INSTALL_DIR}/bin/pkgconf" 2>/dev/null | grep -q "NEEDED.*libpkgconf\\.so"; then
    die "pkgconf links to libpkgconf.so (missing at runtime). Rebuild with --disable-shared/--enable-static."
  fi
}

build_ninja
build_make
build_cmake
build_pkgconf

log "Installed:"
ls -lh "${INSTALL_DIR}/bin/ninja" "${INSTALL_DIR}/bin/make" "${INSTALL_DIR}/bin/cmake" "${INSTALL_DIR}/bin/pkgconf" "${INSTALL_DIR}/bin/pkg-config"
