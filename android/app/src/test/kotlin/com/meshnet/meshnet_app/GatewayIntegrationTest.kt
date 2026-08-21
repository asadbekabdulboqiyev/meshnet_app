package com.meshnet.meshnet_app

import com.meshnet.meshnet_app.localnet.LocalNetService
import com.meshnet.meshnet_app.localnet.vpn.GatewayRegistry
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
import android.content.Context
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Phase 5 integratsiya: gateway announce -> registry learning -> health
 * probe zanjiri. Real ProxyServer loopback'da, frame'lar RoutingEngine
 * listener orqali ushlanadi (mesh simulyatsiyasi).
 */
class GatewayIntegrationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var gatewayNode: LocalNetService
    private lateinit var clientNode: LocalNetService
    private var announcedFrame: MeshFrame? = null
    private val announceLatch = CountDownLatch(1)

    companion object {
        private const val ID_GW = "55555555-5555-5555-5555-555555555555"
        private const val ID_CLIENT = "66666666-6666-6666-6666-666666666666"
        private const val GW_HOSTNAME = "gw-node"
    }

    @Before
    fun setUp() {
        MeshDatabase.setInstance(TestDatabaseHelper.createMockDatabase())
        val gwRouting = RoutingEngine(mock(Context::class.java), ID_GW, ByteArray(32), PeerStore(mock(Context::class.java)))
        val clientRouting = RoutingEngine(mock(Context::class.java), ID_CLIENT, ByteArray(32), PeerStore(mock(Context::class.java)))
        gatewayNode = makeService(ID_GW, "GW Node", gwRouting)
        clientNode = makeService(ID_CLIENT, "Client Node", clientRouting)

        // Capture the gateway announce the same way a peer radio would
        gwRouting.addListener(object : RoutingEngine.MessageListener {
            override fun onTextReceived(from: String, message: String, messageId: String) {}
            override fun onDeliveryReport(messageId: String, delivered: Boolean) {}
            override fun onPairResult(deviceId: String, success: Boolean) {}
            override fun onPeerFound(deviceId: String) {}
            override fun onOutboxChanged(messageId: String, status: String) {}
            override fun onRouteChanged(destination: String, nextHop: String, hopCount: Int, quality: Int) {}
            override fun onFrameToSend(frame: MeshFrame, transport: String?) {
                if (frame.type == MessageType.VPN_GW_ANNOUNCE && announcedFrame == null) {
                    announcedFrame = frame
                    announceLatch.countDown()
                }
            }
        })
    }

    @After
    fun tearDown() {
        gatewayNode.stop()
        clientNode.stop()
        MeshDatabase.resetInstance()
    }

    private fun makeService(selfId: String, name: String, routing: RoutingEngine): LocalNetService {
        return LocalNetService(
            selfDeviceId = selfId,
            selfDisplayName = name,
            routing = routing,
            baseDir = tmp.newFolder(),
        ).also { svc ->
            // Deterministic endpoint for tests (no real Wi-Fi on JVM)
            svc.selfEndpointProvider = { "127.0.0.1" to 8080 }
            svc.start()
        }
    }

    @Test
    fun startGateway_announcesAndProbeWorks() {
        assertNull(gatewayNode.buildGatewayAnnouncePayload()) // not running yet

        val port = gatewayNode.startGateway(0)
        assertTrue(port > 0)
        assertTrue(gatewayNode.isGatewayRunning)

        // periodicWork emits the announce frame
        gatewayNode.periodicWork()
        assertTrue(announceLatch.await(2, TimeUnit.SECONDS))
        val frame = announcedFrame!!
        assertEquals(MessageType.VPN_GW_ANNOUNCE, frame.type)
        assertEquals(ID_GW, frame.senderId)
        assertEquals(MeshFrame.BROADCAST, frame.targetId)

        // Client learns from the frame exactly like onGatewayAnnounce does
        clientNode.onGatewayAnnounce(frame)
        assertEquals(1, clientNode.gateways.size)
        val entry = clientNode.gateways.resolve(ID_GW)!!
        assertEquals(GW_HOSTNAME, entry.hostname)
        assertEquals(port, entry.proxyPort)

        // Client needs the hostname in DNS to resolve the gateway node
        assertTrue(
            clientNode.dns.handleAnnounce(GW_HOSTNAME, ID_GW, "GW Node", 1L, 8080, "127.0.0.1"),
        )

        // Health probe over real HTTP through the proxy port
        val probe = clientNode.probeGateway(GW_HOSTNAME)
        assertNotNull(probe)
        assertEquals(true, probe!!["reachable"])
        assertEquals(port, probe["proxyPort"])
        assertEquals(0, probe["activeTunnels"])
        assertTrue((probe["latencyMs"] as Int) >= 0)
    }

    @Test
    fun stopGateway_payloadNullAndProbeFails() {
        gatewayNode.startGateway(0)
        assertTrue(gatewayNode.isGatewayRunning)
        gatewayNode.stopGateway()
        assertFalse(gatewayNode.isGatewayRunning)
        assertNull(gatewayNode.buildGatewayAnnouncePayload())
        assertNull(clientNode.probeGateway(GW_HOSTNAME)) // never learned anyway
    }

    @Test
    fun gatewaysSnapshot_includesSelfWhenActive() {
        assertTrue(gatewayNode.gatewaysSnapshot().isEmpty())
        gatewayNode.startGateway(0)
        val snap = gatewayNode.gatewaysSnapshot()
        assertEquals(1, snap.size)
        assertEquals(true, snap[0]["isSelf"])
        assertEquals(ID_GW, snap[0]["deviceId"])

        // Remote learning shows up as isSelf=false
        clientNode.dns.handleAnnounce(GW_HOSTNAME, ID_GW, "GW Node", 1L, 8080, "127.0.0.1")
        clientNode.gateways.handleAnnounce(
            ID_GW,
            GatewayRegistry.AnnounceData(GW_HOSTNAME, "127.0.0.1", 9999, 1L),
        )
        val clientSnap = clientNode.gatewaysSnapshot()
        assertEquals(1, clientSnap.size)
        assertEquals(false, clientSnap[0]["isSelf"])
        assertEquals(9999, clientSnap[0]["proxyPort"])
    }

    @Test
    fun restartGateway_bindsNewServer() {
        val p1 = gatewayNode.startGateway(0)
        gatewayNode.stopGateway()
        val p2 = gatewayNode.startGateway(0)
        assertTrue(p1 > 0 && p2 > 0)
        assertTrue(gatewayNode.isGatewayRunning)
    }
}
