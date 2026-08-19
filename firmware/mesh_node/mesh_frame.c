#include "mesh_frame.h"

#include <string.h>

int mesh_frame_parse(const uint8_t *buf, size_t len, mesh_frame_t *out) {
    if (buf == NULL || out == NULL || len < MESH_HEADER_SIZE) return 0;
    if (buf[0] != MESH_MAGIC1 || buf[1] != MESH_MAGIC2) return 0;
    if (buf[2] != MESH_VERSION) return 0;

    uint8_t type = buf[3];
    if (type < MSG_PEER_PING || type > MSG_FIND_PEER_ACK) return 0;

    out->type = type;
    out->hop_limit = buf[4];
    out->ttl = buf[5];
    out->flags = buf[6];
    memcpy(out->sender, buf + 7, MESH_ID_BYTES);
    memcpy(out->target, buf + 23, MESH_ID_BYTES);

    /* msg_seq: 8 bayt big-endian */
    out->msg_seq = 0;
    for (int i = 0; i < 8; i++) {
        out->msg_seq = (out->msg_seq << 8) | buf[39 + i];
    }

    size_t off = MESH_HEADER_SIZE;

    /* PAIR_REQ/ACK, FIND_PEER_ACK: header'den so'ng 32 bayt pubkey */
    out->pubkey = NULL;
    out->pubkey_len = 0;
    if (MSG_HAS_PUBKEY(type)) {
        if (off + MESH_PUBKEY_LEN <= len) {
            out->pubkey = buf + off;
            out->pubkey_len = MESH_PUBKEY_LEN;
            off += MESH_PUBKEY_LEN;
        }
    }

    out->payload = buf + off;
    out->payload_len = len - off;
    return 1;
}

size_t mesh_frame_encode(const mesh_frame_t *f, uint8_t *buf, size_t cap) {
    if (f == NULL || buf == NULL) return 0;
    size_t need = MESH_HEADER_SIZE + f->pubkey_len + f->payload_len;
    if (cap < need) return 0;

    buf[0] = MESH_MAGIC1;
    buf[1] = MESH_MAGIC2;
    buf[2] = MESH_VERSION;
    buf[3] = f->type;
    buf[4] = f->hop_limit;
    buf[5] = f->ttl;
    buf[6] = f->flags;
    memcpy(buf + 7, f->sender, MESH_ID_BYTES);
    memcpy(buf + 23, f->target, MESH_ID_BYTES);
    for (int i = 0; i < 8; i++) {
        buf[39 + i] = (uint8_t)(f->msg_seq >> (8 * (7 - i)));
    }
    size_t off = MESH_HEADER_SIZE;
    if (f->pubkey_len > 0 && f->pubkey != NULL) {
        memcpy(buf + off, f->pubkey, f->pubkey_len);
        off += f->pubkey_len;
    }
    if (f->payload_len > 0 && f->payload != NULL) {
        memcpy(buf + off, f->payload, f->payload_len);
    }
    return need;
}

int mesh_frame_id_equals(const uint8_t a[MESH_ID_BYTES], const uint8_t b[MESH_ID_BYTES]) {
    return memcmp(a, b, MESH_ID_BYTES) == 0;
}

int mesh_frame_is_broadcast(const uint8_t id[MESH_ID_BYTES]) {
    for (int i = 0; i < MESH_ID_BYTES; i++) {
        if (id[i] != 0) return 0;
    }
    return 1;
}

/* ESP32 MAC o'qish — faqat ESP32 build'ida mavjud; xost testlarda stub. */
#if defined(ESP32)
#include "esp_mac.h"
void mesh_node_generate_id(uint8_t out[MESH_ID_BYTES]) {
    uint8_t mac[6] = {0};
    esp_efuse_mac_get_default(mac);
    out[0] = 0x6D; /* 'm' */
    out[1] = 0x6E; /* 'n' */
    memcpy(out + 2, mac, 6);
    out[8] = 0x00; out[9] = 0x00; out[10] = 0x00; out[11] = 0x00;
    out[12] = 0x4D; out[13] = 0x4E; out[14] = 0x0E; out[15] = 0x01;
}
#else
void mesh_node_generate_id(uint8_t out[MESH_ID_BYTES]) {
    memset(out, 0, MESH_ID_BYTES);
    out[14] = 0x0E; /* test uchun marker */
}
#endif
