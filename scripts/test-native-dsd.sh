#!/usr/bin/env bash
set -euo pipefail

: "${JAVA_HOME:?Use a JDK 17+ installation}"
: "${FFMPEG_HOST_PREFIX:?Point to a Linux FFmpeg 7.1.5 installation with headers and shared libraries}"
repo=$(cd "$(dirname "$0")/.." && pwd)
build=$(mktemp -d)
trap 'rm -rf "$build"' EXIT
cd "$build"
python3 "$repo/scripts/tests/generate-dsd-fixtures.py"
curl --fail --silent --show-error --location \
    https://fate-suite.ffmpeg.org/dst/dst-64fs44-2ch.dff -o fixtures/dst.dff
printf '%s  %s\n' 29d305bd19731ce079324ed14ac17254f254115d7787b5646a943e367b238ed0 fixtures/dst.dff | sha256sum --check
g++ -std=c++17 -Wall -Wextra -Werror -shared -fPIC \
    -I"$FFMPEG_HOST_PREFIX/include" -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/linux" \
    "$repo/app/src/main/cpp/walkman_ffmpeg_jni.cpp" \
    -L"$FFMPEG_HOST_PREFIX/lib" -lavformat -lavcodec -lswresample -lavutil -o libwalkman_host.so
LD_LIBRARY_PATH="$FFMPEG_HOST_PREFIX/lib" "$JAVA_HOME/bin/java" \
    --add-opens java.base/java.io=ALL-UNNAMED -Dwalkman.native="$build/libwalkman_host.so" \
    "$repo/scripts/tests/FFmpegNativeDsdDecoder.java"
