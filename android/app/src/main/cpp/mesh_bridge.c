/**
 * MeshNet FFI Bridge — minimal C bridge for Flutter FFI.
 *
 * This is scaffolding for future migration of crypto operations
 * from Kotlin/JVM to native C via Dart FFI. The actual crypto
 * currently lives in Kotlin (MeshCrypto.kt, DoubleRatchet.kt).
 *
 * Future use:
 *  - Dart calls mesh_bridge_compute_shared_secret() via FFI
 *  - Avoids MethodChannel overhead for hot-path crypto
 *  - Enables same code on iOS via dart:ffi
 */

#include <jni.h>
#include <string.h>
#include <stdlib.h>

/**
 * Placeholder: compute X25519 shared secret.
 * In production, this would use libsodium or similar.
 * For now, returns a dummy 32-byte buffer.
 */
JNIEXPORT jbyteArray JNICALL
Java_com_meshnet_meshnet_app_MeshBridge_nativeComputeSharedSecret(
    JNIEnv *env,
    jobject thiz,
    jbyteArray private_key,
    jbyteArray public_key
) {
    // Placeholder — real implementation would use libsodium
    jbyteArray result = (*env)->NewByteArray(env, 32);
    return result;
}

/**
 * Placeholder: ChaCha20-Poly1305 encrypt.
 */
JNIEXPORT jbyteArray JNICALL
Java_com_meshnet_meshnet_app_MeshBridge_nativeEncrypt(
    JNIEnv *env,
    jobject thiz,
    jbyteArray key,
    jbyteArray plaintext,
    jbyteArray aad
) {
    // Placeholder — real implementation would use libsodium
    jbyteArray result = (*env)->NewByteArray(env, 32);
    return result;
}

/**
 * Placeholder: ChaCha20-Poly1305 decrypt.
 */
JNIEXPORT jbyteArray JNICALL
Java_com_meshnet_meshnet_app_MeshBridge_nativeDecrypt(
    JNIEnv *env,
    jobject thiz,
    jbyteArray key,
    jbyteArray ciphertext,
    jbyteArray aad
) {
    // Placeholder — real implementation would use libsodium
    jbyteArray result = (*env)->NewByteArray(env, 0);
    return result;
}
