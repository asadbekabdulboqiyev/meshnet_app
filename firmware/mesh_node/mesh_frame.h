#ifndef MESH_FRAME_H
#define MESH_FRAME_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/*
 * MeshNet wire format (MESH_PROTOCOL.md §3, MeshFrame.kt bilan sinxron).
 * Barcha ko'p baytli maydonlar BIG_ENDIAN.
 *
 * | ofset | maydon           | hajm |
 * |-------|------------------|------|
 * | 0-1   | magic 0x4D 0x4E  | 2    |
 * | 2     | version 0x01     | 1    |
 * | 3     | type             | 1    |
 * | 4     | hop_limit        | 1    |
 * | 5     | ttl              | 1    |
 * | 6     | flags (bit0=enc) | 1    |
 * | 7-22  | sender_id        | 16   |
 * | 23-38 | target_id        | 16   |
 * | 39-46 | msg_seq          | 8    |
 * | 47-78 | sender_pubkey    | 32   | (faqat PAIR_REQ/ACK, FIND_PEER_ACK)
 * | ?     | payload          | n    |
 *
 * Broadcast target = 16 bayt nol.
 */

#define MESH_HEADER_SIZE     47
#define MESH_ID_BYTES        16
#define MESH_PUBKEY_LEN      32
#define MESH_VERSION         0x01
#define MESH_MAGIC1          0x4D
#define MESH_MAGIC2          0x4E
/* Bitta BLE write'da maksimal frame (BleTransport.MAX_PAYLOAD) */
#define MESH_MAX_FRAME       244

/* MessageType (MeshFrame.kt / MessageType.kt) */
#define MSG_PEER_PING        0x01
#define MSG_TEXT             0x02
#define MSG_PAIR_REQ         0x03
#define MSG_PAIR_ACK         0x04
#define MSG_RELAY            0x05
#define MSG_DELIVERY_REPORT  0x06
#define MSG_FIND_PEER        0x07
#define MSG_FIND_PEER_ACK    0x08

/* Framelar turi bo'yicha pubkey bor bo'lishi mumkin */
#define MSG_HAS_PUBKEY(t) \
    ((t) == MSG_PAIR_REQ || (t) == MSG_PAIR_ACK || (t) == MSG_FIND_PEER_ACK)

typedef struct {
    uint8_t type;
    uint8_t hop_limit;
    uint8_t ttl;
    uint8_t flags;          /* bit0 = payload shifrlangan */
    uint8_t sender[MESH_ID_BYTES];
    uint8_t target[MESH_ID_BYTES]; /* barchasi nol = broadcast */
    uint64_t msg_seq;       /* epoch-ms (Long, big-endian) */
    const uint8_t *pubkey;  /* MESH_PUBKEY_LEN — faqat MSG_HAS_PUBKEY turlari */
    size_t pubkey_len;
    const uint8_t *payload; /* bufer ichidagi ko'rsatkich */
    size_t payload_len;
} mesh_frame_t;

/* Frame ni buferdan parse qiladi. 1 = ok, 0 = yaroqsiz (magic/hajm/turi). */
int mesh_frame_parse(const uint8_t *buf, size_t len, mesh_frame_t *out);

/* Frame ni buferga yozadi. Qaytarish: yozilgan baytlar (0 = sig'madi). */
size_t mesh_frame_encode(const mesh_frame_t *f, uint8_t *buf, size_t cap);

int mesh_frame_id_equals(const uint8_t a[MESH_ID_BYTES], const uint8_t b[MESH_ID_BYTES]);
int mesh_frame_is_broadcast(const uint8_t id[MESH_ID_BYTES]);

/* ESP32 MAC dan barqaror node deviceId (16 bayt):
 *   [0..1]='m''n', [2..7]=MAC, [12..15]={0x4D,0x4E,0x0E,0x01} */
void mesh_node_generate_id(uint8_t out[MESH_ID_BYTES]);

#ifdef __cplusplus
}
#endif

#endif /* MESH_FRAME_H */
