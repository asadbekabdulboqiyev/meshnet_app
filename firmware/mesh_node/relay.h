#ifndef RELAY_H
#define RELAY_H

#include <stdint.h>

#include "mesh_frame.h"

#ifdef __cplusplus
extern "C" {
#endif

/*
 * Node relay rule (synchronized with RoutingEngine.dedup + TTL):
 *  - Dedup: (sender, msg_seq) key, 60s lifetime (SEEN_CACHE_TTL_MS).
 *  - Frames with ttl == 0 are dropped.
 *  - Otherwise: forward (ttl is decremented), then the entry is recorded as seen.
 * The node is a "transparent repeater": type/size do not change, only ttl--.
 * (RELAY wrapping happens on phones; the node only re-transmits the flood.)
 */

#define RELAY_SEEN_SIZE   64
#define RELAY_SEEN_TTL_MS 60000U

/* Why it was dropped — for debugging */
enum {
    RELAY_DROP_NONE = 0, /* will be forwarded */
    RELAY_DROP_DUP,      /* previously seen (sender,seq) */
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
    int next; /* round-robin write index */
} relay_ctx_t;

void relay_init(relay_ctx_t *ctx);

/* Dedup + TTL check. 1 = forward, 0 = drop. *reason is filled. */
int relay_decide(relay_ctx_t *ctx, const mesh_frame_t *f, uint32_t now_ms, int *reason);

/* Decrements the ttl before forwarding (prevents bouncing back in). */
static inline void relay_decrement_ttl(mesh_frame_t *f) {
    if (f->ttl > 0) f->ttl--;
}

#ifdef __cplusplus
}
#endif

#endif /* RELAY_H */
