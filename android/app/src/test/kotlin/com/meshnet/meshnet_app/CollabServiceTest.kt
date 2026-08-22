package com.meshnet.meshnet_app

import android.content.Context
import com.meshnet.meshnet_app.localnet.collab.CollabService
import com.meshnet.meshnet_app.localnet.collab.WhiteboardState
import com.meshnet.meshnet_app.protocol.MeshFrame
import com.meshnet.meshnet_app.protocol.MessageType
import com.meshnet.meshnet_app.protocol.RoutingEngine
import com.meshnet.meshnet_app.storage.MeshDatabase
import com.meshnet.meshnet_app.storage.PeerStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock

/**
 * CollabService testlari: lokal amallar mesh'ga broadcast qilinishi,
 * masofaviy frame'lar qo'llanilishi, o'z frame'i e'tiborsiz qolishi,
 * persistence (restart) va event listenerlar.
 */
class CollabServiceTest {

    companion object {
        private const val ID_SELF = "11111111-1111-1111-1111-111111111111"
        private const val ID_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
    }

    private lateinit var routing: RoutingEngine
    private lateinit var service: CollabService
    private lateinit var collabDir: java.io.File

    @get:Rule
    val tmp = TemporaryFolder()

    private val emitted = mutableListOf<MeshFrame>()
    private val events = mutableListOf<String>()

    @Before
    fun setUp() {
        MeshDatabase.setInstance(TestDatabaseHelper.createMockDatabase())
        routing = RoutingEngine(mock(Context::class.java), ID_SELF, ByteArray(32), PeerStore(mock(Context::class.java)))
        routing.addListener(object : RoutingEngine.MessageListener {
            override fun onTextReceived(from: String, message: String, messageId: String) {}
            override fun onDeliveryReport(messageId: String, delivered: Boolean) {}
            override fun onPairResult(deviceId: String, success: Boolean) {}
            override fun onPeerFound(deviceId: String) {}
            override fun onOutboxChanged(messageId: String, status: String) {}
            override fun onFrameToSend(frame: MeshFrame, transport: String?) { emitted.add(frame) }
            override fun onRouteChanged(destination: String, nextHop: String, hopCount: Int, quality: Int) {}
        })
        collabDir = tmp.newFolder("collab")
        service = CollabService(ID_SELF, routing, collabDir)
        service.addListener(object : CollabService.Listener {
            override fun onStrokeAdded(roomId: String, stroke: WhiteboardState.Stroke) { events.add("stroke:$roomId") }
            override fun onBoardCleared(roomId: String) { events.add("clear:$roomId") }
            override fun onDocChanged(docId: String, rev: Int, text: String, editorId: String) { events.add("doc:$docId:$rev") }
            override fun onPollCreated(pollId: String) { events.add("pollCreated:$pollId") }
            override fun onPollUpdated(pollId: String) { events.add("pollVoted:$pollId") }
        })
    }

    @After
    fun tearDown() {
        MeshDatabase.resetInstance()
    }

    private fun remoteFrame(type: MessageType, payload: String, seq: Long = 1) = MeshFrame(
        type = type,
        hopLimit = 4, ttl = 6, encrypted = false,
        senderId = ID_A, targetId = MeshFrame.BROADCAST,
        msgSeq = seq, payload = payload.toByteArray(), senderPublicKey = null,
    )

    // ---------------- Boards ----------------

    @Test
    fun addStrokeLocalStoresAndBroadcasts() {
        val strokeId = service.addStrokeLocal(
            "team", 0xFF00FF00.toInt(), 5f,
            listOf(WhiteboardState.Point(1f, 2f), WhiteboardState.Point(3f, 4f)),
        )
        assertNotNull(strokeId)
        assertEquals(1, service.boards["team"]?.size)
        val frame = emitted.firstOrNull { it.type == MessageType.BOARD_STROKE }
        assertNotNull(frame)
        assertTrue(String(frame!!.payload).startsWith("team|$strokeId|"))
        assertTrue(events.contains("stroke:team"))
    }

    @Test
    fun addStrokeRejectsBadInput() {
        assertNull(service.addStrokeLocal("BAD ROOM!", 0, 1f, listOf(WhiteboardState.Point(0f, 0f))))
        assertNull(service.addStrokeLocal("ok", 0, 1f, emptyList()))
        assertNull(service.addStrokeLocal(
            "ok", 0, 1f,
            List(WhiteboardState.MAX_POINTS_PER_STROKE + 1) { WhiteboardState.Point(it.toFloat(), 0f) },
        ))
    }

    @Test
    fun remoteStrokeAppliedOnce() {
        val payload = "room1|stroke-9|-256|2.5|12345|10.0,20.0;30.0,40.0"
        service.onBoardStroke(remoteFrame(MessageType.BOARD_STROKE, payload))
        service.onBoardStroke(remoteFrame(MessageType.BOARD_STROKE, payload)) // duplicate flood
        assertEquals(1, service.boards["room1"]?.size)
        val s = service.boards["room1"]?.all()?.first()
        assertEquals(ID_A, s?.authorId)
        assertEquals(-256, s?.color)
        assertTrue(events.count { it == "stroke:room1" } == 1)
    }

    @Test
    fun ownFramesIgnored() {
        // Loopback of our own flooded frame must not double-apply
        service.addStrokeLocal("mine", 0xFF0000FF.toInt(), 1f, listOf(WhiteboardState.Point(0f, 0f)))
        val before = service.boards["mine"]?.size
        val frame = emitted.last { it.type == MessageType.BOARD_STROKE }
        service.onBoardStroke(frame.copy(senderId = ID_SELF))
        assertEquals(before, service.boards["mine"]?.size)
    }

    @Test
    fun clearBoardBroadcastsAndWipes() {
        service.addStrokeLocal("team", 0, 1f, listOf(WhiteboardState.Point(0f, 0f)))
        assertTrue(service.clearBoardLocal("team"))
        assertEquals(0, service.boards["team"]?.size)
        assertNotNull(emitted.firstOrNull { it.type == MessageType.BOARD_CLEAR })

        // Remote clear also works
        service.addStrokeLocal("team", 0, 1f, listOf(WhiteboardState.Point(1f, 1f)))
        service.onBoardClear(remoteFrame(MessageType.BOARD_CLEAR, "team"))
        assertEquals(0, service.boards["team"]?.size)
    }

    @Test
    fun malformedRemoteStrokesRejected() {
        service.onBoardStroke(remoteFrame(MessageType.BOARD_STROKE, "garbage"))
        service.onBoardStroke(remoteFrame(MessageType.BOARD_STROKE, "bad-room!|s1|1|1|1|1,1"))
        service.onBoardStroke(remoteFrame(MessageType.BOARD_STROKE, "ok|s1|NaN|1|1|1,1"))
        service.onBoardStroke(remoteFrame(MessageType.BOARD_STROKE, "ok|s1|1|1|1|no-points"))
        assertTrue(service.boards.isEmpty() || service.boards.all { it.value.size == 0 })
    }

    // ---------------- Docs ----------------

    @Test
    fun editDocLocalBumpsRevAndBroadcasts() {
        service.ensureDoc("notes", "My Notes")
        val rev1 = service.editDocLocal("notes", "first draft")
        assertEquals(1, rev1)
        val rev2 = service.editDocLocal("notes", "second draft")
        assertEquals(2, rev2)
        // Edits now broadcast FULL doc state (DOC_ANNOUNCE) for creation sync.
        val frame = emitted.last { it.type == MessageType.DOC_ANNOUNCE }
        assertTrue(String(frame.payload).startsWith("notes|2|"))
        assertTrue(events.contains("doc:notes:2"))
    }

    @Test
    fun remoteDocEditMergesByLww() {
        service.ensureDoc("shared", "Shared")
        service.editDocLocal("shared", "local v1") // rev 1 by self
        // Remote edit with higher rev wins
        val text = DocStateB64("remote wins")
        service.onDocEdit(remoteFrame(MessageType.DOC_EDIT, "shared|3|999|$text"))
        assertEquals("remote wins", service.docs["shared"]?.text)
        // Older rev loses
        service.onDocEdit(remoteFrame(MessageType.DOC_EDIT, "shared|2|888|${DocStateB64("old")}"))
        assertEquals("remote wins", service.docs["shared"]?.text)
    }

    @Test
    fun unknownDocEditAutoCreates() {
        // Changed behavior: legacy DOC_EDIT for an unseen doc now auto-creates
        // it (title falls back to the docId) instead of silently dropping.
        service.onDocEdit(remoteFrame(MessageType.DOC_EDIT, "ghost|2|999|${DocStateB64("hi")}"))
        assertEquals("hi", service.docs["ghost"]?.text)
    }

    private fun DocStateB64(s: String): String =
        java.util.Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))

    // ---------------- Polls ----------------

    @Test
    fun createPollLocalBroadcasts() {
        val poll = service.createPollLocal("Kino boramizmi?", listOf("ha", "yoq"))
        assertNotNull(poll)
        assertNotNull(emitted.firstOrNull { it.type == MessageType.POLL_CREATE })
        assertTrue(events.any { it.startsWith("pollCreated:") })
    }

    @Test
    fun voteLocalBroadcastsAndTallies() {
        val poll = service.createPollLocal("q?", listOf("a", "b"))!!
        assertTrue(service.voteLocal(poll.pollId, 1))
        assertEquals(mapOf(1 to 1), service.polls.tally(poll.pollId))
        assertNotNull(emitted.firstOrNull { it.type == MessageType.POLL_VOTE })
        assertFalse(service.voteLocal(poll.pollId, 99)) // out of range
    }

    @Test
    fun remotePollCreateAndVote() {
        val q = DocStateB64("Ovqat?")
        val a = DocStateB64("osh")
        val b = DocStateB64("lagmon")
        service.onPollCreate(remoteFrame(MessageType.POLL_CREATE, "food123|555|$q|$a|$b"))
        assertNotNull(service.polls.getPoll("food123"))
        service.onPollVote(remoteFrame(MessageType.POLL_VOTE, "food123|0"))
        assertEquals(mapOf(0 to 1), service.polls.tally("food123"))
        // Duplicate vote from same voter replaces
        service.onPollVote(remoteFrame(MessageType.POLL_VOTE, "food123|1"))
        assertEquals(mapOf(1 to 1), service.polls.tally("food123"))
    }

    @Test
    fun pollsSnapshotDataShape() {
        val poll = service.createPollLocal("Savol", listOf("x", "y"))!!
        service.voteLocal(poll.pollId, 0)
        val snap = service.pollsSnapshotData()
        assertEquals(1, snap.size)
        assertEquals("Savol", snap[0]["question"])
        assertEquals(listOf("x", "y"), snap[0]["options"])
        assertEquals(1, snap[0]["totalVotes"])
    }

    // ---------------- Persistence (app restart) ----------------

    @Test
    fun stateReloadedFromDiskOnNewInstance() {
        service.addStrokeLocal("persist-board", 0xFF112233.toInt(), 2f, listOf(WhiteboardState.Point(5f, 6f)))
        service.ensureDoc("persist-doc", "Docs")
        service.editDocLocal("persist-doc", "saqlanadigan matn")
        val poll = service.createPollLocal("Saqlanadi?", listOf("ha", "yo'q"))!!
        service.voteLocal(poll.pollId, 0)

        // New instance over the SAME dir ("app restart")
        val revived = CollabService(ID_SELF, routing, collabDir)
        assertEquals(1, revived.boards["persist-board"]?.size)
        assertEquals("saqlanadigan matn", revived.docs["persist-doc"]?.text)
        assertEquals(1, revived.polls.tally(poll.pollId)[0])
        assertEquals(1, revived.polls.voteCount(poll.pollId))
    }

    // ---------------- Id validation ----------------

    @Test
    fun invalidIdsRejectedEverywhere() {
        assertNull(service.ensureBoard("NOT VALID"))
        assertNull(service.ensureDoc("NOT VALID", "title"))
        assertNull(service.createPollLocal("", listOf("a", "b")))
        assertNull(service.createPollLocal("q", listOf("only")))
    }
}
