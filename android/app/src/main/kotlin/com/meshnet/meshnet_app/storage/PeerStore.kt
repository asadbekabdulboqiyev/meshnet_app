package com.meshnet.meshnet_app.storage

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Peer (contact) storage.
 * Authorized peers: verified via QR/PAIR (for E2E).
 */
class PeerStore(context: Context) {

    companion object {
        private const val PREFS = "meshnet_peers"
        private const val K_PEERS = "peers_json"
    }

    data class Peer(
        val deviceId: String,
        val displayName: String,
        val publicKey: String,
        val authorized: Boolean,
        val lastSeenMs: Long = 0L,
        val transport: String = "ble",
        val rssi: Int = 0,
        val linkQuality: Int = 50,   // 0-100 (based on RSSI + success rate)
        val hopDistance: Int = 0,     // number of hops away (0 = direct)
    )

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val gson = Gson()

    // In-memory cache: avoids reading from disk on every read,
    // persisted on write. This allows using RoutingEngine in JVM
    // unit tests (with mock prefs).
    private val peers: MutableMap<String, Peer> by lazy {
        load()
    }

    private fun load(): MutableMap<String, Peer> {
        val json = prefs.getString(K_PEERS, null) ?: return mutableMapOf()
        return try {
            val type = object : TypeToken<MutableMap<String, Peer>>() {}.type
            gson.fromJson<MutableMap<String, Peer>>(json, type) ?: mutableMapOf()
        } catch (e: Exception) {
            mutableMapOf()
        }
    }

    private fun save() {
        prefs.edit().putString(K_PEERS, gson.toJson(peers)).apply()
    }

    fun upsert(peer: Peer) {
        peers[peer.deviceId] = peer.copy(lastSeenMs = System.currentTimeMillis())
        save()
    }

    fun get(deviceId: String): Peer? = peers[deviceId]

    fun all(): List<Peer> = peers.values.sortedByDescending { it.lastSeenMs }

    fun authorized(deviceId: String): Peer? =
        peers[deviceId]?.takeIf { it.authorized }

    fun markAuthorized(deviceId: String, publicKey: String) {
        peers[deviceId] = (peers[deviceId] ?: Peer(
            deviceId = deviceId,
            displayName = "Peer",
            publicKey = publicKey,
            authorized = false,
        )).copy(publicKey = publicKey, authorized = true, lastSeenMs = System.currentTimeMillis())
        save()
    }

    /** Presence: updates a recognizable peer seen in an incoming frame.
     *  Does not create unknown peers — only updates the timestamp of existing records. */
    fun markSeen(deviceId: String) {
        val existing = peers[deviceId] ?: return
        if (System.currentTimeMillis() - existing.lastSeenMs > 5000) {
            // Do not write to disk for repeated pings within 5s
            peers[deviceId] = existing.copy(lastSeenMs = System.currentTimeMillis())
            save()
        }
    }

    fun remove(deviceId: String) {
        peers.remove(deviceId)
        save()
    }
}