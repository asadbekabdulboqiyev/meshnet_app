package com.meshnet.meshnet_app.storage

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.meshnet.meshnet_app.localnet.rbac.Role

/**
 * MeshDatabase — single SQLite database replacing all SharedPreferences stores.
 * Tables: peers, incoming_messages, outbox_messages, groups, group_members,
 *         group_messages, ratchet_sessions, identity.
 */
open class MeshDatabase(context: Context) : SQLiteOpenHelper(
    context, DB_NAME, null, DB_VERSION
) {

    companion object {
        const val DB_NAME = "meshnet.db"
        const val DB_VERSION = 3

        @Volatile
        private var instance: MeshDatabase? = null

        /** Set a pre-created instance (for testing or manual injection). */
        fun setInstance(db: MeshDatabase) {
            instance = db
        }

        /** Reset the singleton (for test isolation). */
        fun resetInstance() {
            instance = null
        }

        fun getInstance(context: Context): MeshDatabase {
            return instance ?: synchronized(this) {
                instance ?: try {
                    MeshDatabase(context.applicationContext).also { instance = it }
                } catch (e: Exception) {
                    // JVM unit tests: applicationContext may be null from mock.
                    // Return a no-op database (SQLite methods return defaults).
                    MeshDatabase(context).also { instance = it }
                }
            }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(CREATE_PEERS)
        db.execSQL(CREATE_INCOMING_MESSAGES)
        db.execSQL(CREATE_OUTBOX_MESSAGES)
        db.execSQL(CREATE_GROUPS)
        db.execSQL(CREATE_GROUP_MEMBERS)
        db.execSQL(CREATE_GROUP_MESSAGES)
        db.execSQL(CREATE_RATCHET_SESSIONS)
        db.execSQL(CREATE_IDENTITY)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // v1 -> v2: add group_messages table, preserve existing data.
            db.execSQL(CREATE_GROUP_MESSAGES)
            return
        }
        if (oldVersion < 3) {
            // v2 -> v3: add role_grants and signing_keys tables
            db.execSQL(CREATE_ROLE_GRANTS)
            db.execSQL(CREATE_SIGNING_KEYS)
            return
        }
        // Fallback: drop and recreate (should not happen with additive migrations)
        db.execSQL("DROP TABLE IF EXISTS peers")
        db.execSQL("DROP TABLE IF EXISTS incoming_messages")
        db.execSQL("DROP TABLE IF EXISTS outbox_messages")
        db.execSQL("DROP TABLE IF EXISTS groups")
        db.execSQL("DROP TABLE IF EXISTS group_members")
        db.execSQL("DROP TABLE IF EXISTS group_messages")
        db.execSQL("DROP TABLE IF EXISTS ratchet_sessions")
        db.execSQL("DROP TABLE IF EXISTS identity")
        onCreate(db)
    }

    // =================== Table creation SQL ===================

    private val CREATE_PEERS = """
        CREATE TABLE peers (
            deviceId TEXT PRIMARY KEY,
            displayName TEXT NOT NULL DEFAULT '',
            publicKey TEXT NOT NULL DEFAULT '',
            authorized INTEGER NOT NULL DEFAULT 0,
            lastSeenMs INTEGER NOT NULL DEFAULT 0,
            transport TEXT NOT NULL DEFAULT 'ble',
            rssi INTEGER NOT NULL DEFAULT 0,
            linkQuality INTEGER NOT NULL DEFAULT 50,
            hopDistance INTEGER NOT NULL DEFAULT 0
        )
    """.trimIndent()

    private val CREATE_INCOMING_MESSAGES = """
        CREATE TABLE incoming_messages (
            messageId TEXT PRIMARY KEY,
            fromDeviceId TEXT NOT NULL,
            message TEXT NOT NULL DEFAULT '',
            receivedAtMs INTEGER NOT NULL,
            isRead INTEGER NOT NULL DEFAULT 0,
            readAtMs INTEGER NOT NULL DEFAULT 0
        )
    """.trimIndent()

    private val CREATE_OUTBOX_MESSAGES = """
        CREATE TABLE outbox_messages (
            messageId TEXT PRIMARY KEY,
            targetDeviceId TEXT NOT NULL,
            encodedFrame TEXT NOT NULL DEFAULT '',
            createdAtMs INTEGER NOT NULL,
            retryCount INTEGER NOT NULL DEFAULT 0
        )
    """.trimIndent()

    private val CREATE_GROUPS = """
        CREATE TABLE groups (
            groupId TEXT PRIMARY KEY,
            name TEXT NOT NULL,
            symmetricKey TEXT NOT NULL,
            createdAtMs INTEGER NOT NULL,
            createdBy TEXT NOT NULL
        )
    """.trimIndent()

    private val CREATE_GROUP_MEMBERS = """
        CREATE TABLE group_members (
            groupId TEXT NOT NULL,
            deviceId TEXT NOT NULL,
            displayName TEXT NOT NULL DEFAULT '',
            role TEXT NOT NULL DEFAULT 'member',
            PRIMARY KEY (groupId, deviceId)
        )
    """.trimIndent()

    private val CREATE_GROUP_MESSAGES = """
        CREATE TABLE group_messages (
            messageId TEXT PRIMARY KEY,
            groupId TEXT NOT NULL,
            senderId TEXT NOT NULL,
            senderName TEXT NOT NULL DEFAULT '',
            message TEXT NOT NULL DEFAULT '',
            fromMe INTEGER NOT NULL DEFAULT 0,
            timestampMs INTEGER NOT NULL,
            status TEXT NOT NULL DEFAULT 'delivered'
        )
    """.trimIndent()

    private val CREATE_RATCHET_SESSIONS = """
        CREATE TABLE ratchet_sessions (
            peerId TEXT PRIMARY KEY,
            localPrivateKey TEXT NOT NULL DEFAULT '',
            localPublicKey TEXT NOT NULL DEFAULT '',
            remotePublicKey TEXT NOT NULL DEFAULT '',
            serializedState TEXT NOT NULL DEFAULT '',
            createdAtMs INTEGER NOT NULL
        )
    """.trimIndent()

    private val CREATE_IDENTITY = """
        CREATE TABLE identity (
            key TEXT PRIMARY KEY,
            value TEXT NOT NULL DEFAULT ''
        )
    """.trimIndent()

    private val CREATE_ROLE_GRANTS = """
        CREATE TABLE role_grants (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            grantId TEXT UNIQUE NOT NULL,
            targetDeviceId TEXT NOT NULL,
            role INTEGER NOT NULL,
            grantedByDeviceId TEXT,
            grantedAtMs INTEGER NOT NULL DEFAULT (cast(strftime('%s', 'now') as integer) * 1000),
            expiresAtMs INTEGER,
            grantedAtMs INTEGER NOT NULL DEFAULT (cast(strftime('%s', 'now') as integer) * 1000),
            PRIMARY KEY (grantId, targetDeviceId)
        )
    """.trimIndent()

    private val CREATE_SIGNING_KEYS = """
        CREATE TABLE signing_keys (
            keyId TEXT PRIMARY KEY,
            deviceId TEXT NOT NULL,
            publicKeyB64 TEXT NOT NULL,
            privateKeyB64 TEXT,
            createdAtMs INTEGER NOT NULL DEFAULT (cast(strftime('%s', 'now') as integer) * 1000),
            updatedAtMs INTEGER NOT NULL DEFAULT (cast(strftime('%s', 'now') as integer) * 1000),
            isActive INTEGER NOT NULL DEFAULT 1
        )
    """.trimIndent()

    // =================== Peer operations ===================

    open fun upsertPeer(peer: PeerStore.Peer) {
        val cv = ContentValues().apply {
            put("deviceId", peer.deviceId)
            put("displayName", peer.displayName)
            put("publicKey", peer.publicKey)
            put("authorized", if (peer.authorized) 1 else 0)
            put("lastSeenMs", peer.lastSeenMs)
            put("transport", peer.transport)
            put("rssi", peer.rssi)
            put("linkQuality", peer.linkQuality)
            put("hopDistance", peer.hopDistance)
        }
        writableDatabase.insertWithOnConflict("peers", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    open fun getPeer(deviceId: String): PeerStore.Peer? {
        val cursor = readableDatabase.query(
            "peers", null, "deviceId = ?", arrayOf(deviceId),
            null, null, null
        )
        return cursor.use { if (it.moveToFirst()) cursorToPeer(it) else null }
    }

    open fun getAllPeers(): List<PeerStore.Peer> {
        val cursor = readableDatabase.query(
            "peers", null, null, null, null, null, "lastSeenMs DESC"
        )
        return cursor.use {
            val list = mutableListOf<PeerStore.Peer>()
            while (it.moveToNext()) list.add(cursorToPeer(it))
            list
        }
    }

    open fun removePeer(deviceId: String) {
        writableDatabase.delete("peers", "deviceId = ?", arrayOf(deviceId))
    }

    private fun cursorToPeer(c: Cursor): PeerStore.Peer = PeerStore.Peer(
        deviceId = c.getString(c.getColumnIndexOrThrow("deviceId")),
        displayName = c.getString(c.getColumnIndexOrThrow("displayName")),
        publicKey = c.getString(c.getColumnIndexOrThrow("publicKey")),
        authorized = c.getInt(c.getColumnIndexOrThrow("authorized")) == 1,
        lastSeenMs = c.getLong(c.getColumnIndexOrThrow("lastSeenMs")),
        transport = c.getString(c.getColumnIndexOrThrow("transport")),
        rssi = c.getInt(c.getColumnIndexOrThrow("rssi")),
        linkQuality = c.getInt(c.getColumnIndexOrThrow("linkQuality")),
        hopDistance = c.getInt(c.getColumnIndexOrThrow("hopDistance")),
    )

    // =================== Incoming message operations ===================

    open fun addIncomingMessage(msg: MessageStore.IncomingMessage) {
        val cv = ContentValues().apply {
            put("messageId", msg.messageId)
            put("fromDeviceId", msg.fromDeviceId)
            put("message", msg.message)
            put("receivedAtMs", msg.receivedAtMs)
            put("isRead", if (msg.isRead) 1 else 0)
            put("readAtMs", msg.readAtMs)
        }
        writableDatabase.insertWithOnConflict("incoming_messages", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    open fun loadIncomingMessages(): List<MessageStore.IncomingMessage> {
        val cursor = readableDatabase.query(
            "incoming_messages", null, null, null, null, null,
            "receivedAtMs DESC", "200"
        )
        return cursor.use {
            val list = mutableListOf<MessageStore.IncomingMessage>()
            while (it.moveToNext()) list.add(cursorToIncomingMessage(it))
            list
        }
    }

    open fun getUnreadCount(fromDeviceId: String): Int {
        val cursor = readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM incoming_messages WHERE fromDeviceId = ? AND isRead = 0",
            arrayOf(fromDeviceId)
        )
        return cursor.use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    open fun getTotalUnreadCount(): Int {
        val cursor = readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM incoming_messages WHERE isRead = 0", null
        )
        return cursor.use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    open fun markMessagesRead(fromDeviceId: String): Int {
        val now = System.currentTimeMillis()
        val cv = ContentValues().apply {
            put("isRead", 1)
            put("readAtMs", now)
        }
        return writableDatabase.update(
            "incoming_messages", cv,
            "fromDeviceId = ? AND isRead = 0", arrayOf(fromDeviceId)
        )
    }

    open fun deleteOldIncomingMessages(maxCount: Int = 200) {
        writableDatabase.execSQL(
            "DELETE FROM incoming_messages WHERE messageId NOT IN " +
            "(SELECT messageId FROM incoming_messages ORDER BY receivedAtMs DESC LIMIT $maxCount)"
        )
    }

    private fun cursorToIncomingMessage(c: Cursor): MessageStore.IncomingMessage =
        MessageStore.IncomingMessage(
            messageId = c.getString(c.getColumnIndexOrThrow("messageId")),
            fromDeviceId = c.getString(c.getColumnIndexOrThrow("fromDeviceId")),
            message = c.getString(c.getColumnIndexOrThrow("message")),
            receivedAtMs = c.getLong(c.getColumnIndexOrThrow("receivedAtMs")),
            isRead = c.getInt(c.getColumnIndexOrThrow("isRead")) == 1,
            readAtMs = c.getLong(c.getColumnIndexOrThrow("readAtMs")),
        )

    // =================== Outbox operations ===================

    open fun saveOutbox(messages: List<MessageStore.OutboxMessage>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("outbox_messages", null, null)
            for (msg in messages) {
                val cv = ContentValues().apply {
                    put("messageId", msg.messageId)
                    put("targetDeviceId", msg.targetDeviceId)
                    put("encodedFrame", msg.encodedFrame)
                    put("createdAtMs", msg.createdAtMs)
                    put("retryCount", msg.retryCount)
                }
                db.insert("outbox_messages", null, cv)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    open fun loadOutbox(): List<MessageStore.OutboxMessage> {
        val cursor = readableDatabase.query(
            "outbox_messages", null, null, null, null, null, "createdAtMs ASC"
        )
        return cursor.use {
            val list = mutableListOf<MessageStore.OutboxMessage>()
            while (it.moveToNext()) list.add(cursorToOutboxMessage(it))
            list
        }
    }

    private fun cursorToOutboxMessage(c: Cursor): MessageStore.OutboxMessage =
        MessageStore.OutboxMessage(
            messageId = c.getString(c.getColumnIndexOrThrow("messageId")),
            targetDeviceId = c.getString(c.getColumnIndexOrThrow("targetDeviceId")),
            encodedFrame = c.getString(c.getColumnIndexOrThrow("encodedFrame")),
            createdAtMs = c.getLong(c.getColumnIndexOrThrow("createdAtMs")),
            retryCount = c.getInt(c.getColumnIndexOrThrow("retryCount")),
        )

    // =================== Group operations ===================

    open fun insertGroup(group: com.meshnet.meshnet_app.protocol.GroupStore.Group) {
        val cv = ContentValues().apply {
            put("groupId", group.groupId)
            put("name", group.name)
            put("symmetricKey", group.symmetricKey)
            put("createdAtMs", group.createdAtMs)
            put("createdBy", group.createdBy)
        }
        writableDatabase.insertWithOnConflict("groups", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        // Full membership replace: drop stale members that are no longer in the list.
        writableDatabase.delete("group_members", "groupId = ?", arrayOf(group.groupId))
        for (member in group.members) {
            val mCv = ContentValues().apply {
                put("groupId", group.groupId)
                put("deviceId", member.deviceId)
                put("displayName", member.displayName)
                put("role", member.role)
            }
            writableDatabase.insertWithOnConflict("group_members", null, mCv, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    open fun getGroup(groupId: String): com.meshnet.meshnet_app.protocol.GroupStore.Group? {
        val cursor = readableDatabase.query(
            "groups", null, "groupId = ?", arrayOf(groupId),
            null, null, null
        )
        return cursor.use {
            if (it.moveToFirst()) cursorToGroup(it) else null
        }
    }

    open fun getAllGroups(): List<com.meshnet.meshnet_app.protocol.GroupStore.Group> {
        val cursor = readableDatabase.query("groups", null, null, null, null, null, "createdAtMs DESC")
        return cursor.use {
            val list = mutableListOf<com.meshnet.meshnet_app.protocol.GroupStore.Group>()
            while (it.moveToNext()) list.add(cursorToGroup(it))
            list
        }
    }

    open fun deleteGroup(groupId: String) {
        writableDatabase.delete("groups", "groupId = ?", arrayOf(groupId))
        writableDatabase.delete("group_members", "groupId = ?", arrayOf(groupId))
        writableDatabase.delete("group_messages", "groupId = ?", arrayOf(groupId))
    }

    open fun getGroupMembers(groupId: String): List<com.meshnet.meshnet_app.protocol.GroupStore.GroupMember> {
        val cursor = readableDatabase.query(
            "group_members", null, "groupId = ?", arrayOf(groupId),
            null, null, null
        )
        return cursor.use {
            val list = mutableListOf<com.meshnet.meshnet_app.protocol.GroupStore.GroupMember>()
            while (it.moveToNext()) {
                list.add(com.meshnet.meshnet_app.protocol.GroupStore.GroupMember(
                    deviceId = it.getString(it.getColumnIndexOrThrow("deviceId")),
                    displayName = it.getString(it.getColumnIndexOrThrow("displayName")),
                    role = it.getString(it.getColumnIndexOrThrow("role")),
                ))
            }
            list
        }
    }

    private fun cursorToGroup(c: Cursor): com.meshnet.meshnet_app.protocol.GroupStore.Group {
        val groupId = c.getString(c.getColumnIndexOrThrow("groupId"))
        return com.meshnet.meshnet_app.protocol.GroupStore.Group(
            groupId = groupId,
            name = c.getString(c.getColumnIndexOrThrow("name")),
            members = getGroupMembers(groupId),
            symmetricKey = c.getString(c.getColumnIndexOrThrow("symmetricKey")),
            createdAtMs = c.getLong(c.getColumnIndexOrThrow("createdAtMs")),
            createdBy = c.getString(c.getColumnIndexOrThrow("createdBy")),
        )
    }

    // =================== Group message operations ===================

    data class GroupMessage(
        val messageId: String,
        val groupId: String,
        val senderId: String,
        val senderName: String,
        val message: String,
        val fromMe: Boolean,
        val timestampMs: Long,
        val status: String,
    )

    open fun insertGroupMessage(msg: GroupMessage) {
        val cv = ContentValues().apply {
            put("messageId", msg.messageId)
            put("groupId", msg.groupId)
            put("senderId", msg.senderId)
            put("senderName", msg.senderName)
            put("message", msg.message)
            put("fromMe", if (msg.fromMe) 1 else 0)
            put("timestampMs", msg.timestampMs)
            put("status", msg.status)
        }
        writableDatabase.insertWithOnConflict(
            "group_messages", null, cv, SQLiteDatabase.CONFLICT_IGNORE
        )
    }

    open fun updateGroupMessageStatus(messageId: String, status: String) {
        val cv = ContentValues().apply { put("status", status) }
        writableDatabase.update("group_messages", cv, "messageId = ?", arrayOf(messageId))
    }

    open fun getGroupMessageById(messageId: String): GroupMessage? {
        val cursor = readableDatabase.query(
            "group_messages", null, "messageId = ?", arrayOf(messageId),
            null, null, null
        )
        return cursor.use {
            if (it.moveToFirst()) cursorToGroupMessage(it) else null
        }
    }

    open fun getGroupMessages(groupId: String, limit: Int = 200): List<GroupMessage> {
        val cursor = readableDatabase.query(
            "group_messages", null, "groupId = ?", arrayOf(groupId),
            null, null, "timestampMs DESC", limit.toString()
        )
        return cursor.use {
            val list = mutableListOf<GroupMessage>()
            while (it.moveToNext()) list.add(cursorToGroupMessage(it))
            list
        }
    }

    open fun deleteGroupMessages(groupId: String) {
        writableDatabase.delete("group_messages", "groupId = ?", arrayOf(groupId))
    }

    private fun cursorToGroupMessage(c: Cursor): GroupMessage =
        GroupMessage(
            messageId = c.getString(c.getColumnIndexOrThrow("messageId")),
            groupId = c.getString(c.getColumnIndexOrThrow("groupId")),
            senderId = c.getString(c.getColumnIndexOrThrow("senderId")),
            senderName = c.getString(c.getColumnIndexOrThrow("senderName")),
            message = c.getString(c.getColumnIndexOrThrow("message")),
            fromMe = c.getInt(c.getColumnIndexOrThrow("fromMe")) == 1,
            timestampMs = c.getLong(c.getColumnIndexOrThrow("timestampMs")),
            status = c.getString(c.getColumnIndexOrThrow("status")),
        )

    // =================== Ratchet session operations ===================

    open fun saveRatchetSession(peerId: String, info: com.meshnet.meshnet_app.crypto.RatchetSessionStore.SessionInfo) {
        val cv = ContentValues().apply {
            put("peerId", peerId)
            put("localPrivateKey", info.localPrivateKey)
            put("localPublicKey", info.localPublicKey)
            put("remotePublicKey", info.remotePublicKey)
            put("serializedState", info.serializedState)
            put("createdAtMs", info.createdAtMs)
        }
        writableDatabase.insertWithOnConflict("ratchet_sessions", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    open fun getRatchetSessionInfo(peerId: String): com.meshnet.meshnet_app.crypto.RatchetSessionStore.SessionInfo? {
        val cursor = readableDatabase.query(
            "ratchet_sessions", null, "peerId = ?", arrayOf(peerId),
            null, null, null
        )
        return cursor.use {
            if (it.moveToFirst()) com.meshnet.meshnet_app.crypto.RatchetSessionStore.SessionInfo(
                peerId = it.getString(it.getColumnIndexOrThrow("peerId")),
                localPrivateKey = it.getString(it.getColumnIndexOrThrow("localPrivateKey")),
                localPublicKey = it.getString(it.getColumnIndexOrThrow("localPublicKey")),
                remotePublicKey = it.getString(it.getColumnIndexOrThrow("remotePublicKey")),
                serializedState = it.getString(it.getColumnIndexOrThrow("serializedState")),
                createdAtMs = it.getLong(it.getColumnIndexOrThrow("createdAtMs")),
            ) else null
        }
    }

    open fun removeRatchetSession(peerId: String) {
        writableDatabase.delete("ratchet_sessions", "peerId = ?", arrayOf(peerId))
    }

    open fun hasRatchetSession(peerId: String): Boolean {
        val cursor = readableDatabase.rawQuery(
            "SELECT 1 FROM ratchet_sessions WHERE peerId = ? LIMIT 1", arrayOf(peerId)
        )
        return cursor.use { it.moveToFirst() }
    }

    open fun getAllRatchetSessionIds(): Set<String> {
        val cursor = readableDatabase.query("ratchet_sessions", arrayOf("peerId"), null, null, null, null, null)
        return cursor.use {
            val set = mutableSetOf<String>()
            while (it.moveToNext()) set.add(it.getString(0))
            set
        }
    }

    // =================== Identity operations ===================

    open fun setIdentity(key: String, value: String) {
        val cv = ContentValues().apply {
            put("key", key)
            put("value", value)
        }
        writableDatabase.insertWithOnConflict("identity", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    open fun getIdentity(key: String): String? {
        val cursor = readableDatabase.query(
            "identity", arrayOf("value"), "key = ?", arrayOf(key),
            null, null, null
        )
        return cursor.use { if (it.moveToFirst()) it.getString(0) else null }
    }

    open fun hasIdentity(key: String): Boolean {
        val cursor = readableDatabase.rawQuery(
            "SELECT 1 FROM identity WHERE key = ? LIMIT 1", arrayOf(key)
        )
        return cursor.use { it.moveToFirst() }
    }

    // =================== Role grants operations ===================

    open fun grantRole(grantId: String, targetDeviceId: String, role: Role, grantedByDeviceId: String? = null) {
        val cv = ContentValues().apply {
            put("grantId", grantId)
            put("targetDeviceId", targetDeviceId)
            put("role", role.level)
        }
        cv.put("grantedByDeviceId", grantedByDeviceId ?: "")
        cv.put("grantedAtMs", System.currentTimeMillis())
        writableDatabase.insertWithOnConflict("role_grants", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    open fun getRoleGrants(targetDeviceId: String): List<Map<String, Any>> {
        val cursor = readableDatabase.query(
            "role_grants", null, "targetDeviceId = ?", arrayOf(targetDeviceId),
            null, null, "grantedAtMs DESC"
        )
        return cursor.use {
            val list = mutableListOf<Map<String, Any>>()
            while (it.moveToNext()) {
                list.add(mapOf(
                    "grantId" to it.getString(it.getColumnIndexOrThrow("grantId")),
                    "role" to Role.values()[it.getInt(it.getColumnIndexOrThrow("role"))],
                    "grantedByDeviceId" to it.getString(it.getColumnIndexOrThrow("grantedByDeviceId")),
                    "grantedAtMs" to it.getLong(it.getColumnIndexOrThrow("grantedAtMs")),
                    "expiresAtMs" to it.getLong(it.getColumnIndexOrThrow("expiresAtMs"))
                ))
            }
            list
        }
    }

    open fun revokeRole(grantId: String) {
        writableDatabase.delete("role_grants", "grantId = ?", arrayOf(grantId))
    }

    // =================== Signing keys operations ===================

    open fun setSigningKey(deviceId: String, publicKeyB64: String, privateKeyB64: String?, keyId: String? = null) {
        val id = keyId ?: "${deviceId}_${System.currentTimeMillis()}"
        val cv = ContentValues().apply {
            put("keyId", id)
            put("deviceId", deviceId)
            put("publicKeyB64", publicKeyB64)
            put("privateKeyB64", privateKeyB64 ?: "")
            put("createdAtMs", System.currentTimeMillis())
            put("updatedAtMs", System.currentTimeMillis())
            put("isActive", 1)
        }
        writableDatabase.insertWithOnConflict("signing_keys", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    open fun getSigningKey(keyId: String): Map<String, Any?>? {
        val cursor = readableDatabase.query(
            "signing_keys", null, "keyId = ?", arrayOf(keyId),
            null, null, null
        )
        return cursor.use {
            if (it.moveToFirst()) mapOf(
                "keyId" to it.getString(it.getColumnIndexOrThrow("keyId")),
                "deviceId" to it.getString(it.getColumnIndexOrThrow("deviceId")),
                "publicKeyB64" to it.getString(it.getColumnIndexOrThrow("publicKeyB64")),
                "privateKeyB64" to it.getString(it.getColumnIndexOrThrow("privateKeyB64")),
                "createdAtMs" to it.getLong(it.getColumnIndexOrThrow("createdAtMs")),
                "updatedAtMs" to it.getLong(it.getColumnIndexOrThrow("updatedAtMs")),
                "isActive" to it.getInt(it.getColumnIndexOrThrow("isActive"))
            ) else null
        }
    }

    open fun listSigningKeys(deviceId: String): List<Map<String, Any>> {
        val cursor = readableDatabase.query(
            "signing_keys", null, "deviceId = ?", arrayOf(deviceId),
            null, null, "createdAtMs DESC"
        )
        return cursor.use {
            val list = mutableListOf<Map<String, Any>>()
            while (it.moveToNext()) {
                list.add(mapOf(
                    "keyId" to it.getString(it.getColumnIndexOrThrow("keyId")),
                    "deviceId" to it.getString(it.getColumnIndexOrThrow("deviceId")),
                    "publicKeyB64" to it.getString(it.getColumnIndexOrThrow("publicKeyB64")),
                    "createdAtMs" to it.getLong(it.getColumnIndexOrThrow("createdAtMs")),
                    "isActive" to it.getInt(it.getColumnIndexOrThrow("isActive"))
                ))
            }
            list
        }
    }

    open fun deactivateSigningKey(keyId: String) {
        val cv = ContentValues().apply { put("isActive", 0) }
        writableDatabase.update("signing_keys", cv, "keyId = ?", arrayOf(keyId))
    }
}
