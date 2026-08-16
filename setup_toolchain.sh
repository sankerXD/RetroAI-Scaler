#!/usr/bin/env bash
# ==============================================================================
# Retro-AI-Scaler Toolchain & Dependency Setup Script for macOS / Linux
# ==============================================================================
set -e

echo "=========================================================="
echo " [Retro-AI-Scaler] Checking and Setting up Toolchain"
echo "=========================================================="

# Default install directories
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export ANDROID_SDK_ROOT="${ANDROID_HOME}"
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NCNN_DIR="${PROJECT_ROOT}/app/src/main/cpp/libs/ncnn"

echo "Using ANDROID_HOME: ${ANDROID_HOME}"
echo "Project Root: ${PROJECT_ROOT}"

mkdir -p "${ANDROID_HOME}"
mkdir -p "${ANDROID_HOME}/cmdline-tools"
mkdir -p "${NCNN_DIR}"

# 1. Check Java / JDK
if command -v java >/dev/null 2>&1; then
    JAVA_VER=$(java -version 2>&1 | head -n 1)
    echo "[✓] Java found: ${JAVA_VER}"
elif [ -d "/opt/homebrew/opt/openjdk" ]; then
    export JAVA_HOME="/opt/homebrew/opt/openjdk"
    export PATH="${JAVA_HOME}/bin:${PATH}"
    echo "[✓] Using Homebrew OpenJDK at ${JAVA_HOME}"
else
    echo "[!] Java not found. Please install OpenJDK via 'brew install openjdk' or set JAVA_HOME."
fi

# 2. Check Android Command-line Tools
CMDLINE_LATEST="${ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager"
if [ ! -f "${CMDLINE_LATEST}" ]; then
    echo "[*] Android cmdline-tools not found. Downloading commandlinetools for macOS..."
    TEMP_ZIP="/tmp/cmdline-tools.zip"
    curl -Lo "${TEMP_ZIP}" "https://dl.google.com/android/repository/commandlinetools-mac-11076708_latest.zip"
    mkdir -p "${ANDROID_HOME}/cmdline-tools/tmp"
    unzip -q -o "${TEMP_ZIP}" -d "${ANDROID_HOME}/cmdline-tools/tmp"
    rm -rf "${ANDROID_HOME}/cmdline-tools/latest"
    mv "${ANDROID_HOME}/cmdline-tools/tmp/cmdline-tools" "${ANDROID_HOME}/cmdline-tools/latest"
    rm -rf "${ANDROID_HOME}/cmdline-tools/tmp" "${TEMP_ZIP}"
    echo "[✓] Android Command-line tools installed at ${ANDROID_HOME}/cmdline-tools/latest"
fi

SDKMANAGER="${ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager"

# 3. Accept licenses and install platform, ndk, cmake
echo "[*] Installing Android SDK Platform 30 (Android 11), Build-Tools, NDK (r25c/r26b), and CMake..."
yes | "${SDKMANAGER}" --licenses >/dev/null 2>&1 || true
"${SDKMANAGER}" "platform-tools" "platforms;android-30" "build-tools;34.0.0" "ndk;25.2.9519653" "cmake;3.22.1" || true

# 4. Download / Setup NCNN Android prebuilts
NCNN_HEADER="${NCNN_DIR}/include/ncnn/net.h"
if [ ! -f "${NCNN_HEADER}" ]; then
    echo "[*] Downloading NCNN Android prebuilt library (Vulkan + OpenMP + NEON)..."
    NCNN_ZIP_URL="https://github.com/Tencent/ncnn/releases/download/20240410/ncnn-20240410-android-vulkan.zip"
    NCNN_TEMP="/tmp/ncnn-android.zip"
    curl -Lo "${NCNN_TEMP}" "${NCNN_ZIP_URL}" || true
    if [ -f "${NCNN_TEMP}" ]; then
        mkdir -p "/tmp/ncnn_extracted"
        unzip -q -o "${NCNN_TEMP}" -d "/tmp/ncnn_extracted"
        cp -r /tmp/ncnn_extracted/ncnn-20240410-android-vulkan/* "${NCNN_DIR}/" || true
        rm -rf "/tmp/ncnn_extracted" "${NCNN_TEMP}"
        echo "[✓] NCNN Android prebuilt library installed at ${NCNN_DIR}"
    fi
fi

# 5. Summary
echo "=========================================================="
echo " [Retro-AI-Scaler] Toolchain Summary"
echo "  - ANDROID_HOME: ${ANDROID_HOME}"
echo "  - NDK Directory: ${ANDROID_HOME}/ndk/25.2.9519653"
echo "  - CMake: ${ANDROID_HOME}/cmake/3.22.1"
echo "  - NCNN Path: ${NCNN_DIR}"
echo "=========================================================="
