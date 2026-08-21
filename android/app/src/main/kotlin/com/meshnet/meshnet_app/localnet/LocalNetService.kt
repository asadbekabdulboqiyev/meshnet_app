package com.meshnet.meshnet_app.localnet

import android.util.Log
import com.meshnet.meshnet_app.localnet.chunk.ChunkStore
import com.meshnet.meshnet_app.localnet.chunk.Chunker
import com.meshnet.meshnet_app.localnet.chunk.FileAssembler
import com.meshnet.meshnet_app.localnet.chunk.FileManifest
import com.meshnet.meshnet_app.localnet.chunk.SyncPlanner
import com.meshnet.meshnet_app.localnet.collab.CollabService
import com.meshnet.meshnet_app.localnet.emergency.EmergencyManager
import com.meshnet.meshnet_app.localnet.rbac.AccessControl
import com.meshnet.meshnet_app.localnet.rbac.AccessControlHolder
import com.meshnet.meshnet_app.localnet.rbac.Permission
import com.meshnet.meshnet_app.localnet.search.SearchIndex
import com.meshnet.meshnet_app.localnet.vpn.GatewayRegistry
import com.meshnet.meshnet_app.localnet.vpn.ProxyServer
import com.meshnet.meshnet_app.protocol.MeshFrame
import com.meshnet.meshnet_app.protocol.RoutingEngine
import java.io.File
import java.net.NetworkInterface
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * LocalNetService - LocalNet orchestrator (Phase 1 DNS/web + Phase 2 files).
 *
 * Wires together:
 *   - DnsRegistry      (decentralized hostname -> deviceId/ip:port map)
 *   - LocalHttpServer  (offline web + file server on this device)
 *   - ChunkStore       (content-addressed chunk storage, dedup by design)
 *   - RoutingEngine    (DNS_ANNOUNCE / DNS_QUERY / DNS_RESPONSE transport)
 *
 * Flow:
 *   announce  : periodic DNS_ANNOUNCE broadcast (hostname + http endpoint)
 *   resolve   : local lookup, miss -> DNS_QUERY flood -> owner answers
 *   share     : file -> chunks -> store -> manifest (deterministic fileId)
 *   fetch     : peer manifest -> SyncPlanner -> download ONLY missing
 *               chunks over HTTP -> assemble with hash verification
 */
class LocalNetService(
    private val selfDeviceId: String,
    private val selfDisplayName: String,
    private val routing: RoutingEngine,
    baseDir: File? = null,
    nowMs: () -> Long = System::currentTimeMillis,
) : RoutingEngine.DnsHandler, RoutingEngine.GatewayHandler, RoutingEngine.EmergencyHandler, RoutingEngine.SearchHandler {

    companion object {
        private const val TAG = "LocalNetService"
        const val ANNOUNCE_INTERVAL_MS = 30_000L
        const val PENDING_QUERY_TIMEOUT_MS = 5_000L

        /** Sanitize user display name into a valid hostname label. */
        fun hostnameFromDisplayName(displayName: String): String {
            val cleaned = displayName.lowercase()
                .map { if (it.isLetterOrDigit() && it.code < 128) it else if (it == ' ' || it == '-' || it == '_') '-' else null }
                .filterNotNull()
                .joinToString("")
                .trim('-')
            return if (DnsRegistry.isValidHostname(cleaned)) cleaned else "node"
        }

        /** Best-effort LAN IP (Wi-Fi Direct group or Wi-Fi), null if none. */
        fun discoverLocalIp(): String? = try {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .flatMap { it.inetAddresses.asSequence() }
                .filterIsInstance<java.net.Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
                ?.hostAddress
        } catch (_: Exception) {
            null
        }
    }

    val dns = DnsRegistry(nowMs)
    var httpServer: LocalHttpServer? = null
        private set

    /** Phase 3: collaboration (whiteboards, docs, polls). */
    val collab: CollabService

    /** Phase 5: internet gateway presence of OTHER peers. */
    val gateways = GatewayRegistry(nowMs)

    /** Phase 5: OUR proxy server when sharing internet (null = off). */
    var gatewayServer: ProxyServer? = null
        private set
    private val gatewayStartedAtMs by lazy { nowMs() }

    /** Phase 6: RBAC access control. */
    val accessControl: AccessControl

    /** Phase 6: Emergency broadcast system. */
    val emergency: EmergencyManager

    /** Phase 6: Mesh-wide search index. */
    val search: SearchIndex

    /** Content-addressed chunk storage (created even before start()). */
    val chunkStore: ChunkStore
    private val manifestsDir: File

    /** Where fetched files are assembled (public for app install handoff). */
    val downloadsDir: File

    /** fileId -> original local path at share time (best-effort metadata). */
    val sharedOriginPaths = ConcurrentHashMap<String, String>()

    /** Hostname we announce to the mesh. */
    @Volatile
    var selfHostname: String = ""
        private set

    /** Provides our current endpoint for announces; injectable for tests. */
    @Volatile
    var selfEndpointProvider: () -> Pair<String?, Int> = { discoverLocalIp() to (httpServer?.boundPort ?: -1) }

    private val httpClient = HttpClient()
    private val fetchExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "LocalNet-fetch").apply { isDaemon = true }
    }

    // fileId -> manifest of files WE share (persisted in manifestsDir)
    private val sharedManifests = ConcurrentHashMap<String, FileManifest>()

    // fileId -> manifest of files fetched from peers (Phase 4 app install handoff)
    private val downloadedManifests = ConcurrentHashMap<String, FileManifest>()

    init {
        val base = baseDir ?: File(System.getProperty("java.io.tmpdir") ?: ".", "localnet-test")
        chunkStore = ChunkStore(File(base, "chunks"))
        manifestsDir = File(base, "manifests").apply { mkdirs() }
        downloadsDir = File(base, "downloads").apply { mkdirs() }
        collab = CollabService(selfDeviceId, routing, File(base, "collab"))
        accessControl = AccessControlHolder.getInstance()
        emergency = EmergencyManager(selfDeviceId, routing, accessControl)
        search = SearchIndex(selfDeviceId, routing, accessControl)
        loadPersistedManifests()
    }

    interface Listener {
        fun onHostDiscovered(hostname: String, deviceId: String) {}
        fun onHostResolved(hostname: String, deviceId: String?) {}
        fun onHttpServerStateChanged(running: Boolean, port: Int) {}
        fun onFileSyncProgress(fileId: String, fileName: String, have: Int, total: Int, state: String, filePath: String) {}
        fun onGatewayStateChanged(running: Boolean, port: Int) {}
        fun onGatewayDiscovered(hostname: String, deviceId: String) {}
        // Phase 6: RBAC
        fun onRoleChanged(deviceId: String, resourceType: String, resourceId: String, oldRole: com.meshnet.meshnet_app.localnet.rbac.Role?, newRole: com.meshnet.meshnet_app.localnet.rbac.Role) {}
        // Phase 6: Emergency
        fun onEmergencyAlert(alert: com.meshnet.meshnet_app.localnet.emergency.EmergencyManager.EmergencyAlert) {}
        fun onEmergencyAck(alertId: String, ackerId: String, totalAcks: Int) {}
        fun onEmergencyCancelled(alertId: String, senderId: String) {}
        // Phase 6: Search
        fun onSearchResult(result: com.meshnet.meshnet_app.localnet.search.SearchIndex.SearchResult) {}
    }

    private val listeners = mutableListOf<Listener>()
    fun addListener(l: Listener) { synchronized(listeners) { listeners.add(l) } }
    fun removeListener(l: Listener) { synchronized(listeners) { listeners.remove(l) } }

    // hostname -> timestamp when we flooded a query for it (dedup window)
    private val pendingQueries = ConcurrentHashMap<String, Long>()

    // ---------------- Lifecycle ----------------

    /** Register own hostname and start the HTTP server. Returns port or -1. */
    fun start(): Int {
        val host = hostnameFromDisplayName(selfDisplayName)
        if (dns.registerSelf(host, selfDeviceId, selfDisplayName)) {
            selfHostname = host
        } else {
            // Name taken by an earlier node: fall back to device-suffixed name.
            val suffix = selfDeviceId.take(4).lowercase()
            val fallback = "${host}-$suffix".take(DnsRegistry.HOSTNAME_MAX_LEN).trimEnd('-')
            if (dns.registerSelf(fallback, selfDeviceId, selfDisplayName)) {
                selfHostname = fallback
            }
        }
        val server = LocalHttpServer(LocalHttpServer.DEFAULT_PORT, contentProvider, fileProvider, collabProvider)
        val ok = server.start()
        httpServer = if (ok) server else null
        val port = if (ok) server.boundPort else -1
        Log.i(TAG, "LocalNet start: host=$selfHostname http=${if (ok) "port $port" else "FAILED"}")
        notifyListeners { it.onHttpServerStateChanged(ok, port) }
        return port
    }

    fun stop() {
        stopGateway()
        httpServer?.stop()
        httpServer = null
        notifyListeners { it.onHttpServerStateChanged(false, -1) }
        Log.i(TAG, "LocalNet stopped")
    }

    /**
     * Periodic work called from MeshEngine worker loop:
     * re-announce own name (+endpoint), expire stale entries and queries.
     */
    fun periodicWork() {
        if (selfHostname.isNotEmpty()) {
            buildAnnouncePayload()?.let { routing.sendDnsAnnounce(it) }
        }
        // Phase 5: announce gateway presence while sharing internet
        if (isGatewayRunning) {
            buildGatewayAnnouncePayload()?.let { routing.sendGatewayAnnounce(it) }
        }
        // Phase 6: emergency and search periodic cleanup
        emergency.periodicCleanup()
        search.periodicCleanup()
        dns.expire()
        gateways.expire()
        val now = System.currentTimeMillis()
        pendingQueries.entries.removeIf { now - it.value > PENDING_QUERY_TIMEOUT_MS }
    }

    /** Announce payload including our HTTP endpoint when available. */
    internal fun buildAnnouncePayload(): String? {
        val entry = dns.resolve(selfHostname) ?: return null
        val (ip, port) = selfEndpointProvider()
        return if (ip != null && port > 0) {
            "${entry.hostname}|${entry.firstRegisteredMs}|$port|$ip"
        } else {
            "${entry.hostname}|${entry.firstRegisteredMs}"
        }
    }

    // ---------------- Resolution ----------------

    /**
     * Resolve hostname. Local cache first; on miss flood a DNS_QUERY.
     * Result arrives later via [Listener.onHostResolved].
     */
    fun resolve(hostname: String): DnsRegistry.HostEntry? {
        val local = dns.resolve(hostname)
        if (local != null) return local
        val now = System.currentTimeMillis()
        val last = pendingQueries[hostname]
        if (last != null && now - last < PENDING_QUERY_TIMEOUT_MS) return null // already querying
        pendingQueries[hostname] = now
        routing.sendDnsQuery(hostname)
        return null
    }

    /** All known live hosts as list of maps (for Flutter). */
    fun hostsSnapshot(): List<Map<String, Any?>> = dns.snapshot().map { e ->
        mapOf(
            "hostname" to e.hostname,
            "fqdn" to DnsRegistry.toFqdn(e.hostname),
            "deviceId" to e.deviceId,
            "displayName" to e.displayName,
            "isSelf" to (e.deviceId == selfDeviceId),
            "firstRegisteredMs" to e.firstRegisteredMs,
            "httpPort" to e.httpPort,
            "ipAddress" to e.ipAddress,
            "hasEndpoint" to e.hasEndpoint,
        )
    }

    // ---------------- Phase 2: share / list / fetch ----------------

    /**
     * Share a local file: chunk into the store, register + persist manifest.
     * Returns the manifest, or null on IO failure.
     */
    fun shareFile(filePath: String): FileManifest? {
        val file = File(filePath)
        if (!file.exists() || !file.isFile) return null
        return try {
            val chunks = Chunker().split(file.inputStream().buffered())
            val manifest = FileManifest.fromChunks(
                fileName = file.name,
                mimeType = guessMime(file.name),
                chunks = chunks,
                chunkSize = Chunker.DEFAULT_CHUNK_SIZE,
                senderDeviceId = selfDeviceId,
                createdAtMs = System.currentTimeMillis(),
            )
            // Chunks are already content-addressed; put() dedups silently
            chunks.forEach { chunkStore.put(it.data) }
            sharedManifests[manifest.fileId] = manifest
            sharedOriginPaths[manifest.fileId] = file.absolutePath
            persistManifest(manifest)
            Log.i(TAG, "Shared file: ${manifest.fileName} (${manifest.chunks.size} chunks, id=${manifest.fileId})")
            manifest
        } catch (e: Exception) {
            Log.e(TAG, "shareFile failed: ${e.message}")
            null
        }
    }

    /** Files we share (for Flutter / /files endpoint). */
    fun sharedFiles(): List<FileManifest> = sharedManifests.values.sortedBy { it.fileName }

    fun manifestById(fileId: String): FileManifest? = sharedManifests[fileId]

    /** Manifest of a previously fetched (downloaded) file, if any. */
    fun downloadedManifestById(fileId: String): FileManifest? = downloadedManifests[fileId]

    /** Remove a file from our shares (chunks stay — other files may use them). */
    fun unshareFile(fileId: String): Boolean {
        val removed = sharedManifests.remove(fileId) != null
        if (removed) File(manifestsDir, "$fileId.lnm").delete()
        return removed
    }

    /**
     * Fetch a file from a remote host (async).
     * Only missing chunks are downloaded (incremental sync); every chunk is
     * hash-verified; assembly aborts on any mismatch.
     */
    fun fetchFile(hostname: String, fileId: String): Boolean {
        val host = dns.resolve(hostname)
        if (host == null || !host.hasEndpoint) {
            Log.w(TAG, "fetchFile: no reachable endpoint for $hostname")
            emitProgress(fileId, "", 0, 0, "failed", "")
            return false
        }
        fetchExecutor.submit {
            try {
                runFetch(host.ipAddress, host.httpPort, fileId)
            } catch (e: Exception) {
                Log.e(TAG, "fetch error: ${e.message}")
                emitProgress(fileId, "", 0, 0, "failed", "")
            }
        }
        return true
    }

    private fun runFetch(ip: String, port: Int, fileId: String) {
        val manifestText = httpClient.getText(ip, port, "/manifest/$fileId") ?: run {
            emitProgress(fileId, "", 0, 0, "failed", "")
            return
        }
        val manifest = FileManifest.parse(manifestText) ?: run {
            emitProgress(fileId, "", 0, 0, "failed", "")
            return
        }
        downloadedManifests[manifest.fileId] = manifest
        val total = manifest.chunks.size
        emitProgress(fileId, manifest.fileName, SyncPlanner.haveCount(manifest, chunkStore), total, "started", "")

        val missing = SyncPlanner.missingChunks(manifest, chunkStore)
        var have = total - missing.size
        for ((index, hash) in missing.withIndex()) {
            val data = httpClient.getBytes(ip, port, "/chunk/$hash") ?: run {
                emitProgress(fileId, manifest.fileName, have, total, "failed", "")
                return
            }
            // Verify BEFORE storing: never trust wire bytes
            if (Chunker.sha256Hex(data) != hash || data.size < 1) {
                emitProgress(fileId, manifest.fileName, have, total, "failed", "")
                return
            }
            chunkStore.put(data)
            have++
            emitProgress(fileId, manifest.fileName, have, total, "progress", "")
            if (index == missing.lastIndex) break
        }

        val assembled = FileAssembler.assemble(manifest, chunkStore, downloadsDir)
        if (assembled != null && FileAssembler.verifySize(manifest, assembled)) {
            Log.i(TAG, "Fetched ${manifest.fileName}: transferred ${missing.size}/$total chunks")
            emitProgress(fileId, manifest.fileName, total, total, "done", assembled.absolutePath)
        } else {
            emitProgress(fileId, manifest.fileName, have, total, "failed", "")
        }
    }

    /** List a remote host's shared files (blocking; call off the UI thread). */
    fun fetchHostFileList(hostname: String): List<FileManifest> {
        val host = dns.resolve(hostname) ?: return emptyList()
        if (!host.hasEndpoint) return emptyList()
        val idsText = httpClient.getText(host.ipAddress, host.httpPort, "/files") ?: return emptyList()
        return idsText.lines().filter { it.isNotBlank() }.mapNotNull { id ->
            httpClient.getText(host.ipAddress, host.httpPort, "/manifest/$id")?.let { FileManifest.parse(it) }
        }
    }

    private fun emitProgress(fileId: String, fileName: String, have: Int, total: Int, state: String, filePath: String) {
        notifyListeners { it.onFileSyncProgress(fileId, fileName, have, total, state, filePath) }
    }

    // ---------------- Manifest persistence ----------------

    private fun persistManifest(manifest: FileManifest) {
        try {
            File(manifestsDir, "${manifest.fileId}.lnm").writeText(manifest.serialize())
        } catch (e: Exception) {
            Log.w(TAG, "persistManifest failed: ${e.message}")
        }
    }

    private fun loadPersistedManifests() {
        manifestsDir.listFiles { f -> f.name.endsWith(".lnm") }?.forEach { f ->
            try {
                FileManifest.parse(f.readText())?.let { sharedManifests[it.fileId] = it }
            } catch (_: Exception) {
                f.delete()
            }
        }
    }

    private fun guessMime(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "mp3" -> "audio/mpeg"
            "ogg", "opus" -> "audio/ogg"
            "aac" -> "audio/aac"
            "mp4" -> "video/mp4"
            "pdf" -> "application/pdf"
            "txt" -> "text/plain"
            "apk" -> "application/vnd.android.package-archive"
            else -> "application/octet-stream"
        }
    }

    // ---------------- HTTP providers ----------------

    private val contentProvider = object : LocalHttpServer.ContentProvider {
        override fun deviceName(): String = selfDisplayName
        override fun deviceId(): String = selfDeviceId
        override fun knownHostsJson(): String {
            val items = dns.snapshot().joinToString(",") { e ->
                "{\"hostname\":\"${LocalHttpServer.escapeJson(e.hostname)}\"," +
                    "\"deviceId\":\"${LocalHttpServer.escapeJson(e.deviceId)}\"," +
                    "\"displayName\":\"${LocalHttpServer.escapeJson(e.displayName)}\"}"
            }
            return "{\"hosts\":[$items]}"
        }
    }

    private val fileProvider = object : LocalHttpServer.FileProvider {
        override fun sharedFileIds(): List<String> = sharedManifests.keys.toList()
        override fun manifestText(fileId: String): String? = sharedManifests[fileId]?.serialize()
        override fun chunkData(hash: String): ByteArray? = chunkStore.get(hash)
    }

    private val collabProvider = object : LocalHttpServer.CollabProvider {
        override fun boardSnapshot(roomId: String): String? = collab.boardSnapshotText(roomId)
        override fun docSnapshot(docId: String): String? = collab.docSnapshotText(docId)
        override fun pollsSnapshot(): String = collab.pollsSnapshotText()
    }

    // ---------------- DnsHandler (frames from mesh) ----------------

    override fun onDnsAnnounce(frame: MeshFrame) {
        if (frame.senderId == selfDeviceId) return
        val parsed = parseAnnouncePayload(String(frame.payload, StandardCharsets.UTF_8)) ?: return
        val senderName = parsed.hostname // display fallback; PeerStore name shown in UI
        if (dns.handleAnnounce(
                parsed.hostname, frame.senderId, senderName,
                parsed.firstRegisteredMs, parsed.httpPort, parsed.ipAddress,
            )
        ) {
            Log.d(TAG, "DNS host learned: ${parsed.hostname} (${frame.senderId})")
            notifyListeners { it.onHostDiscovered(parsed.hostname, frame.senderId) }
        }
    }

    override fun onDnsQuery(frame: MeshFrame) {
        if (frame.senderId == selfDeviceId) return
        val hostname = String(frame.payload, StandardCharsets.UTF_8)
        val answer = dns.buildResponsePayload(hostname) ?: return
        routing.sendDnsResponse(frame.senderId, answer)
    }

    override fun onDnsResponse(frame: MeshFrame) {
        if (frame.targetId != selfDeviceId) return
        val data = parseResponsePayload(String(frame.payload, StandardCharsets.UTF_8)) ?: return
        if (dns.handleResponse(data)) {
            pendingQueries.remove(data.hostname)
            Log.d(TAG, "DNS resolved via mesh: ${data.hostname} -> ${data.deviceId}")
            notifyListeners { it.onHostResolved(data.hostname, data.deviceId) }
        } else {
            notifyListeners { it.onHostResolved(data.hostname, null) }
        }
    }

    internal fun parseAnnouncePayload(payload: String): DnsRegistry.AnnounceData? =
        DnsRegistry.parseAnnounce(payload)

    internal fun parseResponsePayload(payload: String): DnsRegistry.ResponseData? =
        DnsRegistry.parseResponse(payload)

    // ---------------- Phase 5: internet gateway ----------------

    val isGatewayRunning: Boolean get() = gatewayServer?.isRunning == true

    /**
     * Start sharing OUR internet: launch the forward proxy and announce it
     * to the mesh. Returns the bound port, or -1 on failure.
     */
    fun startGateway(port: Int = ProxyServer.DEFAULT_PORT): Int {
        if (isGatewayRunning) return gatewayServer!!.boundPort
        val server = ProxyServer(port)
        if (!server.start()) {
            // Requested port busy -> try an ephemeral one
            if (port != 0) {
                val fallback = ProxyServer(0)
                if (!fallback.start()) {
                    notifyListeners { it.onGatewayStateChanged(false, -1) }
                    return -1
                }
                gatewayServer = fallback
                notifyListeners { it.onGatewayStateChanged(true, fallback.boundPort) }
                Log.i(TAG, "Internet gateway started on ephemeral port ${fallback.boundPort}")
                return fallback.boundPort
            }
            notifyListeners { it.onGatewayStateChanged(false, -1) }
            return -1
        }
        gatewayServer = server
        notifyListeners { it.onGatewayStateChanged(true, server.boundPort) }
        Log.i(TAG, "Internet gateway started on port ${server.boundPort}")
        return server.boundPort
    }

    /** Stop sharing our internet. */
    fun stopGateway() {
        gatewayServer?.stop()
        gatewayServer = null
        notifyListeners { it.onGatewayStateChanged(false, -1) }
        Log.i(TAG, "Internet gateway stopped")
    }

    /** Wire payload for our VPN_GW_ANNOUNCE frame (null when not a gateway). */
    internal fun buildGatewayAnnouncePayload(): String? {
        val server = gatewayServer ?: return null
        if (!server.isRunning) return null
        val (ip, _) = selfEndpointProvider()
        val ipText = ip ?: "0.0.0.0"
        return "$selfHostname|$ipText|${server.boundPort}|$gatewayStartedAtMs"
    }

    override fun onGatewayAnnounce(frame: MeshFrame) {
        if (frame.senderId == selfDeviceId) return
        val data = GatewayRegistry.parseAnnouncePayload(String(frame.payload, StandardCharsets.UTF_8)) ?: return
        if (gateways.handleAnnounce(frame.senderId, data)) {
            Log.d(TAG, "Gateway learned: ${data.hostname} (${frame.senderId}) :${data.proxyPort}")
            notifyListeners { it.onGatewayDiscovered(data.hostname, frame.senderId) }
        }
    }

    // ---------------- Phase 6: Emergency ----------------

    override fun onEmergencyFrame(frame: MeshFrame) {
        emergency.onEmergencyFrame(frame)
    }

    // ---------------- Phase 6: Search ----------------

    override fun onSearchFrame(frame: MeshFrame) {
        search.onSearchFrame(frame)
    }

    /** All known gateways (+ourselves when active) as maps for Flutter. */
    fun gatewaysSnapshot(): List<Map<String, Any?>> {
        val list = mutableListOf<Map<String, Any?>>()
        gatewayServer?.let { srv ->
            if (srv.isRunning) {
                list.add(
                    mapOf(
                        "deviceId" to selfDeviceId,
                        "hostname" to selfHostname,
                        "ipAddress" to (selfEndpointProvider().first ?: ""),
                        "proxyPort" to srv.boundPort,
                        "startedAtMs" to gatewayStartedAtMs,
                        "isSelf" to true,
                    ),
                )
            }
        }
        list.addAll(
            gateways.snapshot().map { e ->
                mapOf(
                    "deviceId" to e.deviceId,
                    "hostname" to e.hostname,
                    "ipAddress" to e.ipAddress,
                    "proxyPort" to e.proxyPort,
                    "startedAtMs" to e.startedAtMs,
                    "isSelf" to false,
                )
            },
        )
        return list
    }

    /**
     * Probe a gateway's /meshgw/health endpoint over HTTP.
     * Returns latency + live stats, or null when unreachable.
     * Blocking network I/O - call off the UI thread.
     */
    fun probeGateway(hostname: String): Map<String, Any?>? {
        val host = dns.resolve(hostname) ?: return null
        val proxyPort = if (host.deviceId == selfDeviceId) {
            gatewayServer?.boundPort ?: return null
        } else {
            gateways.resolve(host.deviceId)?.proxyPort ?: return null
        }
        val started = System.currentTimeMillis()
        val text = httpClient.getText(host.ipAddress, proxyPort, "/meshgw/health") ?: return null
        val latencyMs = (System.currentTimeMillis() - started).toInt()
        val running = text.contains("\"running\":true")
        val activeTunnels = Regex("\"activeTunnels\":(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val totalConns = Regex("\"totalConnections\":(\\d+)").find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        return mapOf(
            "hostname" to host.hostname,
            "deviceId" to host.deviceId,
            "ipAddress" to host.ipAddress,
            "proxyPort" to proxyPort,
            "reachable" to running,
            "latencyMs" to latencyMs,
            "activeTunnels" to activeTunnels,
            "totalConnections" to totalConns,
        )
    }

    private fun notifyListeners(block: (Listener) -> Unit) {
        synchronized(listeners) { listeners.toList() }.forEach(block)
    }

    // ---------------- Phase 6: Emergency API ----------------

    fun sendEmergencyAlert(
        level: EmergencyManager.AlertLevel,
        title: String,
        message: String,
        location: String? = null,
        coordinates: String? = null,
        ttlMinutes: Int = 60,
        requiresAck: Boolean = true
    ): EmergencyManager.EmergencyAlert {
        return emergency.sendAlert(level, title, message, location, coordinates, ttlMinutes, requiresAck)
    }

    fun acknowledgeEmergency(alertId: String) {
        emergency.acknowledge(alertId)
    }

    fun cancelEmergencyAlert(alertId: String): Boolean {
        return emergency.cancelAlert(alertId)
    }

    fun getActiveEmergencies(): List<EmergencyManager.EmergencyAlert> {
        return emergency.getActiveAlerts()
    }

    fun getEmergencyAlert(alertId: String): EmergencyManager.EmergencyAlert? {
        return emergency.getAlert(alertId)
    }

    fun getEmergencyAckCount(alertId: String): Int = emergency.getAckCount(alertId)

    fun getEmergencyAckers(alertId: String): Set<String> = emergency.getAckers(alertId)

    // ---------------- Phase 6: Search API ----------------

    fun searchLocal(terms: List<String>, resourceTypes: Set<String> = emptySet(), maxResults: Int = 20): List<SearchIndex.DocumentRef> {
        return search.searchLocal(terms, resourceTypes, maxResults)
    }

    fun searchDistributed(
        terms: List<String>,
        resourceTypes: Set<String> = emptySet(),
        maxResults: Int = 20,
        onResult: (SearchIndex.SearchResult) -> Unit
    ): String {
        return search.searchDistributed(terms, resourceTypes, maxResults, onResult)
    }

    fun indexContent(
        resourceType: String,
        resourceId: String,
        title: String,
        content: String,
        tags: List<String> = emptyList(),
        metadata: Map<String, String> = emptyMap()
    ): String {
        val docId = "doc_${System.currentTimeMillis()}_${(Math.random() * 10000).toInt()}"
        val doc = SearchIndex.Document(
            docId = docId,
            resourceType = resourceType,
            resourceId = resourceId,
            ownerId = selfDeviceId,
            title = title,
            content = content,
            tags = tags,
            metadata = metadata
        )
        search.indexDocument(doc)
        return docId
    }

    fun removeFromIndex(docId: String) {
        search.removeDocument(docId)
    }

    fun getSearchStats(): Map<String, Any> = search.getStats()

    // ---------------- Phase 6: RBAC API ----------------

    fun setDeviceRole(deviceId: String, role: com.meshnet.meshnet_app.localnet.rbac.Role) {
        accessControl.setDeviceRole(deviceId, role)
    }

    fun getDeviceRole(deviceId: String): com.meshnet.meshnet_app.localnet.rbac.Role {
        return accessControl.getDeviceRole(deviceId)
    }

    fun setResourceRole(
        resourceType: String,
        resourceId: String,
        deviceId: String,
        role: com.meshnet.meshnet_app.localnet.rbac.Role
    ) {
        accessControl.setRole(resourceType, resourceId, deviceId, role)
    }

    fun getResourceRole(
        resourceType: String,
        resourceId: String,
        deviceId: String
    ): com.meshnet.meshnet_app.localnet.rbac.Role {
        return accessControl.getRole(resourceType, resourceId, deviceId)
    }

    fun hasPermission(deviceId: String, permission: Permission): Boolean {
        return accessControl.hasPermission(deviceId, permission)
    }

    fun hasPermission(
        deviceId: String,
        resourceType: String,
        resourceId: String,
        permission: Permission
    ): Boolean {
        return accessControl.hasPermission(deviceId, resourceType, resourceId, permission)
    }

    fun checkAccess(deviceId: String, resourceType: String, resourceId: String, permission: Permission): Boolean {
        return accessControl.canAccess(deviceId, resourceType, resourceId, permission)
    }

    fun banDevice(deviceId: String) {
        accessControl.ban(deviceId)
    }

    fun unbanDevice(deviceId: String) {
        accessControl.unban(deviceId)
    }

    fun isBanned(deviceId: String): Boolean = accessControl.isBanned(deviceId)

    fun assignOwnerIfEmpty(
        resourceType: String,
        resourceId: String,
        creatorDeviceId: String
    ): Boolean {
        return accessControl.assignOwnerIfEmpty(resourceType, resourceId, creatorDeviceId)
    }
}
