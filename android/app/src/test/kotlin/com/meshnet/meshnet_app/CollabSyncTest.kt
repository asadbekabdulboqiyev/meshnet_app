package com.meshnet.meshnet_app

import android.content.Context
import com.meshnet.meshnet_app.localnet.collab.CollabService
import com.meshnet.meshnet_app.protocol.MeshFrame
import com.meshnet.meshnet_app.protocol.MessageType
import com.meshnet.meshnet_app.protocol.RoutingEngine
import com.meshnet.meshnet_app.storage.MeshDatabase
import com.meshnet.meshnet_app.storage.PeerStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import java.io.File

/**
 * LocalNet collab sinxronizatsiyasi testlari.
 *
 * Muammo: broadcast frame'lar (BOARD_STROKE, DOC_EDIT, POLL_CREATE, DNS...)
 * MeshEngine.onFrameToSend'da "broadcast" target sifatida yuborilar,
 * hech qaysi transport uni hal qilolmasdi — frame jimgina tashlab yuborilar.
 * Yechim: flood() + multi-hop relayBroadcast + DOC_ANNOUNCE to'liq holat sinxi.
 */
class CollabSyncTest {

    companion object {
        private const val ID_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        private const val ID_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
        private const val ID_C = "cccccccc-cccc-cccc-cccc-cccccccccccc"
    }

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var routingA: RoutingEngine
    private lateinit var routingB: RoutingEngine
    private lateinit var serviceA: CollabService
    private lateinit var serviceB: CollabService
    private lateinit var captureA: CapturingListener
    private val emittedB = mutableListOf<MeshFrame>()

    @Before
    fun setUp() {
        MeshDatabase.setInstance(TestDatabaseHelper.createMockDatabase())
        routingA = RoutingEngine(mock(Context::class.java), ID_A, ByteArray(32), PeerStore(mock(Context::class.java)))
        routingB = RoutingEngine(mock(Context::class.java), ID_B, ByteArray(32), PeerStore(mock(Context::class.java)))
        serviceA = CollabService(ID_A, routingA, tmp.newFolder("collab_a"))
        serviceB = CollabService(ID_B, routingB, File(tmp.root, "collab_b"))
        routingA.collabHandler = serviceA
        routingB.collabHandler = serviceB
        captureA = CapturingListener()
        routingA.addListener(captureA)
        // B's outgoing frames (incl. re-floods) are captured here.
        routingB.addListener(object : RoutingEngine.MessageListener {
            override fun onTextReceived(from: String, message: String, messageId: String) {}
            override fun onDeliveryReport(messageId: String, delivered: Boolean) {}
            override fun onPairResult(deviceId: String, success: Boolean) {}
            override fun onPeerFound(deviceId: String) {}
            override fun onOutboxChanged(messageId: String, status: String) {}
            override fun onFrameToSend(frame: MeshFrame, transport: String?) { emittedB.add(frame) }
            override fun onRouteChanged(destination: String, nextHop: String, hopCount: Int, quality: Int) {}
        })
    }

    @After
    fun tearDown() {
        MeshDatabase.resetInstance()
    }

    // =================== doc announce sync ===================

    @Test
    fun docAnnounce_peerLearnsDocWithoutLocalCreation() {
        serviceA.ensureDoc("team-notes", "Team Notes")
        serviceA.editDocLocal("team-notes", "birinchi qator")

        // Deliver every broadcast from A to B (simulated radio hop).
        captureA.captured.filter { it.type == MessageType.DOC_ANNOUNCE }
            .forEach { routingB.handleIncomingFrame(it) }

        val remote = serviceB.docs["team-notes"]
        assertNotNull("B should learn the doc purely via announce", remote)
        assertEquals("birinchi qator", remote!!.text)
    }

    @Test
    fun docAnnounce_lwwRejectsOlderRevision() {
        serviceA.ensureDoc("doc-x", "X")
        serviceA.editDocLocal("doc-x", "newer text")   // rev 1
        serviceA.editDocLocal("doc-x", "newest text")  // rev 2

        val newest = captureA.captured.filter { it.type == MessageType.DOC_ANNOUNCE }.last()
        routingB.handleIncomingFrame(newest)
        assertEquals("newest text", serviceB.docs["doc-x"]!!.text)

        // Replay an OLDER state -> must be rejected by LWW.
        val older = newest.copy(
            payload = ("doc-x|1|1|" +
                java.util.Base64.getEncoder().encodeToString("X".toByteArray()) + "|" +
                java.util.Base64.getEncoder().encodeToString("older text".toByteArray()))
                .toByteArray(),
        )
        routingB.handleIncomingFrame(older)
        assertEquals("newest text", serviceB.docs["doc-x"]!!.text)
    }

    @Test
    fun legacyDocEdit_autoCreatesMissingDoc() {
        // Old-path incremental edit for a doc B has never seen must not be dropped.
        val payload = "legacy-doc|3|123456|" +
            java.util.Base64.getEncoder().encodeToString("salom".toByteArray())
        routingB.handleIncomingFrame(
            MeshFrame(
                type = MessageType.DOC_EDIT,
                hopLimit = 4, ttl = 6, encrypted = false,
                senderId = ID_A, targetId = MeshFrame.BROADCAST,
                msgSeq = 7, payload = payload.toByteArray(), senderPublicKey = null,
            ),
        )
        assertEquals("salom", serviceB.docs["legacy-doc"]?.text)
    }

    // =================== broadcast multi-hop relay ===================

    @Test
    fun broadcastStroke_isReFloodedByReceiverWithDecrementedHops() {
        // A sends a stroke; B receives it and must re-flood a copy with hop-1.
        val stroke = serviceA.addStrokeLocal(
            roomId = "room-1",
            color = 0xFF4E8CFF.toInt(),
            width = 3f,
            points = listOf(
                com.meshnet.meshnet_app.localnet.collab.WhiteboardState.Point(0f, 0f),
                com.meshnet.meshnet_app.localnet.collab.WhiteboardState.Point(5f, 5f),
            ),
        )
        assertNotNull(stroke)
        val original = emittedFromA().single { it.type == MessageType.BOARD_STROKE }
        assertEquals(RoutingEngine.MAX_HOP, original.hopLimit)

        routingB.handleIncomingFrame(original)

        val reflood = emittedB.filter {
            it.type == MessageType.BOARD_STROKE && it.msgSeq == original.msgSeq
        }
        assertEquals(1, reflood.size)
        assertEquals(original.hopLimit - 1, reflood.first().hopLimit)

        // And B stored the stroke locally.
        assertEquals(1, serviceB.boards["room-1"]!!.all().size)
    }

    @Test
    fun broadcastDuplicate_isNotReFloodedTwice() {
        serviceA.addStrokeLocal(
            "room-2", 0xFFFF5252.toInt(), 2f,
            listOf(
                com.meshnet.meshnet_app.localnet.collab.WhiteboardState.Point(1f, 1f),
                com.meshnet.meshnet_app.localnet.collab.WhiteboardState.Point(2f, 2f),
            ),
        )
        val original = emittedFromA().single { it.type == MessageType.BOARD_STROKE }
        routingB.handleIncomingFrame(original)
        routingB.handleIncomingFrame(original) // duplicate copy from another neighbor

        assertEquals(
            "dedup must stop repeat floods",
            1,
            emittedB.count { it.type == MessageType.BOARD_STROKE && it.msgSeq == original.msgSeq },
        )
    }

    @Test
    fun peerPing_isNotReFlooded() {
        routingA.sendPing()
        val ping = emittedFromA().single { it.type == MessageType.PEER_PING }
        routingB.handleIncomingFrame(ping)
        assertEquals(0, emittedB.count { it.type == MessageType.PEER_PING && it.msgSeq == ping.msgSeq })
    }

    // =================== helpers ===================

    private class CapturingListener : RoutingEngine.MessageListener {
        val captured = mutableListOf<MeshFrame>()
        override fun onFrameToSend(frame: MeshFrame, transport: String?) { captured.add(frame) }
        override fun onDeliveryReport(messageId: String, delivered: Boolean) {}
        override fun onPairResult(deviceId: String, success: Boolean) {}
        override fun onPeerFound(deviceId: String) {}
        override fun onOutboxChanged(messageId: String, status: String) {}
        override fun onRouteChanged(destination: String, nextHop: String, hopCount: Int, quality: Int) {}
        override fun onTextReceived(from: String, message: String, messageId: String) {}
    }

    private fun emittedFromA(): List<MeshFrame> = captureA.captured.toList()
}
