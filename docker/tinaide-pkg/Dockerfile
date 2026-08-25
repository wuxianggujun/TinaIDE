# TinaIDE Package Builder - 通用 Android NDK 构建环境
# 用于编译 Android 平台的静态库/动态库
# 支持架构: arm64-v8a, armeabi-v7a, x86_64, x86

FROM ubuntu:24.04

ARG ANDROID_NDK_VERSION=r27c
ARG ANDROID_API=21

# ===== Layer 1: 基础系统配置 (镜像源) =====
RUN sed -i 's@//.*archive.ubuntu.com@//mirrors.tuna.tsinghua.edu.cn@g' /etc/apt/sources.list.d/ubuntu.sources && \
    sed -i 's@//.*security.ubuntu.com@//mirrors.tuna.tsinghua.edu.cn@g' /etc/apt/sources.list.d/ubuntu.sources && \
    apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates \
    curl \
    unzip \
    && rm -rf /var/lib/apt/lists/*

# ===== Layer 2: 下载并安装 Android NDK (约 633 MB, 独立层便于缓存) =====
ENV ANDROID_NDK_HOME=/opt/android-ndk-${ANDROID_NDK_VERSION}
ENV ANDROID_NDK_ROOT=${ANDROID_NDK_HOME}
ENV PATH="${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/linux-x86_64/bin:${PATH}"

RUN echo "Downloading Android NDK ${ANDROID_NDK_VERSION}..." && \
    NDK_URL="https://dl.google.com/android/repository/android-ndk-${ANDROID_NDK_VERSION}-linux.zip" && \
    NDK_MIRROR="https://mirrors.aliyun.com/android/repository/android-ndk-${ANDROID_NDK_VERSION}-linux.zip" && \
    curl -fsSL -o /tmp/ndk.zip "${NDK_MIRROR}" || curl -fsSL -o /tmp/ndk.zip "${NDK_URL}" && \
    unzip -q /tmp/ndk.zip -d /opt && \
    rm /tmp/ndk.zip && \
    echo "NDK installed at ${ANDROID_NDK_HOME}"

# ===== Layer 3: 安装构建工具 =====
RUN apt-get update && apt-get install -y --no-install-recommends \
    git \
    make \
    cmake \
    ninja-build \
    autoconf \
    automake \
    libtool \
    pkg-config \
    python3 \
    python3-pip \
    wget \
    xz-utils \
    zstd \
    file \
    patch \
    bison \
    flex \
    && rm -rf /var/lib/apt/lists/*

# ===== Layer 4: 设置工作目录和环境变量 =====
WORKDIR /build
ENV ANDROID_API=${ANDROID_API}

# 架构映射表 (在脚本中使用)
# arm64-v8a   -> aarch64-linux-android
# armeabi-v7a -> armv7a-linux-androideabi
# x86_64      -> x86_64-linux-android
# x86         -> i686-linux-android

# ===== Layer 5: 复制构建脚本 =====
COPY build-common.sh /build/
COPY build.sh /build/
COPY package-native-library.sh /build/
COPY libs/ /build/libs/

RUN chmod +x /build/build-common.sh /build/build.sh /build/package-native-library.sh && \
    chmod +x /build/libs/*.sh 2>/dev/null || true

# 默认入口点
ENTRYPOINT ["/bin/bash"]
