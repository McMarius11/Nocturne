#!/usr/bin/env bash
# =============================================================================
# NEXUS Companion App — Automated Build Script for Fedora Linux
# Builds APK for Google Pixel 9 (arm64, Android 14)
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}"
ANDROID_NDK_VERSION="27.2.12479018"
CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"

export ANDROID_SDK_ROOT
export ANDROID_HOME="$ANDROID_SDK_ROOT"
export PATH="$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$ANDROID_SDK_ROOT/platform-tools:$PATH"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

info()  { echo -e "${GREEN}[INFO]${NC} $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC} $*"; }
error() { echo -e "${RED}[ERROR]${NC} $*"; exit 1; }

# ---- Step 1: System Dependencies ----
info "Checking system dependencies..."
PACKAGES=""
command -v java   >/dev/null || PACKAGES+=" java-17-openjdk-devel"
command -v cmake  >/dev/null || PACKAGES+=" cmake"
command -v unzip  >/dev/null || PACKAGES+=" unzip"
command -v wget   >/dev/null || PACKAGES+=" wget"
command -v git    >/dev/null || PACKAGES+=" git"

if [ -n "$PACKAGES" ]; then
    info "Installing:$PACKAGES"
    sudo dnf install -y $PACKAGES
fi

# ---- Step 2: Android SDK ----
if [ ! -d "$ANDROID_SDK_ROOT/cmdline-tools/latest" ]; then
    info "Installing Android SDK command-line tools..."
    mkdir -p "$ANDROID_SDK_ROOT/cmdline-tools"
    TMPZIP=$(mktemp /tmp/cmdline-tools-XXXXX.zip)
    wget -q --show-progress -O "$TMPZIP" "$CMDLINE_TOOLS_URL"
    unzip -q -o "$TMPZIP" -d "$ANDROID_SDK_ROOT/cmdline-tools"
    mv "$ANDROID_SDK_ROOT/cmdline-tools/cmdline-tools" "$ANDROID_SDK_ROOT/cmdline-tools/latest"
    rm -f "$TMPZIP"
fi

info "Accepting SDK licenses..."
yes | sdkmanager --licenses >/dev/null 2>&1 || true

info "Installing SDK components..."
sdkmanager --install \
    "platform-tools" \
    "platforms;android-35" \
    "build-tools;35.0.0" \
    "ndk;$ANDROID_NDK_VERSION" \
    "cmake;3.22.1"

# ---- Step 3: Clone llama.cpp ----
LLAMA_DIR="$SCRIPT_DIR/external/llama.cpp"
if [ ! -d "$LLAMA_DIR" ]; then
    info "Cloning llama.cpp..."
    mkdir -p "$SCRIPT_DIR/external"
    git clone --depth 1 https://github.com/ggerganov/llama.cpp.git "$LLAMA_DIR"
else
    info "llama.cpp already present, updating..."
    git -C "$LLAMA_DIR" pull --ff-only || true
fi

# ---- Step 4: Create local.properties ----
info "Writing local.properties..."
cat > "$SCRIPT_DIR/local.properties" <<EOF
sdk.dir=$ANDROID_SDK_ROOT
ndk.dir=$ANDROID_SDK_ROOT/ndk/$ANDROID_NDK_VERSION
EOF

# ---- Step 5: Build APK ----
info "Building debug APK..."
cd "$SCRIPT_DIR"

if [ -f "./gradlew" ]; then
    chmod +x ./gradlew
    ./gradlew assembleDebug
else
    gradle wrapper
    chmod +x ./gradlew
    ./gradlew assembleDebug
fi

APK_PATH="$SCRIPT_DIR/app/build/outputs/apk/debug/app-debug.apk"

if [ -f "$APK_PATH" ]; then
    info "APK built successfully: $APK_PATH"
    info "Size: $(du -h "$APK_PATH" | cut -f1)"
else
    error "APK build failed!"
fi

# ---- Step 6: Install via ADB (optional) ----
if command -v adb >/dev/null && adb devices | grep -q "device$"; then
    info "Pixel 9 detected! Installing APK..."
    adb install -r "$APK_PATH"
    info "App installed. Starting..."
    adb shell am start -n com.nexus.companion/.MainActivity
else
    warn "No device connected. Connect Pixel 9 via USB and run:"
    echo "  adb install -r $APK_PATH"
fi

echo ""
info "=============================="
info "  NEXUS Companion App Ready!"
info "=============================="
info ""
info "Nächste Schritte:"
info "1. App starten"
info "2. Modell wird automatisch heruntergeladen (~4GB)"
info "3. Warten bis Modell geladen ist"
info "4. Losschreiben!"
