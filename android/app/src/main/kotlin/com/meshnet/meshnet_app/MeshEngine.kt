package com.meshnet.meshnet_app

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.meshnet.meshnet_app.crypto.MeshCrypto
import com.meshnet.meshnet_app.localnet.LocalNetService
import com.meshnet.meshnet_app.protocol.FileTransferManager
import com.meshnet.meshnet_app.protocol.GroupStore
import com.meshnet.meshnet_app.protocol.MeshFrame
import com.meshnet.meshnet_app.protocol.RoutingEngine
import com.meshnet.meshnet_app.protocol.VoiceEncoder
import com.meshnet.meshnet_app.protocol.VoicePlayer
import com.meshnet.meshnet_app.protocol.VoiceRecorder
import com.meshnet.meshnet_app.storage.MeshDatabase
import com.meshnet.meshnet_app.storage.MessageStore
import com.meshnet.meshnet_app.storage.PeerStore
import com.meshnet.meshnet_app.transport.BleTransport
import com.meshnet.meshnet_app.transport.MeshBackgroundService
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
    private val voicePlayer = VoicePlayer(voiceEncoder)

    /** messageId -> playable encoded audio file (sent + received). */
    private val voiceFiles = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val groupStore = GroupStore(context)
    private val db = MeshDatabase.getInstance(context)

    // LocalNet (Phase 1): created in init(), started/stopped with the engine
    private var localNet: LocalNetService? = null

    // App Distribution (Phase 4): offline APK repository over file sharing
    var apps: com.meshnet.meshnet_app.localnet.apps.AppRepository? = null
        private set

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

            "syncWithBackground" -> {
                val res = syncWithBackgroundService()
                result.success(res ?: mapOf("synced" to false))
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
                    val msgId = routing.sendVoiceMessage(
                        targetId, bytes, durationMs,
                        VoicePlayer.codecFromPath(filePath),
                    )
                    if (msgId != null) voiceFiles[msgId] = filePath
                    result.success(msgId)
                } catch (e: Exception) {
                    Log.e(TAG, "sendVoiceMessage error: ${e.message}")
                    result.error("SEND_VOICE_FAILED", e.message, null)
                }
            }
            "playVoiceMessage" -> {
                val messageId = call.argument<String>("messageId") ?: ""
                val path = voiceFiles[messageId]
                if (path == null) {
                    Log.w(TAG, "playVoiceMessage: no audio for $messageId")
                    result.success(false)
                } else {
                    result.success(voicePlayer.play(messageId, path))
                }
            }
            "pauseVoiceMessage" -> {
                val messageId = call.argument<String>("messageId") ?: ""
                result.success(voicePlayer.pause(messageId))
            }
            "setVoicePlaybackSpeed" -> {
                val messageId = call.argument<String>("messageId") ?: ""
                val speed = (call.argument<Number>("speed") ?: 1.0).toFloat()
                result.success(voicePlayer.setSpeed(messageId, speed))
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
                    // Distribute the definition to members so they can see and join.
                    routing.distributeGroup(group)
                    // Persist own message history baseline (empty at creation).
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
                if (msgId != null) {
                    // Persist own message so history survives restarts.
                    try {
                        db.insertGroupMessage(MeshDatabase.GroupMessage(
                            messageId = msgId,
                            groupId = groupId,
                            senderId = identity.deviceId(),
                            senderName = identity.displayName(),
                            message = message,
                            fromMe = true,
                            timestampMs = System.currentTimeMillis(),
                            status = "pending",
                        ))
                    } catch (e: Exception) {
                        Log.w(TAG, "group message persist failed: ${e.message}")
                    }
                }
                result.success(msgId)
            }
            "getGroupMessages" -> {
                val groupId = call.argument<String>("groupId") ?: ""
                val msgs = db.getGroupMessages(groupId).map { m ->
                    mapOf(
                        "messageId" to m.messageId,
                        "groupId" to m.groupId,
                        "senderId" to m.senderId,
                        "senderName" to m.senderName,
                        "message" to m.message,
                        "fromMe" to m.fromMe,
                        "timestamp" to m.timestampMs,
                        "status" to m.status,
                    )
                }
                result.success(msgs)
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

            "getLocalNetInfo" -> {
                val ln = localNet
                result.success(mapOf(
                    "available" to (ln != null),
                    "hostname" to (ln?.selfHostname ?: ""),
                    "fqdn" to (ln?.selfHostname?.let { "$it.mesh" } ?: ""),
                    "httpPort" to (if (ln?.httpServer?.isRunning == true) ln.httpServer!!.boundPort else -1),
                    "hosts" to (ln?.hostsSnapshot() ?: emptyList<Map<String, Any?>>()),
                ))
            }

            "resolveHost" -> {
                val hostname = call.argument<String>("hostname") ?: ""
                if (hostname.isBlank()) {
                    result.error("bad_args", "hostname required", null)
                    return
                }
                val entry = localNet?.resolve(hostname)
                result.success(mapOf(
                    "found" to (entry != null),
                    "hostname" to hostname,
                    "deviceId" to (entry?.deviceId ?: ""),
                    "pendingQuery" to (entry == null && localNet != null),
                ))
            }

            // ---------------- LocalNet Phase 2: file sharing ----------------

            "shareLocalFile" -> {
                val filePath = call.argument<String>("filePath") ?: ""
                val ln = localNet
                if (filePath.isBlank() || ln == null) {
                    result.error("bad_args", "filePath required", null)
                    return
                }
                val manifest = ln.shareFile(filePath)
                if (manifest != null) {
                    result.success(manifestToMap(manifest))
                } else {
                    result.error("SHARE_FAILED", "Could not read or chunk file", null)
                }
            }

            "getSharedFiles" -> {
                val files: List<Map<String, Any?>> = localNet?.sharedFiles()?.map { manifestToMap(it) } ?: emptyList()
                result.success(files)
            }

            "unshareFile" -> {
                val fileId = call.argument<String>("fileId") ?: ""
                result.success(localNet?.unshareFile(fileId) ?: false)
            }

            "getHostFiles" -> {
                val hostname = call.argument<String>("hostname") ?: ""
                val ln = localNet
                if (hostname.isBlank() || ln == null) {
                    result.error("bad_args", "hostname required", null)
                    return
                }
                // Network I/O — reply asynchronously from worker thread
                workerScope.launch(Dispatchers.IO) {
                    val files = try {
                        ln.fetchHostFileList(hostname).map { manifestToMap(it) }
                    } catch (e: Exception) {
                        Log.w(TAG, "getHostFiles error: ${e.message}")
                        emptyList<Map<String, Any?>>()
                    }
                    result.success(files)
                }
            }

            "fetchHostFile" -> {
                val hostname = call.argument<String>("hostname") ?: ""
                val fileId = call.argument<String>("fileId") ?: ""
                val ln = localNet
                if (hostname.isBlank() || fileId.isBlank() || ln == null) {
                    result.error("bad_args", "hostname/fileId required", null)
                    return
                }
                val started = ln.fetchFile(hostname, fileId)
                result.success(mapOf("started" to started))
            }

            // ---------------- LocalNet Phase 3: collaboration ----------------

            "createBoard" -> {
                val roomId = (call.argument<String>("roomId") ?: "").trim()
                val ln = localNet
                if (ln == null) {
                    result.error("unavailable", "engine not ready", null)
                    return
                }
                val id = if (roomId.isBlank()) "board-${java.util.UUID.randomUUID().toString().replace("-", "").take(8)}" else roomId
                val board = ln.collab.ensureBoard(id)
                if (board != null) {
                    result.success(mapOf("roomId" to board.roomId, "strokeCount" to board.size))
                } else {
                    result.error("bad_args", "invalid roomId", null)
                }
            }

            "sendStroke" -> {
                val roomId = call.argument<String>("roomId") ?: ""
                val color = call.argument<Number>("color")?.toInt() ?: 0xFF000000.toInt()
                val width = call.argument<Number>("width")?.toFloat() ?: 3f
                val rawPoints = call.argument<List<List<Number>>>("points")
                val ln = localNet
                if (roomId.isBlank() || rawPoints.isNullOrEmpty() || ln == null) {
                    result.error("bad_args", "roomId/points required", null)
                    return
                }
                val points = rawPoints.mapNotNull { p ->
                    val x = p.getOrNull(0)?.toFloat()
                    val y = p.getOrNull(1)?.toFloat()
                    if (x != null && y != null) {
                        com.meshnet.meshnet_app.localnet.collab.WhiteboardState.Point(
                            (x * 10).toInt() / 10f, (y * 10).toInt() / 10f,
                        )
                    } else null
                }
                val strokeId = ln.collab.addStrokeLocal(roomId, color, width, points)
                if (strokeId != null) {
                    result.success(mapOf("strokeId" to strokeId))
                } else {
                    result.error("STROKE_FAILED", "could not add stroke", null)
                }
            }

            "getBoard" -> {
                val roomId = call.argument<String>("roomId") ?: ""
                val ln = localNet
                if (roomId.isBlank() || ln == null) {
                    result.error("bad_args", "roomId required", null)
                    return
                }
                val board = ln.collab.boards[roomId]
                result.success(mapOf(
                    "roomId" to roomId,
                    "exists" to (board != null),
                    "strokes" to (board?.all()?.map { s ->
                        mapOf(
                            "strokeId" to s.strokeId,
                            "authorId" to s.authorId,
                            "color" to s.color,
                            "width" to s.width,
                            "points" to s.points.map { listOf(it.x, it.y) },
                        )
                    } ?: emptyList()),
                ))
            }

            "clearBoard" -> {
                val roomId = call.argument<String>("roomId") ?: ""
                val ln = localNet
                if (roomId.isBlank() || ln == null) {
                    result.error("bad_args", "roomId required", null)
                    return
                }
                result.success(mapOf("cleared" to ln.collab.clearBoardLocal(roomId)))
            }

            "createDoc" -> {
                val docId = (call.argument<String>("docId") ?: "").trim()
                val title = call.argument<String>("title") ?: "Untitled"
                val ln = localNet
                if (ln == null) {
                    result.error("unavailable", "engine not ready", null)
                    return
                }
                val id = if (docId.isBlank()) "doc-${java.util.UUID.randomUUID().toString().replace("-", "").take(8)}" else docId
                val doc = ln.collab.ensureDoc(id, title)
                if (doc != null) {
                    // Broadcast so peers see the doc immediately, not just after edits.
                    ln.collab.announceDoc(doc.docId)
                    result.success(mapOf("docId" to doc.docId, "title" to doc.title, "rev" to doc.rev, "text" to doc.text))
                } else {
                    result.error("bad_args", "invalid docId", null)
                }
            }

            "editDoc" -> {
                val docId = call.argument<String>("docId") ?: ""
                val text = call.argument<String>("text")
                if (text == null) {
                    result.error("bad_args", "text required", null)
                    return
                }
                val ln = localNet
                if (docId.isBlank() || ln == null) {
                    result.error("bad_args", "docId required", null)
                    return
                }
                val rev = ln.collab.editDocLocal(docId, text)
                if (rev >= 0) {
                    result.success(mapOf("docId" to docId, "rev" to rev))
                } else {
                    result.error("EDIT_FAILED", "rejected (too large or stale revision)", null)
                }
            }

            "getDoc" -> {
                val docId = call.argument<String>("docId") ?: ""
                val ln = localNet
                if (docId.isBlank() || ln == null) {
                    result.error("bad_args", "docId required", null)
                    return
                }
                val doc = ln.collab.docs[docId]
                if (doc != null) {
                    result.success(mapOf("docId" to doc.docId, "title" to doc.title, "rev" to doc.rev, "text" to doc.text))
                } else {
                    result.success(null)
                }
            }

            "listDocs" -> {
                result.success(localNet?.collab?.listDocs() ?: emptyList<Map<String, Any?>>())
            }

            "createPoll" -> {
                val question = call.argument<String>("question") ?: ""
                @Suppress("UNCHECKED_CAST")
                val options = call.argument<List<String>>("options") ?: emptyList()
                val ln = localNet
                if (question.isBlank() || options.size < 2 || ln == null) {
                    result.error("bad_args", "question + 2+ options required", null)
                    return
                }
                val poll = ln.collab.createPollLocal(question, options)
                if (poll != null) {
                    result.success(mapOf("pollId" to poll.pollId, "question" to poll.question, "options" to poll.options))
                } else {
                    result.error("POLL_FAILED", "could not create poll", null)
                }
            }

            "votePoll" -> {
                val pollId = call.argument<String>("pollId") ?: ""
                val optionIndex = call.argument<Number>("optionIndex")?.toInt() ?: -1
                val ln = localNet
                if (pollId.isBlank() || ln == null) {
                    result.error("bad_args", "pollId required", null)
                    return
                }
                result.success(mapOf("accepted" to ln.collab.voteLocal(pollId, optionIndex)))
            }

            "getPolls" -> {
                result.success(localNet?.collab?.pollsSnapshotData() ?: emptyList<Map<String, Any?>>())
            }

            // ------------- LocalNet Phase 4: app distribution -------------

            "getLocalApps" -> {
                result.success(apps?.localApps()?.map { it.toMap() } ?: emptyList<Map<String, Any?>>())
            }

            "getHostApps" -> {
                val hostname = call.argument<String>("hostname") ?: ""
                val repo = apps
                if (hostname.isBlank() || repo == null) {
                    result.error("bad_args", "hostname required", null)
                    return
                }
                // Blocking HTTP listing -> run off the main thread
                workerScope.launch {
                    val list = try {
                        repo.hostApps(hostname).map { it.toMap() }
                    } catch (e: Exception) {
                        Log.w(TAG, "getHostApps($hostname) failed: ${e.message}")
                        emptyList<Map<String, Any?>>()
                    }
                    mainHandler.post { result.success(list) }
                }
            }

            "installApk" -> {
                val fileId = call.argument<String>("fileId") ?: ""
                val repo = apps
                if (fileId.isBlank() || repo == null) {
                    result.error("bad_args", "fileId required", null)
                    return
                }
                val path = repo.downloadedPath(fileId)
                    ?: repo.localApps().firstOrNull { it.fileId == fileId }
                        ?.let { localNet?.sharedOriginPaths?.get(fileId) }
                if (path == null) {
                    result.error("NOT_DOWNLOADED", "APK not available locally", null)
                    return
                }
                result.success(mapOf("launched" to com.meshnet.meshnet_app.localnet.apps.ApkInstaller.install(context, path)))
            }

            // ------------- LocalNet Phase 5: internet gateway -------------

            "startInternetGateway" -> {
                val ln = localNet
                if (ln == null) {
                    result.error("unavailable", "engine not ready", null)
                    return
                }
                val port = call.argument<Number>("port")?.toInt() ?: 0
                val bound = ln.startGateway(port)
                if (bound > 0) {
                    result.success(mapOf("running" to true, "port" to bound))
                } else {
                    result.error("GATEWAY_FAILED", "could not start proxy server", null)
                }
            }

            "stopInternetGateway" -> {
                localNet?.stopGateway()
                result.success(mapOf("running" to false))
            }

            "getGateways" -> {
                result.success(localNet?.gatewaysSnapshot() ?: emptyList<Map<String, Any?>>())
            }

            "testGateway" -> {
                val hostname = call.argument<String>("hostname") ?: ""
                val ln = localNet
                if (hostname.isBlank() || ln == null) {
                    result.error("bad_args", "hostname required", null)
                    return
                }
                // Blocking HTTP probe -> run off the main thread
                workerScope.launch {
                    val probe = try {
                        ln.probeGateway(hostname)
                    } catch (e: Exception) {
                        Log.w(TAG, "testGateway($hostname) failed: ${e.message}")
                        null
                    }
                    mainHandler.post { result.success(probe) }
                }
            }

            // ------------- LocalNet Phase 6: RBAC, Emergency, Search -------------

            "sendEmergencyAlert" -> {
                val ln = localNet ?: run { result.error("unavailable", "engine not ready", null); return }
                val level = call.argument<Int>("level") ?: 1
                val title = call.argument<String>("title") ?: ""
                val message = call.argument<String>("message") ?: ""
                val location = call.argument<String>("location")
                val coordinates = call.argument<String>("coordinates")
                val ttlMinutes = call.argument<Number>("ttlMinutes")?.toInt() ?: 60
                val requiresAck = call.argument<Boolean>("requiresAck") ?: true
                val alertLevel = com.meshnet.meshnet_app.localnet.emergency.EmergencyManager.AlertLevel.fromPriority(level)
                val alert = ln.sendEmergencyAlert(alertLevel, title, message, location, coordinates, ttlMinutes, requiresAck)
                result.success(mapOf(
                    "alertId" to alert.alertId,
                    "senderId" to alert.senderId,
                    "level" to alert.level.name,
                    "title" to alert.title,
                    "message" to alert.message,
                    "expiresAtMs" to alert.expiresAtMs,
                ))
            }

            "acknowledgeEmergency" -> {
                val ln = localNet ?: run { result.error("unavailable", "engine not ready", null); return }
                val alertId = call.argument<String>("alertId") ?: ""
                if (alertId.isBlank()) { result.error("bad_args", "alertId required", null); return }
                ln.acknowledgeEmergency(alertId)
                result.success(mapOf("acknowledged" to true))
            }

            "cancelEmergency" -> {
                val ln = localNet ?: run { result.error("unavailable", "engine not ready", null); return }
                val alertId = call.argument<String>("alertId") ?: ""
                if (alertId.isBlank()) { result.error("bad_args", "alertId required", null); return }
                val ok = ln.cancelEmergencyAlert(alertId)
                result.success(mapOf("cancelled" to ok))
            }

            "getEmergencies" -> {
                val ln = localNet ?: run { result.error("unavailable", "engine not ready", null); return }
                val alerts = ln.getActiveEmergencies().map { a ->
                    mapOf(
                        "alertId" to a.alertId,
                        "senderId" to a.senderId,
                        "senderName" to a.senderName,
                        "level" to a.level.name,
                        "title" to a.title,
                        "message" to a.message,
                        "location" to a.location,
                        "coordinates" to a.coordinates,
                        "expiresAtMs" to a.expiresAtMs,
                        "requiresAck" to a.requiresAck,
                        "metadata" to a.metadata,
                    )
                }
                result.success(alerts)
            }

            "setDeviceRole" -> {
                val ln = localNet ?: run { result.error("unavailable", "engine not ready", null); return }
                val deviceId = call.argument<String>("deviceId") ?: ""
                val roleStr = call.argument<String>("role") ?: ""
                if (deviceId.isBlank() || roleStr.isBlank()) { result.error("bad_args", "deviceId and role required", null); return }
                val role = com.meshnet.meshnet_app.localnet.rbac.Role.fromString(roleStr)
                ln.setDeviceRole(deviceId, role)
                result.success(mapOf("ok" to true))
            }

            "getDeviceRole" -> {
                val ln = localNet ?: run { result.error("unavailable", "engine not ready", null); return }
                val deviceId = call.argument<String>("deviceId") ?: ""
                if (deviceId.isBlank()) { result.error("bad_args", "deviceId required", null); return }
                val role = ln.getDeviceRole(deviceId)
                result.success(mapOf("deviceId" to deviceId, "role" to role.name))
            }

            "setResourceRole" -> {
                val ln = localNet ?: run { result.error("unavailable", "engine not ready", null); return }
                val resourceType = call.argument<String>("resourceType") ?: ""
                val resourceId = call.argument<String>("resourceId") ?: ""
                val deviceId = call.argument<String>("deviceId") ?: ""
                val roleStr = call.argument<String>("role") ?: ""
                if (resourceType.isBlank() || resourceId.isBlank() || deviceId.isBlank() || roleStr.isBlank()) {
                    result.error("bad_args", "all fields required", null); return
                }
                val role = com.meshnet.meshnet_app.localnet.rbac.Role.fromString(roleStr)
                ln.setResourceRole(resourceType, resourceId, deviceId, role)
                result.success(mapOf("ok" to true))
            }

            "checkPermission" -> {
                val ln = localNet ?: run { result.error("unavailable", "engine not ready", null); return }
                val deviceId = call.argument<String>("deviceId") ?: ""
                val permissionKey = call.argument<String>("permission") ?: ""
                val resourceType = call.argument<String>("resourceType")
                val resourceId = call.argument<String>("resourceId")
                if (deviceId.isBlank() || permissionKey.isBlank()) { result.error("bad_args", "deviceId and permission required", null); return }
                val permission = com.meshnet.meshnet_app.localnet.rbac.Permission.fromKey(permissionKey)
                if (permission == null) { result.error("bad_args", "unknown permission: $permissionKey", null); return }
                val hasPerm = if (resourceType != null && resourceId != null) {
                    ln.hasPermission(deviceId, resourceType, resourceId, permission)
                } else {
                    ln.hasPermission(deviceId, permission)
                }
                result.success(mapOf("hasPermission" to hasPerm))
            }

            "banDevice" -> {
                val ln = localNet ?: run { result.error("unavailable", "engine not ready", null); return }
                val deviceId = call.argument<String>("deviceId") ?: ""
                if (deviceId.isBlank()) { result.error("bad_args", "deviceId required", null); return }
                ln.banDevice(deviceId)
                result.success(mapOf("banned" to true))
            }

            "unbanDevice" -> {
                val ln = localNet ?: run { result.error("unavailable", "engine not ready", null); return }
                val deviceId = call.argument<String>("deviceId") ?: ""
                if (deviceId.isBlank()) { result.error("bad_args", "deviceId required", null); return }
                ln.unbanDevice(deviceId)
                result.success(mapOf("unbanned" to true))
            }

            "searchLocal" -> {
                val ln = localNet ?: run { result.error("unavailable", "engine not ready", null); return }
                val terms = (call.argument<List<String>>("terms") ?: emptyList<String>()).filter { it.isNotBlank() }
                val resourceTypes = (call.argument<List<String>>("resourceTypes") ?: emptyList<String>()).toSet()
                val maxResults = call.argument<Number>("maxResults")?.toInt() ?: 20
                if (terms.isEmpty()) { result.success(emptyList<Any>()); return }
                val results = ln.searchLocal(terms, resourceTypes, maxResults).map { r ->
                    mapOf(
                        "docId" to r.docId,
                        "resourceType" to r.resourceType,
                        "ownerId" to r.ownerId,
                        "title" to r.title,
                        "snippet" to r.snippet,
                        "score" to r.score,
                        "metadata" to r.metadata,
                    )
                }
                result.success(results)
            }

            "searchDistributed" -> {
                val ln = localNet ?: run { result.error("unavailable", "engine not ready", null); return }
                val terms = (call.argument<List<String>>("terms") ?: emptyList<String>()).filter { it.isNotBlank() }
                val resourceTypes = (call.argument<List<String>>("resourceTypes") ?: emptyList<String>()).toSet()
                val maxResults = call.argument<Number>("maxResults")?.toInt() ?: 20
                if (terms.isEmpty()) { result.success(emptyList<Any>()); return }
                val queryId = ln.searchDistributed(terms, resourceTypes, maxResults) { res ->
                    mainHandler.post { emit("searchResult", mapOf(
                        "queryId" to res.queryId,
                        "responderId" to res.responderId,
                        "results" to res.results.map { r ->
                            mapOf(
                                "docId" to r.docId,
                                "resourceType" to r.resourceType,
                                "ownerId" to r.ownerId,
                                "title" to r.title,
                                "snippet" to r.snippet,
                                "score" to r.score,
                                "metadata" to r.metadata,
                            )
                        },
                        "totalHits" to res.totalHits,
                        "tookMs" to res.tookMs,
                    ))}
                }
                result.success(mapOf("queryId" to queryId))
            }

            "indexContent" -> {
                val ln = localNet ?: run { result.error("unavailable", "engine not ready", null); return }
                val resourceType = call.argument<String>("resourceType") ?: ""
                val resourceId = call.argument<String>("resourceId") ?: ""
                val title = call.argument<String>("title") ?: ""
                val content = call.argument<String>("content") ?: ""
                val tags = call.argument<List<String>>("tags") ?: emptyList()
                val metadata = (call.argument<Map<String, String>>("metadata") ?: emptyMap())
                if (resourceType.isBlank() || resourceId.isBlank() || title.isBlank() || content.isBlank()) {
                    result.error("bad_args", "resourceType, resourceId, title, content required", null); return
                }
                val docId = ln.indexContent(resourceType, resourceId, title, content, tags, metadata)
                result.success(mapOf("docId" to docId))
            }

            "removeFromIndex" -> {
                val ln = localNet ?: run { result.error("unavailable", "engine not ready", null); return }
                val docId = call.argument<String>("docId") ?: ""
                if (docId.isBlank()) { result.error("bad_args", "docId required", null); return }
                ln.removeFromIndex(docId)
                result.success(mapOf("removed" to true))
            }

            "getSearchStats" -> {
                val ln = localNet ?: run { result.error("unavailable", "engine not ready", null); return }
                result.success(ln.getSearchStats())
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
        voicePlayer.listener = object : VoicePlayer.Listener {
            override fun onPlaybackStateChanged(
                messageId: String,
                isPlaying: Boolean,
                positionMs: Long,
                durationMs: Long,
                finished: Boolean,
            ) {
                emit("voicePlayback", mapOf(
                    "messageId" to messageId,
                    "isPlaying" to isPlaying,
                    "positionMs" to positionMs,
                    "durationMs" to durationMs,
                    "finished" to finished,
                ))
            }
        }
        transportManager.setRouteListener { frame ->
            routing.handleIncomingFrame(frame)
        }
        // LocalNet (Phase 1+2): decentralized DNS, offline HTTP, file sharing
        localNet = LocalNetService(
            selfDeviceId = identity.deviceId(),
            selfDisplayName = identity.displayName(),
            routing = routing,
            baseDir = java.io.File(context.filesDir, "localnet"),
        ).also { ln ->
            ln.addListener(localNetListener)
            ln.collab.addListener(collabListener)
            routing.dnsHandler = ln
            routing.collabHandler = ln.collab
            routing.gatewayHandler = ln
        }
        // Phase 4: APK repository on top of the file-sharing transport
        apps = com.meshnet.meshnet_app.localnet.apps.AppRepository(
            requireNotNull(localNet),
            com.meshnet.meshnet_app.localnet.apps.PackageManagerApkExtractor(context),
        )
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
            val status = if (delivered) "delivered" else "failed"
            // If this message belongs to a group chat, persist + notify as group event.
            val groupMsg = try { db.getGroupMessageById(messageId) } catch (_: Exception) { null }
            if (groupMsg != null && groupMsg.fromMe) {
                try { db.updateGroupMessageStatus(messageId, status) } catch (_: Exception) {}
            }
            emit("deliveryStatus", mapOf(
                "messageId" to messageId,
                "status" to status,
            ))
            // Group delivery events drive GroupChatView status icons.
            if (groupMsg != null) {
                emit("groupDeliveryStatus", mapOf(
                    "messageId" to messageId,
                    "status" to status,
                    "groupId" to groupMsg.groupId,
                ))
            }
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
            // Broadcast frames (collab, DNS, emergency, search, presence) must
            // FLOOD to every connected peer — no transport can resolve the
            // literal "broadcast" target, so sendFrame would drop them.
            if (frame.targetId == MeshFrame.BROADCAST) {
                transportManager.flood(frame)
                return
            }
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
            // Persist so history survives restarts (dedup by messageId).
            try {
                db.insertGroupMessage(MeshDatabase.GroupMessage(
                    messageId = messageId,
                    groupId = groupId,
                    senderId = senderId,
                    senderName = senderName,
                    message = message,
                    fromMe = false,
                    timestampMs = System.currentTimeMillis(),
                    status = "delivered",
                ))
            } catch (e: Exception) {
                Log.w(TAG, "group msg persist failed: ${e.message}")
            }
            emit("groupMessageReceived", mapOf(
                "groupId" to groupId,
                "senderId" to senderId,
                "message" to message,
                "senderName" to senderName,
                "messageId" to messageId,
                "timestamp" to System.currentTimeMillis(),
            ))
        }

        override fun onGroupReceived(groupId: String, name: String, memberCount: Int) {
            emit("groupDiscovered", mapOf(
                "groupId" to groupId,
                "name" to name,
                "memberCount" to memberCount,
            ))
        }

        override fun onVoiceMessageReceived(senderId: String, audioData: ByteArray, messageId: String, durationMs: Int, codec: String) {
            try {
                val safe = messageId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
                val ext = if (codec.equals("opus", ignoreCase = true)) "opus" else "aac"
                val file = java.io.File(context.cacheDir, "voice_recv_$safe.$ext")
                java.io.FileOutputStream(file).use { it.write(audioData) }
                voiceFiles[messageId] = file.absolutePath
                emit("voiceMessageReceived", mapOf(
                    "fromDeviceId" to senderId,
                    "messageId" to messageId,
                    "filePath" to file.absolutePath,
                    "durationMs" to durationMs,
                    "timestamp" to System.currentTimeMillis(),
                ))
            } catch (e: Exception) {
                Log.e(TAG, "onVoiceMessageReceived error: ${e.message}")
            }
        }
    }

    /** LocalNet events -> Flutter. */
    private val localNetListener = object : LocalNetService.Listener {        override fun onHostDiscovered(hostname: String, deviceId: String) {
            emit("dnsHostDiscovered", mapOf(
                "hostname" to hostname,
                "fqdn" to "$hostname.mesh",
                "deviceId" to deviceId,
            ))
        }

        override fun onHostResolved(hostname: String, deviceId: String?) {
            emit("dnsHostResolved", mapOf(
                "hostname" to hostname,
                "deviceId" to (deviceId ?: ""),
                "found" to (deviceId != null),
            ))
        }

        override fun onHttpServerStateChanged(running: Boolean, port: Int) {
            emit("httpServerState", mapOf("running" to running, "port" to port))
        }

        override fun onFileSyncProgress(fileId: String, fileName: String, have: Int, total: Int, state: String, filePath: String) {
            emit("fileSyncProgress", mapOf(
                "fileId" to fileId,
                "fileName" to fileName,
                "have" to have,
                "total" to total,
                "state" to state,
                "filePath" to filePath,
            ))
            // Phase 4: a finished APK download becomes an installable app
            if (state == "done") {
                val meta = apps?.onDownloadCompleted(fileId, filePath)
                if (meta != null) {
                    emit("appReady", meta.toMap())
                }
            }
        }

        override fun onGatewayStateChanged(running: Boolean, port: Int) {
            emit("gatewayState", mapOf("running" to running, "port" to port))
        }

        override fun onGatewayDiscovered(hostname: String, deviceId: String) {
            emit("gatewayDiscovered", mapOf("hostname" to hostname, "deviceId" to deviceId))
        }

        // Phase 6: RBAC
        override fun onRoleChanged(deviceId: String, resourceType: String, resourceId: String, oldRole: com.meshnet.meshnet_app.localnet.rbac.Role?, newRole: com.meshnet.meshnet_app.localnet.rbac.Role) {
            emit("roleChanged", mapOf(
                "deviceId" to deviceId,
                "resourceType" to resourceType,
                "resourceId" to resourceId,
                "oldRole" to oldRole?.name,
                "newRole" to newRole.name,
            ))
        }

        // Phase 6: Emergency
        override fun onEmergencyAlert(alert: com.meshnet.meshnet_app.localnet.emergency.EmergencyManager.EmergencyAlert) {
            emit("emergencyAlert", mapOf(
                "alertId" to alert.alertId,
                "senderId" to alert.senderId,
                "senderName" to alert.senderName,
                "level" to alert.level.name,
                "title" to alert.title,
                "message" to alert.message,
                "location" to alert.location,
                "coordinates" to alert.coordinates,
                "expiresAtMs" to alert.expiresAtMs,
                "requiresAck" to alert.requiresAck,
                "metadata" to alert.metadata,
            ))
        }

        override fun onEmergencyAck(alertId: String, ackerId: String, totalAcks: Int) {
            emit("emergencyAck", mapOf(
                "alertId" to alertId,
                "ackerId" to ackerId,
                "totalAcks" to totalAcks,
            ))
        }

        override fun onEmergencyCancelled(alertId: String, senderId: String) {
            emit("emergencyCancelled", mapOf(
                "alertId" to alertId,
                "senderId" to senderId,
            ))
        }

        // Phase 6: Search
        override fun onSearchResult(result: com.meshnet.meshnet_app.localnet.search.SearchIndex.SearchResult) {
            emit("searchResult", mapOf(
                "queryId" to result.queryId,
                "responderId" to result.responderId,
                "results" to result.results.map { r ->
                    mapOf(
                        "docId" to r.docId,
                        "resourceType" to r.resourceType,
                        "ownerId" to r.ownerId,
                        "title" to r.title,
                        "snippet" to r.snippet,
                        "score" to r.score,
                        "metadata" to r.metadata,
                    )
                },
                "totalHits" to result.totalHits,
                "tookMs" to result.tookMs,
            ))
        }
    }

    /** Phase 3 collab events -> Flutter. */
    private val collabListener = object : com.meshnet.meshnet_app.localnet.collab.CollabService.Listener {
        override fun onStrokeAdded(roomId: String, stroke: com.meshnet.meshnet_app.localnet.collab.WhiteboardState.Stroke) {
            emit("collabStroke", mapOf(
                "roomId" to roomId,
                "strokeId" to stroke.strokeId,
                "authorId" to stroke.authorId,
                "color" to stroke.color,
                "width" to stroke.width,
                "points" to stroke.points.map { listOf(it.x, it.y) },
            ))
        }

        override fun onBoardCleared(roomId: String) {
            emit("collabBoardCleared", mapOf("roomId" to roomId))
        }

        override fun onDocChanged(docId: String, rev: Int, text: String, editorId: String) {
            emit("docUpdated", mapOf(
                "docId" to docId,
                "rev" to rev,
                "text" to text,
                "editorId" to editorId,
            ))
        }

        override fun onPollCreated(pollId: String) {
            emit("pollUpdated", mapOf("pollId" to pollId, "reason" to "created"))
        }

        override fun onPollUpdated(pollId: String) {
            emit("pollUpdated", mapOf("pollId" to pollId, "reason" to "vote"))
        }
    }

    private fun manifestToMap(m: com.meshnet.meshnet_app.localnet.chunk.FileManifest): Map<String, Any?> = mapOf(
        "fileId" to m.fileId,
        "fileName" to m.fileName,
        "fileSize" to m.fileSize,
        "mimeType" to m.mimeType,
        "chunkCount" to m.chunks.size,
        "chunkSize" to m.chunkSize,
        "createdAtMs" to m.createdAtMs,
        "senderDeviceId" to m.senderDeviceId,
    )

    /** Start all transports. */
    fun start(): Boolean {
        if (running) return true
        if (!::identity.isInitialized) init(null)
        emit("engineState", mapOf("state" to "starting"))
        try {
            transportManager.start()
            localNet?.start()
            running = true
            startWorker()
            // Start background service for persistent connectivity
            context.startService(Intent(context, MeshBackgroundService::class.java).apply {
                action = MeshBackgroundService.ACTION_START
            })
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
    private var groupSyncTicks = 0

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
                    localNet?.periodicWork()
                    // Re-distribute owned groups every ~2 min so members that
                    // were offline/pairing at creation eventually sync.
                    groupSyncTicks++
                    if (groupSyncTicks % 8 == 0) syncOwnedGroups()
                } catch (e: Exception) {
                    Log.w(TAG, "Worker iteration error: ${e.message}")
                }
            }
        }
    }

    /** Re-send groups I created. Upsert on the receiving side is idempotent,
     *  so this safely backfills members that missed the initial GROUP_CREATE
     *  (offline at creation time) once they come online. */
    private fun syncOwnedGroups() {
        try {
            val mine = groupStore.getAllGroups().filter { it.createdBy == identity.deviceId() }
            for (g in mine) routing.distributeGroup(g)
        } catch (e: Exception) {
            Log.w(TAG, "group resync error: ${e.message}")
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
        try { localNet?.stop() } catch (_: Exception) {}
        try { transportManager.stop() } catch (_: Exception) {}
        // Stop background service
        context.stopService(Intent(context, MeshBackgroundService::class.java).apply {
            action = MeshBackgroundService.ACTION_STOP
        })
        running = false
        emit("engineState", mapOf("state" to "stopped"))
    }

    /** Sync with background service (call when app comes to foreground). */
    fun syncWithBackgroundService(): Map<String, Any?>? {
        try {
            val serviceIntent = Intent(context, MeshBackgroundService::class.java)
            // Background service doesn't have a direct API, but we can get peers via broadcast
            // For now, return null - the peers will come via broadcast receiver
            return mapOf("synced" to true)
        } catch (e: Exception) {
            return null
        }
    }

    /** Register background service callbacks (call after eventSink is set). */
    fun registerBackgroundServiceCallbacks() {
        // The background service broadcasts peer updates via intent
        // We could register a receiver here, but for now the peers will come
        // through the normal transport manager when app is foreground
    }

    private fun emit(event: String, data: Map<String, Any?>) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            eventSink?.success(mapOf("event" to event).plus(data))
        } else {
            mainHandler.post { eventSink?.success(mapOf("event" to event).plus(data)) }
        }
    }
}