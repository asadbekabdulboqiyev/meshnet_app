/*
 * Host testlar (gcc): mesh_frame + relay logikasi — apparatsiz tekshirish.
 * Ishga tushirish:  make test  (yoki cc -Wall -I.. test_relay.c ../relay.c ../mesh_frame.c)
 */
#include <assert.h>
#include <stdio.h>
#include <string.h>

#include "../mesh_frame.h"
#include "../relay.h"

#define FAIL(...) do { fprintf(stderr, "FAIL %d: ", __LINE__); fprintf(stderr, __VA_ARGS__); fprintf(stderr, "\n"); return 1; } while (0)

static int tests = 0;

/* Oddiy TEXT frame qurish (real Android emit kabi). */
static size_t build_text(uint8_t *buf, const uint8_t sender[16], const uint8_t target[16],
                         uint64_t seq, uint8_t ttl, const char *payload) {
    mesh_frame_t f;
    memset(&f, 0, sizeof(f));
    f.type = MSG_TEXT;
    f.hop_limit = 2;
    f.ttl = ttl;
    f.flags = 0x01; /* encrypted */
    memcpy(f.sender, sender, 16);
    memcpy(f.target, target, 16);
    f.msg_seq = seq;
    f.payload = (const uint8_t *)payload;
    f.payload_len = strlen(payload);
    size_t n = mesh_frame_encode(&f, buf, MESH_MAX_FRAME);
    assert(n > 0);
    return n;
}

static int test_frame_roundtrip(void) {
    uint8_t sender[16], target[16];
    for (int i = 0; i < 16; i++) { sender[i] = (uint8_t)i; target[i] = (uint8_t)(16 - i); }

    uint8_t buf[MESH_MAX_FRAME];
    size_t n = build_text(buf, sender, target, 0x0102030405060708ULL, 6, "salom mesh");
    assert(n == MESH_HEADER_SIZE + 10);

    mesh_frame_t f;
    if (!mesh_frame_parse(buf, n, &f)) FAIL("parse muvaffaqiyatsiz");
    if (f.type != MSG_TEXT) FAIL("type");
    if (f.hop_limit != 2) FAIL("hop_limit");
    if (f.ttl != 6) FAIL("ttl");
    if (!(f.flags & 0x01)) FAIL("encrypted flag");
    if (!mesh_frame_id_equals(f.sender, sender)) FAIL("sender");
    if (!mesh_frame_id_equals(f.target, target)) FAIL("target");
    if (f.msg_seq != 0x0102030405060708ULL) FAIL("msg_seq");
    if (f.payload_len != 10 || memcmp(f.payload, "salom mesh", 10) != 0) FAIL("payload");
    if (f.pubkey != NULL) FAIL("TEXT'da pubkey bo'lmasligi kerak");

    /* Re-encode -> bayt-bayt bir xil */
    uint8_t out[MESH_MAX_FRAME];
    size_t m = mesh_frame_encode(&f, out, sizeof(out));
    if (m != n || memcmp(out, buf, n) != 0) FAIL("re-encode farqli");
    tests++;
    return 0;
}

static int test_broadcast_target(void) {
    uint8_t sender[16] = {0};
    uint8_t bc[16] = {0};
    sender[0] = 0xAA;
    if (!mesh_frame_is_broadcast(bc)) FAIL("bc nol bo'lishi kerak");
    if (mesh_frame_is_broadcast(sender)) FAIL("sender broadcast bo'lmasligi kerak");
    tests++;
    return 0;
}

static int test_frame_invalid(void) {
    uint8_t buf[MESH_HEADER_SIZE];
    memset(buf, 0, sizeof(buf));
    buf[0] = 0x4D; buf[1] = 0x4E; buf[2] = 0x01; buf[3] = 0x02; /* TEXT */

    mesh_frame_t f;
    if (mesh_frame_parse(buf, 0, &f)) FAIL("0 bayt o'tmasligi kerak");
    if (mesh_frame_parse(buf, MESH_HEADER_SIZE - 1, &f)) FAIL("hajm kichik bo'lsa o'tmasligi kerak");

    uint8_t bad[MESH_HEADER_SIZE + 4];
    memcpy(bad, buf, MESH_HEADER_SIZE);
    bad[0] = 0x00; /* noto'g'ri magic */
    if (mesh_frame_parse(bad, sizeof(bad), &f)) FAIL("magic xato bo'lsa o'tmasligi kerak");
    bad[0] = 0x4D; bad[1] = 0x4E;
    bad[3] = 0x63; /* noma'lum tur */
    if (mesh_frame_parse(bad, sizeof(bad), &f)) FAIL("noma'lum tur o'tmasligi kerak");
    tests++;
    return 0;
}

static int test_pair_frame_pubkey(void) {
    uint8_t sender[16], target[16];
    for (int i = 0; i < 16; i++) { sender[i] = (uint8_t)i; target[i] = (uint8_t)i; }

    mesh_frame_t f;
    memset(&f, 0, sizeof(f));
    f.type = MSG_PAIR_ACK;
    f.hop_limit = 2;
    f.ttl = 6;
    memcpy(f.sender, sender, 16);
    memcpy(f.target, target, 16);
    f.msg_seq = 99;
    uint8_t pk[MESH_PUBKEY_LEN];
    for (int i = 0; i < (int)MESH_PUBKEY_LEN; i++) pk[i] = (uint8_t)(0x80 + i);
    f.pubkey = pk;
    f.pubkey_len = MESH_PUBKEY_LEN;

    uint8_t buf[MESH_MAX_FRAME];
    size_t n = mesh_frame_encode(&f, buf, sizeof(buf));
    if (n != MESH_HEADER_SIZE + MESH_PUBKEY_LEN) FAIL("PAIR hajmi");

    mesh_frame_t p;
    if (!mesh_frame_parse(buf, n, &p)) FAIL("PAIR parse");
    if (p.pubkey_len != MESH_PUBKEY_LEN || memcmp(p.pubkey, pk, MESH_PUBKEY_LEN) != 0) FAIL("pubkey");
    tests++;
    return 0;
}

static int test_relay_dedup_and_ttl(void) {
    uint8_t sender[16], target[16];
    for (int i = 0; i < 16; i++) { sender[i] = (uint8_t)i; target[i] = (uint8_t)(i + 1); }

    uint8_t buf[MESH_MAX_FRAME];
    size_t n = build_text(buf, sender, target, 1000, 6, "xabar");
    mesh_frame_t f;
    mesh_frame_parse(buf, n, &f);

    relay_ctx_t ctx;
    relay_init(&ctx);

    int reason = -1;
    if (!relay_decide(&ctx, &f, 1000, &reason)) FAIL("birinchi marta forward bo'lishi kerak (reason=%d)", reason);

    /* TTL pasaytirilib, yana forward: endi ttl=5 */
    relay_decrement_ttl(&f);
    if (f.ttl != 5) FAIL("ttl-- noto'g'ri");

    /* Dublikat: bir xil (sender,seq) -> drop */
    mesh_frame_t f2 = f;
    if (relay_decide(&ctx, &f2, 1500, &reason)) FAIL("dublikat o'tmasligi kerak");
    if (reason != RELAY_DROP_DUP) FAIL("dup reason");

    /* ttl==0 -> drop */
    f2.ttl = 0;
    if (relay_decide(&ctx, &f2, 2000, &reason)) FAIL("ttl=0 o'tmasligi kerak");
    if (reason != RELAY_DROP_TTL) FAIL("ttl reason");

    /* Yangi seq -> forward */
    f2 = f;
    f2.msg_seq = 1001;
    f2.ttl = 5;
    if (!relay_decide(&ctx, &f2, 2500, &reason)) FAIL("yangi seq forward bo'lishi kerak");
    tests++;
    return 0;
}

static int test_relay_seen_expiry(void) {
    uint8_t sender[16] = {0};
    uint8_t target[16] = {0};
    sender[0] = 0x01;

    uint8_t buf[MESH_MAX_FRAME];
    size_t n = build_text(buf, sender, target, 7, 6, "takror");
    mesh_frame_t f;
    mesh_frame_parse(buf, n, &f);

    relay_ctx_t ctx;
    relay_init(&ctx);

    int reason = -1;
    if (!relay_decide(&ctx, &f, 0, &reason)) FAIL("ilk forward");

    /* 61s keyin (TTL 60s) — dublikat endi o'tishi kerak */
    if (relay_decide(&ctx, &f, RELAY_SEEN_TTL_MS + 1000, &reason)) {
        tests++;
        return 0;
    }
    FAIL("60s keyin takror qabul qilinishi kerak (reason=%d)", reason);
}

static int test_relay_cache_full(void) {
    uint8_t target[16] = {0};
    relay_ctx_t ctx;
    relay_init(&ctx);

    /* Cache'dan katta: 64+ xabar turli seq'lar bilan */
    for (uint64_t s = 0; s < RELAY_SEEN_SIZE + 20; s++) {
        uint8_t sender[16] = {0};
        sender[15] = (uint8_t)(s & 0xFF);
        sender[14] = (uint8_t)((s >> 8) & 0xFF);
        uint8_t buf[MESH_MAX_FRAME];
        size_t n = build_text(buf, sender, target, s, 6, "z");
        mesh_frame_t f;
        mesh_frame_parse(buf, n, &f);
        int reason = -1;
        if (!relay_decide(&ctx, &f, (uint32_t)s, &reason)) FAIL("yangi entry o'tishi kerak");
    }
    tests++;
    return 0;
}

int main(void) {
    int rc = 0;
    rc |= test_frame_roundtrip();
    rc |= test_broadcast_target();
    rc |= test_frame_invalid();
    rc |= test_pair_frame_pubkey();
    rc |= test_relay_dedup_and_ttl();
    rc |= test_relay_seen_expiry();
    rc |= test_relay_cache_full();
    if (rc == 0) {
        printf("PASS: %d ta test\n", tests);
    }
    return rc;
}
