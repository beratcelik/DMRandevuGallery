#!/bin/bash
#
# Builds whisper.cpp as an xcframework for iOS, device and simulator.
#
# whisper.cpp ships build-xcframework.sh, which also builds macOS, visionOS and tvOS. This is the
# same build with the same flags, for the two platforms this app runs on, so it takes a couple of
# minutes rather than a quarter of an hour.
#
# Run it after checking out or updating third_party/whisper.cpp:
#
#   ios/Scripts/build-whisper-xcframework.sh
#
# The result lands in ios/Frameworks/whisper.xcframework, which is not committed — it is a build
# product of a pinned submodule and rebuilding it is one command.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SRC="$ROOT/third_party/whisper.cpp"
OUT="$ROOT/ios/Frameworks"
MIN_IOS=18.0

# cmake is not always on the PATH on a Mac set up for Android work; the SDK ships one.
CMAKE=$(command -v cmake || true)
if [ -z "$CMAKE" ]; then
    CMAKE=$(ls -1 "$HOME"/Library/Android/sdk/cmake/*/bin/cmake 2>/dev/null | tail -1 || true)
fi
if [ -z "$CMAKE" ]; then
    echo "cmake not found. Install it (brew install cmake) or via the Android SDK." >&2
    exit 1
fi

if [ ! -f "$SRC/CMakeLists.txt" ]; then
    echo "whisper.cpp is missing. Run: git submodule update --init --recursive" >&2
    exit 1
fi

# Plain makefiles rather than the Xcode generator. These are static libraries and need no
# signing, but Xcode tries to sign cmake's compiler-probe target anyway and fails on a missing
# Info.plist — an error that says nothing about the actual cause.
COMMON=(
    -G "Unix Makefiles"
    -DCMAKE_SYSTEM_NAME=iOS
    -DBUILD_SHARED_LIBS=OFF
    -DWHISPER_BUILD_EXAMPLES=OFF
    -DWHISPER_BUILD_TESTS=OFF
    -DWHISPER_BUILD_SERVER=OFF
    # Metal, embedded so there is no shader file to ship alongside. This is the one real
    # difference from the Android build, which is CPU only: on the phone it is the difference
    # between a recognition pass you wait for and one you give up on.
    -DGGML_METAL=ON
    -DGGML_METAL_EMBED_LIBRARY=ON
    -DGGML_BLAS_DEFAULT=ON
    -DGGML_NATIVE=OFF
    -DGGML_OPENMP=OFF
)
FLAGS="-Wno-macro-redefined -Wno-shorten-64-to-32 -Wno-unused-command-line-argument"

cd "$SRC"
rm -rf build-ios-device build-ios-sim

echo "Building for iOS device..."
"$CMAKE" -B build-ios-device "${COMMON[@]}" \
    -DCMAKE_OSX_DEPLOYMENT_TARGET=$MIN_IOS \
    -DCMAKE_OSX_SYSROOT=iphoneos \
    -DCMAKE_OSX_ARCHITECTURES="arm64" \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_C_FLAGS="$FLAGS" -DCMAKE_CXX_FLAGS="$FLAGS" \
    -S . > /dev/null
"$CMAKE" --build build-ios-device -j "$(sysctl -n hw.ncpu)"

# arm64 only: this Xcode no longer ships an x86_64 simulator runtime, and asking for it fails
# with a missing-compiler error that says nothing about the cause.
echo "Building for the simulator..."
"$CMAKE" -B build-ios-sim "${COMMON[@]}" \
    -DCMAKE_OSX_DEPLOYMENT_TARGET=$MIN_IOS \
    -DCMAKE_OSX_SYSROOT=iphonesimulator \
    -DCMAKE_OSX_ARCHITECTURES="arm64" \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_C_FLAGS="$FLAGS" -DCMAKE_CXX_FLAGS="$FLAGS" \
    -S . > /dev/null
"$CMAKE" --build build-ios-sim -j "$(sysctl -n hw.ncpu)"

# One static library per platform, since whisper and ggml build as several.
bundle() {
    local dir=$1 config=$2 out=$3
    local libs
    libs=$(find "$dir" -name "*.a")
    [ -n "$libs" ] || { echo "no libraries in $dir/$config" >&2; exit 1; }
    rm -f "$out"
    # shellcheck disable=SC2086
    libtool -static -o "$out" $libs 2>/dev/null
}

rm -rf "$OUT"
mkdir -p "$OUT/device/Headers" "$OUT/sim/Headers"
bundle build-ios-device Release "$OUT/device/libwhisper.a"
bundle build-ios-sim Release "$OUT/sim/libwhisper.a"
for h in include/whisper.h ggml/include/ggml.h ggml/include/ggml-alloc.h \
         ggml/include/ggml-backend.h ggml/include/ggml-cpu.h ggml/include/ggml-metal.h; do
    [ -f "$h" ] && cp "$h" "$OUT/device/Headers/" && cp "$h" "$OUT/sim/Headers/"
done

xcodebuild -create-xcframework \
    -library "$OUT/device/libwhisper.a" -headers "$OUT/device/Headers" \
    -library "$OUT/sim/libwhisper.a" -headers "$OUT/sim/Headers" \
    -output "$OUT/whisper.xcframework" > /dev/null

rm -rf "$OUT/device" "$OUT/sim"
echo "Built $OUT/whisper.xcframework"
