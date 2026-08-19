package com.meshnet.meshnet_app

import android.content.Context
import com.meshnet.meshnet_app.crypto.MeshCrypto
import com.meshnet.meshnet_app.protocol.MeshFrame
import com.meshnet.meshnet_app.protocol.MessageType
import com.meshnet.meshnet_app.protocol.RoutingEngine
import com.meshnet.meshnet_app.storage.MeshDatabase
import com.meshnet.meshnet_app.storage.PeerStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock

/**
 * RoutingEngine testlari: 2-hop relay, dublikat nazorati, E2E shifrlash va
 * delivery report. PeerStore real instance (mock SharedPreferences bilan)
 * ishlatiladi — shuning uchun PeerStore in-memory keshga ega.
 */
class RoutingEngineTest {

    companion object {
        private const val ID_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        private const val ID_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
        private const val ID_C = "cccccccc-cccc-cccc-cccc-cccccccccccc"
        private const val ID_M = "dddddddd-dddd-dddd-dddd-dddddddddddd"
    }

    /** Har bir engine uchun listener + chiqqan framelar ro'yxati. */
    private class Harness(
        val engine: RoutingEngine,
        val peerStore: PeerStore,
        val emitted: MutableList<MeshFrame> = mutableListOf(),
        val receivedMessages: MutableList<Triple<String, String, String>> = mutableListOf(),
        val deliveryReports: MutableList<Pair<String, Boolean>> = mutableListOf(),
        val pairResults: MutableList<Pair<String, Boolean>> = mutableListOf(),
        val peersFound: MutableList<String> = mutableListOf(),
        val outboxStatus: MutableList<Pair<String, String>> = mutableListOf(),
    ) : RoutingEngine.MessageListener {
        override fun onTextReceived(from: String, message: String, messageId: String) {
            receivedMessages.add(Triple(from, message, messageId))
        }

        override fun onDeliveryReport(messageId: String, delivered: Boolean) {
            deliveryReports.add(messageId to delivered)
        }

        override fun onPairResult(deviceId: String, success: Boolean) {
            pairResults.add(deviceId to success)
        }

        override fun onPeerFound(deviceId: String) {
            peersFound.add(deviceId)
        }

        override fun onOutboxChanged(messageId: String, status: String) {
            outboxStatus.add(messageId to status)
        }

        override fun onRouteChanged(destination: String, nextHop: String, hopCount: Int, quality: Int) {}

        override fun onFrameToSend(frame: MeshFrame, transport: String?) {
            emitted.add(frame)
        }
    }

    @Before
    fun setUp() {
        MeshDatabase.setInstance(TestDatabaseHelper.createMockDatabase())
    }

    @After
    fun tearDown() {
        MeshDatabase.resetInstance()
    }

    private fun makeEngine(id: String, keyPair: MeshCrypto.KeyPair): Harness {
        val ctx = mock(Context::class.java)
        val store = PeerStore(ctx)
        val engine = RoutingEngine(ctx, id, keyPair.privateKey, store)
        engine.setIdentityPublicKey(keyPair.publicKey)
        val harness = Harness(engine, store)
        engine.addListener(harness)
        return harness
    }

    @Test
    fun sendText_toAuthorizedPeer_emitsEncryptedFrame() {
        val a = makeEngine(ID_A, MeshCrypto.generateKeyPair())
        val keyB = MeshCrypto.generateKeyPair()
        a.peerStore.markAuthorized(ID_B, MeshCrypto.b64(keyB.publicKey))

        val msgId = a.engine.sendText(ID_B, "salom do'st")

        assertNotNull(msgId)
        assertEquals(1, a.emitted.size)
        val frame = a.emitted.first()
        assertEquals(MessageType.TEXT, frame.type)
        assertEquals(ID_B, frame.targetId)
        assertTrue(frame.encrypted)
        assertEquals(4, frame.hopLimit)
    }

    @Test
    fun sendText_toUnauthorizedPeer_rejected() {
        val a = makeEngine(ID_A, MeshCrypto.generateKeyPair())

        val msgId = a.engine.sendText(ID_B, "salom")

        assertNull(msgId)
        assertTrue(a.emitted.isEmpty())
    }

    @Test
    fun endToEnd_textDelivered_withDeliveryReport() {
        val keyA = MeshCrypto.generateKeyPair()
        val keyB = MeshCrypto.generateKeyPair()
        val a = makeEngine(ID_A, keyA)
        val b = makeEngine(ID_B, keyB)
        a.peerStore.markAuthorized(ID_B, MeshCrypto.b64(keyB.publicKey))
        b.peerStore.markAuthorized(ID_A, MeshCrypto.b64(keyA.publicKey))

        val msgId = a.engine.sendText(ID_B, "salom do'st")
        val textFrame = a.emitted.last()

        // B qabul qiladi
        b.engine.handleIncomingFrame(textFrame)
        assertEquals(1, b.receivedMessages.size)
        assertEquals("salom do'st", b.receivedMessages.first().second)
        assertEquals(msgId, b.receivedMessages.first().third)

        // B delivery report qaytaradi
        val reportFrame = b.emitted.last()
        assertEquals(MessageType.DELIVERY_REPORT, reportFrame.type)
        assertEquals(ID_A, reportFrame.targetId)

        // A reportni qayta ishlaydi
        a.engine.handleIncomingFrame(reportFrame)
        assertEquals(1, a.deliveryReports.size)
        assertEquals(msgId, a.deliveryReports.first().first)
        assertTrue(a.deliveryReports.first().second)
    }

    @Test
    fun duplicateFrame_dropped() {
        val keyA = MeshCrypto.generateKeyPair()
        val keyB = MeshCrypto.generateKeyPair()
        val a = makeEngine(ID_A, keyA)
        val b = makeEngine(ID_B, keyB)
        a.peerStore.markAuthorized(ID_B, MeshCrypto.b64(keyB.publicKey))
        b.peerStore.markAuthorized(ID_A, MeshCrypto.b64(keyA.publicKey))

        a.engine.sendText(ID_B, "takrorlanuvchi")
        val textFrame = a.emitted.last()

        b.engine.handleIncomingFrame(textFrame)
        b.engine.handleIncomingFrame(textFrame) // dublikat

        assertEquals(1, b.receivedMessages.size)
        assertEquals(1, b.emitted.filter { it.type == MessageType.DELIVERY_REPORT }.size)
    }

    @Test
    fun middleNode_relaysTextToFinalTarget() {
        val keyA = MeshCrypto.generateKeyPair()
        val keyC = MeshCrypto.generateKeyPair()
        val a = makeEngine(ID_A, keyA)
        val m = makeEngine(ID_M, MeshCrypto.generateKeyPair())
        a.peerStore.markAuthorized(ID_C, MeshCrypto.b64(keyC.publicKey))

        a.engine.sendText(ID_C, "orqali uzatiladigan xabar")
        val textFrame = a.emitted.last()

        // M qabul qiladi: target C (M emas), hopLimit 2 > 0 -> relay
        m.engine.handleIncomingFrame(textFrame)

        assertEquals(1, m.emitted.size)
        val relay = m.emitted.first()
        assertEquals(MessageType.RELAY, relay.type)

        val inner = MeshFrame.decode(relay.payload)!!
        assertEquals(MessageType.TEXT, inner.type)
        assertEquals(ID_C, inner.targetId)
        // RELAY o'rab turuvchi hopLimit kamaytirildi (4 -> 3)
        assertEquals(3, relay.hopLimit)
        assertEquals(4, inner.hopLimit)
    }

    @Test
    fun relay_stopsWhenHopLimitExhausted() {
        val m = makeEngine(ID_M, MeshCrypto.generateKeyPair())

        // hopLimit=1: relayFrame nextHop=0 -> uzatish to'xtaydi
        val frame = MeshFrame(
            type = MessageType.TEXT,
            hopLimit = 1,
            ttl = 6,
            encrypted = false,
            senderId = ID_A,
            targetId = ID_C,
            msgSeq = 123,
            payload = ByteArray(0),
            senderPublicKey = null,
        )

        m.engine.handleIncomingFrame(frame)
        assertTrue(m.emitted.isEmpty())
    }

    @Test
    fun pairReq_marksAuthorizedAndSendsAck() {
        val keyA = MeshCrypto.generateKeyPair()
        val keyB = MeshCrypto.generateKeyPair()
        val a = makeEngine(ID_A, keyA)
        val b = makeEngine(ID_B, keyB)

        val req = MeshFrame(
            type = MessageType.PAIR_REQ,
            hopLimit = 2,
            ttl = 6,
            encrypted = false,
            senderId = ID_A,
            targetId = ID_B,
            msgSeq = 1,
            payload = ByteArray(0),
            senderPublicKey = keyA.publicKey,
        )

        b.engine.handleIncomingFrame(req)

        assertNotNull(b.peerStore.authorized(ID_A))
        assertEquals(1, b.emitted.size)
        val ack = b.emitted.first()
        assertEquals(MessageType.PAIR_ACK, ack.type)
        assertEquals(ID_A, ack.targetId)

        // A ACK qabul qiladi -> B authorized bo'ladi
        a.engine.handleIncomingFrame(ack)
        assertNotNull(a.peerStore.authorized(ID_B))
    }

    @Test
    fun tamperedText_failsToDecrypt_reportsFailed() {
        val keyA = MeshCrypto.generateKeyPair()
        val keyB = MeshCrypto.generateKeyPair()
        val a = makeEngine(ID_A, keyA)
        val b = makeEngine(ID_B, keyB)
        a.peerStore.markAuthorized(ID_B, MeshCrypto.b64(keyB.publicKey))
        b.peerStore.markAuthorized(ID_A, MeshCrypto.b64(keyA.publicKey))

        a.engine.sendText(ID_B, "shifrlangan xabar")
        val textFrame = a.emitted.last().let {
            it.copy(payload = it.payload.also { p -> p[p.size - 1] = (p.last().toInt() xor 0x01).toByte() })
        }

        b.engine.handleIncomingFrame(textFrame)

        // Ochib bo'lmaydi: foydalanuvchiga berilmaydi, failed report yuboriladi
        assertTrue(b.receivedMessages.isEmpty())
        val report = b.emitted.last()
        assertEquals(MessageType.DELIVERY_REPORT, report.type)
        assertEquals(0x00, report.payload[0].toInt())
    }

    // ---------------- Phase 2: 2-hop relay (A -> B -> C) ----------------

    @Test
    fun e2e_2hop_AToBToC_deliveredAndReported() {
        val keyA = MeshCrypto.generateKeyPair()
        val keyC = MeshCrypto.generateKeyPair()
        val a = makeEngine(ID_A, keyA)
        val b = makeEngine(ID_B, MeshCrypto.generateKeyPair())
        val c = makeEngine(ID_C, keyC)
        a.peerStore.markAuthorized(ID_C, MeshCrypto.b64(keyC.publicKey))
        c.peerStore.markAuthorized(ID_A, MeshCrypto.b64(keyA.publicKey))

        // A -> C (B orqali 2-hop)
        val msgId = a.engine.sendText(ID_C, "ikki pog'onali xabar")
        val textFrame = a.emitted.last()

        // B relay qiladi (RELAY emisi)
        b.engine.handleIncomingFrame(textFrame)
        val relay = b.emitted.single { it.type == MessageType.RELAY }
        assertEquals(3, relay.hopLimit)

        // C RELAY qabul qiladi va yetkazadi (bitta marta)
        c.engine.handleIncomingFrame(relay)
        assertEquals(1, c.receivedMessages.size)
        assertEquals("ikki pog'onali xabar", c.receivedMessages.first().second)
        assertEquals(msgId, c.receivedMessages.first().third)

        // C delivery report -> A ga (B orqali)
        val report = c.emitted.last()
        assertEquals(MessageType.DELIVERY_REPORT, report.type)
        assertEquals(ID_A, report.targetId)

        // B reportni relay qiladi
        b.engine.handleIncomingFrame(report)
        val reportRelay = b.emitted.filter { it.type == MessageType.RELAY }.last()

        // A reportni qabul qiladi
        a.engine.handleIncomingFrame(reportRelay)
        assertEquals(1, a.deliveryReports.size)
        assertEquals(msgId, a.deliveryReports.first().first)
        assertTrue(a.deliveryReports.first().second)

        // Statistika hisoblagichlari (TEXT + DELIVERY_REPORT relay)
        assertEquals(2L, b.engine.stats()["framesRelayed"])
        assertEquals(1L, c.engine.stats()["messagesDelivered"])
        assertEquals(1L, a.engine.stats()["messagesSent"])
    }

    @Test
    fun duplicateRelay_deliversOnce() {
        val keyA = MeshCrypto.generateKeyPair()
        val keyC = MeshCrypto.generateKeyPair()
        val a = makeEngine(ID_A, keyA)
        val b = makeEngine(ID_B, MeshCrypto.generateKeyPair())
        val c = makeEngine(ID_C, keyC)
        a.peerStore.markAuthorized(ID_C, MeshCrypto.b64(keyC.publicKey))
        c.peerStore.markAuthorized(ID_A, MeshCrypto.b64(keyA.publicKey))

        a.engine.sendText(ID_C, "dublikat relay")
        b.engine.handleIncomingFrame(a.emitted.last())
        val relay = b.emitted.single { it.type == MessageType.RELAY }

        // Dublikat RELAY nusxasi ham C'ga yetib keladi (flood)
        c.engine.handleIncomingFrame(relay)
        c.engine.handleIncomingFrame(relay)

        assertEquals(1, c.receivedMessages.size)
        // Faqat bitta delivery report (dublikat uchun emas)
        assertEquals(1, c.emitted.filter { it.type == MessageType.DELIVERY_REPORT }.size)
        assertEquals(1L, c.engine.stats()["duplicatesDropped"])
    }

    @Test
    fun floodLoopback_ownText_notRelayedOrDelivered() {
        val a = makeEngine(ID_A, MeshCrypto.generateKeyPair())
        a.peerStore.markAuthorized(ID_C, MeshCrypto.b64(MeshCrypto.generateKeyPair().publicKey))

        a.engine.sendText(ID_C, "loop-back")
        val textFrame = a.emitted.last()
        a.emitted.clear()

        // Flood orqachali qaytgan o'z frame'i (A <- B <- A)
        a.engine.handleIncomingFrame(textFrame)

        assertTrue(a.receivedMessages.isEmpty()) // o'z xabari yetkazilmaydi
        assertTrue(a.emitted.isEmpty()) // qayta relay qilinmaydi
        assertEquals(1L, a.engine.stats()["duplicatesDropped"])
    }

    @Test
    fun floodLoopback_ownDeliveryReport_dropped() {
        val keyA = MeshCrypto.generateKeyPair()
        val keyC = MeshCrypto.generateKeyPair()
        val a = makeEngine(ID_A, keyA)
        val c = makeEngine(ID_C, keyC)
        a.peerStore.markAuthorized(ID_C, MeshCrypto.b64(keyC.publicKey))
        c.peerStore.markAuthorized(ID_A, MeshCrypto.b64(keyA.publicKey))

        a.engine.sendText(ID_C, "report loop-back")
        c.engine.handleIncomingFrame(a.emitted.last())
        val report = c.emitted.last()

        // Report C->A to'g'ri va (dublikat yo'l) flood nusxalari
        a.engine.handleIncomingFrame(report)
        a.engine.handleIncomingFrame(report)

        assertEquals(1, a.deliveryReports.size)
        assertEquals(1L, a.engine.stats()["duplicatesDropped"])
    }

    // ---------------- Phase 3: QR pairing tarmoq oqimi ----------------

    @Test
    fun qrPairing_scanTriggersPairReq_BauthorizesA_andAck() {
        val keyA = MeshCrypto.generateKeyPair()
        val keyB = MeshCrypto.generateKeyPair()
        val a = makeEngine(ID_A, keyA)
        val b = makeEngine(ID_B, keyB)

        // A B'ning QR'ini skanerladi: B'ni authorized deb biladi (QR ishonch)
        a.peerStore.markAuthorized(ID_B, MeshCrypto.b64(keyB.publicKey))

        // A PAIR_REQ yuboradi (peer B bizni ham tanisin)
        val sent = a.engine.sendPairRequest(ID_B)
        assertTrue(sent)
        val req = a.emitted.last()
        assertEquals(MessageType.PAIR_REQ, req.type)
        assertEquals(ID_B, req.targetId)
        assertNotNull(req.senderPublicKey)

        // B qabul: A authorized bo'ladi, PAIR_ACK qaytaradi
        b.engine.handleIncomingFrame(req)
        assertNotNull(b.peerStore.authorized(ID_A))
        val ack = b.emitted.last()
        assertEquals(MessageType.PAIR_ACK, ack.type)
        assertEquals(ID_A, ack.targetId)

        // A ACK qabul: pairResult(B, true)
        a.engine.handleIncomingFrame(ack)
        assertEquals(listOf(ID_B to true), a.pairResults)
        // B ham A'ni authorized deb oldi — ikki yo'nalishli juftlash
        assertNotNull(b.peerStore.authorized(ID_A))
    }

    @Test
    fun qrPairing_2hop_AtoC_viaB_relayed() {
        val keyA = MeshCrypto.generateKeyPair()
        val keyC = MeshCrypto.generateKeyPair()
        val a = makeEngine(ID_A, keyA)
        val b = makeEngine(ID_B, MeshCrypto.generateKeyPair())
        val c = makeEngine(ID_C, keyC)

        a.peerStore.markAuthorized(ID_C, MeshCrypto.b64(keyC.publicKey))

        // A -> C: PAIR_REQ (B orqali 2-hop)
        a.engine.sendPairRequest(ID_C)
        val req = a.emitted.last()
        b.engine.handleIncomingFrame(req)
        val relayed = b.emitted.single { it.type == MessageType.RELAY }

        // C qabul -> A authorized + ACK
        c.engine.handleIncomingFrame(relayed)
        assertNotNull(c.peerStore.authorized(ID_A))
        val ack = c.emitted.last()
        assertEquals(MessageType.PAIR_ACK, ack.type)

        // ACK B orqali A'ga
        b.engine.handleIncomingFrame(ack)
        val ackRelay = b.emitted.filter { it.type == MessageType.RELAY }.last()
        a.engine.handleIncomingFrame(ackRelay)
        assertEquals(listOf(ID_C to true), a.pairResults)
    }

    // ---------------- Phase 4: FIND_PEER (route recovery) ----------------

    @Test
    fun findPeer_broadcast_targetRepliesAck() {
        val a = makeEngine(ID_A, MeshCrypto.generateKeyPair())
        val c = makeEngine(ID_C, MeshCrypto.generateKeyPair())

        // A C'ni qidiradi (authorized bo'lmasa ham topish mumkin)
        val sent = a.engine.sendFindPeer(ID_C)
        assertTrue(sent)
        val find = a.emitted.last()
        assertEquals(MessageType.FIND_PEER, find.type)
        assertEquals(MeshFrame.BROADCAST, find.targetId)
        assertEquals(ID_C, String(find.payload, Charsets.UTF_8))

        // C qabul: o'zi qidirilayotgan — ACK qaytaradi
        c.engine.handleIncomingFrame(find)
        val ack = c.emitted.last()
        assertEquals(MessageType.FIND_PEER_ACK, ack.type)
        assertEquals(ID_A, ack.targetId)

        // A ACK qabul: peerFound(C)
        a.engine.handleIncomingFrame(ack)
        assertEquals(listOf(ID_C), a.peersFound)
        // ACK publik key olib keldi — C avtomatik authorized bo'ldi
        assertNotNull(a.peerStore.authorized(ID_C))
    }

    @Test
    fun findPeer_2hop_viaB_ackRelayed() {
        val a = makeEngine(ID_A, MeshCrypto.generateKeyPair())
        val b = makeEngine(ID_B, MeshCrypto.generateKeyPair())
        val c = makeEngine(ID_C, MeshCrypto.generateKeyPair())

        a.engine.sendFindPeer(ID_C)
        val find = a.emitted.last()

        // B broadcast qidiruvni relay qiladi
        b.engine.handleIncomingFrame(find)
        val relayed = b.emitted.single { it.type == MessageType.RELAY }

        // C qabul -> ACK A'ga
        c.engine.handleIncomingFrame(relayed)
        val ack = c.emitted.last()
        assertEquals(MessageType.FIND_PEER_ACK, ack.type)

        // ACK B orqali A'ga yetadi
        b.engine.handleIncomingFrame(ack)
        val ackRelay = b.emitted.filter { it.type == MessageType.RELAY }.last()
        a.engine.handleIncomingFrame(ackRelay)
        assertEquals(listOf(ID_C), a.peersFound)
    }

    @Test
    fun findPeer_unknownTarget_noAck_noFound() {
        val a = makeEngine(ID_A, MeshCrypto.generateKeyPair())
        val b = makeEngine(ID_B, MeshCrypto.generateKeyPair())

        a.engine.sendFindPeer(ID_C) // C tarmoqda yo'q
        b.engine.handleIncomingFrame(a.emitted.last())

        // B relay qiladi (qidiruv davom etadi), lekin ACK yo'q
        assertEquals(1, b.emitted.filter { it.type == MessageType.RELAY }.size)
        assertTrue(b.emitted.none { it.type == MessageType.FIND_PEER_ACK })
        assertTrue(b.peersFound.isEmpty())
    }

    @Test
    fun findPeer_loopback_ownRequest_dropped() {
        val a = makeEngine(ID_A, MeshCrypto.generateKeyPair())

        a.engine.sendFindPeer(ID_C)
        val find = a.emitted.last()
        a.emitted.clear()

        // Flood orqachali qaytgan o'z qidiruvi qayta relay qilinmaydi
        a.engine.handleIncomingFrame(find)
        assertTrue(a.emitted.isEmpty())
        assertEquals(1L, a.engine.stats()["duplicatesDropped"])
    }

    // ---------------- Phase 5: Store-and-forward ----------------

    /** A'ga yetadigan DELIVERY_REPORT frame qurish (to'g'ridan-to'g'ri). */
    private fun makeDeliveryReport(fromId: String, forMsgId: String, delivered: Boolean): MeshFrame {
        val idBytes = forMsgId.toByteArray(Charsets.UTF_8)
        val payload = ByteArray(1 + idBytes.size)
        payload[0] = if (delivered) 0x01 else 0x00
        idBytes.copyInto(payload, 1)
        return MeshFrame(
            type = MessageType.DELIVERY_REPORT,
            hopLimit = 2,
            ttl = 6,
            encrypted = false,
            senderId = fromId,
            targetId = ID_A,
            msgSeq = System.currentTimeMillis(),
            payload = payload,
            senderPublicKey = null,
        )
    }

    @Test
    fun sendText_queuesInOutbox_untilDelivered() {
        val keyA = MeshCrypto.generateKeyPair()
        val keyB = MeshCrypto.generateKeyPair()
        val a = makeEngine(ID_A, keyA)
        val b = makeEngine(ID_B, keyB)
        a.peerStore.markAuthorized(ID_B, MeshCrypto.b64(keyB.publicKey))
        b.peerStore.markAuthorized(ID_A, MeshCrypto.b64(keyA.publicKey))

        // Xabar yuboriladi -> qatorga tushadi ("queued")
        val msgId = a.engine.sendText(ID_B, "yetkazilmagan")
        assertNotNull(msgId)
        assertEquals(listOf(msgId to "queued"), a.outboxStatus)
        assertEquals(1, a.engine.outboxSnapshot().size)

        // Real oqim: B qabul qiladi va delivery report qaytaradi
        b.engine.handleIncomingFrame(a.emitted.last())
        val report = b.emitted.last()
        assertEquals(MessageType.DELIVERY_REPORT, report.type)

        // A report qabul: qatordan o'chadi ("delivered")
        a.engine.handleIncomingFrame(report)
        assertTrue(a.engine.outboxSnapshot().isEmpty())
        assertEquals(listOf(msgId to "queued", msgId to "delivered"), a.outboxStatus)
        assertEquals(listOf(msgId to true), a.deliveryReports)
    }

    @Test
    fun retryPending_reshootsQueuedFrames_toAllOrTarget() {
        val keyA = MeshCrypto.generateKeyPair()
        val keyB = MeshCrypto.generateKeyPair()
        val keyC = MeshCrypto.generateKeyPair()
        val a = makeEngine(ID_A, keyA)
        a.peerStore.markAuthorized(ID_B, MeshCrypto.b64(keyB.publicKey))
        a.peerStore.markAuthorized(ID_C, MeshCrypto.b64(keyC.publicKey))

        val m1 = a.engine.sendText(ID_B, "uchun B")
        val m2 = a.engine.sendText(ID_C, "uchun C")
        a.emitted.clear()
        val queuedCount = a.outboxStatus.size // 2 ta "queued"

        // Faqat B uchun retry -> 1 ta frame
        a.engine.retryPending(ID_B)
        assertEquals(1, a.emitted.size)
        assertEquals(ID_B, a.emitted.first().targetId)

        // Rate limit: ikkinchi retry tez orada ishlamaydi (cooldown)
        a.emitted.clear()
        a.engine.retryPending(null)
        assertEquals(0, a.emitted.size) // rate-limited, nothing sent

        // Qatordan hech narsa o'chmaydi va status o'zgarmaydi
        assertEquals(2, a.engine.outboxSnapshot().size)
        assertEquals(queuedCount, a.outboxStatus.size)
    }

    @Test
    fun expirePending_removesStale_afterTTL() {
        val a = makeEngine(ID_A, MeshCrypto.generateKeyPair())
        a.peerStore.markAuthorized(ID_B, MeshCrypto.b64(MeshCrypto.generateKeyPair().publicKey))

        // Yangi xabar yuboramiz — msgSeq hozirgi vaqt
        val msgId = a.engine.sendText(ID_B, "juda eski")
        assertEquals(1, a.engine.outboxSnapshot().size)
        a.outboxStatus.clear()

        // expirePending: 24 soat + 1s keyin chaqirsak, frame muddati o'tgan bo'ladi
        val futureNow = System.currentTimeMillis() + RoutingEngine.OUTBOX_TTL_MS + 1000
        a.engine.expirePending(futureNow)

        assertTrue(a.engine.outboxSnapshot().isEmpty())
        assertEquals(listOf(msgId to "expired"), a.outboxStatus)
    }

    @Test
    fun restoreOutbox_expiresStaleImmediately() {
        val keyA = MeshCrypto.generateKeyPair()
        val a = makeEngine(ID_A, keyA)
        a.peerStore.markAuthorized(ID_B, MeshCrypto.b64(MeshCrypto.generateKeyPair().publicKey))

        // Yangi xabar yuboramiz
        val freshId = a.engine.sendText(ID_B, "tiklansin")
        val snapshot = a.engine.outboxSnapshot()
        assertEquals(1, snapshot.size)

        // Eski frame: msgSeq 24 soatdan eski
        val staleId = "stale-msg-id"
        val staleFrame = snapshot.single().second.copy(
            msgSeq = System.currentTimeMillis() - RoutingEngine.OUTBOX_TTL_MS - 1000
        )

        // Restart simulyatsiyasi — yangi engine, yangi outbox
        val restarted = makeEngine(ID_A, keyA)
        restarted.peerStore.markAuthorized(ID_B, MeshCrypto.b64(
            MeshCrypto.generateKeyPair().publicKey))

        // Ikkalasini ham restore qilamiz — faqat fresh saqlanadi
        restarted.engine.restoreOutbox(listOf(
            freshId!! to snapshot.single().second,
            staleId to staleFrame,
        ))
        assertEquals(1, restarted.engine.outboxSnapshot().size)
        assertEquals(freshId, restarted.engine.outboxSnapshot().first().first)
    }

    @Test
    fun restoreOutbox_recoversQueuedFrames() {
        val keyA = MeshCrypto.generateKeyPair()
        val a = makeEngine(ID_A, keyA)
        a.peerStore.markAuthorized(ID_B, MeshCrypto.b64(MeshCrypto.generateKeyPair().publicKey))

        val msgId = a.engine.sendText(ID_B, "tiklansin")
        val snapshot = a.engine.outboxSnapshot()
        assertEquals(1, snapshot.size)

        // Restart simulyatsiyasi: yangi engine (bir xil identity) qatorni tiklaydi
        val restarted = makeEngine(ID_A, keyA)
        restarted.peerStore.markAuthorized(ID_B, MeshCrypto.b64(
            MeshCrypto.generateKeyPair().publicKey))
        restarted.engine.restoreOutbox(snapshot)

        assertEquals(1, restarted.engine.outboxSnapshot().size)
        restarted.engine.retryPending(ID_B)
        assertEquals(1, restarted.emitted.size)
        assertEquals(MessageType.TEXT, restarted.emitted.first().type)
    }

    // ---------------- Phase 6: Heartbeat / presence ----------------

    @Test
    fun sendPing_broadcastAndLoopbackProtected() {
        val a = makeEngine(ID_A, MeshCrypto.generateKeyPair())

        a.engine.sendPing()
        assertEquals(1, a.emitted.size)
        val ping = a.emitted.first()
        assertEquals(MessageType.PEER_PING, ping.type)
        assertEquals(MeshFrame.BROADCAST, ping.targetId)
        assertFalse(ping.encrypted)

        // O'z ping'i qaytib kelsa ham dedup tufayli relay qilinmaydi
        a.emitted.clear()
        a.engine.handleIncomingFrame(ping)
        assertTrue(a.emitted.isEmpty())
    }

    @Test
    fun incomingFrame_marksSenderSeen() {
        val keyA = MeshCrypto.generateKeyPair()
        val keyB = MeshCrypto.generateKeyPair()
        val a = makeEngine(ID_A, keyA)
        val b = makeEngine(ID_B, keyB)

        // B A'ga pair bo'lgan (A B'ni biladi), lekin B hali ko'rinmagan
        b.peerStore.markAuthorized(ID_A, MeshCrypto.b64(keyA.publicKey))

        // A ping yuboradi -> B ko'rgan bo'ladi
        a.engine.sendPing()
        b.engine.handleIncomingFrame(a.emitted.last())
        assertNotNull(b.peerStore.get(ID_A))
        assertTrue(b.peerStore.get(ID_A)!!.lastSeenMs > 0)

        // A'ning o'z ping'i (broadcast) B'da seen bo'lib, relay qilinmaydi
        assertTrue(b.emitted.isEmpty())
    }

    @Test
    fun deliveryReport_forUnknownMsg_doesNotCrash() {
        val a = makeEngine(ID_A, MeshCrypto.generateKeyPair())
        a.engine.handleIncomingFrame(makeDeliveryReport(ID_B, "noma'lum", true))
        assertTrue(a.engine.outboxSnapshot().isEmpty())
        assertEquals(listOf("noma'lum" to true), a.deliveryReports)
    }

    @Test
    fun deliveryReport_failed_removesFromOutbox() {
        val a = makeEngine(ID_A, MeshCrypto.generateKeyPair())
        a.peerStore.markAuthorized(ID_B, MeshCrypto.b64(MeshCrypto.generateKeyPair().publicKey))

        val msgId = a.engine.sendText(ID_B, "sot bo'lmadi")
        a.engine.handleIncomingFrame(makeDeliveryReport(ID_B, msgId!!, false))

        assertTrue(a.engine.outboxSnapshot().isEmpty())
        assertEquals(listOf(msgId to "queued", msgId to "delivered"), a.outboxStatus) // failed ham qatordan o'chadi
        assertEquals(listOf(msgId to false), a.deliveryReports)
    }
}
