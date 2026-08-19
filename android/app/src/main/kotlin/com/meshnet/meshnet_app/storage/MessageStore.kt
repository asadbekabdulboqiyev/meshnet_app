package com.meshnet.meshnet_app.storage

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Message history (local-only). Chat history can also be saved on the Flutter side,
 * but for MVP it's backed up locally and kept secure (E2E encrypted).
 */
class MessageStore(context: Context) {

    companion object {
        private const val PREFS = "meshnet_messages"
        private const val K_INBOX = "inbox_json"
        private const val K_OUTBOX = "outbox_json"
    }

    /** Incoming messages (inbox). */
    data class IncomingMessage(
        val messageId: String,
        val fromDeviceId: String,
        val message: String,       // decrypted text
        val receivedAtMs: Long = System.currentTimeMillis(),
        val isRead: Boolean = false,
        val readAtMs: Long = 0,
    )

    /** Sent but not yet delivered messages (store-and-forward queue).
     *  Messages are stored as E2E encrypted frames — no plaintext on disk. */
    data class OutboxMessage(
        val messageId: String,
        val targetDeviceId: String,
        val encodedFrame: String,  // base64(MeshFrame.encode) — for resending
        val createdAtMs: Long = System.currentTimeMillis(),
        val retryCount: Int = 0,
    )

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val gson = Gson()

    fun addIncoming(msg: IncomingMessage) {
        val list = loadIncoming().toMutableList()
        list.add(0, msg) // new items to the front
        if (list.size > 200) list.removeAt(list.size - 1) // limit
        prefs.edit().putString(K_INBOX, gson.toJson(list)).apply()
    }

    fun loadIncoming(): List<IncomingMessage> {
        val json = prefs.getString(K_INBOX, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<IncomingMessage>>() {}.type
            gson.fromJson<List<IncomingMessage>>(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Number of unread messages from a given peer. */
    fun getUnreadCount(fromDeviceId: String): Int {
        return loadIncoming().count { it.fromDeviceId == fromDeviceId && !it.isRead }
    }

    /** Total unread message count (across all peers). */
    fun getTotalUnreadCount(): Int {
        return loadIncoming().count { !it.isRead }
    }

    /** Mark all messages from a peer as read. */
    fun markMessagesRead(fromDeviceId: String): Int {
        val list = loadIncoming().toMutableList()
        var count = 0
        val now = System.currentTimeMillis()
        for (i in list.indices) {
            if (list[i].fromDeviceId == fromDeviceId && !list[i].isRead) {
                list[i] = list[i].copy(isRead = true, readAtMs = now)
                count++
            }
        }
        if (count > 0) {
            prefs.edit().putString(K_INBOX, gson.toJson(list)).apply()
        }
        return count
    }

    fun saveOutbox(list: List<OutboxMessage>) {
        prefs.edit().putString(K_OUTBOX, gson.toJson(list)).apply()
    }

    fun loadOutbox(): List<OutboxMessage> {
        val json = prefs.getString(K_OUTBOX, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<OutboxMessage>>() {}.type
            gson.fromJson<List<OutboxMessage>>(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }
}