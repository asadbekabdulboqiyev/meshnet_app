package com.meshnet.meshnet_app

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.meshnet.meshnet_app.crypto.MeshCrypto
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * Local identity: deviceId (UUID), X25519 keypair, displayName.
 * Keys are never logged (security policy).
 *
 * Storage: SharedPreferences (for secret storage unicode, Android 6+ Keystore
 * would be longer to implement — in MVP only private key in SharedPreferences; and
 * this is noted in the README Security section. This is an MVP limitation.)
 */
class IdentityStore(context: Context) {

    companion object {
        private const val TAG = "IdentityStore"
        private const val PREFS = "meshnet_identity"
        private const val K_DEVICE_ID = "device_id"
        private const val K_PRIVATE_KEY = "private_key"
        private const val K_PUBLIC_KEY = "public_key"
        private const val K_DISPLAY_NAME = "display_name"
        private const val DEFAULT_NAME = "MeshNet User"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val mutex = Mutex()

    /** New user: creates and stores deviceId + X25519 keypair (once). */
    fun init(displayName: String?) {
        // If an identity already exists, do not recreate it
        if (prefs.getString(K_DEVICE_ID, null) == null) {
            val keyPair = MeshCrypto.generateKeyPair()
            prefs.edit()
                .putString(K_DEVICE_ID, UUID.randomUUID().toString())
                .putString(K_PRIVATE_KEY, MeshCrypto.b64(keyPair.privateKey))
                .putString(K_PUBLIC_KEY, MeshCrypto.b64(keyPair.publicKey))
                .putString(K_DISPLAY_NAME, displayName?.ifEmpty { DEFAULT_NAME } ?: DEFAULT_NAME)
                .apply()
            Log.i(TAG, "New identity created (deviceId=${deviceId()})")
        } else {
            if (displayName != null) setDisplayName(displayName)
        }
    }

    fun deviceId(): String =
        prefs.getString(K_DEVICE_ID, null) ?: run {
            init(null)
            prefs.getString(K_DEVICE_ID, "")!!
        }

    fun privateKey(): ByteArray =
        MeshCrypto.unb64(prefs.getString(K_PRIVATE_KEY, "")!!)

    fun publicKey(): ByteArray =
        MeshCrypto.unb64(prefs.getString(K_PUBLIC_KEY, "")!!)

    fun displayName(): String =
        prefs.getString(K_DISPLAY_NAME, DEFAULT_NAME)!!

    fun setDisplayName(name: String) {
        prefs.edit().putString(K_DISPLAY_NAME, name.trim().ifEmpty { DEFAULT_NAME }).apply()
    }
}