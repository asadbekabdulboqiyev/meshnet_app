package com.meshnet.meshnet_app.localnet.collab

import android.util.Log
import com.meshnet.meshnet_app.protocol.MeshFrame
import com.meshnet.meshnet_app.protocol.RoutingEngine
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * CollabService - LocalNet Phase 3 collaboration orchestrator.
 *
 * Owns three shared spaces that sync over mesh-flooded frames:
 *   - Whiteboards : stroke-by-stroke drawing (dedup by strokeId)
 *   - Docs        : shared notes with last-writer-wins merging
 *   - Polls       : create + vote, tallied locally everywhere
 *
 * Transport: every local action is broadcast as a mesh frame so it reaches
 * ALL devices multi-hop (BLE or Wi-Fi Direct). Full-state snapshots are
 * additionally served over HTTP for late joiners (the /collab/ endpoints).
 *
 * HONEST LIMITS: no auth (anyone in the mesh can draw/edit/vote), no
 * encryption on collab frames (same as DNS frames), and concurrent doc
 * edits resolve by last-writer-wins, not CRDT.
 */
class CollabService(
    private val selfDeviceId: String,
    private val routing: RoutingEngine,
    private val collabDir: File,
) : RoutingEngine.CollabHandler {

    companion object {
        private const val TAG = "CollabService"
        const val MAX_BOARDS = 20
        const val MAX_DOCS = 20
    }

    interface Listener {
        fun onStrokeAdded(roomId: String, stroke: WhiteboardState.Stroke) {}
        fun onBoardCleared(roomId: String) {}
        fun onDocChanged(docId: String, rev: Int, text: String, editorId: String) {}
        fun onPollCreated(pollId: String) {}
        fun onPollUpdated(pollId: String) {}
    }

    private val listeners = mutableListOf<Listener>()
    fun addListener(l: Listener) { synchronized(listeners) { listeners.add(l) } }
    fun removeListener(l: Listener) { synchronized(listeners) { listeners.remove(l) } }

    val boards = ConcurrentHashMap<String, WhiteboardState>()
    val docs = ConcurrentHashMap<String, DocState>()
    val polls = PollManager()

    init {
        collabDir.mkdirs()
        loadPersisted()
    }

    // ---------------- Whiteboards ----------------

    /** Get or create a board locally (no network traffic). */
    fun ensureBoard(roomId: String): WhiteboardState? {
        if (!WhiteboardState.isValidRoomId(roomId)) return null
        return boards.getOrPut(roomId) {
            persistBoard(WhiteboardState(roomId))
            WhiteboardState(roomId)
        }
    }

    fun listBoards(): List<String> = boards.keys.toList().sorted()

    /**
     * Add a stroke from the LOCAL user: store, persist, broadcast.
     * Returns the assigned stroke id, or null when rejected.
     */
    fun addStrokeLocal(
        roomId: String,
        color: Int,
        width: Float,
        points: List<WhiteboardState.Point>,
    ): String? {
        val board = ensureBoard(roomId) ?: return null
        if (points.isEmpty() || points.size > WhiteboardState.MAX_POINTS_PER_STROKE) return null
        val stroke = WhiteboardState.Stroke(
            strokeId = UUID.randomUUID().toString().replace("-", ""),
            authorId = selfDeviceId,
            color = color,
            width = width,
            createdAtMs = System.currentTimeMillis(),
            points = points,
        )
        if (!board.put(stroke)) return null
        persistBoard(board)
        routing.sendBoardStroke(encodeStroke(roomId, stroke))
        notifyListeners { it.onStrokeAdded(roomId, stroke) }
        return stroke.strokeId
    }

    /** Clear a board from the LOCAL user: wipe, persist, broadcast. */
    fun clearBoardLocal(roomId: String): Boolean {
        val board = boards[roomId] ?: return false
        board.clearAll()
        persistBoard(board)
        routing.sendBoardClear(roomId)
        notifyListeners { it.onBoardCleared(roomId) }
        return true
    }

    // ---------------- Docs ----------------

    /** Get or create a doc locally (no network traffic). */
    fun ensureDoc(docId: String, title: String): DocState? {
        if (!DocState.isValidDocId(docId)) return null
        return docs.getOrPut(docId) {
            val d = DocState(docId, title)
            persistDoc(d)
            d
        }
    }

    fun listDocs(): List<Map<String, Any?>> = docs.values.map {
        mapOf(
            "docId" to it.docId,
            "title" to it.title,
            "rev" to it.rev,
            "size" to it.text.length,
            "updatedAtMs" to it.updatedAtMs,
            "lastEditorId" to it.lastEditorId,
        )
    }.sortedBy { (it["title"] as? String) ?: "" }

    /**
     * Local edit: bump revision, broadcast FULL state (announce).
     * Full-state broadcast doubles as creation sync: peers that never saw
     * this doc learn it on the first edit, and peers that missed earlier
     * edits converge via LWW. Returns new rev or -1.
     */
    fun editDocLocal(docId: String, newText: String): Int {
        val doc = docs[docId] ?: return -1
        val nextRev = doc.rev + 1
        val applied = doc.editLocal(nextRev, selfDeviceId, newText, System.currentTimeMillis())
        if (applied < 0) return -1
        persistDoc(doc)
        routing.sendDocAnnounce(encodeDocAnnounce(doc))
        notifyListeners { it.onDocChanged(docId, nextRev, newText, selfDeviceId) }
        return nextRev
    }

    /** Broadcast an existing doc's full state (e.g. right after creation). */
    fun announceDoc(docId: String): Boolean {
        val doc = docs[docId] ?: return false
        return routing.sendDocAnnounce(encodeDocAnnounce(doc))
    }

    // ---------------- Polls ----------------

    /** Create a poll from the LOCAL user: register, persist, broadcast. */
    fun createPollLocal(question: String, options: List<String>): PollManager.Poll? {
        if (question.isBlank() || options.size !in 2..PollManager.MAX_OPTIONS) return null
        if (options.any { it.isBlank() }) return null
        val poll = PollManager.Poll(
            pollId = UUID.randomUUID().toString().replace("-", "").take(16),
            creatorId = selfDeviceId,
            createdAtMs = System.currentTimeMillis(),
            question = question.take(200),
            options = options.map { it.take(100) },
        )
        if (!polls.createPoll(poll)) return null
        persistPolls()
        routing.sendPollCreate(encodePoll(poll))
        notifyListeners { it.onPollCreated(poll.pollId) }
        return poll
    }

    /** Vote from the LOCAL user: record, persist, broadcast. */
    fun voteLocal(pollId: String, optionIndex: Int): Boolean {
        if (!polls.recordVote(pollId, selfDeviceId, optionIndex)) return false
        persistPolls()
        routing.sendPollVote("$pollId|$optionIndex")
        notifyListeners { it.onPollUpdated(pollId) }
        return true
    }

    /** Poll list for Flutter. */
    fun pollsSnapshotData(): List<Map<String, Any?>> = polls.all().map { p ->
        mapOf(
            "pollId" to p.pollId,
            "question" to p.question,
            "options" to p.options,
            "creatorId" to p.creatorId,
            "createdAtMs" to p.createdAtMs,
            "tally" to polls.tally(p.pollId),
            "totalVotes" to polls.voteCount(p.pollId),
        )
    }.sortedByDescending { it["createdAtMs"] as Long }

    // ---------------- Frame handlers (from mesh) ----------------

    override fun onBoardStroke(frame: MeshFrame) {
        if (frame.senderId == selfDeviceId) return
        val payload = String(frame.payload, Charsets.UTF_8)
        val parts = payload.split("|", limit = 6)
        if (parts.size != 6) return
        val roomId = parts[0]
        val strokeId = parts[1]
        if (!WhiteboardState.isValidRoomId(roomId)) return
        if (strokeId.isBlank() || strokeId.length > 64) return
        val color = parts[2].toIntOrNull() ?: return
        val width = parts[3].toFloatOrNull() ?: return
        val ts = parts[4].toLongOrNull() ?: return
        val points = parsePoints(parts[5]) ?: return
        val board = ensureBoard(roomId) ?: return
        val stroke = WhiteboardState.Stroke(strokeId, frame.senderId, color, width, ts, points)
        if (board.put(stroke)) {
            persistBoard(board)
            Log.d(TAG, "stroke added to $roomId from ${frame.senderId}")
            notifyListeners { it.onStrokeAdded(roomId, stroke) }
        }
    }

    override fun onBoardClear(frame: MeshFrame) {
        if (frame.senderId == selfDeviceId) return
        val roomId = String(frame.payload, Charsets.UTF_8)
        val board = boards[roomId] ?: return
        board.clearAll()
        persistBoard(board)
        notifyListeners { it.onBoardCleared(roomId) }
    }

    override fun onDocEdit(frame: MeshFrame) {
        if (frame.senderId == selfDeviceId) return
        val parts = String(frame.payload, Charsets.UTF_8).split("|", limit = 4)
        if (parts.size != 4) return
        val (docId, revStr, tsStr, bodyB64) = parts
        if (!DocState.isValidDocId(docId)) return
        val rev = revStr.toIntOrNull() ?: return
        val ts = tsStr.toLongOrNull() ?: return
        val text = DocState.unb64(bodyB64) ?: return
        // Auto-create when unknown so first edits are not dropped.
        val doc = docs[docId] ?: ensureDoc(docId, docId) ?: return
        if (doc.applyEdit(rev, frame.senderId, text, ts)) {
            persistDoc(doc)
            notifyListeners { it.onDocChanged(docId, doc.rev, doc.text, frame.senderId) }
        }
    }

    /** Full-state doc sync: upsert with LWW (higher rev, tie -> newer ts). */
    override fun onDocAnnounce(frame: MeshFrame) {
        if (frame.senderId == selfDeviceId) return
        val parts = String(frame.payload, Charsets.UTF_8).split("|", limit = 5)
        if (parts.size != 5) return
        val docId = parts[0]
        if (!DocState.isValidDocId(docId)) return
        val rev = parts[1].toIntOrNull() ?: return
        val updatedAtMs = parts[2].toLongOrNull() ?: return
        val title = DocState.unb64(parts[3]) ?: return
        val text = DocState.unb64(parts[4]) ?: return

        val existing = docs[docId]
        if (existing != null) {
            // LWW: accept only strictly newer knowledge.
            val incomingNewer =
                rev > existing.rev ||
                    (rev == existing.rev && updatedAtMs > existing.updatedAtMs)
            if (!incomingNewer) return
            existing.applyEdit(rev, frame.senderId, text, updatedAtMs)
            if (title.isNotBlank()) existing.title = title
        } else {
            val doc = DocState(docId, title.ifBlank { docId })
            doc.applyEdit(rev, frame.senderId, text, updatedAtMs)
            docs[docId] = doc
        }
        persistDoc(docs[docId]!!)
        Log.d(TAG, "doc announced/updated: $docId rev=$rev from ${frame.senderId}")
        notifyListeners { it.onDocChanged(docId, docs[docId]!!.rev, docs[docId]!!.text, frame.senderId) }
    }

    override fun onPollCreate(frame: MeshFrame) {
        if (frame.senderId == selfDeviceId) return
        val poll = parsePollPayload(String(frame.payload, Charsets.UTF_8), frame.senderId) ?: return
        if (polls.createPoll(poll)) {
            persistPolls()
            notifyListeners { it.onPollCreated(poll.pollId) }
        }
    }

    override fun onPollVote(frame: MeshFrame) {
        if (frame.senderId == selfDeviceId) return
        val parts = String(frame.payload, Charsets.UTF_8).split("|")
        if (parts.size != 2) return
        val pollId = parts[0]
        val idx = parts[1].toIntOrNull() ?: return
        if (polls.recordVote(pollId, frame.senderId, idx)) {
            persistPolls()
            notifyListeners { it.onPollUpdated(pollId) }
        }
    }

    // ---------------- Encoding helpers ----------------

    internal fun encodeStroke(roomId: String, s: WhiteboardState.Stroke): String =
        "$roomId|${s.strokeId}|${s.color}|${s.width}|${s.createdAtMs}|" +
            s.points.joinToString(";") { "${it.x},${it.y}" }

    internal fun encodePoll(p: PollManager.Poll): String =
        "${p.pollId}|${p.createdAtMs}|${DocState.b64(p.question)}" +
            p.options.joinToString("") { "|${DocState.b64(it)}" }

    /** Full doc state wire format: docId|rev|updatedAtMs|titleB64|textB64 */
    internal fun encodeDocAnnounce(doc: DocState): String =
        "${doc.docId}|${doc.rev}|${doc.updatedAtMs}|" +
            DocState.b64(doc.title) + "|" + DocState.b64(doc.text)

    private fun parsePollPayload(payload: String, creatorId: String): PollManager.Poll? {
        val parts = payload.split("|")
        if (parts.size < 4) return null
        val pollId = parts[0]
        if (!PollManager.isValidPollId(pollId)) return null
        val ts = parts[1].toLongOrNull() ?: return null
        val question = DocState.unb64(parts[2]) ?: return null
        val options = parts.drop(3).map { DocState.unb64(it) ?: return null }
        if (options.size !in 2..PollManager.MAX_OPTIONS) return null
        return PollManager.Poll(pollId, creatorId, ts, question, options)
    }

    private fun parsePoints(data: String): List<WhiteboardState.Point>? {
        val points = mutableListOf<WhiteboardState.Point>()
        for (p in data.split(';')) {
            if (p.isBlank()) continue
            val xy = p.split(',')
            if (xy.size != 2) return null
            val x = xy[0].toFloatOrNull() ?: return null
            val y = xy[1].toFloatOrNull() ?: return null
            points.add(WhiteboardState.Point(x, y))
            if (points.size > WhiteboardState.MAX_POINTS_PER_STROKE) return null
        }
        return if (points.isEmpty()) null else points
    }

    // ---------------- Persistence ----------------

    private fun safeName(id: String): String = id.replace(Regex("[^a-z0-9-]"), "")

    internal fun persistBoard(board: WhiteboardState) {
        try {
            File(collabDir, "board_${safeName(board.roomId)}.lnboard").writeText(board.serialize())
        } catch (e: Exception) {
            Log.w(TAG, "persistBoard failed: ${e.message}")
        }
    }

    internal fun persistDoc(doc: DocState) {
        try {
            File(collabDir, "doc_${safeName(doc.docId)}.lndoc").writeText(doc.serialize())
        } catch (e: Exception) {
            Log.w(TAG, "persistDoc failed: ${e.message}")
        }
    }

    internal fun persistPolls() {
        try {
            File(collabDir, "polls.lnpolls").writeText(polls.serialize())
        } catch (e: Exception) {
            Log.w(TAG, "persistPolls failed: ${e.message}")
        }
    }

    private fun loadPersisted() {
        collabDir.listFiles { f -> f.name.startsWith("board_") && f.name.endsWith(".lnboard") }?.forEach { f ->
            try {
                WhiteboardState.parse(f.readText())?.let { boards[it.roomId] = it }
            } catch (_: Exception) { f.delete() }
        }
        collabDir.listFiles { f -> f.name.startsWith("doc_") && f.name.endsWith(".lndoc") }?.forEach { f ->
            try {
                DocState.parse(f.readText())?.let { docs[it.docId] = it }
            } catch (_: Exception) { f.delete() }
        }
        val pollsFile = File(collabDir, "polls.lnpolls")
        if (pollsFile.exists()) {
            try {
                PollManager.parse(pollsFile.readText())?.let { polls.mergeFrom(it) }
            } catch (_: Exception) { /* start fresh */ }
        }
    }

    // ---------------- HTTP snapshot providers ----------------

    fun boardSnapshotText(roomId: String): String? =
        boards[roomId]?.takeIf { WhiteboardState.isValidRoomId(roomId) }?.serialize()

    fun docSnapshotText(docId: String): String? =
        docs[docId]?.takeIf { DocState.isValidDocId(docId) }?.serialize()

    fun pollsSnapshotText(): String = polls.serialize()

    private fun notifyListeners(block: (Listener) -> Unit) {
        synchronized(listeners) { listeners.toList() }.forEach(block)
    }
}
