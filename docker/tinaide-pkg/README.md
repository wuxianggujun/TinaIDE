# TinaIDE Package Builder

通用 Android NDK 原生库构建系统，用于编译各种依赖库的静态/动态版本。

## 特性

- **统一构建环境**：基于 Docker，确保构建可重复
- **NDK 缓存复用**：NDK 层独立缓存，避免重复下载
- **源码持久化**：使用 Docker volume 保存源码，支持增量构建
- **极致压缩**：使用 xz -9e 最大压缩率
- **16KB 页对齐**：满足 Android 15+ 要求
- **多架构支持**：arm64-v8a, armeabi-v7a, x86_64, x86

## 目录结构

```
tinaide-pkg/
├── Dockerfile           # 构建环境镜像
├── build-pkg.ps1        # Windows 编排脚本
├── build.sh             # 容器内主构建脚本
├── build-common.sh      # 公共函数库
├── clean.ps1            # 清理脚本
├── libs/                # 各库的构建脚本
│   ├── build-zlib.sh
│   ├── build-openssl.sh
│   ├── build-curl.sh
│   ├── build-libssh2.sh
│   ├── build-libgit2.sh
│   ├── build-pcre2.sh
│   ├── build-sdl2.sh
│   ├── build-sdl3.sh
│   ├── build-sdl-extension.sh
│   ├── build-raylib.sh
│   └── build-box2d.sh
├── package-native-library.sh # 生成通用兼容包和按 ABI 拆分的 Registry 产物
├── package-sdl2.sh     # 生成 SDL2 通用兼容包和按 ABI 拆分产物
├── package-for-assets.sh # 打包为 assets 格式
└── output/              # 构建输出
    ├── zlib/
    │   └── arm64-v8a/
    │       └── zlib-arm64-v8a-static.tar.xz
    └── ...
```

## 快速开始

### 前置要求

- Windows 10/11 + PowerShell 5.1+
- Docker Desktop (启用 WSL2 后端)
- **推荐**: 复用现有的 `tinaide-toolchain-builder` 容器（已包含 NDK + CMake）

### 方式 1: 复用现有容器（推荐）

如果你已经有运行中的 `tinaide-toolchain-builder` 容器：

```bash
# 检查容器是否运行
docker ps | grep tinaide-toolchain-builder

# 复制构建脚本到容器
docker cp build-common.sh tinaide-toolchain-builder:/build/
docker cp libs/build-sdl3.sh tinaide-toolchain-builder:/build/libs/

# 在容器中构建
docker exec tinaide-toolchain-builder bash -c "
  export ANDROID_NDK_HOME=/opt/android-ndk-r27
  cd /build/libs
  bash build-sdl3.sh arm64-v8a shared
"

# 复制产物到本地
docker cp tinaide-toolchain-builder:/output/sdl3/arm64-v8a/sdl3-arm64-v8a-shared.tar.xz ./output/sdl3/arm64-v8a/
```

### 方式 2: 使用 PowerShell 脚本

构建 Docker 镜像并编译（首次运行需要下载 NDK）：

```powershell
# 构建 zlib 静态库 (arm64-v8a)
.\build-pkg.ps1 -Library zlib -Arch arm64-v8a -LinkType static

# 构建 openssl 动态库 (x86_64)
.\build-pkg.ps1 -Library openssl -Arch x86_64 -LinkType shared
```

### 构建所有库

```powershell
# 构建所有支持静态产物的库（自动跳过 shared-only 库）
.\build-pkg.ps1 -Library all -Arch all -LinkType static

# 构建所有库的 arm64 支持产物（按库过滤链接类型）
.\build-pkg.ps1 -Library all -Arch arm64-v8a -LinkType all
```

### 查看可用选项

```powershell
.\build-pkg.ps1 -Help
.\build-pkg.ps1 -List
```

### 清理

```powershell
# 清理所有 (输出 + Docker 资源)
.\clean.ps1 -All

# 只清理输出
.\clean.ps1 -Output

# 只清理 Docker 资源
.\clean.ps1 -Docker
```

## 支持的库

| 库名 | 版本 | 依赖 | 说明 |
|------|------|------|------|
| zlib | 1.3.1 | 无 | 压缩库 |
| openssl | 3.2.1 | 无 | TLS/SSL 库 |
| pcre2 | 10.43 | 无 | 正则表达式 |
| curl | 8.6.0 | openssl (可选) | HTTP 客户端 |
| libssh2 | 1.11.0 | openssl, zlib | SSH2 协议 |
| libgit2 | 1.7.2 | openssl, libssh2, zlib | Git 操作库 |
| sdl2 | 2.32.10.2（上游 2.32.10） | 无 | SDL2 Android runtime（TinaIDE JNI 包名） |
| sdl2-image | 2.8.12 | sdl2 | SDL2 图像加载库（内建解码器） |
| sdl2-ttf | 2.24.0 | sdl2 | SDL2 TrueType 字体渲染库（内置 FreeType） |
| sdl2-mixer | 2.8.2 | sdl2 | SDL2 音频混音库（内建常用解码器） |
| sdl2-net | 2.4.0 | sdl2 | SDL2 TCP/UDP 网络库 |
| sdl3 | 3.5.0 | 无 | 跨平台多媒体库 |
| sdl3-image | 3.4.4 | sdl3 | SDL3 图像加载库（内建解码器） |
| sdl3-ttf | 3.2.2 | sdl3 | SDL3 TrueType 字体渲染库（内置 FreeType） |
| sdl3-mixer | 3.2.4 | sdl3 | SDL3 音频混音库（内建常用解码器） |
| sdl3-net | 3.2.0 | sdl3 | SDL3 网络库 |
| raylib | 6.0 | 无 | 游戏开发库（动态库） |
| box2d | 3.1.1 | 无 | 2D 物理引擎（静态库） |

### 依赖关系图

```
zlib ───────────────────────────────┐
                                    ├──> libssh2 ──> libgit2
openssl ────────────────────────────┘
   └──> curl（可选）

sdl2 ──> sdl2-image / sdl2-ttf / sdl2-mixer / sdl2-net
sdl3 ──> sdl3-image / sdl3-ttf / sdl3-mixer / sdl3-net

pcre2 / raylib / box2d（独立）
```

### 推荐构建顺序

如需构建 libgit2（完整功能），按以下顺序：

1. zlib
2. openssl
3. libssh2
4. libgit2

## 输出格式

每个库的输出包含：

```
{库名}-{架构}-{链接类型}.tar.xz
```

包内结构：

```
├── include/          # 头文件
│   └── *.h
└── lib/              # 库文件
    ├── *.a           # 静态库
    └── *.so*         # 动态库
```

### 解压使用

```bash
# 解压到项目目录
tar -xJf zlib-arm64-v8a-static.tar.xz -C /path/to/project/libs/arm64-v8a/
```

## 高级用法

### 构建模式

```powershell
# 增量模式 (默认): 复用已有镜像和源码
.\build-pkg.ps1 -Mode incremental

# 重建模式: 重建 Docker 镜像，保留源码
.\build-pkg.ps1 -Mode rebuild

# 清理模式: 完全清理后重新构建
.\build-pkg.ps1 -Mode clean
```

### 进入容器调试

```powershell
docker run -it --rm `
    -v tinaide-pkg-source:/build/src `
    -v ${PWD}/output:/output `
    tinaide-pkg-builder `
    /bin/bash
```

### 手动构建单个库

```bash
# 在容器内
source /build/build-common.sh
setup_toolchain arm64-v8a
bash /build/libs/build-zlib.sh arm64-v8a static
```

## 添加新库

1. 创建 `libs/build-{库名}.sh`
2. 参考现有脚本结构
3. 在 `build.sh` 的 `AVAILABLE_LIBS` 数组中添加库名
4. 在 `build-pkg.ps1` 的 `ValidateSet` 中添加库名

模板：

```bash
#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/../build-common.sh"

# 配置
LIB_VERSION="x.y.z"
LIB_URL="https://..."

# 参数
ARCH=${1:-arm64-v8a}
LINK_TYPE=${2:-static}

# 设置工具链
setup_toolchain "$ARCH"

# 下载、配置、编译、打包...
```

## 技术细节

### NDK 配置

- NDK 版本: r27c
- 最低 API: 28（与 TinaIDE app minSdk 保持一致）
- 工具链: LLVM/Clang
- 页对齐: 16KB (`-Wl,-z,max-page-size=16384`)

### 编译标志

```bash
CFLAGS="-O2 -fPIC"
CXXFLAGS="-O2 -fPIC"
LDFLAGS="-Wl,-z,max-page-size=16384"
```

### 压缩策略

使用 xz 极限压缩：

```bash
tar -cf - . | xz -9e --threads=0 > output.tar.xz
```

- `-9e`: 最大压缩级别 + 极限模式
- `--threads=0`: 使用所有 CPU 核心

### Docker 层优化

1. **Layer 1**: 系统配置 + 镜像源
2. **Layer 2**: NDK 下载 (~633 MB, 独立缓存)
3. **Layer 3**: 构建工具
4. **Layer 4**: 脚本复制

## 常见问题

### Q: 首次构建很慢？

A: 首次需要下载 NDK (~633 MB)，后续构建会使用缓存。

### Q: 如何减小包体积？

A:
- 使用静态库时，链接器会只包含用到的符号
- 动态库会自动 strip 调试符号
- xz -9e 已是最大压缩

### Q: 构建失败如何调试？

A: 进入容器手动执行：
```powershell
docker run -it --rm tinaide-pkg-builder /bin/bash
```

### Q: 如何更新库版本？

A: 修改对应 `libs/build-{库名}.sh` 中的版本号和 URL。

---

## SDL2 Android 包

SDL2 必须使用本目录的构建脚本，不能直接发布官方 Android `.so`。TinaIDE
同时打包 SDL2 与 SDL3 Java glue，因此 SDL2 native JNI 注册路径已从
`org/libsdl/app` 重定位为 `org/libsdl2/app`。

```powershell
# registry 至少构建 TinaIDE 当前支持的两个 ABI
.\build-pkg.ps1 -Library sdl2 -Arch arm64-v8a -LinkType shared
.\build-pkg.ps1 -Library sdl2 -Arch x86_64 -LinkType shared
```

```bash
# 合并 ABI，生成 Registry 包版本 2.32.10.2 的通用及 ABI 独立归档
bash ./package-sdl2.sh "arm64-v8a x86_64"
```

最终包 ID 固定为 `sdl2`，包含 `include/SDL2`、`lib/<abi>/libSDL2.so`、
`lib/cmake/SDL2`、`pkgconfig/sdl2.pc`、`LICENSE.txt` 与 `package.json`。
Java glue、头文件和 native 库必须保持同一 SDL release；当前固定为
`release-2.32.10` / `5d249570393f7a37e037abf22cd6012a4cc56a71`。Registry
包版本为 `2.32.10.2`，用于让已安装旧 `2.32.10` 包的客户端识别 HID JNI
重定位修复；归档内 `upstreamVersion` 仍准确记录为 `2.32.10`。

## SDL3 完整使用示例

### 1. 编译 SDL3

```powershell
# 编译 arm64-v8a 动态库（推荐）
.\build-pkg.ps1 -Library sdl3 -Arch arm64-v8a -LinkType shared

# 编译所有架构
.\build-pkg.ps1 -Library sdl3 -Arch all -LinkType shared
```

### 2. 打包为 Assets

```bash
# 打包单架构（推荐，减小 APK 体积）
# 默认使用 xz 格式（最高压缩率）
./package-for-assets.sh sdl3 arm64-v8a

# 指定压缩格式
./package-for-assets.sh sdl3 arm64-v8a xz     # tar.xz (默认，1.2 MB)
./package-for-assets.sh sdl3 arm64-v8a zstd   # tar.zst (快速)
./package-for-assets.sh sdl3 arm64-v8a gz     # tar.gz (兼容)
./package-for-assets.sh sdl3 arm64-v8a zip    # zip (1.6 MB，不推荐)

# 打包多架构
./package-for-assets.sh sdl3 "arm64-v8a armeabi-v7a" xz
```

**压缩格式对比**：
- `xz`: 最高压缩率（1.2 MB），解压稍慢，**推荐**
- `zstd`: 快速压缩/解压，压缩率略低
- `gz`: 兼容性最好，压缩率中等
- `zip`: Windows 友好，但压缩率最低（1.6 MB）

产物：`app/src/main/assets/bundled_packages/sdl3.tar.xz` (~1.2 MB)

### 3. 应用集成

TinaIDE 已内置自动安装器，支持多种压缩格式：

**支持的格式**：
- `.tar.xz` - 最高压缩率（推荐）
- `.tar.zst` - 快速压缩/解压
- `.tar.gz` - 兼容性最好
- `.zip` - Windows 友好

**工作流程**：
1. 将打包好的文件放到 `app/src/main/assets/bundled_packages/`
2. 应用启动时自动扫描并解压到 `filesDir/installed-packages/sdl3/`
3. 自动解析 `package.json` 元数据并更新安装状态
4. 已安装的包会跳过（幂等性）

SDL3 的打包要点以本 README 当前章节为准；仓库不再单独维护额外的
`docs/` 专题长文档。

### 4. 在用户项目中使用

**CMakeLists.txt**:
```cmake
set(SDL3_ROOT /data/data/com.wuxianggujun.tinaide/files/installed-packages/sdl3)
target_include_directories(my_app PRIVATE ${SDL3_ROOT}/include)
target_link_libraries(my_app PRIVATE ${SDL3_ROOT}/lib/${ANDROID_ABI}/libSDL3.so)
```

**Clang 命令行**:
```bash
clang++ -I/data/data/.../sdl3/include \
        -L/data/data/.../sdl3/lib/arm64-v8a \
        -lSDL3 main.cpp -o my_app
```

### 5. 扩展库与游戏开发库

使用相同流程可编译并发布 SDL2/SDL3 生态系统库：

- **SDL2_image / SDL3_image**：图像加载，使用内建后端，不引入额外动态 codec 依赖
- **SDL2_mixer / SDL3_mixer**：WAV、Ogg、MP3、FLAC 音频混音，codec 静态内置
- **SDL2_ttf / SDL3_ttf**：TrueType 字体渲染，FreeType 静态内置
- **SDL2_net / SDL3_net**：TCP/UDP 网络能力
- **raylib**：游戏开发动态库；保持 shared 构建，必须导出 `ANativeActivity_onCreate`、保留未定义的普通 `main`，且不得依赖 SDL
- **Box2D**：2D 物理引擎静态库

Registry 打包会保留一个供旧客户端使用的双 ABI 通用包，并为 `arm64-v8a` 与
`x86_64` 分别生成独立归档。新客户端按当前 App ABI 下载独立归档，不再让 ARM64
用户额外下载 x86_64 库；每个独立归档仍包含头文件、对应的 `lib/<abi>`、
CMake package、pkg-config 和仅声明当前 ABI 的 `package.json`：

```powershell
# 在构建镜像中运行，复用容器内的 readelf、tar 和 xz
docker run --rm --entrypoint /build/package-native-library.sh `
    -v "${PWD}\output:/output" tinaide-pkg-builder sdl2-image
docker run --rm --entrypoint /build/package-native-library.sh `
    -v "${PWD}\output:/output" tinaide-pkg-builder box2d
```

以 `raylib` 为例，输出目录包含：

```text
output/registry/raylib/6.0/raylib.tar.xz              # 旧客户端兼容
output/registry/raylib/6.0/raylib-arm64-v8a.tar.xz    # ARM64
output/registry/raylib/6.0/raylib-x86_64.tar.xz       # x86_64
```

---

## 参考文档

- [Android NDK 官方文档](https://developer.android.com/ndk)
