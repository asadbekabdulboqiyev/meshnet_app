package com.meshnet.meshnet_app.protocol

import android.content.Context
import android.util.Log
import com.meshnet.meshnet_app.crypto.MeshCrypto
import com.meshnet.meshnet_app.crypto.DoubleRatchet
import com.meshnet.meshnet_app.crypto.RatchetSessionStore
import com.meshnet.meshnet_app.storage.PeerStore
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

/**
 * RoutingEngine - 2-hop flooding relay.
 *
 *  A ---> B ---> C
 *  A: sends TEXT frame (target=C). B: relays to target C, sends
 *     delivery report back to A (RELAY wrapping). C: receives => delivery report
 *     sent to A (via route).
 *
 * MeshFax relay: only authorized peers, E2E.
 * Payload encrypted (B cannot read), since sender uses: sharedSecret.
 */
class RoutingEngine(
    private val context: Context,
    private val identityDeviceId: String,
    private val identityPrivateKey: ByteArray,
    private val peerStore: PeerStore,
) {

    companion object {
        private const val TAG = "RoutingEngine"
        const val MAX_HOP = 4 // 2→4: wider network range
        const val SEEN_CACHE_TTL_MS = 60_000L
        const val OUTBOX_TTL_MS = 24L * 60 * 60 * 1000
        const val MAX_OUTBOX = 500
        const val MIN_RETRY_INTERVAL_MS = 3_000L
        // Route table: routes are automatically learned and expire
        const val ROUTE_TTL_MS = 90_000L
        const val MAX_ROUTE_TABLE = 200
    }

    interface MessageListener {
        fun onTextReceived(from: String, message: String, messageId: String)
        fun onDeliveryReport(messageId: String, delivered: Boolean)
        fun onPairResult(deviceId: String, success: Boolean)
        fun onPeerFound(deviceId: String)
        fun onOutboxChanged(messageId: String, status: String)
        fun onFrameToSend(frame: MeshFrame, transport: String?)
        // New: notify about route changes
        fun onRouteChanged(destination: String, nextHop: String, hopCount: Int, quality: Int)
        fun onFileTransferStarted(transferId: String, fileName: String, fileSize: Long, mimeType: String, senderId: String) {}
        fun onFileTransferProgress(transferId: String, bytesTransferred: Long, totalBytes: Long) {}
        fun onFileTransferComplete(transferId: String, filePath: String, fileName: String, senderId: String) {}
        fun onGroupMessageReceived(groupId: String, senderId: String, message: String, senderName: String, messageId: String) {}
        fun onVoiceMessageReceived(senderId: String, audioData: ByteArray, messageId: String) {}
        fun onReadReceived(fromDeviceId: String, messageIds: List<String>) {}
        fun onLocalEvent(event: Map<String, Any?>) {}
    }

    /**
     * LocalNet (Phase 1): DNS frames are handled outside RoutingEngine by
     * LocalNetService. Kept as an optional hook so the routing core stays
     * decoupled from LocalNet.
     */
    interface DnsHandler {
        fun onDnsAnnounce(frame: MeshFrame) {}
        fun onDnsQuery(frame: MeshFrame) {}
        fun onDnsResponse(frame: MeshFrame) {}
    }

    @Volatile
    var dnsHandler: DnsHandler? = null

    /**
     * LocalNet (Phase 3): collaboration frames (whiteboard/docs/polls) are
     * handled outside RoutingEngine by CollabService. Optional hook keeps
     * the routing core decoupled from LocalNet.
     */
    interface CollabHandler {
        fun onBoardStroke(frame: MeshFrame) {}
        fun onBoardClear(frame: MeshFrame) {}
        fun onDocEdit(frame: MeshFrame) {}
        fun onPollCreate(frame: MeshFrame) {}
        fun onPollVote(frame: MeshFrame) {}
    }

    @Volatile
    var collabHandler: CollabHandler? = null

    /**
     * LocalNet (Phase 5): internet gateway presence frames are handled
     * outside RoutingEngine by LocalNetService. Optional hook keeps the
     * routing core decoupled from LocalNet.
     */
    interface GatewayHandler {
        fun onGatewayAnnounce(frame: MeshFrame) {}
    }

    @Volatile
    var gatewayHandler: GatewayHandler? = null

    /**
     * LocalNet (Phase 6): emergency broadcast frames are handled
     * outside RoutingEngine by LocalNetService.
     */
    interface EmergencyHandler {
        fun onEmergencyFrame(frame: MeshFrame) {}
    }

    @Volatile
    var emergencyHandler: EmergencyHandler? = null

    /**
     * LocalNet (Phase 6): search frames are handled
     * outside RoutingEngine by LocalNetService.
     */
    interface SearchHandler {
        fun onSearchFrame(frame: MeshFrame) {}
    }

    @Volatile
    var searchHandler: SearchHandler? = null

    /** Route table entry: how to reach a specific node. */
    data class RouteEntry(
        val destination: String,
        val nextHop: String,
        val hopCount: Int,
        var linkQuality: Int, // 0-100 (RSSI asosida)
        val timestamp: Long,
        var lastUsed: Long = timestamp,
        var successCount: Int = 0,
        var failCount: Int = 0,
    ) {
        fun isExpired(): Boolean =
            System.currentTimeMillis() - timestamp > ROUTE_TTL_MS

        fun qualityScore(): Int {
            val total = successCount + failCount
            if (total == 0) return linkQuality
            val successRate = (successCount * 100) / total
            return (successRate * 70 + linkQuality * 30) / 100
        }
    }

    private val listenerList = ConcurrentHashMap.newKeySet<MessageListener>()

    // msgId -> seenAtMs (prevent duplicate reprocessing, with TTL)
    private val seenMessages = ConcurrentHashMap<String, Long>()

    // Route table: destination -> RouteEntry (passive route learning)
    private val routeTable = ConcurrentHashMap<String, RouteEntry>()

    // Relay/statistics counters (for topology debug screen)
    var framesReceived = 0L; private set
    var framesRelayed = 0L; private set
    var messagesSent = 0L; private set
    var messagesDelivered = 0L; private set
    var duplicatesDropped = 0L; private set

    // Store-and-forward queue: messageId -> encrypted frame
    // (retried on retry; removed on delivery report)
    private val outbox = LinkedHashMap<String, MeshFrame>()

    private val groupStore = GroupStore(context)
    private val ratchetSessions = RatchetSessionStore(context)
    private val fileTransferManager = FileTransferManager()

    fun addListener(l: MessageListener) { listenerList.add(l) }
    fun removeListener(l: MessageListener) { listenerList.remove(l) }

    /** Monotonically increasing seq — two calls at the same millis will not
     *  produce the same messageId (ensures outbox key survival).
     *  Based on: System.currentTimeMillis(), so TTL calculation also works. */
    private var lastMsgSeq = 0L
    private fun nextSeq(): Long {
        val now = System.currentTimeMillis()
        lastMsgSeq = if (now > lastMsgSeq) now else lastMsgSeq + 1
        return lastMsgSeq
    }

    /** Duplicate check: registers if new and returns false. */
    private fun registerSeen(msgKey: String): Boolean {
        val now = System.currentTimeMillis()
        if (seenMessages.size > 1024) {
            val cutoff = now - SEEN_CACHE_TTL_MS
            seenMessages.entries.removeAll { it.value < cutoff }
        }
        val seenAt = seenMessages[msgKey]
        if (seenAt != null && now - seenAt < SEEN_CACHE_TTL_MS) return true
        seenMessages[msgKey] = now
        return false
    }

    // ---------------- Route learning (AODV-like passive) ----------------

    /** Learn route from incoming frame: sender → via nextHop. */
    fun learnRoute(destination: String, nextHop: String, hopCount: Int, linkQuality: Int = 50) {
        if (destination == identityDeviceId) return
        if (routeTable.size >= MAX_ROUTE_TABLE) {
            // Evict oldest and lowest quality route
            val worst = routeTable.entries.minByOrNull { it.value.qualityScore() }
            if (worst != null) routeTable.remove(worst.key)
        }
        val existing = routeTable[destination]
        val now = System.currentTimeMillis()
        if (existing != null) {
            // Only update existing route if improvement
            val newHopCount = hopCount + 1
            if (newHopCount <= existing.hopCount || existing.isExpired()) {
                if (newHopCount < existing.hopCount) {
                    routeTable[destination] = RouteEntry(
                        destination, nextHop, newHopCount, linkQuality, now, now,
                        existing.successCount, existing.failCount
                    )
                    listenerList.forEach { it.onRouteChanged(destination, nextHop, newHopCount, linkQuality) }
                } else {
                    existing.lastUsed = now
                    existing.linkQuality = (existing.linkQuality + linkQuality) / 2
                }
            }
        } else {
            routeTable[destination] = RouteEntry(
                destination, nextHop, hopCount + 1, linkQuality, now, now
            )
            listenerList.forEach { it.onRouteChanged(destination, nextHop, hopCount + 1, linkQuality) }
        }
    }

    /** Look up route in the route table. */
    fun findRoute(destination: String): RouteEntry? {
        val entry = routeTable[destination] ?: return null
        if (entry.isExpired()) {
            routeTable.remove(destination)
            return null
        }
        return entry
    }

    /** Route used successfully — increase quality. */
    fun routeSuccess(destination: String) {
        routeTable[destination]?.let {
            it.successCount++
            it.lastUsed = System.currentTimeMillis()
        }
    }

    /** Route failed — decrease quality. */
    fun routeFailure(destination: String) {
        routeTable[destination]?.let {
            it.failCount++
            if (it.failCount > 5 && it.qualityScore() < 20) {
                routeTable.remove(destination)
                Log.d(TAG, "Route removed (low quality): $destination")
            }
        }
    }

    /** Expire stale routes. */
    fun expireRoutes() {
        val expired = routeTable.entries.filter { it.value.isExpired() }
        expired.forEach { routeTable.remove(it.key) }
        if (expired.isNotEmpty()) {
            Log.d(TAG, "expireRoutes: ${expired.size} routes expired")
        }
    }

    /** Route table snapshot (for debug/UI). */
    fun routeSnapshot(): List<Map<String, Any?>> = routeTable.values.map { r ->
        mapOf(
            "destination" to r.destination,
            "nextHop" to r.nextHop,
            "hopCount" to r.hopCount,
            "quality" to r.qualityScore(),
            "age" to (System.currentTimeMillis() - r.timestamp),
            "success" to r.successCount,
            "fail" to r.failCount,
        )
    }

    /** Analyze incoming frame. RELAY is a wrapper — the inner
     *  frame `(senderId:seq)` goes through duplicate control (each copy
     *  delivered only once). */
    fun handleIncomingFrame(frame: MeshFrame) {
        framesReceived++
        if (frame.type != MessageType.RELAY) {
            val msgKey = "${frame.senderId}:${frame.msgSeq}"
            if (registerSeen(msgKey)) {
                duplicatesDropped++
                Log.d(TAG, "Duplicate frame: $msgKey")
                return
            }
        }
        // Presence: incoming frame (ping or message) updates peer to online
        if (frame.senderId != identityDeviceId) {
            peerStore.markSeen(frame.senderId)
        }
        // Route learning: sender → directly reachable (hop=1)
        if (frame.senderId != identityDeviceId && frame.senderId != MeshFrame.BROADCAST) {
            learnRoute(frame.senderId, frame.senderId, 1)
        }
        routeFrame(frame)
    }

    private fun routeFrame(frame: MeshFrame) {
        when (frame.type) {
            MessageType.TEXT -> handleText(frame)
            MessageType.FILE_START -> handleFileStart(frame)
            MessageType.FILE_CHUNK -> handleFileChunk(frame)
            MessageType.FILE_END -> handleFileEnd(frame)
            MessageType.GROUP_MSG -> handleGroupMsg(frame)
            MessageType.GROUP_KEY_DIST -> handleGroupKeyDist(frame)
            MessageType.VOICE_MSG -> handleVoiceMsg(frame)
            MessageType.RATCHET_INIT -> handleRatchetInit(frame)
            MessageType.RATCHET_MSG -> handleRatchetMsg(frame)
            MessageType.RELAY -> handleRelay(frame)
            MessageType.DELIVERY_REPORT -> handleDeliveryReport(frame)
            MessageType.PAIR_REQ -> handlePairReq(frame)
            MessageType.PAIR_ACK -> handlePairAck(frame)
            MessageType.FIND_PEER -> handleFindPeer(frame)
            MessageType.FIND_PEER_ACK -> handleFindPeerAck(frame)
            MessageType.READ_RECEIPT -> handleReadReceipt(frame)
            MessageType.DNS_ANNOUNCE -> dnsHandler?.onDnsAnnounce(frame)
            MessageType.DNS_QUERY -> dnsHandler?.onDnsQuery(frame)
            MessageType.DNS_RESPONSE -> dnsHandler?.onDnsResponse(frame)
            MessageType.BOARD_STROKE -> collabHandler?.onBoardStroke(frame)
            MessageType.BOARD_CLEAR -> collabHandler?.onBoardClear(frame)
            MessageType.DOC_EDIT -> collabHandler?.onDocEdit(frame)
            MessageType.POLL_CREATE -> collabHandler?.onPollCreate(frame)
            MessageType.POLL_VOTE -> collabHandler?.onPollVote(frame)
            MessageType.VPN_GW_ANNOUNCE -> gatewayHandler?.onGatewayAnnounce(frame)
            MessageType.EMERGENCY_ALERT, MessageType.EMERGENCY_ACK, MessageType.EMERGENCY_CANCEL ->
                emergencyHandler?.onEmergencyFrame(frame)
            MessageType.SEARCH_QUERY, MessageType.SEARCH_RESULT, MessageType.SEARCH_INDEX_SYNC ->
                searchHandler?.onSearchFrame(frame)
            MessageType.PEER_PING -> { /* javob kerak emas - borlik */ }
            MessageType.GROUP_CREATE -> { /* group yaratish qabul qilindi */ }
            MessageType.GROUP_ADD_MEMBER -> { /* a'zo qo'shildi */ }
            MessageType.GROUP_REMOVE_MEMBER -> { /* a'zo o'chirildi */ }
            MessageType.GROUP_LEAVE -> { /* a'zo chiqdi */ }
        }
    }

    /** Send chat message (from A): encrypt -> flood routing.
     *  Returns: messageId if successful, null otherwise. */
    fun sendText(targetId: String, message: String): String? {
        val peer = peerStore.authorized(targetId)
        if (peer == null) {
            Log.w(TAG, "sendText: target not authorized: $targetId")
            return null
        }

        val sharedSecret = MeshCrypto.computeSharedSecret(
            identityPrivateKey,
            MeshCrypto.unb64(peer.publicKey)
        )

        // AAD: routing data (protects address routing from weakness)
        val aad = "MeshNet:$targetId".toByteArray(StandardCharsets.UTF_8)
        val ciphertext = MeshCrypto.encrypt(sharedSecret, message.toByteArray(StandardCharsets.UTF_8), aad)

        val seq = nextSeq()
        // Record our own message in seen-cache — flood echo
        // copy (A<-B<-C) won't be processed (loop-back protection).
        registerSeen("$identityDeviceId:$seq")
        val frame = MeshFrame(
            type = MessageType.TEXT,
            hopLimit = MAX_HOP,
            ttl = 6,
            encrypted = true,
            senderId = identityDeviceId,
            targetId = targetId,
            msgSeq = seq,
            payload = ciphertext,
            senderPublicKey = null,
        )
        val msgId = "${identityDeviceId}:$seq"
        messagesSent++
        // Store-and-forward: frame saved to queue (if peer offline,
        // retried via retryPending; removed on delivery report).
        if (outbox.size >= MAX_OUTBOX) {
            // evict oldest entry (round-robin eviction)
            outbox.entries.firstOrNull()?.key?.let { outbox.remove(it) }
        }
        outbox[msgId] = frame
        listenerList.forEach { it.onOutboxChanged(msgId, "queued") }
        emitForSend(frame, transportHint(targetId))
        return msgId
    }

    /** When TEXT arrives: is it for us, or does it need relay. */
    private fun handleText(frame: MeshFrame) {
        if (frame.targetId == identityDeviceId || frame.targetId == MeshFrame.BROADCAST) {
            // For us: decrypt
            decryptAndDeliver(frame)
        } else if (frame.hopLimit > 0) {
            // Relay needed: different node (C) — we relay it
            relayFrame(frame)
        }
    }

    private fun handleRelay(frame: MeshFrame) {
        // RELAY contains inner frame - checks if target is us.
        // Inner frame (senderId:seq) goes through duplicate control; RELAY
        // wrapper is not deduped, so C gets only one copy
        // (one original msgId => one onTextReceived).
        val inner = MeshFrame.decode(frame.payload)
        if (inner != null) {
            handleIncomingFrame(inner.copy(
                hopLimit = frame.hopLimit,
                ttl = frame.ttl,
            ))
        }
    }

    private fun relayFrame(frame: MeshFrame) {
        val nextHop = frame.hopLimit - 1
        val nextTtl = frame.ttl - 1
        if (nextHop <= 0 || nextTtl <= 0) {
            Log.d(TAG, "Relay stopped: hop=$nextHop ttl=$nextTtl")
            return
        }
        framesRelayed++
        val relayFrame = MeshFrame(
            type = MessageType.RELAY,
            hopLimit = nextHop,
            ttl = nextTtl,
            encrypted = false,
            senderId = frame.senderId,
            targetId = frame.targetId,
            msgSeq = frame.msgSeq,
            payload = MeshFrame.encode(frame),
            senderPublicKey = null,
        )
        // Route lookup: if known route exists, unicast; otherwise flood
        val route = findRoute(frame.targetId)
        if (route != null && route.nextHop != frame.senderId) {
            // Unicast: send only along known route
            Log.d(TAG, "Relay unicast: ${frame.targetId} via ${route.nextHop} (hop=$nextHop)")
            listenerList.forEach { it.onFrameToSend(relayFrame, null) }
            routeSuccess(route.destination)
        } else {
            // Flood: to all neighbors
            Log.d(TAG, "Relay flood: ${frame.senderId} -> ${frame.targetId} (hop=$nextHop ttl=$nextTtl)")
            listenerList.forEach { it.onFrameToSend(relayFrame, null) }
        }
    }

    private fun handleDeliveryReport(frame: MeshFrame) {
        if (frame.targetId == identityDeviceId) {
            val delivered = frame.payload.isNotEmpty() && frame.payload[0] == 0x01.toByte()
            val msgId = if (frame.payload.size > 1) {
                String(frame.payload, 1, frame.payload.size - 1, StandardCharsets.UTF_8)
            } else {
                "${frame.senderId}:${frame.msgSeq}"
            }
            Log.d(TAG, "handleDeliveryReport: from=${frame.senderId}, msgId=$msgId, delivered=$delivered, outboxSize=${outbox.size}")
            if (outbox.remove(msgId) != null) {
                Log.d(TAG, "handleDeliveryReport: removed from outbox! msgId=$msgId, new outboxSize=${outbox.size}")
                listenerList.forEach { it.onOutboxChanged(msgId, "delivered") }
            } else {
                Log.w(TAG, "handleDeliveryReport: not found in outbox! msgId=$msgId, outboxKeys=${outbox.keys.take(5)}")
            }
            listenerList.forEach { it.onDeliveryReport(msgId, delivered) }
        } else if (frame.hopLimit > 0) {
            relayFrame(frame)
        }
    }

    private fun handlePairReq(frame: MeshFrame) {
        if (frame.targetId == identityDeviceId && frame.senderPublicKey != null) {
            peerStore.markAuthorized(frame.senderId, MeshCrypto.b64(frame.senderPublicKey!!))
            // Send PAIR_ACK in response
            val ack = MeshFrame(
                type = MessageType.PAIR_ACK,
                hopLimit = MAX_HOP,
                ttl = 6,
                encrypted = false,
                senderId = identityDeviceId,
                targetId = frame.senderId,
                msgSeq = nextSeq(),
                payload = ByteArray(0),
                senderPublicKey = identityPublicKey,
            )
            emitForSend(ack, transportHint(frame.senderId))
        } else if (frame.hopLimit > 0) {
            // 2-hop: pairing request intended for another node
            relayFrame(frame)
        }
    }

    /** Send pairing request (to QR-scanned peer): peer recognizes us as
     *  authorized and sends back PAIR_ACK. */
    fun sendPairRequest(targetId: String): Boolean {
        val pubKey = identityPublicKey ?: run {
            Log.w(TAG, "sendPairRequest: identity public key not set")
            return false
        }
        val seq = nextSeq()
        registerSeen("$identityDeviceId:$seq")
        val req = MeshFrame(
            type = MessageType.PAIR_REQ,
            hopLimit = MAX_HOP,
            ttl = 6,
            encrypted = false,
            senderId = identityDeviceId,
            targetId = targetId,
            msgSeq = seq,
            payload = ByteArray(0),
            senderPublicKey = pubKey,
        )
        emitForSend(req, transportHint(targetId))
        return true
    }

    private fun handlePairAck(frame: MeshFrame) {
        if (frame.targetId == identityDeviceId && frame.senderPublicKey != null) {
            peerStore.markAuthorized(frame.senderId, MeshCrypto.b64(frame.senderPublicKey!!))
            Log.i(TAG, "Pair accepted: ${frame.senderId}")
            listenerList.forEach { it.onPairResult(frame.senderId, true) }
        } else if (frame.hopLimit > 0) {
            relayFrame(frame)
        }
    }

    // ---------------- Phase 4: FIND_PEER (route recovery) ----------------

    /** Find peer in network: FIND_PEER broadcast (flood). */
    fun sendFindPeer(targetId: String): Boolean {
        if (targetId.isEmpty()) return false
        val seq = nextSeq()
        registerSeen("$identityDeviceId:$seq")
        val req = MeshFrame(
            type = MessageType.FIND_PEER,
            hopLimit = MAX_HOP,
            ttl = 6,
            encrypted = false,
            senderId = identityDeviceId,
            targetId = MeshFrame.BROADCAST,
            msgSeq = seq,
            payload = targetId.toByteArray(StandardCharsets.UTF_8),
            senderPublicKey = null,
        )
        emitForSend(req, null)
        return true
    }

    private fun handleFindPeer(frame: MeshFrame) {
        // Payload: node ID being searched for
        val targetId = String(frame.payload, StandardCharsets.UTF_8)
        if (targetId == identityDeviceId) {
            // We are the node being searched: send FIND_PEER_ACK.
            // Public key also included — requester can pair if needed.
            val ack = MeshFrame(
                type = MessageType.FIND_PEER_ACK,
                hopLimit = MAX_HOP,
                ttl = 6,
                encrypted = false,
                senderId = identityDeviceId,
                targetId = frame.senderId,
                msgSeq = nextSeq(),
                payload = ByteArray(0),
                senderPublicKey = identityPublicKey,
            )
            emitForSend(ack, transportHint(frame.senderId))
        } else if (frame.hopLimit > 0) {
            // Forward search to other nodes (flood)
            relayFrame(frame)
        }
    }

    private fun handleFindPeerAck(frame: MeshFrame) {
        if (frame.targetId == identityDeviceId) {
            // Found: authorize if pubkey received
            if (frame.senderPublicKey != null) {
                peerStore.markAuthorized(frame.senderId, MeshCrypto.b64(frame.senderPublicKey!!))
            }
            listenerList.forEach { it.onPeerFound(frame.senderId) }
        } else if (frame.hopLimit > 0) {
            relayFrame(frame)
        }
    }

    // ---------------- LocalNet Phase 1: DNS frames ----------------

    /** Broadcast our hostname announcement to the whole mesh. */
    fun sendDnsAnnounce(payload: String): Boolean {
        val seq = nextSeq()
        registerSeen("$identityDeviceId:$seq")
        val frame = MeshFrame(
            type = MessageType.DNS_ANNOUNCE,
            hopLimit = MAX_HOP,
            ttl = 6,
            encrypted = false,
            senderId = identityDeviceId,
            targetId = MeshFrame.BROADCAST,
            msgSeq = seq,
            payload = payload.toByteArray(StandardCharsets.UTF_8),
            senderPublicKey = null,
        )
        emitForSend(frame, null)
        return true
    }

    /** Broadcast "who has this hostname?" query. */
    fun sendDnsQuery(hostname: String): Boolean {
        if (hostname.isEmpty()) return false
        val seq = nextSeq()
        registerSeen("$identityDeviceId:$seq")
        val frame = MeshFrame(
            type = MessageType.DNS_QUERY,
            hopLimit = MAX_HOP,
            ttl = 6,
            encrypted = false,
            senderId = identityDeviceId,
            targetId = MeshFrame.BROADCAST,
            msgSeq = seq,
            payload = hostname.toByteArray(StandardCharsets.UTF_8),
            senderPublicKey = null,
        )
        emitForSend(frame, null)
        return true
    }

    /** Unicast DNS answer back to the querying node. */
    fun sendDnsResponse(targetId: String, responsePayload: String): Boolean {
        val seq = nextSeq()
        registerSeen("$identityDeviceId:$seq")
        val frame = MeshFrame(
            type = MessageType.DNS_RESPONSE,
            hopLimit = MAX_HOP,
            ttl = 6,
            encrypted = false,
            senderId = identityDeviceId,
            targetId = targetId,
            msgSeq = seq,
            payload = responsePayload.toByteArray(StandardCharsets.UTF_8),
            senderPublicKey = null,
        )
        emitForSend(frame, transportHint(targetId))
        return true
    }

    // ---------------- LocalNet Phase 3: collaboration frames ----------------

    /** Broadcast a whiteboard stroke (flood, multi-hop). */
    fun sendBoardStroke(payload: String): Boolean =
        sendCollabBroadcast(MessageType.BOARD_STROKE, payload)

    /** Broadcast "clear this board". */
    fun sendBoardClear(roomId: String): Boolean =
        sendCollabBroadcast(MessageType.BOARD_CLEAR, roomId)

    /** Broadcast a doc edit (LWW merge on every receiver). */
    fun sendDocEdit(payload: String): Boolean =
        sendCollabBroadcast(MessageType.DOC_EDIT, payload)

    /** Broadcast a new poll. */
    fun sendPollCreate(payload: String): Boolean =
        sendCollabBroadcast(MessageType.POLL_CREATE, payload)

    /** Broadcast a vote. */
    fun sendPollVote(payload: String): Boolean =
        sendCollabBroadcast(MessageType.POLL_VOTE, payload)

    private fun sendCollabBroadcast(type: MessageType, payload: String): Boolean {
        if (payload.isEmpty()) return false
        val seq = nextSeq()
        registerSeen("$identityDeviceId:$seq")
        val frame = MeshFrame(
            type = type,
            hopLimit = MAX_HOP,
            ttl = 6,
            encrypted = false,
            senderId = identityDeviceId,
            targetId = MeshFrame.BROADCAST,
            msgSeq = seq,
            payload = payload.toByteArray(StandardCharsets.UTF_8),
            senderPublicKey = null,
        )
        emitForSend(frame, null)
        return true
    }

    // ---------------- LocalNet Phase 5: gateway presence ----------------

    /** Broadcast "I am an internet gateway" (flood, multi-hop). */
    fun sendGatewayAnnounce(payload: String): Boolean =
        sendCollabBroadcast(MessageType.VPN_GW_ANNOUNCE, payload)

    // ---------------- LocalNet Phase 6: emergency broadcast ----------------

    /** Broadcast emergency alert (flood, high priority). */
    fun sendEmergencyAlert(payload: ByteArray): Boolean =
        sendCollabBroadcastBytes(MessageType.EMERGENCY_ALERT, payload)

    /** Broadcast emergency acknowledgment. */
    fun sendEmergencyAck(payload: ByteArray): Boolean =
        sendCollabBroadcastBytes(MessageType.EMERGENCY_ACK, payload)

    /** Broadcast emergency cancellation. */
    fun sendEmergencyCancel(payload: ByteArray): Boolean =
        sendCollabBroadcastBytes(MessageType.EMERGENCY_CANCEL, payload)

    // ---------------- LocalNet Phase 6: mesh-wide search ----------------

    /** Broadcast search query (flood). */
    fun sendSearchQuery(payload: ByteArray): Boolean =
        sendCollabBroadcastBytes(MessageType.SEARCH_QUERY, payload)

    /** Send search result to specific target (unicast). */
    fun sendSearchResult(targetId: String, payload: ByteArray): Boolean {
        val frame = MeshFrame(
            type = MessageType.SEARCH_RESULT,
            hopLimit = MAX_HOP,
            ttl = 4,
            encrypted = false,
            senderId = identityDeviceId,
            targetId = targetId,
            msgSeq = nextSeq(),
            payload = payload,
            senderPublicKey = null,
        )
        emitForSend(frame, null)
        return true
    }

    /** Broadcast index sync (periodic, low priority). */
    fun sendSearchIndexSync(payload: ByteArray): Boolean =
        sendCollabBroadcastBytes(MessageType.SEARCH_INDEX_SYNC, payload)

    /** Internal: broadcast with raw byte payload (for emergency/search). */
    private fun sendCollabBroadcastBytes(type: MessageType, payload: ByteArray): Boolean {
        if (payload.isEmpty()) return false
        val seq = nextSeq()
        registerSeen("$identityDeviceId:$seq")
        val frame = MeshFrame(
            type = type,
            hopLimit = MAX_HOP,
            ttl = 6,
            encrypted = false,
            senderId = identityDeviceId,
            targetId = MeshFrame.BROADCAST,
            msgSeq = seq,
            payload = payload,
            senderPublicKey = null,
        )
        emitForSend(frame, null)
        return true
    }

    // ---------------- Phase 5: Store-and-forward ----------------

    /** Retry all queued messages (or those for a specific target).
     *  MeshEngine calls this every 15-20s and when peer comes online. */
    fun retryPending(targetDeviceId: String? = null) {
        val now = System.currentTimeMillis()
        if (now - lastRetryMs < MIN_RETRY_INTERVAL_MS) {
            Log.d(TAG, "retryPending: rate limit — ${MIN_RETRY_INTERVAL_MS - (now - lastRetryMs)}ms left")
            return
        }
        val frames = outbox.entries.filter { (_, f) ->
            targetDeviceId == null || f.targetId == targetDeviceId
        }
        if (frames.isEmpty()) return
        Log.d(TAG, "retryPending: ${frames.size} messages (target=$targetDeviceId)")
        frames.forEach { (_, frame) ->
            listenerList.forEach { it.onFrameToSend(frame, null) }
        }
        lastRetryMs = now
    }

    /** Remove queued messages older than 24 hours and notify.
     *  (In sendText, msgSeq = System.currentTimeMillis(), so this is also createdAt). */
    fun expirePending(nowMs: Long = System.currentTimeMillis()) {
        val expired = outbox.entries.filter { (_, f) ->
            nowMs - f.msgSeq > OUTBOX_TTL_MS
        }
        if (expired.isEmpty()) return
        Log.d(TAG, "expirePending: ${expired.size} messages expired")
        expired.forEach { (msgId, _) ->
            outbox.remove(msgId)
            listenerList.forEach { it.onOutboxChanged(msgId, "expired") }
        }
        // Also clean up the route table
        expireRoutes()
    }

    /** Reload from MessageStore on app restart.
     *  Messages older than 24 hours are immediately deleted. */
    fun restoreOutbox(records: List<Pair<String, MeshFrame>>) {
        val nowMs = System.currentTimeMillis()
        var restored = 0
        var expired = 0
        records.forEach { (msgId, frame) ->
            if (nowMs - frame.msgSeq > OUTBOX_TTL_MS) {
                expired++
                listenerList.forEach { it.onOutboxChanged(msgId, "expired") }
                return@forEach
            }
            if (outbox.size < MAX_OUTBOX) {
                outbox[msgId] = frame
                restored++
            }
        }
        Log.d(TAG, "restoreOutbox: $restored messages restored, $expired expired messages deleted")
    }

    /** Returns queue snapshot for persistence (messageId -> frame). */
    fun outboxSnapshot(): List<Pair<String, MeshFrame>> =
        outbox.entries.map { it.key to it.value }

    // ---------------- Phase 6: Heartbeat ----------------

    /** Presence beacon: PEER_PING broadcast (every 15s, with jitter). */
    fun sendPing() {
        val seq = nextSeq()
        registerSeen("$identityDeviceId:$seq")
        val ping = MeshFrame(
            type = MessageType.PEER_PING,
            hopLimit = MAX_HOP,
            ttl = 6,
            encrypted = false,
            senderId = identityDeviceId,
            targetId = MeshFrame.BROADCAST,
            msgSeq = seq,
            payload = ByteArray(0),
            senderPublicKey = null,
        )
        emitForSend(ping, null)
    }

    /** Retry status for statistics (read by MeshEngine). */
    @Volatile
    var lastRetryMs = 0L
        private set

    private fun decryptAndDeliver(frame: MeshFrame) {
        val peer = peerStore.authorized(frame.senderId)
        if (peer == null) {
            Log.w(TAG, "Message sender not authorized: ${frame.senderId} — not reading")
            return
        }
        try {
            val sharedSecret = MeshCrypto.computeSharedSecret(
                identityPrivateKey,
                MeshCrypto.unb64(peer.publicKey)
            )
            // AAD in encryption (sendText) also bound to targetId — matches.
            val aad = "MeshNet:${frame.targetId}".toByteArray(StandardCharsets.UTF_8)
            val plain = MeshCrypto.decrypt(sharedSecret, frame.payload, aad)
            val text = String(plain, StandardCharsets.UTF_8)
            val msgId = "${frame.senderId}:${frame.msgSeq}"
            messagesDelivered++
            listenerList.forEach { it.onTextReceived(frame.senderId, text, msgId) }

            // Delivery report: to sender
            sendDeliveryReport(frame, true)
        } catch (e: Exception) {
            Log.e(TAG, "Could not decrypt message: ${e.message}")
            sendDeliveryReport(frame, false)
        }
    }

    private fun sendDeliveryReport(frame: MeshFrame, delivered: Boolean) {
        // Payload: [delivered flag] + original messageId — receiver
        // can find message status precisely.
        val originalMsgId = "${frame.senderId}:${frame.msgSeq}"
        val seq = nextSeq()
        // Record our own report in seen-cache — flood echo
        // copy (A<-C) won't be processed (loop-back protection).
        registerSeen("$identityDeviceId:$seq")
        val idBytes = originalMsgId.toByteArray(StandardCharsets.UTF_8)
        val payload = ByteArray(1 + idBytes.size)
        payload[0] = if (delivered) 0x01 else 0x00
        System.arraycopy(idBytes, 0, payload, 1, idBytes.size)
        val report = MeshFrame(
            type = MessageType.DELIVERY_REPORT,
            hopLimit = MAX_HOP,
            ttl = 6,
            encrypted = false,
            senderId = identityDeviceId,
            targetId = frame.senderId,
            msgSeq = seq,
            payload = payload,
            senderPublicKey = null,
        )
        emitForSend(report, transportHint(frame.senderId))
    }

    /** Sent when user reads messages (read receipt).
     *  Payload: [0x01] + messageId1(UTF-8) + ',' + messageId2 + ... */
    fun sendReadReceipt(targetId: String, messageIds: List<String>) {
        if (messageIds.isEmpty()) return
        val peer = peerStore.authorized(targetId) ?: return
        val seq = nextSeq()
        registerSeen("$identityDeviceId:$seq")
        val idsStr = messageIds.joinToString(",")
        val idBytes = idsStr.toByteArray(StandardCharsets.UTF_8)
        val payload = ByteArray(1 + idBytes.size)
        payload[0] = 0x01
        System.arraycopy(idBytes, 0, payload, 1, idBytes.size)
        val frame = MeshFrame(
            type = MessageType.READ_RECEIPT,
            hopLimit = MAX_HOP,
            ttl = 6,
            encrypted = false,
            senderId = identityDeviceId,
            targetId = targetId,
            msgSeq = seq,
            payload = payload,
            senderPublicKey = null,
        )
        emitForSend(frame, transportHint(targetId))
        Log.d(TAG, "sendReadReceipt: $targetId, ${messageIds.size} messages")
    }

    private fun handleReadReceipt(frame: MeshFrame) {
        if (frame.targetId == identityDeviceId) {
            if (frame.payload.size < 2) return
            val idsStr = String(frame.payload, 1, frame.payload.size - 1, StandardCharsets.UTF_8)
            val messageIds = idsStr.split(",").filter { it.isNotEmpty() }
            Log.d(TAG, "handleReadReceipt: from=${frame.senderId}, ${messageIds.size} messages read")
            listenerList.forEach { it.onReadReceived(frame.senderId, messageIds) }
        } else if (frame.hopLimit > 0) {
            relayFrame(frame)
        }
    }

    private fun transportHint(deviceId: String): String? {
        // MVP: transport decision made in TransportManager
        return null
    }

    fun emitForSend(frame: MeshFrame, transport: String?) {
        listenerList.forEach { it.onFrameToSend(frame, transport) }
    }

    /** Emit a local event to all listeners (for emergency, search, RBAC events). */
    fun emitLocalEvent(event: Map<String, Any?>) {
        listenerList.forEach { it.onLocalEvent(event) }
    }

    // Identity public key (for pairing)
    private var identityPublicKey: ByteArray? = null
    fun setIdentityPublicKey(key: ByteArray) {
        identityPublicKey = key
    }

    /** Statistics map for debug/topology screen. */
    fun stats(): Map<String, Long> = mapOf(
        "framesReceived" to framesReceived,
        "framesRelayed" to framesRelayed,
        "messagesSent" to messagesSent,
        "messagesDelivered" to messagesDelivered,
        "duplicatesDropped" to duplicatesDropped,
        "seenCacheSize" to seenMessages.size.toLong(),
        "routeTableSize" to routeTable.size.toLong(),
    )

    // === FILE TRANSFER HANDLERS ===

    private fun handleFileStart(frame: MeshFrame) {
        val info = fileTransferManager.handleFileStart(frame.senderId, frame.payload) ?: return
        listenerList.forEach { it.onFileTransferStarted(info.transferId, info.fileName, info.fileSize, info.mimeType, frame.senderId) }
        sendDeliveryReport(frame, true)
        if (frame.hopLimit > 0) relayFrame(frame)
    }

    private fun handleFileChunk(frame: MeshFrame) {
        fileTransferManager.handleFileChunk(frame.payload)
        val progress = fileTransferManager.getProgress(
            frame.payload.copyOfRange(0, 16).let { bytes ->
                try {
                    val bb = java.nio.ByteBuffer.wrap(bytes)
                    java.util.UUID(bb.long, bb.long).toString()
                } catch (_: Exception) { "" }
            }
        )
        if (progress != null) {
            listenerList.forEach { it.onFileTransferProgress(progress.transferId, progress.sentBytes + progress.receivedBytes, progress.totalBytes) }
        }
        if (frame.hopLimit > 0) relayFrame(frame)
    }

    private fun handleFileEnd(frame: MeshFrame) {
        val (transferId, fileBytes, info) = fileTransferManager.handleFileEnd(frame.payload) ?: return
        if (fileBytes != null && info != null) {
            val savedFile = fileTransferManager.saveReceivedFile(fileBytes, info.fileName, context.cacheDir)
            listenerList.forEach { it.onFileTransferComplete(transferId, savedFile.absolutePath, info.fileName, frame.senderId) }
        }
        sendDeliveryReport(frame, true)
        if (frame.hopLimit > 0) relayFrame(frame)
    }

    // === GROUP MESSAGE HANDLERS ===

    private fun handleGroupMsg(frame: MeshFrame) {
        if (frame.targetId != identityDeviceId && frame.targetId != "broadcast") {
            if (frame.hopLimit > 0) relayFrame(frame)
            return
        }
        val groups = groupStore.getAllGroups()
        for (group in groups) {
            try {
                val key = groupStore.getSymmetricKey(group.groupId) ?: continue
                val aad = "MeshGroup:${group.groupId}".toByteArray(Charsets.UTF_8)
                val plain = MeshCrypto.decrypt(key, frame.payload, aad)
                val text = String(plain, Charsets.UTF_8)
                val senderName = peerStore.get(frame.senderId)?.displayName ?: "Unknown"
                listenerList.forEach { it.onGroupMessageReceived(group.groupId, frame.senderId, text, senderName, "${frame.senderId}:${frame.msgSeq}") }
                sendDeliveryReport(frame, true)
                return
            } catch (_: Exception) { }
        }
        sendDeliveryReport(frame, false)
    }

    private fun handleGroupKeyDist(frame: MeshFrame) {
        val peer = peerStore.authorized(frame.senderId) ?: return
        val sharedSecret = MeshCrypto.computeSharedSecret(identityPrivateKey, MeshCrypto.unb64(peer.publicKey))
        try {
            val symmetricKey = MeshCrypto.decrypt(sharedSecret, frame.payload)
            Log.d(TAG, "Group key received: ${frame.senderId}")
        } catch (e: Exception) {
            Log.e(TAG, "Group key decrypt error: ${e.message}")
        }
    }

    // === VOICE MESSAGE HANDLER ===

    private fun handleVoiceMsg(frame: MeshFrame) {
        if (frame.targetId != identityDeviceId) {
            if (frame.hopLimit > 0) relayFrame(frame)
            return
        }
        listenerList.forEach { it.onVoiceMessageReceived(frame.senderId, frame.payload, "${frame.senderId}:${frame.msgSeq}") }
        sendDeliveryReport(frame, true)
    }

    // === DOUBLE RATCHET HANDLERS ===

    private fun handleRatchetInit(frame: MeshFrame) {
        val payload = frame.payload
        if (payload.size < 32) return
        val theirPublicKey = payload.copyOfRange(0, 32)

        val session = DoubleRatchet(
            sharedSecret = ByteArray(32),
            dhSendKeyPair = DoubleRatchet.generateKeyPair(),
            dhRemoteKey = theirPublicKey,
        )
        ratchetSessions.save(frame.senderId, session)
        Log.d(TAG, "Ratchet session started: ${frame.senderId}")
    }

    private fun handleRatchetMsg(frame: MeshFrame) {
        val session = ratchetSessions.load(frame.senderId)
        if (session == null) {
            Log.w(TAG, "Ratchet session not found: ${frame.senderId}")
            return
        }
        val plaintext = session.decrypt(frame.payload)
        if (plaintext != null) {
            ratchetSessions.save(frame.senderId, session)
            val text = String(plaintext, Charsets.UTF_8)
            listenerList.forEach { it.onTextReceived(frame.senderId, text, "${frame.senderId}:${frame.msgSeq}") }
            sendDeliveryReport(frame, true)

            // Key rotation: if 100+ messages or 1 hour has passed
            if (session.shouldRotate()) {
                initiateKeyRotation(frame.senderId)
            }
        }
    }

    private fun initiateKeyRotation(peerId: String) {
        val newKeyPair = DoubleRatchet.generateKeyPair()
        val frame = MeshFrame(
            type = MessageType.RATCHET_INIT,
            hopLimit = 1,
            ttl = 4,
            encrypted = false,
            senderId = identityDeviceId,
            targetId = peerId,
            msgSeq = nextSeq(),
            payload = newKeyPair.publicKey,
            senderPublicKey = newKeyPair.publicKey,
        )
        emitForSend(frame, null)
        Log.d(TAG, "Ratchet key rotation started: $peerId")
    }

    // === PUBLIC FILE/VOICE/GROUP SEND METHODS ===

    fun sendFile(targetId: String, fileBytes: ByteArray, fileName: String, mimeType: String): String? {
        val peer = peerStore.authorized(targetId) ?: return null
        val sharedSecret = MeshCrypto.computeSharedSecret(identityPrivateKey, MeshCrypto.unb64(peer.publicKey))
        val aad = "MeshNet:$targetId".toByteArray(Charsets.UTF_8)
        val encryptedBytes = MeshCrypto.encrypt(sharedSecret, fileBytes, aad)

        val useWifi = fileBytes.size > FileTransferManager.BLE_SIZE_THRESHOLD
        val (transferId, startFrame) = fileTransferManager.startTransfer(
            fileName, fileBytes.size.toLong(), mimeType, identityDeviceId
        )

        emitForSend(startFrame, null)
        val chunkFrames = fileTransferManager.generateChunkFrames(
            transferId, encryptedBytes, identityDeviceId, targetId, useWifi
        )
        chunkFrames.forEach { emitForSend(it, null) }

        return transferId
    }

    fun sendVoiceMessage(targetId: String, audioData: ByteArray, durationMs: Int): String? {
        val peer = peerStore.authorized(targetId) ?: return null
        val sharedSecret = MeshCrypto.computeSharedSecret(identityPrivateKey, MeshCrypto.unb64(peer.publicKey))
        val aad = "MeshNet:$targetId".toByteArray(Charsets.UTF_8)
        val encryptedBytes = MeshCrypto.encrypt(sharedSecret, audioData, aad)

        val payload = ByteArray(4 + encryptedBytes.size)
        payload[0] = (durationMs ushr 24).toByte()
        payload[1] = (durationMs ushr 16).toByte()
        payload[2] = (durationMs ushr 8).toByte()
        payload[3] = durationMs.toByte()
        System.arraycopy(encryptedBytes, 0, payload, 4, encryptedBytes.size)

        val frame = MeshFrame(
            type = MessageType.VOICE_MSG,
            hopLimit = MAX_HOP,
            ttl = 6,
            encrypted = true,
            senderId = identityDeviceId,
            targetId = targetId,
            msgSeq = nextSeq(),
            payload = payload,
            senderPublicKey = null,
        )
        emitForSend(frame, null)
        return "${identityDeviceId}:${frame.msgSeq}"
    }

    fun sendGroupMessage(groupId: String, message: String): String? {
        val group = groupStore.getGroup(groupId) ?: return null
        val symmetricKey = groupStore.getSymmetricKey(groupId) ?: return null
        val aad = "MeshGroup:$groupId".toByteArray(Charsets.UTF_8)
        val ciphertext = MeshCrypto.encrypt(symmetricKey, message.toByteArray(Charsets.UTF_8), aad)

        val memberIds = group.members.map { it.deviceId }.filter { it != identityDeviceId }
        val seq = nextSeq()

        memberIds.forEach { memberId ->
            val frame = MeshFrame(
                type = MessageType.GROUP_MSG,
                hopLimit = MAX_HOP,
                ttl = 6,
                encrypted = true,
                senderId = identityDeviceId,
                targetId = memberId,
                msgSeq = seq,
                payload = ciphertext,
                senderPublicKey = null,
            )
            emitForSend(frame, null)
        }

        return "${identityDeviceId}:$seq"
    }
}