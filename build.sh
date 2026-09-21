#!/usr/bin/env bash
set -e

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$PROJECT_DIR"

echo "=== Building HyperOS Status Bar Battery Monitor (Zygisk) ==="

SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/home/zze/Android/Sdk}}"
NDK_ROOT="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
if [ -z "$NDK_ROOT" ] || [ ! -d "$NDK_ROOT" ]; then
    NDK_ROOT=$(ls -d "$SDK_ROOT/ndk/"* 2>/dev/null | sort -V | tail -n 1)
fi

TOOLCHAIN=$(ls -d "$NDK_ROOT/toolchains/llvm/prebuilt/"*/bin 2>/dev/null | tail -n 1)
CLANG=$(ls -d "$TOOLCHAIN"/aarch64-linux-android*-clang++ 2>/dev/null | sort -V | tail -n 1)
STRIP="$TOOLCHAIN/llvm-strip"
D8=$(ls -d "$SDK_ROOT/build-tools/"*/d8 2>/dev/null | sort -V | tail -n 1)
ANDROID_JAR=$(ls -d "$SDK_ROOT/platforms/android-"[0-9]*/android.jar 2>/dev/null | sort -V | tail -n 1)

echo "[1/5] Checking toolchain..."
echo "  SDK: $SDK_ROOT"
echo "  NDK: $NDK_ROOT"
echo "  Clang: $CLANG"
echo "  d8: $D8"
echo "  Android Jar: $ANDROID_JAR"

mkdir -p build/classes build/dex build/module_stage output

echo "[2/5] Compiling Java code to DEX..."
javac -source 17 -target 17 -d build/classes -classpath "$ANDROID_JAR" src/java/com/battmon/*.java
"$D8" --lib "$ANDROID_JAR" --output build/dex/ build/classes/com/battmon/*.class

echo "[3/5] Embedding DEX into C++ header..."
python3 -c '
with open("build/dex/classes.dex", "rb") as f:
    data = f.read()
with open("src/native/battmon_dex.h", "w") as f:
    f.write("#pragma once\n\n")
    f.write(f"static const unsigned int battmon_dex_len = {len(data)};\n")
    f.write("static const unsigned char battmon_dex[] = {\n")
    for i, b in enumerate(data):
        f.write(f"0x{b:02x}, ")
        if (i + 1) % 16 == 0:
            f.write("\n")
    f.write("\n};\n")
'
echo "  Generated src/native/battmon_dex.h ($(wc -c < build/dex/classes.dex) bytes DEX)"

echo "[4/5] Compiling Zygisk arm64-v8a shared library..."
"$CLANG" -O3 -fPIC -shared -fvisibility=hidden -std=c++20 \
    -I src/native -llog src/native/main.cpp -o build/arm64-v8a.so
"$STRIP" --strip-all build/arm64-v8a.so
echo "  Compiled build/arm64-v8a.so ($(wc -c < build/arm64-v8a.so) bytes)"

echo "[5/5] Assembling flashable module zip..."
rm -rf build/module_stage
mkdir -p build/module_stage/zygisk build/module_stage/webroot

cp module/module.prop build/module_stage/
cp module/customize.sh build/module_stage/
cp module/service.sh build/module_stage/
cp module/banner.webp build/module_stage/
cp CHANGELOG.md build/module_stage/ 2>/dev/null || true
chmod +x build/module_stage/*.sh

cp build/arm64-v8a.so build/module_stage/zygisk/arm64-v8a.so
cp webroot/index.html build/module_stage/webroot/index.html

# Initial default config
cat << 'EOF' > build/module_stage/config.json
{
  "enabled": true,
  "layout_mode": "dual_line",
  "content_mode": 1,
  "refresh_ms": 1000,
  "temp_unit": "°C",
  "power_unit": "W",
  "font_size_sp": 6.5,
  "bold_font": false,
  "charging_only": false,
  "padding_top_dp": 1.5,
  "padding_left_dp": 0.0
}
EOF

MODULE_VER=$(grep "^version=" module/module.prop | cut -d= -f2 | tr -d '\r')
[ -z "$MODULE_VER" ] && MODULE_VER="v1.0"

cd build/module_stage
zip -r -9 "$PROJECT_DIR/output/statusbar_battmon-${MODULE_VER}.zip" ./*
cp "$PROJECT_DIR/output/statusbar_battmon-${MODULE_VER}.zip" "$PROJECT_DIR/output/statusbar_battmon.zip"
cd "$PROJECT_DIR"

echo "=== Build Complete! ==="
ls -lh output/
unzip -l output/statusbar_battmon.zip

