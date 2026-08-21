package com.meshnet.meshnet_app

import com.meshnet.meshnet_app.protocol.MessageType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * MessageType testlari: fromCode barcha 22 ta code uchun, noto'g'ri code.
 */
class MessageTypeTest {

    @Test
    fun fromCode_peerPing() {
        assertEquals(MessageType.PEER_PING, MessageType.fromCode(0x01))
    }

    @Test
    fun fromCode_text() {
        assertEquals(MessageType.TEXT, MessageType.fromCode(0x02))
    }

    @Test
    fun fromCode_pairReq() {
        assertEquals(MessageType.PAIR_REQ, MessageType.fromCode(0x03))
    }

    @Test
    fun fromCode_pairAck() {
        assertEquals(MessageType.PAIR_ACK, MessageType.fromCode(0x04))
    }

    @Test
    fun fromCode_relay() {
        assertEquals(MessageType.RELAY, MessageType.fromCode(0x05))
    }

    @Test
    fun fromCode_deliveryReport() {
        assertEquals(MessageType.DELIVERY_REPORT, MessageType.fromCode(0x06))
    }

    @Test
    fun fromCode_findPeer() {
        assertEquals(MessageType.FIND_PEER, MessageType.fromCode(0x07))
    }

    @Test
    fun fromCode_findPeerAck() {
        assertEquals(MessageType.FIND_PEER_ACK, MessageType.fromCode(0x08))
    }

    @Test
    fun fromCode_fileStart() {
        assertEquals(MessageType.FILE_START, MessageType.fromCode(0x10))
    }

    @Test
    fun fromCode_fileChunk() {
        assertEquals(MessageType.FILE_CHUNK, MessageType.fromCode(0x11))
    }

    @Test
    fun fromCode_fileEnd() {
        assertEquals(MessageType.FILE_END, MessageType.fromCode(0x12))
    }

    @Test
    fun fromCode_groupCreate() {
        assertEquals(MessageType.GROUP_CREATE, MessageType.fromCode(0x20))
    }

    @Test
    fun fromCode_groupMsg() {
        assertEquals(MessageType.GROUP_MSG, MessageType.fromCode(0x21))
    }

    @Test
    fun fromCode_groupAddMember() {
        assertEquals(MessageType.GROUP_ADD_MEMBER, MessageType.fromCode(0x22))
    }

    @Test
    fun fromCode_groupRemoveMember() {
        assertEquals(MessageType.GROUP_REMOVE_MEMBER, MessageType.fromCode(0x23))
    }

    @Test
    fun fromCode_groupKeyDist() {
        assertEquals(MessageType.GROUP_KEY_DIST, MessageType.fromCode(0x24))
    }

    @Test
    fun fromCode_groupLeave() {
        assertEquals(MessageType.GROUP_LEAVE, MessageType.fromCode(0x25))
    }

    @Test
    fun fromCode_voiceMsg() {
        assertEquals(MessageType.VOICE_MSG, MessageType.fromCode(0x30))
    }

    @Test
    fun fromCode_ratchetInit() {
        assertEquals(MessageType.RATCHET_INIT, MessageType.fromCode(0x40))
    }

    @Test
    fun fromCode_ratchetMsg() {
        assertEquals(MessageType.RATCHET_MSG, MessageType.fromCode(0x41))
    }

    // =================== Invalid codes ===================

    @Test
    fun fromCode_invalidZero_returnsNull() {
        assertNull(MessageType.fromCode(0x00))
    }

    @Test
    fun fromCode_invalidFF_returnsNull() {
        assertNull(MessageType.fromCode(0xFF.toByte()))
    }

    @Test
    fun fromCode_gapBetweenTextAndPair_returnsNull() {
        assertNull(MessageType.fromCode(0x09))
    }

    @Test
    fun fromCode_gapBetweenFindAndFile_returnsNull() {
        assertNull(MessageType.fromCode(0x09))
    }

    @Test
    fun fromCode_gapBetweenFileAndGroup_returnsNull() {
        assertNull(MessageType.fromCode(0x13))
    }

    @Test
    fun fromCode_gapBetweenGroupAndVoice_returnsNull() {
        assertNull(MessageType.fromCode(0x26))
    }

    @Test
    fun fromCode_gapBetweenVoiceAndRatchet_returnsNull() {
        assertNull(MessageType.fromCode(0x31))
    }

    @Test
    fun fromCode_gapBetweenRatchetInitAndMsg_returnsNull() {
        assertNull(MessageType.fromCode(0x42))
    }

    @Test
    fun fromCode_negativeOne_returnsNull() {
        assertNull(MessageType.fromCode((-1).toByte()))
    }

    // =================== Code properties ===================

    @Test
    fun allCodes_areUnique() {
        val codes = MessageType.entries.map { it.code }
        assertEquals(codes.size, codes.toSet().size)
    }

    @Test
    fun allEntries_count() {
        assertEquals(36, MessageType.entries.size)
    }

    @Test
    fun peerPing_codeIs0x01() {
        assertEquals(0x01.toByte(), MessageType.PEER_PING.code)
    }

    @Test
    fun text_codeIs0x02() {
        assertEquals(0x02.toByte(), MessageType.TEXT.code)
    }

    @Test
    fun relay_codeIs0x05() {
        assertEquals(0x05.toByte(), MessageType.RELAY.code)
    }

    @Test
    fun fileStart_codeIs0x10() {
        assertEquals(0x10.toByte(), MessageType.FILE_START.code)
    }

    @Test
    fun groupCreate_codeIs0x20() {
        assertEquals(0x20.toByte(), MessageType.GROUP_CREATE.code)
    }

    @Test
    fun voiceMsg_codeIs0x30() {
        assertEquals(0x30.toByte(), MessageType.VOICE_MSG.code)
    }

    @Test
    fun ratchetInit_codeIs0x40() {
        assertEquals(0x40.toByte(), MessageType.RATCHET_INIT.code)
    }

    @Test
    fun ratchetMsg_codeIs0x41() {
        assertEquals(0x41.toByte(), MessageType.RATCHET_MSG.code)
    }

    // =================== fromCode roundtrip ===================

    @Test
    fun fromCode_roundtrip_allEntries() {
        for (entry in MessageType.entries) {
            assertEquals(entry, MessageType.fromCode(entry.code))
        }
    }

    @Test
    fun fromCode_codeValuesAreNonZero() {
        for (entry in MessageType.entries) {
            assertEquals("Code for ${entry.name} should not be 0",
                true, entry.code != 0x00.toByte())
        }
    }
}
