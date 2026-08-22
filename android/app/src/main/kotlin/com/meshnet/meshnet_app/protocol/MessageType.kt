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
    READ_RECEIPT(0x50),
    // LocalNet (Phase 1): decentralized DNS over mesh
    DNS_ANNOUNCE(0x60),
    DNS_QUERY(0x61),
    DNS_RESPONSE(0x62),
    // LocalNet Phase 3: collaboration over mesh
    BOARD_STROKE(0x63),
    DOC_EDIT(0x64),
    POLL_CREATE(0x65),
    POLL_VOTE(0x66),
    BOARD_CLEAR(0x67),
    // LocalNet: full doc state broadcast (creation + gap fill)
    DOC_ANNOUNCE(0x76),
    // LocalNet (Phase 5): internet gateway presence over mesh
    VPN_GW_ANNOUNCE(0x68),
    // LocalNet (Phase 6): emergency broadcast
    EMERGENCY_ALERT(0x70),
    EMERGENCY_ACK(0x71),
    EMERGENCY_CANCEL(0x72),
    // LocalNet (Phase 6): mesh-wide search
    SEARCH_QUERY(0x73),
    SEARCH_RESULT(0x74),
    SEARCH_INDEX_SYNC(0x75),
    // RBAC / CRDT wire protocol
    ROLE_GRANT(0x77),
    SIGN_KEY(0x78),
    DOC_OPS(0x79);

    companion object {
        fun fromCode(code: Byte): MessageType? = entries.firstOrNull { it.code == code }
    }
}