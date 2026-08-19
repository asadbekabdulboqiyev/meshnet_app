package com.meshnet.meshnet_app.crypto

import android.content.Context
import android.util.Log
import com.meshnet.meshnet_app.storage.MeshDatabase

class RatchetSessionStore {

    companion object {
        private const val TAG = "RatchetSession"
    }

    data class SessionInfo(
        val peerId: String,
        val localPrivateKey: String,
        val localPublicKey: String,
        val remotePublicKey: String,
        val serializedState: String,
        val createdAtMs: Long,
    )

    private val db: MeshDatabase

    /** Production constructor */
    constructor(context: Context) {
        db = MeshDatabase.getInstance(context)
    }

    /** Testing constructor */
    constructor(database: MeshDatabase) {
        db = database
    }

    fun save(peerId: String, session: DoubleRatchet) {
        val localKP = session.getSendPublicKey()
        val info = SessionInfo(
            peerId = peerId,
            localPrivateKey = "",
            localPublicKey = MeshCrypto.b64(localKP),
            remotePublicKey = "",
            serializedState = MeshCrypto.b64(session.serialize()),
            createdAtMs = System.currentTimeMillis(),
        )
        db.saveRatchetSession(peerId, info)
    }

    fun load(peerId: String): DoubleRatchet? {
        val info = db.getRatchetSessionInfo(peerId) ?: return null
        return try {
            val session = DoubleRatchet(
                sharedSecret = ByteArray(32),
                dhSendKeyPair = DoubleRatchet.DHKeyPair(ByteArray(32), MeshCrypto.unb64(info.localPublicKey)),
                dhRemoteKey = ByteArray(32),
            )
            session.deserialize(MeshCrypto.unb64(info.serializedState))
            session
        } catch (e: Exception) {
            Log.e(TAG, "Session load error: ${e.message}")
            null
        }
    }

    fun remove(peerId: String) {
        db.removeRatchetSession(peerId)
    }

    fun hasSession(peerId: String): Boolean =
        db.hasRatchetSession(peerId)

    fun getAllSessionIds(): Set<String> =
        db.getAllRatchetSessionIds()
}
