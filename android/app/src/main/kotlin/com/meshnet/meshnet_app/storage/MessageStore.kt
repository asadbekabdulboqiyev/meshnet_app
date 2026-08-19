package com.meshnet.meshnet_app.storage

import android.content.Context

/**
 * Message history (local-only). Chat history can also be saved on the Flutter side,
 * but for MVP it's backed up locally and kept secure (E2E encrypted).
 *
 * Backed by [MeshDatabase] (SQLite) — no SharedPreferences / Gson.
 */
class MessageStore {

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

    private val db: MeshDatabase

    /** Production constructor */
    constructor(context: Context) {
        db = MeshDatabase.getInstance(context)
    }

    /** Testing constructor */
    constructor(database: MeshDatabase) {
        db = database
    }

    fun addIncoming(msg: IncomingMessage) {
        db.addIncomingMessage(msg)
        db.deleteOldIncomingMessages()
    }

    fun loadIncoming(): List<IncomingMessage> {
        return db.loadIncomingMessages()
    }

    /** Number of unread messages from a given peer. */
    fun getUnreadCount(fromDeviceId: String): Int {
        return db.getUnreadCount(fromDeviceId)
    }

    /** Total unread message count (across all peers). */
    fun getTotalUnreadCount(): Int {
        return db.getTotalUnreadCount()
    }

    /** Mark all messages from a peer as read. */
    fun markMessagesRead(fromDeviceId: String): Int {
        return db.markMessagesRead(fromDeviceId)
    }

    fun saveOutbox(list: List<OutboxMessage>) {
        db.saveOutbox(list)
    }

    fun loadOutbox(): List<OutboxMessage> {
        return db.loadOutbox()
    }
}