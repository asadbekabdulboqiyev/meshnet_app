package com.meshnet.meshnet_app.protocol

enum class MessageType(val code: Byte) {
    PEER_PING(0x01),
    TEXT(0x02),
    PAIR_REQ(0x03),
    PAIR_ACK(0x04),
    RELAY(0x05),
    DELIVERY_REPORT(0x06),
    FIND_PEER(0x07),
    FIND_PEER_ACK(0x08),
    FILE_START(0x10),
    FILE_CHUNK(0x11),
    FILE_END(0x12),
    GROUP_CREATE(0x20),
    GROUP_MSG(0x21),
    GROUP_ADD_MEMBER(0x22),
    GROUP_REMOVE_MEMBER(0x23),
    GROUP_KEY_DIST(0x24),
    GROUP_LEAVE(0x25),
    VOICE_MSG(0x30),
    RATCHET_INIT(0x40),
    RATCHET_MSG(0x41),
    READ_RECEIPT(0x50);

    companion object {
        fun fromCode(code: Byte): MessageType? = entries.firstOrNull { it.code == code }
    }
}