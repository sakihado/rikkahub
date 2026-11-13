#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SDK_DIR="${ANDROID_HOME:-$HOME/android-sdk}"

echo "[devcontainer] 1/5 Installing gcc-aarch64-linux-gnu for sdcard_ro..."
sudo apt-get update
sudo apt-get install -y --no-install-recommends gcc-aarch64-linux-gnu unzip curl

echo "[devcontainer] 2/5 Installing pnpm@11 and web-ui dependencies..."
if ! command -v pnpm >/dev/null 2>&1 || [ "$(pnpm --version | cut -d. -f1)" != "11" ]; then
  npm install -g pnpm@11
fi
(cd "$REPO_ROOT/web-ui" && pnpm install --frozen-lockfile)

echo "[devcontainer] 3/5 Setting up Android SDK command-line tools..."
if [ ! -x "$SDK_DIR/cmdline-tools/latest/bin/sdkmanager" ]; then
  CMDLINE_URL="$(curl -fsSL https://developer.android.com/studio | grep -oE 'https://dl\.google\.com/android/repository/commandlinetools-linux-[0-9]+_latest\.zip' | head -n1 || true)"
  if [ -z "$CMDLINE_URL" ]; then
    CMDLINE_URL="https://dl.google.com/android/repository/commandlinetools-linux-15859902_latest.zip"
  fi
  mkdir -p "$SDK_DIR/cmdline-tools"
  TMP_ZIP="$(mktemp /tmp/cmdline-tools-XXXXXX.zip)"
  curl -fSL "$CMDLINE_URL" -o "$TMP_ZIP"
  TMP_EXTRACT="$(mktemp -d /tmp/cmdline-extract-XXXXXX)"
  unzip -q "$TMP_ZIP" -d "$TMP_EXTRACT"
  rm -rf "$SDK_DIR/cmdline-tools/latest"
  mv "$TMP_EXTRACT/cmdline-tools" "$SDK_DIR/cmdline-tools/latest"
  rm -rf "$TMP_ZIP" "$TMP_EXTRACT"
fi

export ANDROID_HOME="$SDK_DIR"
export ANDROID_SDK_ROOT="$SDK_DIR"
export PATH="$SDK_DIR/cmdline-tools/latest/bin:$SDK_DIR/platform-tools:$PATH"

echo "[devcontainer] 4/5 Accepting Android SDK licenses & installing platform-tools..."
yes | sdkmanager --licenses >/dev/null || true
sdkmanager "platform-tools" >/dev/null

echo "[devcontainer] 5/5 Preparing local.properties & placeholder google-services.json..."
if [ ! -f "$REPO_ROOT/local.properties" ]; then
  echo "sdk.dir=$SDK_DIR" > "$REPO_ROOT/local.properties"
fi

if [ ! -f "$REPO_ROOT/app/google-services.json" ]; then
  cat > "$REPO_ROOT/app/google-services.json" <<'JSON'
{
  "project_info": {
    "project_number": "123456789012",
    "project_id": "rikkahub-fileapi-build",
    "storage_bucket": "rikkahub-fileapi-build.appspot.com"
  },
  "client": [
    {
      "client_info": {
        "mobilesdk_app_id": "1:123456789012:android:0123456789abcdef012345",
        "android_client_info": { "package_name": "me.rerere.rikkahub" }
      },
      "oauth_client": [],
      "api_key": [ { "current_key": "AIzaSyDUMMYKEYFORPERSONALBUILDONLY00000" } ],
      "services": { "appinvite_service": { "other_platform_oauth_client": [] } }
    },
    {
      "client_info": {
        "mobilesdk_app_id": "1:123456789012:android:0123456789abcdef012345",
        "android_client_info": { "package_name": "me.rerere.rikkahub.debug" }
      },
      "oauth_client": [],
      "api_key": [ { "current_key": "AIzaSyDUMMYKEYFORPERSONALBUILDONLY00000" } ],
      "services": { "appinvite_service": { "other_platform_oauth_client": [] } }
    }
  ],
  "configuration_version": "1"
}
JSON
fi

chmod +x "$REPO_ROOT/gradlew"
echo "[devcontainer] Done! Run './gradlew assembleGithubDebug' whenever you're ready."
