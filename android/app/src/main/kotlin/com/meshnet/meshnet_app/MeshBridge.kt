package com.meshnet.meshnet_app

import android.util.Log

/**
 * MeshBridge — JNI bridge for native crypto operations.
 * Currently a placeholder; real crypto lives in MeshCrypto.kt.
 *
 * Future: Dart FFI will call native C libsodium directly,
 * bypassing MethodChannel for hot-path operations.
 */
class MeshBridge {

    companion object {
        private const val TAG = "MeshBridge"

        init {
            try {
                System.loadLibrary("mesh_bridge")
                Log.i(TAG, "Native bridge loaded")
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "Native bridge not available: ${e.message}")
            }
        }
    }

    private external fun nativeComputeSharedSecret(
        privateKey: ByteArray,
        publicKey: ByteArray,
    ): ByteArray

    private external fun nativeEncrypt(
        key: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray,
    ): ByteArray

    private external fun nativeDecrypt(
        key: ByteArray,
        ciphertext: ByteArray,
        aad: ByteArray,
    ): ByteArray
}
