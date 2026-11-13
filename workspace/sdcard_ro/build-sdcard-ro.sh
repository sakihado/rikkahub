#!/usr/bin/env bash
set -euo pipefail

# Builds glibc LD_PRELOAD read-only guard libraries (`libsdcard_ro_{arm64,x86_64}.so`) for the
# PRoot Ubuntu/Debian sandbox. Output defaults to `app/src/github/assets/sdcard_ro/`.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
SRC_FILE="$SCRIPT_DIR/sdcard_ro.c"
OUT_DIR="${1:-$REPO_ROOT/app/src/github/assets/sdcard_ro}"

mkdir -p "$OUT_DIR"

build_arm64() {
    if command -v aarch64-linux-gnu-gcc >/dev/null 2>&1; then
        echo "[sdcard_ro] Compiling arm64 glibc shared library..."
        aarch64-linux-gnu-gcc -shared -fPIC -O2 -Wall -Wextra -Wno-unused-parameter "$SRC_FILE" -o "$OUT_DIR/libsdcard_ro_arm64.so" -ldl
    elif [ "$(uname -m)" = "aarch64" ] && command -v gcc >/dev/null 2>&1; then
        gcc -shared -fPIC -O2 -Wall -Wextra -Wno-unused-parameter "$SRC_FILE" -o "$OUT_DIR/libsdcard_ro_arm64.so" -ldl
    elif [ -f "$OUT_DIR/libsdcard_ro_arm64.so" ]; then
        echo "[sdcard_ro] Keeping existing prebuilt $OUT_DIR/libsdcard_ro_arm64.so (aarch64-linux-gnu-gcc not installed)"
    else
        echo "[sdcard_ro] WARNING: aarch64-linux-gnu-gcc not found; skipping libsdcard_ro_arm64.so" >&2
    fi
}

build_x86_64() {
    if command -v x86_64-linux-gnu-gcc >/dev/null 2>&1; then
        echo "[sdcard_ro] Compiling x86_64 glibc shared library..."
        x86_64-linux-gnu-gcc -shared -fPIC -O2 -Wall -Wextra -Wno-unused-parameter "$SRC_FILE" -o "$OUT_DIR/libsdcard_ro_x86_64.so" -ldl
    elif [ "$(uname -m)" = "x86_64" ] && command -v gcc >/dev/null 2>&1; then
        gcc -shared -fPIC -O2 -Wall -Wextra -Wno-unused-parameter "$SRC_FILE" -o "$OUT_DIR/libsdcard_ro_x86_64.so" -ldl
    elif [ -f "$OUT_DIR/libsdcard_ro_x86_64.so" ]; then
        echo "[sdcard_ro] Keeping existing prebuilt $OUT_DIR/libsdcard_ro_x86_64.so (x86_64-linux-gnu-gcc not installed)"
    else
        echo "[sdcard_ro] WARNING: x86_64-linux-gnu-gcc not found; skipping libsdcard_ro_x86_64.so" >&2
    fi
}

build_arm64
build_x86_64
ls -la "$OUT_DIR"
