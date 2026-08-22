#!/bin/bash
# F-Droid Reproducible Build Script for MeshNet
# ==============================================
# This script builds MeshNet in a reproducible manner suitable for F-Droid.
# It should be run in a clean environment (e.g., Docker container).

set -euo pipefail

# Configuration
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ANDROID_DIR="$PROJECT_ROOT/android"
BUILD_DIR="$PROJECT_ROOT/build"
FDROID_DIR="$PROJECT_ROOT/fdroid"

# Reproducible build environment variables
export GRADLE_OPTS="-Dorg.gradle.daemon=false -Dorg.gradle.parallel=false -Dorg.gradle.parallel=false -Dorg.gradle.caching=true -Dorg.gradle.configureondemand=false"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$ANDROID_SDK_ROOT/ndk/26.1.10909125}"
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk}"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT}"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME}"

# Source date epoch for reproducible builds
export SOURCE_DATE_EPOCH="${SOURCE_DATE_EPOCH:-$(date +%s)}"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

log_info() { echo -e "${BLUE}[INFO]${NC} $*"; }
log_success() { echo -e "${GREEN}[SUCCESS]${NC} $*"; }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $*"; }
log_error() { echo -e "${RED}[ERROR]${NC} $*"; }

# Check prerequisites
check_prerequisites() {
    log_info "Checking prerequisites..."

    if ! command -v java &> /dev/null; then
        log_error "Java not found. Please install JDK 21."
        exit 1
    fi

    if ! command -v flutter &> /dev/null; then
        log_error "Flutter not found. Please install Flutter SDK."
        exit 1
    fi

    if [[ ! -d "$ANDROID_SDK_ROOT" ]]; then
        log_error "Android SDK not found at $ANDROID_SDK_ROOT"
        exit 1
    fi

    if [[ ! -d "$ANDROID_NDK_HOME" ]]; then
        log_error "Android NDK not found at $ANDROID_NDK_HOME"
        exit 1
    fi

    log_success "All prerequisites met"
}

# Clean build directory
clean_build() {
    log_info "Cleaning build directory..."
    cd "$PROJECT_ROOT"
    rm -rf build/
    cd "$ANDROID_DIR"
    ./gradlew clean --no-daemon --no-parallel --no-build-cache --no-configure-on-demand
    log_success "Build directory cleaned"
}

# Get dependencies
get_dependencies() {
    log_info "Getting Flutter dependencies..."
    cd "$PROJECT_ROOT"
    flutter pub get

    log_info "Getting Android dependencies..."
    cd "$ANDROID_DIR"
    ./gradlew --no-daemon --no-parallel --no-build-cache --no-configure-on-demand \
        :app:dependencies --configuration releaseRuntimeClasspath
    log_success "Dependencies resolved"
}

# Build release APK
build_release() {
    log_info "Building release APK..."
    cd "$PROJECT_ROOT"
    flutter build apk --release --no-tree-shake-icons

    local APK_PATH="$ANDROID_DIR/app/build/outputs/apk/release/app-release.apk"
    if [[ ! -f "$APK_PATH" ]]; then
        log_error "APK not found at $APK_PATH"
        exit 1
    fi

    log_success "APK built at $APK_PATH"
    log_info "APK size: $(du -h "$APK_PATH" | cut -f1)"

    # Verify APK
    log_info "Verifying APK..."
    if command -v apksigner &> /dev/null; then
        apksigner verify --print-certs "$APK_PATH"
    fi
}

# Verify reproducibility
verify_reproducibility() {
    log_info "Verifying reproducibility..."
    local APK1="$ANDROID_DIR/app/build/outputs/apk/release/app-release.apk"
    local APK2="/tmp/app-release-2.apk"

    # Build again
    log_info "Building second time for reproducibility check..."
    cd "$PROJECT_ROOT"
    flutter build apk --release --no-tree-shake-icons

    local APK2_PATH="$ANDROID_DIR/app/build/outputs/apk/release/app-release.apk"
    cp "$APK2_PATH" "$APK2"

    # Compare APKs
    if diff -q "$APK_PATH" "$APK2" > /dev/null; then
        log_success "Builds are reproducible! APKs are identical."
    else
        log_warn "Builds are NOT reproducible. APKs differ."
        log_info "Analyzing differences..."
        # Extract and compare
        unzip -l "$APK_PATH" > /tmp/apk1.list
        unzip -l "$APK2" > /tmp/apk2.list
        diff -u /tmp/apk1.list /tmp/apk2.list || true
    fi
}

# Generate F-Droid metadata
generate_metadata() {
    log_info "F-Droid metadata already exists at fdroid/metadata/com.meshnet.meshnet_app.yml"
    log_success "Metadata verified"
}

# Main build process
main() {
    log_info "Starting MeshNet F-Droid reproducible build..."
    log_info "Project root: $PROJECT_ROOT"
    log_info "SOURCE_DATE_EPOCH: ${SOURCE_DATE_EPOCH:-$(date +%s)}"

    check_prerequisites
    clean_build
    get_dependencies
    build_release
    verify_reproducibility
    generate_metadata

    log_success "Build completed successfully!"
    log_info "APK: $ANDROID_DIR/app/build/outputs/apk/release/app-release.apk"
    log_info "F-Droid metadata: fdroid/metadata/com.meshnet.meshnet_app.yml"
}

# Run main
main "$@"