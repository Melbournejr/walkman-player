#!/usr/bin/env bash
set -euo pipefail

: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to Android NDK r26b}"
repo=$(cd "$(dirname "$0")/.." && pwd)
toolchain="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64"
build=$(mktemp -d)
trap 'rm -rf "$build"' EXIT
revision=3a0867c2bfda4a4d4309ca1a8cbdc6175e67f587 # FFmpeg n7.1.5

git -C "$build" init -q
git -C "$build" fetch --quiet --depth 1 https://github.com/FFmpeg/FFmpeg.git "$revision"
git -C "$build" checkout --quiet FETCH_HEAD
test "$(git -C "$build" rev-parse HEAD)" = "$revision"
cd "$build"

./configure \
    --prefix="$build/install" \
    --enable-cross-compile --target-os=android --arch=aarch64 --cpu=armv8-a \
    --cc="$toolchain/bin/aarch64-linux-android26-clang" \
    --cxx="$toolchain/bin/aarch64-linux-android26-clang++" \
    --ar="$toolchain/bin/llvm-ar" --nm="$toolchain/bin/llvm-nm" \
    --ranlib="$toolchain/bin/llvm-ranlib" --strip="$toolchain/bin/llvm-strip" \
    --enable-shared --disable-static --disable-programs --disable-doc \
    --disable-avdevice --disable-swscale --disable-avfilter --disable-network \
    --disable-everything --disable-autodetect \
    --enable-avutil --enable-swresample --enable-avcodec --enable-avformat \
    --enable-demuxer=dsf,iff --enable-decoder=dsd_lsbf,dsd_msbf,dsd_lsbf_planar,dsd_msbf_planar,dst \
    --extra-ldflags="-Wl,-z,max-page-size=16384"

make -j"${FFMPEG_JOBS:-$(nproc)}"
make install

mkdir -p "$repo/app/src/main/cpp/include" "$repo/app/src/main/jniLibs/arm64-v8a"
cp -r "$build/install/include/." "$repo/app/src/main/cpp/include/"
for name in avutil swresample avcodec avformat; do
    cp -L "$build/install/lib/lib$name.so" "$repo/app/src/main/jniLibs/arm64-v8a/"
done
