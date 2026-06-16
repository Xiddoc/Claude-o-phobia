#!/usr/bin/env bash
#
# android-init.sh
#
# Init script for a fresh Claude (or any Debian/Ubuntu-based Linux) environment.
# Installs everything needed to develop, build, and sign Android apps headlessly:
#
#   * A JDK (required by the Android command-line tools / Gradle)
#   * The Android "command-line tools" (sdkmanager, avdmanager, apksigner, ...)
#   * platform-tools (adb), build-tools (aapt2, d8, zipalign, apksigner)
#   * A target platform (android-<API>)
#
# It is idempotent: re-running it will not re-download things that already exist.
#
# Usage:
#   ./android-init.sh                 # use defaults
#   ANDROID_API=34 BUILD_TOOLS=34.0.0 ./android-init.sh
#
# After it finishes, either open a new shell or run:
#   source "$HOME/.android-env"
#
set -euo pipefail

# ---------------------------------------------------------------------------
# Configuration (override via environment variables)
# ---------------------------------------------------------------------------
ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/android-sdk}"
ANDROID_API="${ANDROID_API:-34}"          # platform API level to install
BUILD_TOOLS="${BUILD_TOOLS:-34.0.0}"      # build-tools version (aapt2/zipalign/apksigner)
CMDLINE_TOOLS_VERSION="${CMDLINE_TOOLS_VERSION:-11076708}"  # "latest" cmdline-tools build
JDK_PACKAGE="${JDK_PACKAGE:-openjdk-17-jdk-headless}"
ENV_FILE="${ENV_FILE:-$HOME/.android-env}"

# Base URL for the Google-hosted command-line tools zip (Linux).
CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_VERSION}_latest.zip"

log()  { printf '\033[1;32m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[warn]\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31m[error]\033[0m %s\n' "$*" >&2; exit 1; }

# ---------------------------------------------------------------------------
# 1. System prerequisites (JDK + download/unzip utilities)
# ---------------------------------------------------------------------------
install_prereqs() {
  log "Installing system prerequisites (JDK, curl, unzip)..."

  local need_install=()
  command -v java  >/dev/null 2>&1 || need_install+=("$JDK_PACKAGE")
  command -v curl  >/dev/null 2>&1 || need_install+=(curl)
  command -v unzip >/dev/null 2>&1 || need_install+=(unzip)

  if [ "${#need_install[@]}" -eq 0 ]; then
    log "Prerequisites already present."
    return
  fi

  local SUDO=""
  if [ "$(id -u)" -ne 0 ]; then
    command -v sudo >/dev/null 2>&1 && SUDO="sudo" \
      || die "Need root to install: ${need_install[*]} (no sudo available)."
  fi

  if command -v apt-get >/dev/null 2>&1; then
    $SUDO apt-get update -y
    DEBIAN_FRONTEND=noninteractive $SUDO apt-get install -y --no-install-recommends "${need_install[@]}"
  elif command -v dnf >/dev/null 2>&1; then
    $SUDO dnf install -y java-17-openjdk-devel curl unzip
  elif command -v apk >/dev/null 2>&1; then
    $SUDO apk add --no-cache openjdk17 curl unzip
  else
    die "Unsupported distro: install a JDK 17, curl and unzip manually, then re-run."
  fi
}

# ---------------------------------------------------------------------------
# 2. Android command-line tools (sdkmanager lives here)
# ---------------------------------------------------------------------------
install_cmdline_tools() {
  local target_dir="$ANDROID_SDK_ROOT/cmdline-tools/latest"
  if [ -x "$target_dir/bin/sdkmanager" ]; then
    log "Command-line tools already installed at $target_dir"
    return
  fi

  log "Downloading Android command-line tools..."
  local tmp; tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' RETURN

  curl -fSL --retry 4 --retry-delay 2 -o "$tmp/cmdline-tools.zip" "$CMDLINE_TOOLS_URL" \
    || die "Failed to download command-line tools from $CMDLINE_TOOLS_URL"

  unzip -q "$tmp/cmdline-tools.zip" -d "$tmp"
  # The zip extracts to a top-level "cmdline-tools" dir; Google expects it
  # relocated under cmdline-tools/latest so sdkmanager can find the SDK root.
  mkdir -p "$ANDROID_SDK_ROOT/cmdline-tools"
  rm -rf "$target_dir"
  mv "$tmp/cmdline-tools" "$target_dir"

  log "Command-line tools installed at $target_dir"
}

# ---------------------------------------------------------------------------
# 3. SDK packages: platform-tools, platform, build-tools
# ---------------------------------------------------------------------------
install_sdk_packages() {
  local sdkmanager="$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"
  [ -x "$sdkmanager" ] || die "sdkmanager not found at $sdkmanager"

  export ANDROID_SDK_ROOT ANDROID_HOME="$ANDROID_SDK_ROOT"

  log "Accepting SDK licenses..."
  yes | "$sdkmanager" --sdk_root="$ANDROID_SDK_ROOT" --licenses >/dev/null || true

  log "Installing platform-tools, platforms;android-${ANDROID_API}, build-tools;${BUILD_TOOLS}..."
  "$sdkmanager" --sdk_root="$ANDROID_SDK_ROOT" \
    "platform-tools" \
    "platforms;android-${ANDROID_API}" \
    "build-tools;${BUILD_TOOLS}"

  log "SDK packages installed."
}

# ---------------------------------------------------------------------------
# 4. Persist environment so future shells can find the tools
# ---------------------------------------------------------------------------
write_env_file() {
  log "Writing environment file to $ENV_FILE"
  cat > "$ENV_FILE" <<EOF
# Android SDK environment — generated by android-init.sh
export ANDROID_SDK_ROOT="$ANDROID_SDK_ROOT"
export ANDROID_HOME="$ANDROID_SDK_ROOT"
export PATH="\$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:\$ANDROID_SDK_ROOT/platform-tools:\$ANDROID_SDK_ROOT/build-tools/$BUILD_TOOLS:\$PATH"
EOF

  # Make it load automatically in interactive shells (avoid duplicate lines).
  local line="[ -f \"$ENV_FILE\" ] && source \"$ENV_FILE\""
  for rc in "$HOME/.bashrc" "$HOME/.profile"; do
    [ -f "$rc" ] || continue
    grep -qF "$ENV_FILE" "$rc" 2>/dev/null || printf '\n%s\n' "$line" >> "$rc"
  done
}

# ---------------------------------------------------------------------------
# 5. Sanity check
# ---------------------------------------------------------------------------
verify() {
  # shellcheck disable=SC1090
  source "$ENV_FILE"
  log "Verifying installation..."
  java -version 2>&1 | head -n1 || warn "java not on PATH"
  "$ANDROID_SDK_ROOT/platform-tools/adb" --version | head -n1 || warn "adb missing"
  "$ANDROID_SDK_ROOT/build-tools/$BUILD_TOOLS/apksigner" version >/dev/null 2>&1 \
    && log "apksigner present (APK signing ready)" \
    || warn "apksigner missing — check build-tools version '$BUILD_TOOLS'"
}

main() {
  log "Android SDK init starting (SDK root: $ANDROID_SDK_ROOT)"
  mkdir -p "$ANDROID_SDK_ROOT"
  install_prereqs
  install_cmdline_tools
  install_sdk_packages
  write_env_file
  verify
  cat <<EOF

$(printf '\033[1;32m✓ Done.\033[0m')

Android development environment is ready under: $ANDROID_SDK_ROOT

Installed:
  • JDK ($JDK_PACKAGE)
  • cmdline-tools (sdkmanager, avdmanager)
  • platform-tools (adb)
  • platforms;android-${ANDROID_API}
  • build-tools;${BUILD_TOOLS} (aapt2, d8, zipalign, apksigner)

To use the tools in your CURRENT shell, run:
  source "$ENV_FILE"

New shells will pick this up automatically.

Signing an APK looks like:
  keytool -genkeypair -v -keystore my.keystore -alias mykey \\
      -keyalg RSA -keysize 2048 -validity 10000
  zipalign -v -p 4 app-unsigned.apk app-aligned.apk
  apksigner sign --ks my.keystore --out app-release.apk app-aligned.apk
EOF
}

main "$@"
