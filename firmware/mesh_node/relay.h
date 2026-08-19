#ifndef RELAY_H
#define RELAY_H

#include <stdint.h>

#include "mesh_frame.h"

#ifdef __cplusplus
extern "C" {
#endif

/*
 * Node relay qoidasi (RoutingEngine.dedup + TTL bilan sinxron):
 *  - Dedup: (sender, msg_seq) kaliti, 60s yashash (SEEN_CACHE_TTL_MS).
 *  - ttl == 0 bo'lsa tashlab yuboriladi.
 *  - Qolganida: forward (ttl pasaytiriladi), keyin seen'ga qayd etiladi.
 * Node "shaffof repeater": tur/o'lcham o'zgarmaydi, faqat ttl--.
 * (RELAY o'rash — telefonlarda; node faqat floodni qayta uzatadi.)
 */

#define RELAY_SEEN_SIZE   64
#define RELAY_SEEN_TTL_MS 60000U

/* Nega tashlandi — debug uchun */
enum {
    RELAY_DROP_NONE = 0, /* forward qilinadi */
    RELAY_DROP_DUP,      /* oldin ko'rilgan (sender,seq) */
    RELAY_DROP_TTL,      /* ttl == 0 */
};

typedef struct {
    uint8_t sender[MESH_ID_BYTES];
    uint64_t seq;
    uint32_t seen_ms;
} relay_seen_entry_t;

typedef struct {
    relay_seen_entry_t entries[RELAY_SEEN_SIZE];
    int count;
    int next; /* round-robin yozuv indeksi */
} relay_ctx_t;

void relay_init(relay_ctx_t *ctx);

/* Dedup + TTL tekshiruvi. 1 = forward, 0 = drop. *reason to'ldiriladi. */
int relay_decide(relay_ctx_t *ctx, const mesh_frame_t *f, uint32_t now_ms, int *reason);

/* Forward oldidan ttl ni pasaytiradi (eshikdan qaytib tashlash yo'q). */
static inline void relay_decrement_ttl(mesh_frame_t *f) {
    if (f->ttl > 0) f->ttl--;
}

#ifdef __cplusplus
}
#endif

#endif /* RELAY_H */
