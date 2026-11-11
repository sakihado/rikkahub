#!/usr/bin/env bash
set -euo pipefail

# Builds glibc LD_PRELOAD trash-guard libraries (`libsafesdcard_{arm64,x86_64}.so`) for the
# Ubuntu PRoot workspace Rootfs. Note: These run inside Ubuntu glibc (NOT Android Bionic), so
# they are compiled with GNU gcc / aarch64-linux-gnu-gcc rather than Android NDK clang.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
SRC_FILE="$SCRIPT_DIR/safesdcard.c"
OUT_DIR="${1:-$REPO_ROOT/app/src/github/assets/safesdcard}"

mkdir -p "$OUT_DIR"

HOST_ARCH="$(uname -m)"

build_arm64() {
    if command -v aarch64-linux-gnu-gcc >/dev/null 2>&1; then
        aarch64-linux-gnu-gcc -shared -fPIC -O2 -Wall "$SRC_FILE" -o "$OUT_DIR/libsafesdcard_arm64.so" -ldl
    elif [ "$HOST_ARCH" = "aarch64" ] && command -v gcc >/dev/null 2>&1; then
        gcc -shared -fPIC -O2 -Wall "$SRC_FILE" -o "$OUT_DIR/libsafesdcard_arm64.so" -ldl
    elif [ -f "$OUT_DIR/libsafesdcard_arm64.so" ]; then
        echo "[safesdcard] Keeping existing prebuilt $OUT_DIR/libsafesdcard_arm64.so (aarch64-linux-gnu-gcc not installed)"
    else
        echo "[safesdcard] WARNING: aarch64-linux-gnu-gcc not found; skipping libsafesdcard_arm64.so" >&2
    fi
}

build_x86_64() {
    if command -v x86_64-linux-gnu-gcc >/dev/null 2>&1; then
        x86_64-linux-gnu-gcc -shared -fPIC -O2 -Wall "$SRC_FILE" -o "$OUT_DIR/libsafesdcard_x86_64.so" -ldl
    elif [ "$HOST_ARCH" = "x86_64" ] && command -v gcc >/dev/null 2>&1; then
        gcc -shared -fPIC -O2 -Wall "$SRC_FILE" -o "$OUT_DIR/libsafesdcard_x86_64.so" -ldl
    elif [ -f "$OUT_DIR/libsafesdcard_x86_64.so" ]; then
        echo "[safesdcard] Keeping existing prebuilt $OUT_DIR/libsafesdcard_x86_64.so (x86_64-linux-gnu-gcc not installed)"
    else
        echo "[safesdcard] WARNING: x86_64-linux-gnu-gcc not found; skipping libsafesdcard_x86_64.so" >&2
    fi
}

build_arm64
build_x86_64
