package com.meshnet.meshnet_app.crypto

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson

class RatchetSessionStore(context: Context) {

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

    private val prefs: SharedPreferences =
        context.getSharedPreferences("meshnet_ratchet", Context.MODE_PRIVATE)
    private val gson = Gson()

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
        prefs.edit().putString("session_$peerId", gson.toJson(info)).apply()
    }

    fun load(peerId: String): DoubleRatchet? {
        val json = prefs.getString("session_$peerId", null) ?: return null
        return try {
            val info = gson.fromJson(json, SessionInfo::class.java)
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
        prefs.edit().remove("session_$peerId").apply()
    }

    fun hasSession(peerId: String): Boolean =
        prefs.contains("session_$peerId")

    fun getAllSessionIds(): Set<String> {
        return prefs.all.keys.filter { it.startsWith("session_") }
            .map { it.removePrefix("session_") }.toSet()
    }
}
