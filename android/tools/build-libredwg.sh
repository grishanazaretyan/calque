#!/bin/sh
# Builds the LibreDWG static libraries that Calque links (app/src/main/cpp/libredwg/<abi>/libredwg.a).
# LibreDWG is GPL-3.0-or-later: https://github.com/LibreDWG/libredwg
# Usage: tools/build-libredwg.sh /path/to/android-ndk /path/to/cmake/bin
set -e
NDK=$1; CM=$2
COMMIT=$(sed -n 's/^LibreDWG commit \([0-9a-f]*\).*/\1/p' app/src/main/cpp/libredwg/VERSION.txt)
WORK=${TMPDIR:-/tmp}/calque-libredwg
rm -rf "$WORK" && git clone -q https://github.com/LibreDWG/libredwg "$WORK" && git -C "$WORK" checkout -q "$COMMIT"
for ABI in arm64-v8a armeabi-v7a x86_64; do
  B="$WORK/build-$ABI"; mkdir -p "$B"
  (cd "$B" && "$CM/cmake" .. -G Ninja -DCMAKE_MAKE_PROGRAM="$CM/ninja" \
     -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" -DANDROID_ABI=$ABI -DANDROID_PLATFORM=android-24 \
     -DLIBREDWG_LIBONLY=On -DBUILD_SHARED_LIBS=Off -DDISABLE_WERROR=On -DLIBREDWG_DISABLE_JSON=On -DENABLE_LTO=Off \
     -DCMAKE_BUILD_TYPE=MinSizeRel -DCMAKE_POSITION_INDEPENDENT_CODE=On && "$CM/ninja" -j4 redwg)
  mkdir -p app/src/main/cpp/libredwg/$ABI
  "$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" --strip-debug "$B/libredwg.a" -o app/src/main/cpp/libredwg/$ABI/libredwg.a
done
