package com.meshnet.meshnet_app

import android.content.Context
import android.util.Log
import com.meshnet.meshnet_app.crypto.MeshCrypto
import com.meshnet.meshnet_app.storage.MeshDatabase
import kotlinx.coroutines.sync.Mutex
import java.util.UUID

/**
 * Local identity: deviceId (UUID), X25519 keypair, displayName.
 * Keys are never logged (security policy).
 *
 * Storage: MeshDatabase (SQLite) — single source of truth for all persistent
 * state. The private key is stored in the identity table; for production a
 * hardware-backed Keystore-backed encryption layer should wrap it (noted in
 * the README Security section). This is an MVP limitation.
 */
class IdentityStore {

    companion object {
        private const val TAG = "IdentityStore"
        private const val K_DEVICE_ID = "device_id"
        private const val K_PRIVATE_KEY = "private_key"
        private const val K_PUBLIC_KEY = "public_key"
        private const val K_DISPLAY_NAME = "display_name"
        private const val DEFAULT_NAME = "MeshNet User"
    }

    private val db: MeshDatabase

    /** Production constructor */
    constructor(context: Context) {
        db = MeshDatabase.getInstance(context)
    }

    /** Testing constructor */
    constructor(database: MeshDatabase) {
        db = database
    }

    private val mutex = Mutex()

    /** New user: creates and stores deviceId + X25519 keypair (once). */
    fun init(displayName: String?) {
        synchronized(mutex) {
            // If an identity already exists, do not recreate it
            if (!db.hasIdentity(K_DEVICE_ID)) {
                val keyPair = MeshCrypto.generateKeyPair()
                db.setIdentity(K_DEVICE_ID, UUID.randomUUID().toString())
                db.setIdentity(K_PRIVATE_KEY, MeshCrypto.b64(keyPair.privateKey))
                db.setIdentity(K_PUBLIC_KEY, MeshCrypto.b64(keyPair.publicKey))
                db.setIdentity(K_DISPLAY_NAME, displayName?.ifEmpty { DEFAULT_NAME } ?: DEFAULT_NAME)
                Log.i(TAG, "New identity created (deviceId=${deviceId()})")
            } else {
                if (displayName != null) setDisplayName(displayName)
            }
        }
    }

    fun deviceId(): String =
        db.getIdentity(K_DEVICE_ID) ?: run {
            init(null)
            db.getIdentity(K_DEVICE_ID) ?: ""
        }

    fun privateKey(): ByteArray =
        MeshCrypto.unb64(db.getIdentity(K_PRIVATE_KEY) ?: "")

    fun publicKey(): ByteArray =
        MeshCrypto.unb64(db.getIdentity(K_PUBLIC_KEY) ?: "")

    fun displayName(): String =
        db.getIdentity(K_DISPLAY_NAME) ?: DEFAULT_NAME

    fun setDisplayName(name: String) {
        synchronized(mutex) {
            db.setIdentity(K_DISPLAY_NAME, name.trim().ifEmpty { DEFAULT_NAME })
        }
    }
}