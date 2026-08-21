package com.meshnet.meshnet_app

import android.content.Context
import com.meshnet.meshnet_app.localnet.HttpClient
import com.meshnet.meshnet_app.localnet.LocalHttpServer
import com.meshnet.meshnet_app.localnet.LocalNetService
import com.meshnet.meshnet_app.localnet.collab.WhiteboardState
import com.meshnet.meshnet_app.protocol.RoutingEngine
import com.meshnet.meshnet_app.storage.MeshDatabase
import com.meshnet.meshnet_app.storage.PeerStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock

/**
 * Phase 3 integratsiya testlari: real HTTP server orqali collab snapshot
 * endpointlari — kech qo'shilgan peer to'liq holatni oladi.
 */
class CollabHttpIntegrationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var service: LocalNetService
    private lateinit var server: LocalHttpServer
    private val http = HttpClient(connectTimeoutMs = 2000, readTimeoutMs = 5000)

    private var port = -1
    private val hostIp = "127.0.0.1"

    companion object {
        private const val ID_SERVER = "88888888-8888-8888-8888-888888888888"
    }

    @Before
    fun setUp() {
        MeshDatabase.setInstance(TestDatabaseHelper.createMockDatabase())
        val routing = RoutingEngine(mock(Context::class.java), ID_SERVER, ByteArray(32), PeerStore(mock(Context::class.java)))
        service = LocalNetService(
            selfDeviceId = ID_SERVER,
            selfDisplayName = "Server Node",
            routing = routing,
            baseDir = tmp.newFolder(),
        )
        // Port 0 -> OS free port (parallel-safe)
        server = LocalHttpServer(0, object : LocalHttpServer.ContentProvider {
            override fun deviceName() = "Server Node"
            override fun deviceId() = ID_SERVER
            override fun knownHostsJson() = "{}"
        }, null, object : LocalHttpServer.CollabProvider {
            override fun boardSnapshot(roomId: String) = service.collab.boardSnapshotText(roomId)
            override fun docSnapshot(docId: String) = service.collab.docSnapshotText(docId)
            override fun pollsSnapshot() = service.collab.pollsSnapshotText()
        })
        assertTrue(server.start())
        port = server.boundPort
    }

    @After
    fun tearDown() {
        server.stop()
        MeshDatabase.resetInstance()
    }

    @Test
    fun boardSnapshotServedOverHttp() {
        assertNotNull(
            service.collab.addStrokeLocal(
                "design", 0xFF0000FF.toInt(), 4f,
                listOf(WhiteboardState.Point(1f, 2f), WhiteboardState.Point(30f, 40f)),
            ),
        )
        val text = http.getText(hostIp, port, "/collab/board/design")
        assertNotNull(text)
        assertTrue(text!!.startsWith("LNBOARD design"))
        val parsed = WhiteboardState.parse(text)
        assertEquals(1, parsed?.size)
    }

    @Test
    fun docSnapshotServedOverHttp() {
        service.collab.ensureDoc("plan", "Plan")
        service.collab.editDocLocal("plan", "1. Whiteboard\n2. Docs\n3. Polls")
        val text = http.getText(hostIp, port, "/collab/doc/plan")
        assertNotNull(text)
        assertTrue(text!!.startsWith("LNDOC plan"))
        assertEquals("1. Whiteboard\n2. Docs\n3. Polls", com.meshnet.meshnet_app.localnet.collab.DocState.parse(text)?.text)
    }

    @Test
    fun pollsSnapshotServedOverHttp() {
        val poll = service.collab.createPollLocal("Tayyormisiz?", listOf("ha", "yo'q"))
        assertNotNull(poll)
        service.collab.voteLocal(poll!!.pollId, 0)
        val text = http.getText(hostIp, port, "/collab/polls")
        assertNotNull(text)
        assertTrue(text!!.startsWith("LNPOLLS"))
        val parsed = com.meshnet.meshnet_app.localnet.collab.PollManager.parse(text)
        assertEquals(mapOf(0 to 1), parsed?.tally(poll.pollId))
    }

    @Test
    fun unknownRoomAndDocReturn404Null() {
        assertNull(http.getText(hostIp, port, "/collab/board/no-such-room"))
        assertNull(http.getText(hostIp, port, "/collab/doc/no-such-doc"))
    }

    @Test
    fun malformedIdsRejectedWith400Null() {
        assertNull(http.getText(hostIp, port, "/collab/board/UPPER")) // invalid id -> 400
        assertNull(http.getText(hostIp, port, "/collab/doc/has%20space"))
    }
}
