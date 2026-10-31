#!/usr/bin/env bash
# Build TermDeck standalone Android APK (id.termdeck.app).
# Automatically provisions a local JDK 17, Gradle, and Android SDK if not already installed.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TOOLCHAIN_DIR="${TERMDECK_ANDROID_TOOLCHAIN:-$HOME/.local/share/termdeck-android-sdk}"
JDK_DIR="$TOOLCHAIN_DIR/jdk-17"
GRADLE_DIR="$TOOLCHAIN_DIR/gradle-8.7"
ANDROID_SDK_DIR="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$TOOLCHAIN_DIR/android-sdk}}"

echo "==> [1/4] Building TermDeck frontend assets..."
npm --prefix "$ROOT/frontend" test
npm --prefix "$ROOT/frontend" run build

ASSETS_WEBAPP="$ROOT/android/app/src/main/assets/webapp"
rm -rf "$ASSETS_WEBAPP"
mkdir -p "$ASSETS_WEBAPP"
cp -r "$ROOT/frontend/dist/." "$ASSETS_WEBAPP/"

echo "==> [2/4] Checking Java 17, Gradle, and Android SDK..."
mkdir -p "$TOOLCHAIN_DIR"

if command -v javac >/dev/null 2>&1 && javac -version 2>&1 | grep -qE "javac (17|21)\."; then
    echo "    Using system Java: $(javac -version 2>&1)"
else
    if [[ ! -x "$JDK_DIR/bin/javac" ]]; then
        echo "    Downloading portable OpenJDK 17 (Adoptium)..."
        mkdir -p "$JDK_DIR"
        JDK_ARCHIVE="$TOOLCHAIN_DIR/jdk17.tar.gz"
        curl -fL --retry 3 -o "$JDK_ARCHIVE" \
            "https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse?project=jdk"
        tar -xzf "$JDK_ARCHIVE" -C "$JDK_DIR" --strip-components=1
        rm -f "$JDK_ARCHIVE"
    fi
    export JAVA_HOME="$JDK_DIR"
    export PATH="$JAVA_HOME/bin:$PATH"
    echo "    Using portable Java: $(javac -version 2>&1)"
fi

if command -v gradle >/dev/null 2>&1; then
    GRADLE_BIN="$(command -v gradle)"
else
    if [[ ! -x "$GRADLE_DIR/bin/gradle" ]]; then
        echo "    Downloading Gradle 8.7..."
        GRADLE_ZIP="$TOOLCHAIN_DIR/gradle-8.7-bin.zip"
        curl -fL --retry 3 -o "$GRADLE_ZIP" \
            "https://services.gradle.org/distributions/gradle-8.7-bin.zip"
        python3 -c "import zipfile; zipfile.ZipFile('$GRADLE_ZIP').extractall('$TOOLCHAIN_DIR')"
        chmod +x "$GRADLE_DIR/bin/gradle"
        rm -f "$GRADLE_ZIP"
    fi
    GRADLE_BIN="$GRADLE_DIR/bin/gradle"
fi

SDKMANAGER="$ANDROID_SDK_DIR/cmdline-tools/latest/bin/sdkmanager"
if [[ ! -x "$SDKMANAGER" ]]; then
    echo "    Downloading Android Command Line Tools..."
    mkdir -p "$ANDROID_SDK_DIR/cmdline-tools"
    CMDLINE_ZIP="$TOOLCHAIN_DIR/commandlinetools-linux.zip"
    curl -fL --retry 3 -o "$CMDLINE_ZIP" \
        "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
    python3 -c "import zipfile; zipfile.ZipFile('$CMDLINE_ZIP').extractall('$ANDROID_SDK_DIR/cmdline-tools')"
    rm -rf "$ANDROID_SDK_DIR/cmdline-tools/latest"
    mv "$ANDROID_SDK_DIR/cmdline-tools/cmdline-tools" "$ANDROID_SDK_DIR/cmdline-tools/latest"
    chmod -R +x "$ANDROID_SDK_DIR/cmdline-tools/latest/bin"
    rm -f "$CMDLINE_ZIP"
fi

if [[ ! -d "$ANDROID_SDK_DIR/platforms/android-34" || ! -d "$ANDROID_SDK_DIR/build-tools/34.0.0" ]]; then
    echo "    Installing Android SDK Platform 34 & Build-Tools 34.0.0..."
    yes | "$SDKMANAGER" --sdk_root="$ANDROID_SDK_DIR" --licenses >/dev/null 2>&1 || true
    "$SDKMANAGER" --sdk_root="$ANDROID_SDK_DIR" \
        "platform-tools" \
        "platforms;android-34" \
        "build-tools;34.0.0"
fi

export ANDROID_HOME="$ANDROID_SDK_DIR"
export ANDROID_SDK_ROOT="$ANDROID_SDK_DIR"
printf "sdk.dir=%s\n" "$ANDROID_SDK_DIR" > "$ROOT/android/local.properties"

echo "==> [3/4] Running Android JUnit tests..."
(
    cd "$ROOT/android"
    "$GRADLE_BIN" testDebugUnitTest --no-daemon
)

echo "==> [4/4] Assembling Android APK..."
(
    cd "$ROOT/android"
    "$GRADLE_BIN" assembleDebug --no-daemon
)

mkdir -p "$ROOT/dist"
cp "$ROOT/android/app/build/outputs/apk/debug/app-debug.apk" "$ROOT/dist/termdeck-android.apk"
echo "==> Done! APK generated at: $ROOT/dist/termdeck-android.apk"
