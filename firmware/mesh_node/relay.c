#include "relay.h"

#include <string.h>

void relay_init(relay_ctx_t *ctx) {
    if (ctx == NULL) return;
    memset(ctx, 0, sizeof(*ctx));
    ctx->next = 0;
}

static void relay_evict_expired(relay_ctx_t *ctx, uint32_t now_ms) {
    int w = 0;
    for (int i = 0; i < ctx->count; i++) {
        uint32_t age = now_ms - ctx->entries[i].seen_ms;
        if (age < RELAY_SEEN_TTL_MS) {
            if (w != i) ctx->entries[w] = ctx->entries[i];
            w++;
        }
    }
    ctx->count = w;
}

static int relay_find(const relay_ctx_t *ctx, const uint8_t sender[MESH_ID_BYTES], uint64_t seq) {
    for (int i = 0; i < ctx->count; i++) {
        if (ctx->entries[i].seq == seq &&
            mesh_frame_id_equals(ctx->entries[i].sender, sender)) {
            return i;
        }
    }
    return -1;
}

int relay_decide(relay_ctx_t *ctx, const mesh_frame_t *f, uint32_t now_ms, int *reason) {
    if (reason) *reason = RELAY_DROP_NONE;
    if (ctx == NULL || f == NULL) return 0;

    if (f->ttl == 0) {
        if (reason) *reason = RELAY_DROP_TTL;
        return 0;
    }

    /* Eski yozuvlarni tozalash (massiv kichik — chiziqli tekshirish kifoya) */
    relay_evict_expired(ctx, now_ms);

    if (relay_find(ctx, f->sender, f->msg_seq) >= 0) {
        if (reason) *reason = RELAY_DROP_DUP;
        return 0;
    }

    /* Yangi ko'ringan (sender,seq) qayd etamiz — round-robin almashinuvi */
    relay_seen_entry_t *e;
    if (ctx->count < RELAY_SEEN_SIZE) {
        e = &ctx->entries[ctx->count++];
    } else {
        e = &ctx->entries[ctx->next];
        ctx->next = (ctx->next + 1) % RELAY_SEEN_SIZE;
    }
    memcpy(e->sender, f->sender, MESH_ID_BYTES);
    e->seq = f->msg_seq;
    e->seen_ms = now_ms;

    return 1;
}
