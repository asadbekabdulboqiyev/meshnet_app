package com.meshnet.meshnet_app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.meshnet.meshnet_app.crypto.MeshCrypto
import com.meshnet.meshnet_app.protocol.FileTransferManager
import com.meshnet.meshnet_app.protocol.GroupStore
import com.meshnet.meshnet_app.protocol.MeshFrame
import com.meshnet.meshnet_app.protocol.RoutingEngine
import com.meshnet.meshnet_app.protocol.VoiceEncoder
import com.meshnet.meshnet_app.protocol.VoiceRecorder
import com.meshnet.meshnet_app.storage.MessageStore
import com.meshnet.meshnet_app.storage.PeerStore
import com.meshnet.meshnet_app.transport.BleTransport
import com.meshnet.meshnet_app.transport.TransportManager
import com.meshnet.meshnet_app.transport.WifiDirectTransport
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * MeshEngine - Main controller working with Flutter MethodChannel.
 * Lifecycle: initEngine -> startNode -> [send ...] -> stopNode.
 */
class MeshEngine(private val context: Context) {

    companion object {
        private const val TAG = "MeshEngine"
        const val METHOD_CHANNEL = "meshnet/engine"
        const val EVENT_CHANNEL = "meshnet/events"
        // Heartbeat interval (P5/P6): PEER_PING every 15s + jitter
        private const val HEARTBEAT_MS = 15_000L
        private const val HEARTBEAT_JITTER_MS = 5_000L
        // 3x HEARTBEAT interval (45s) no response -> offline
        private const val PRESENCE_TIMEOUT_MS = 45_000L
    }

    // Core components
    private lateinit var identity: IdentityStore
    private lateinit var peerStore: PeerStore
    private lateinit var messageStore: MessageStore
    private lateinit var bleTransport: BleTransport
    private lateinit var wifiTransport: WifiDirectTransport
    private lateinit var transportManager: TransportManager
    private lateinit var routing: RoutingEngine

    private val fileTransferManager = FileTransferManager()
    private val voiceRecorder = VoiceRecorder(context)
    private val voiceEncoder = VoiceEncoder()
    private val groupStore = GroupStore(context)

    // Event channel sink (to Flutter)
    private var eventSink: EventChannel.EventSink? = null
    private var running = false

    // GATT callbacks run on Binder thread — Flutter events
    // are only sent on main thread (@UiThread requirement).
    private val mainHandler = Handler(Looper.getMainLooper())

    // Background worker (P5: retry/expire, P6: heartbeat/presence)
    private val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var workerJob: Job? = null

    // MethodChannel handler
    val handler = MethodChannel.MethodCallHandler { call, result ->
        handleMethodCall(call, result)
    }

    /** EventChannel stream (Flutter tomondan) setup. */
    val eventListener = object : EventChannel.StreamHandler {
        override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
            eventSink = events
        }

        override fun onCancel(arguments: Any?) {
            eventSink = null
        }
    }

    private fun handleMethodCall(call: MethodCall, result: MethodChannel.Result) {
        if (call.method != "initEngine" && !::identity.isInitialized) {
            result.error("not_initialized", "Engine not initialized yet", null)
            return
        }
        when (call.method) {
            "initEngine" -> {
                val displayName = call.argument<String>("displayName")
                init(displayName)
                result.success(true)
            }

            "startNode" -> {
                result.success(start())
            }

            "stopNode" -> {
                stop()
                result.success(true)
            }

            "getLocalIdentity" -> {
                result.success(
                    mapOf(
                        "deviceId" to identity.deviceId(),
                        "publicKey" to MeshCrypto.b64(identity.publicKey()),
                        "displayName" to identity.displayName(),
                    )
                )
            }

            "scanForPeers" -> {
                // Scan does not restart (always running), result is open
                result.success(true)
            }

            "pairWithPeer" -> {
                val deviceId = call.argument<String>("deviceId")
                val peerPublicKey = call.argument<String>("peerPublicKey")
                if (deviceId == null || peerPublicKey == null) {
                    result.error("bad_args", "deviceId/peerPublicKey required", null)
                    return
                }
                // QR out-of-band trust: immediately authorized
                peerStore.markAuthorized(deviceId, peerPublicKey)
                // Network flow: send PAIR_REQ — peer also recognizes us as
                // authorized and sends back PAIR_ACK (second direction).
                val reqSent = routing.sendPairRequest(deviceId)
                result.success(mapOf(
                    "status" to (if (reqSent) "paired" else "pair_req_failed"),
                ))
            }

            "sendMessage" -> {
                val target = call.argument<String>("targetDeviceId")
                val message = call.argument<String>("message")
                if (target == null || message == null) {
                    result.error("bad_args", "targetDeviceId/message required", null)
                    return
                }
                val messageId = routing.sendText(target, message)
                result.success(mapOf(
                    "status" to (if (messageId != null) "sent" else "failed"),
                    "messageId" to (messageId ?: ""),
                    "timestamp" to System.currentTimeMillis(),
                ))
            }

            "getPeers" -> {
                val now = System.currentTimeMillis()
                val peers = peerStore.all().mapNotNull { p ->
                    val id = p.deviceId ?: return@mapNotNull null
                    if (id.isBlank()) return@mapNotNull null
                    val route = routing.findRoute(id)
                    mapOf(
                        "deviceId" to id,
                        "displayName" to p.displayName,
                        "rssi" to p.rssi,
                        "hop" to (route?.hopCount ?: 0),
                        "authorized" to p.authorized,
                        "online" to (p.lastSeenMs > 0 && now - p.lastSeenMs < 60_000),
                        "linkQuality" to (route?.qualityScore() ?: p.linkQuality),
                        "transport" to p.transport,
                    )
                }
                result.success(peers)
            }

            "findPeer" -> {
                val deviceId = call.argument<String>("deviceId")
                if (deviceId == null) {
                    result.error("bad_args", "deviceId required", null)
                    return
                }
                val sent = routing.sendFindPeer(deviceId)
                result.success(mapOf("status" to (if (sent) "searching" else "failed")))
            }

            "getNodeInfo" -> {
                result.success(mapOf(
                    "deviceId" to identity.deviceId(),
                    "displayName" to identity.displayName(),
                    "publicKey" to MeshCrypto.b64(identity.publicKey()),
                    "running" to running,
                    "peerCount" to peerStore.all().size,
                    "wifiPeers" to (if (::wifiTransport.isInitialized) wifiTransport.socketCount() else 0),
                    "blePeers" to (if (::bleTransport.isInitialized) bleTransport.knownPeerCount() else 0),
                    "stats" to (if (::routing.isInitialized) routing.stats() else emptyMap()),
                    "routes" to (if (::routing.isInitialized) routing.routeSnapshot() else emptyList()),
                ))
            }

            "clearPeer" -> {
                val deviceId = call.argument<String>("deviceId")
                if (deviceId != null) peerStore.remove(deviceId)
                result.success(true)
            }

            "sendFile" -> {
                val targetId = call.argument<String>("targetDeviceId") ?: ""
                val filePath = call.argument<String>("filePath") ?: ""
                try {
                    val file = java.io.File(filePath)
                    val bytes = file.readBytes()
                    val mimeType = context.contentResolver.getType(android.net.Uri.fromFile(file)) ?: "application/octet-stream"
                    val transferId = routing.sendFile(targetId, bytes, file.name, mimeType)
                    result.success(transferId)
                } catch (e: Exception) {
                    Log.e(TAG, "sendFile error: ${e.message}")
                    result.error("SEND_FILE_FAILED", e.message, null)
                }
            }
            "sendImage" -> {
                val targetId = call.argument<String>("targetDeviceId") ?: ""
                val imagePath = call.argument<String>("imagePath") ?: ""
                try {
                    val file = java.io.File(imagePath)
                    val bytes = file.readBytes()
                    val transferId = routing.sendFile(targetId, bytes, file.name, "image/jpeg")
                    result.success(transferId)
                } catch (e: Exception) {
                    Log.e(TAG, "sendImage error: ${e.message}")
                    result.error("SEND_IMAGE_FAILED", e.message, null)
                }
            }
            "cancelTransfer" -> {
                val transferId = call.argument<String>("transferId") ?: ""
                fileTransferManager.cancelTransfer(transferId)
                result.success(true)
            }
            "startRecording" -> {
                voiceRecorder.startRecording()
                result.success(true)
            }
            "stopRecording" -> {
                val pcmData = voiceRecorder.stopRecording()
                if (pcmData != null) {
                    val (aacData, codec) = voiceEncoder.encode(pcmData)
                    val ext = if (codec == "opus") "opus" else "aac"
                    val file = java.io.File(context.cacheDir, "voice_${System.currentTimeMillis()}.$ext")
                    java.io.FileOutputStream(file).use { fos -> fos.write(aacData) }
                    val durationMs = voiceRecorder.getRecordingDurationMs()
                    result.success(mapOf(
                        "filePath" to file.absolutePath,
                        "durationMs" to durationMs,
                        "size" to aacData.size,
                    ))
                } else {
                    result.success(mapOf<String, Any?>("filePath" to null))
                }
            }
            "sendVoiceMessage" -> {
                val targetId = call.argument<String>("targetDeviceId") ?: ""
                val filePath = call.argument<String>("filePath") ?: ""
                val durationMs = call.argument<Int>("durationMs") ?: 0
                try {
                    val bytes = java.io.File(filePath).readBytes()
                    val msgId = routing.sendVoiceMessage(targetId, bytes, durationMs)
                    result.success(msgId)
                } catch (e: Exception) {
                    Log.e(TAG, "sendVoiceMessage error: ${e.message}")
                    result.error("SEND_VOICE_FAILED", e.message, null)
                }
            }
            "createGroup" -> {
                val name = call.argument<String>("name") ?: ""
                val memberIds = call.argument<List<String>>("memberDeviceIds") ?: emptyList()
                try {
                    val members = memberIds.map { deviceId ->
                        val peer = peerStore.get(deviceId)
                        GroupStore.GroupMember(
                            deviceId = deviceId,
                            displayName = peer?.displayName ?: "Unknown",
                            role = "member",
                        )
                    } + GroupStore.GroupMember(
                        deviceId = identity.deviceId(),
                        displayName = identity.displayName(),
                        role = "admin",
                    )
                    val group = groupStore.createGroup(name, members, identity.deviceId())
                    result.success(mapOf(
                        "groupId" to group.groupId,
                        "name" to group.name,
                        "members" to group.members.map { m -> mapOf("deviceId" to m.deviceId, "displayName" to m.displayName, "role" to m.role) },
                        "createdAtMs" to group.createdAtMs,
                        "createdBy" to group.createdBy,
                    ))
                } catch (e: Exception) {
                    Log.e(TAG, "createGroup error: ${e.message}")
                    result.error("CREATE_GROUP_FAILED", e.message, null)
                }
            }
            "getGroups" -> {
                val groups = groupStore.getAllGroups()
                result.success(groups.map { g ->
                    mapOf(
                        "groupId" to g.groupId,
                        "name" to g.name,
                        "members" to g.members.map { m -> mapOf("deviceId" to m.deviceId, "displayName" to m.displayName, "role" to m.role) },
                        "createdAtMs" to g.createdAtMs,
                        "createdBy" to g.createdBy,
                    )
                })
            }
            "sendGroupMessage" -> {
                val groupId = call.argument<String>("groupId") ?: ""
                val message = call.argument<String>("message") ?: ""
                val msgId = routing.sendGroupMessage(groupId, message)
                result.success(msgId != null)
            }
            "getTopology" -> {
                try {
                    val peers = peerStore.all().filter { !it.deviceId.isNullOrBlank() }
                    val routes = routing.routeSnapshot()
                    val nodes = mutableListOf<Map<String, Any?>>()
                    val selfId = identity.deviceId()
                    val selfName = identity.displayName()
                    nodes.add(mapOf(
                        "id" to selfId,
                        "name" to selfName,
                        "isSelf" to true,
                        "isOnline" to true,
                        "quality" to 100,
                        "hops" to 0,
                    ))
                    peers.forEach { peer ->
                        val route = routes.find { r -> r["destination"] == peer.deviceId }
                        val isOnline = (System.currentTimeMillis() - peer.lastSeenMs) < PRESENCE_TIMEOUT_MS
                        nodes.add(mapOf(
                            "id" to peer.deviceId,
                            "name" to peer.displayName,
                            "isSelf" to false,
                            "isOnline" to isOnline,
                            "quality" to (route?.get("quality") as? Int ?: peer.linkQuality),
                            "hops" to (route?.get("hopCount") as? Int ?: 0),
                        ))
                    }
                    val edges = routes.map { route ->
                        mapOf(
                            "from" to route["nextHop"],
                            "to" to route["destination"],
                            "quality" to route["quality"],
                            "hops" to route["hopCount"],
                        )
                    }
                    result.success(mapOf("nodes" to nodes, "edges" to edges))
                } catch (e: Exception) {
                    result.error("TOPOLOGY_FAILED", e.message, null)
                }
            }

            "markMessagesRead" -> {
                val deviceId = call.argument<String>("deviceId")
                if (deviceId == null) {
                    result.error("bad_args", "deviceId required", null)
                    return
                }
                val count = messageStore.markMessagesRead(deviceId)
                // Send read receipt — to message sender
                if (count > 0) {
                    val unreadIds = messageStore.loadIncoming()
                        .filter { it.fromDeviceId == deviceId && it.isRead }
                        .takeLast(count)
                        .map { it.messageId }
                    routing.sendReadReceipt(deviceId, unreadIds)
                }
                result.success(mapOf("marked" to count))
            }

            "getUnreadCounts" -> {
                val counts = mutableMapOf<String, Int>()
                val all = messageStore.loadIncoming()
                for (msg in all) {
                    if (!msg.isRead) {
                        counts[msg.fromDeviceId] = (counts[msg.fromDeviceId] ?: 0) + 1
                    }
                }
                result.success(mapOf(
                    "total" to messageStore.getTotalUnreadCount(),
                    "byDevice" to counts,
                ))
            }

            else -> result.notImplemented()
        }
    }

    /** Create identity (once). */
    fun init(displayName: String?) {
        identity = IdentityStore(context)
        identity.init(displayName)
        peerStore = PeerStore(context)
        messageStore = MessageStore(context)

        // Transports: displayName + deviceId visible in BLE advertising
        bleTransport = BleTransport(context, identity.displayName(), identity.deviceId())
        wifiTransport = WifiDirectTransport(context)
        transportManager = TransportManager(bleTransport, wifiTransport)
        transportManager.setPeerListener(peerListener)

        routing = RoutingEngine(
            context,
            identity.deviceId(),
            identity.privateKey(),
            peerStore,
        )
        routing.setIdentityPublicKey(identity.publicKey())
        routing.addListener(routingListener)
        transportManager.setRouteListener { frame ->
            routing.handleIncomingFrame(frame)
        }
        // P5: restore undelivered messages from disk on app restart
        restoreOutbox()
        Log.i(TAG, "MeshEngine init: ${identity.displayName()} (${identity.deviceId()})")
    }

    /** P5: restore encrypted frames from disk (MessageStore) to RoutingEngine. */
    private fun restoreOutbox() {
        val records = messageStore.loadOutbox().mapNotNull { m ->
            val encoded = m.encodedFrame ?: return@mapNotNull null
            val bytes = try {
                MeshCrypto.unb64(encoded)
            } catch (e: Exception) {
                Log.w(TAG, "restoreOutbox: unb64 error (${m.messageId}): ${e.message}")
                return@mapNotNull null
            }
            val frame = MeshFrame.decode(bytes) ?: return@mapNotNull null
            m.messageId to frame
        }
        routing.restoreOutbox(records)
    }

    /** P5: persist outbox to disk (on every change — compact, encrypted base64). */
    private fun persistOutbox() {
        val records = routing.outboxSnapshot().map { (msgId, frame) ->
            MessageStore.OutboxMessage(
                messageId = msgId,
                targetDeviceId = frame.targetId,
                encodedFrame = MeshCrypto.b64(MeshFrame.encode(frame)),
                createdAtMs = frame.msgSeq,
            )
        }
        messageStore.saveOutbox(records)
    }

    /** Discovery/lost events: update PeerStore, emit to Flutter. */
    private val peerListener = object : TransportManager.PeerListener {
        override fun onPeerDiscovered(
            deviceId: String,
            displayName: String,
            rssi: Int,
            transport: String,
        ) {
            // Scan results may arrive before init completes (release/startup race)
            if (!::peerStore.isInitialized || !::routing.isInitialized) {
                Log.w(TAG, "onPeerDiscovered: engine not ready yet ($deviceId) — skipped")
                return
            }
            val existing = peerStore.get(deviceId)
            val existingName = existing?.displayName
            val display = if (existingName.isNullOrBlank() || existingName == "Peer") {
                displayName
            } else {
                existingName
            }
            peerStore.upsert(
                PeerStore.Peer(
                    deviceId = deviceId,
                    displayName = display,
                    publicKey = existing?.publicKey ?: "",
                    authorized = existing?.authorized ?: false,
                    lastSeenMs = System.currentTimeMillis(),
                    transport = transport,
                    rssi = rssi,
                )
            )
            // P5: peer returned — send all pending messages to it
            routing.retryPending(deviceId)
            // Route learning: peer directly reachable (hop=1)
            routing.learnRoute(deviceId, deviceId, 1, maxOf(0, 100 + rssi))
            emit("peerDiscovered", mapOf(
                "deviceId" to deviceId,
                "displayName" to display,
                "rssi" to rssi,
                "transport" to transport,
            ))
        }

        override fun onPeerLost(deviceId: String) {
            if (!::peerStore.isInitialized) return
            val existing = peerStore.get(deviceId)
            if (existing != null) {
                peerStore.upsert(existing.copy(lastSeenMs = 0))
            }
            emit("peerLost", mapOf("deviceId" to deviceId))
        }
    }

    private val routingListener = object : RoutingEngine.MessageListener {
        override fun onTextReceived(from: String, message: String, messageId: String) {
            messageStore.addIncoming(MessageStore.IncomingMessage(messageId, from, message))
            emit("messageReceived", mapOf(
                "fromDeviceId" to from,
                "message" to message,
                "messageId" to messageId,
                "timestamp" to System.currentTimeMillis(),
            ))
        }

        override fun onDeliveryReport(messageId: String, delivered: Boolean) {
            emit("deliveryStatus", mapOf(
                "messageId" to messageId,
                "status" to (if (delivered) "delivered" else "failed"),
            ))
        }

        override fun onPairResult(deviceId: String, success: Boolean) {
            emit("pairResult", mapOf(
                "deviceId" to deviceId,
                "success" to success,
            ))
        }

        override fun onPeerFound(deviceId: String) {
            // P5: immediately send queued messages for found peer
            routing.retryPending(deviceId)
            emit("peerFound", mapOf("deviceId" to deviceId))
        }

        override fun onOutboxChanged(messageId: String, status: String) {
            persistOutbox()
            emit("outboxStatus", mapOf(
                "messageId" to messageId,
                "status" to status,
            ))
        }

        override fun onFrameToSend(frame: MeshFrame, transport: String?) {
            if (transport != null) {
                val t = when (transport) {
                    "wifi" -> TransportManager.Transport.WIFI
                    "ble" -> TransportManager.Transport.BLE
                    else -> TransportManager.Transport.AUTO
                }
                transportManager.sendFrame(frame.targetId, frame, t) {}
            } else {
                transportManager.sendFrame(frame.targetId, frame, TransportManager.Transport.AUTO) {}
            }
        }

        override fun onRouteChanged(destination: String, nextHop: String, hopCount: Int, quality: Int) {
            Log.d(TAG, "Route changed: $destination via $nextHop (hop=$hopCount, q=$quality)")
            emit("routeChanged", mapOf(
                "destination" to destination,
                "nextHop" to nextHop,
                "hopCount" to hopCount,
                "quality" to quality,
            ))
        }

        override fun onReadReceived(fromDeviceId: String, messageIds: List<String>) {
            emit("readReceipt", mapOf(
                "fromDeviceId" to fromDeviceId,
                "messageIds" to messageIds,
            ))
        }

        override fun onGroupMessageReceived(groupId: String, senderId: String, message: String, senderName: String, messageId: String) {
            emit("groupMessageReceived", mapOf(
                "groupId" to groupId,
                "senderId" to senderId,
                "message" to message,
                "senderName" to senderName,
                "messageId" to messageId,
                "timestamp" to System.currentTimeMillis(),
            ))
        }
    }

    /** Start all transports. */
    fun start(): Boolean {
        if (running) return true
        if (!::identity.isInitialized) init(null)
        emit("engineState", mapOf("state" to "starting"))
        try {
            transportManager.start()
            running = true
            startWorker()
            emit("engineState", mapOf("state" to "running"))
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Start error: ${e.message}")
            emit("engineState", mapOf("state" to "error", "reason" to (e.message ?: "")))
            return false
        }
    }

    /** P5/P6: periodic work — retry (S&F), expire (TTL), heartbeat (PEER_PING),
     *  presence sweep (45s no response -> offline). Jitter for battery. */
    private fun startWorker() {
        workerJob?.cancel()
        workerJob = workerScope.launch {
            while (running) {
                delay(HEARTBEAT_MS + Random.nextLong(0, HEARTBEAT_JITTER_MS))
                if (!running) break
                try {
                    routing.retryPending(null)
                    routing.expirePending()
                    routing.sendPing()
                    presenceSweep()
                } catch (e: Exception) {
                    Log.w(TAG, "Worker iteration error: ${e.message}")
                }
            }
        }
    }

    /** P6: mark peer as offline if not seen for 45s. */
    private fun presenceSweep() {
        val now = System.currentTimeMillis()
        peerStore.all().forEach { p ->
            val id = p.deviceId ?: return@forEach
            if (id.isBlank()) return@forEach
            if (p.lastSeenMs > 0 && now - p.lastSeenMs > PRESENCE_TIMEOUT_MS) {
                peerStore.upsert(p.copy(lastSeenMs = 0))
                emit("peerLost", mapOf("deviceId" to id))
            }
        }
    }

    fun stop() {
        if (!::transportManager.isInitialized) return
        workerJob?.cancel()
        try { transportManager.stop() } catch (_: Exception) {}
        running = false
        emit("engineState", mapOf("state" to "stopped"))
    }

    private fun emit(event: String, data: Map<String, Any?>) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            eventSink?.success(mapOf("event" to event).plus(data))
        } else {
            mainHandler.post { eventSink?.success(mapOf("event" to event).plus(data)) }
        }
    }
}