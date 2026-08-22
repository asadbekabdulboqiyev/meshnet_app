#!/bin/bash
# Generate F-Droid Release Keystore for Local Testing
# ====================================================
# This script generates a keystore for local F-Droid testing.
# DO NOT USE THIS KEYSTORE FOR PRODUCTION RELEASES!
# F-Droid will use its own keys for official builds.

set -euo pipefail

KEYSTORE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
KEYSTORE_FILE="$KEYSTORE_DIR/release.keystore"
KEY_ALIAS="fdroid"
KEY_PASSWORD="fdroid"
STORE_PASSWORD="fdroid"
KEY_ALGORITHM="RSA"
KEY_SIZE=2048
VALIDITY_DAYS=10000
DNAME="CN=MeshNet F-Droid Test, OU=MeshNet, O=MeshNet, L=Unknown, ST=Unknown, C=XX"

set -euo pipefail

log_info() { echo -e "\033[0;34m[INFO]\033[0m $*"; }
log_success() { echo -e "\033[0;32m[SUCCESS]\033[0m $*"; }
log_warn() { echo -e "\033[1;33m[WARN]\033[0m $*"; }
log_error() { echo -e "\033[0;31m[ERROR]\033[0m $*"; }

main() {
    log_info "Generating F-Droid test keystore..."

    if [[ -f "$KEYSTORE_FILE" ]]; then
        log_warn "Keystore already exists at $KEYSTORE_FILE"
        read -p "Overwrite? (y/N): " -n 1 -r
        echo
        if [[ ! $REPLY =~ ^[Yy]$ ]]; then
            log_info "Keystore generation cancelled"
            exit 0
        fi
    fi

    log_info "Generating keystore at $KEYSTORE_FILE..."
    log_info "Alias: $KEY_ALIAS"
    log_info "Password: $STORE_PASSWORD"
    log_info "Validity: $VALIDITY_DAYS days"

    # Generate keystore
    keytool -genkeypair \
        -alias "$KEY_ALIAS" \
        -keyalg "$KEY_ALGORITHM" \
        -keysize "$KEY_SIZE" \
        -validity "$VALIDITY_DAYS" \
        -keystore "$KEYSTORE_FILE" \
        -storetype PKCS12 \
        -storepass "$STORE_PASSWORD" \
        -keypass "$KEY_PASSWORD" \
        -dname "$DNAME" \
        -deststoretype PKCS12

    if [[ $? -eq 0 ]]; then
        log_success "Keystore generated successfully at $KEYSTORE_FILE"

        # Verify keystore
        log_info "Verifying keystore..."
        keytool -list -v -keystore "$KEYSTORE_FILE" -storepass "$STORE_PASSWORD" -alias "$KEY_ALIAS"

        log_success "Keystore generated and verified!"
        log_warn "IMPORTANT: This is a TEST keystore only!"
        log_warn "Do NOT use for production releases!"
        log_warn "F-Droid will use its own keys for official builds."
    else
        log_error "Failed to generate keystore"
        exit 1
    fi
}

main "$@"