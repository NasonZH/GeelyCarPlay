#include "chacha20poly1305.h"

#include <string.h>

#define ROTL32(v, n) (((v) << (n)) | ((v) >> (32 - (n))))

static uint32_t load32(const uint8_t *p) {
    return (uint32_t)p[0] |
           ((uint32_t)p[1] << 8) |
           ((uint32_t)p[2] << 16) |
           ((uint32_t)p[3] << 24);
}

static void store32(uint8_t *p, uint32_t v) {
    p[0] = (uint8_t)v;
    p[1] = (uint8_t)(v >> 8);
    p[2] = (uint8_t)(v >> 16);
    p[3] = (uint8_t)(v >> 24);
}

static void store64(uint8_t *p, uint64_t v) {
    store32(p, (uint32_t)v);
    store32(p + 4, (uint32_t)(v >> 32));
}

static void quarter_round(uint32_t *a, uint32_t *b, uint32_t *c, uint32_t *d) {
    *a += *b; *d ^= *a; *d = ROTL32(*d, 16);
    *c += *d; *b ^= *c; *b = ROTL32(*b, 12);
    *a += *b; *d ^= *a; *d = ROTL32(*d, 8);
    *c += *d; *b ^= *c; *b = ROTL32(*b, 7);
}

static void chacha20_block(
    const uint32_t key[8],
    uint32_t counter,
    const uint32_t nonce[3],
    uint8_t out[64]) {
    uint32_t x[16];
    uint32_t initial[16];
    int i;

    x[0] = 0x61707865u;
    x[1] = 0x3320646eu;
    x[2] = 0x79622d32u;
    x[3] = 0x6b206574u;
    for (i = 0; i < 8; i++) x[4 + i] = key[i];
    x[12] = counter;
    x[13] = nonce[0];
    x[14] = nonce[1];
    x[15] = nonce[2];
    memcpy(initial, x, sizeof(initial));

    for (i = 0; i < 10; i++) {
        quarter_round(&x[0], &x[4], &x[8], &x[12]);
        quarter_round(&x[1], &x[5], &x[9], &x[13]);
        quarter_round(&x[2], &x[6], &x[10], &x[14]);
        quarter_round(&x[3], &x[7], &x[11], &x[15]);
        quarter_round(&x[0], &x[5], &x[10], &x[15]);
        quarter_round(&x[1], &x[6], &x[11], &x[12]);
        quarter_round(&x[2], &x[7], &x[8], &x[13]);
        quarter_round(&x[3], &x[4], &x[9], &x[14]);
    }

    for (i = 0; i < 16; i++) store32(out + 4 * i, x[i] + initial[i]);
}

static void chacha20_xor(
    const uint8_t key[32],
    const uint8_t nonce[12],
    uint32_t counter,
    const uint8_t *in,
    size_t length,
    uint8_t *out) {
    uint32_t key_words[8];
    uint32_t nonce_words[3];
    uint8_t block[64];
    int i;

    for (i = 0; i < 8; i++) key_words[i] = load32(key + 4 * i);
    for (i = 0; i < 3; i++) nonce_words[i] = load32(nonce + 4 * i);

    while (length > 0) {
        size_t take = length < 64 ? length : 64;
        size_t j;
        chacha20_block(key_words, counter, nonce_words, block);
        for (j = 0; j < take; j++) out[j] = (uint8_t)(in[j] ^ block[j]);
        in += take;
        out += take;
        length -= take;
        counter++;
    }
}

typedef struct {
    uint32_t r[5];
    uint32_t h[5];
    uint32_t pad[4];
    size_t leftover;
    uint8_t buffer[16];
} poly1305_state;

static void poly1305_init(poly1305_state *st, const uint8_t key[32]) {
    int i;
    st->r[0] = load32(key + 0) & 0x3ffffffu;
    st->r[1] = (load32(key + 3) >> 2) & 0x3ffff03u;
    st->r[2] = (load32(key + 6) >> 4) & 0x3ffc0ffu;
    st->r[3] = (load32(key + 9) >> 6) & 0x3f03fffu;
    st->r[4] = (load32(key + 12) >> 8) & 0x00fffffu;
    for (i = 0; i < 5; i++) st->h[i] = 0;
    for (i = 0; i < 4; i++) st->pad[i] = load32(key + 16 + 4 * i);
    st->leftover = 0;
}

static void poly1305_blocks(poly1305_state *st, const uint8_t *m, size_t bytes) {
    const uint32_t hibit = 1u << 24;
    const uint32_t r0 = st->r[0];
    const uint32_t r1 = st->r[1];
    const uint32_t r2 = st->r[2];
    const uint32_t r3 = st->r[3];
    const uint32_t r4 = st->r[4];
    const uint32_t s1 = r1 * 5;
    const uint32_t s2 = r2 * 5;
    const uint32_t s3 = r3 * 5;
    const uint32_t s4 = r4 * 5;
    uint32_t h0 = st->h[0];
    uint32_t h1 = st->h[1];
    uint32_t h2 = st->h[2];
    uint32_t h3 = st->h[3];
    uint32_t h4 = st->h[4];

    while (bytes >= 16) {
        uint64_t d0;
        uint64_t d1;
        uint64_t d2;
        uint64_t d3;
        uint64_t d4;
        uint32_t c;

        h0 += load32(m + 0) & 0x3ffffffu;
        h1 += (load32(m + 3) >> 2) & 0x3ffffffu;
        h2 += (load32(m + 6) >> 4) & 0x3ffffffu;
        h3 += (load32(m + 9) >> 6) & 0x3ffffffu;
        h4 += (load32(m + 12) >> 8) | hibit;

        d0 = (uint64_t)h0 * r0 + (uint64_t)h1 * s4 + (uint64_t)h2 * s3 +
             (uint64_t)h3 * s2 + (uint64_t)h4 * s1;
        d1 = (uint64_t)h0 * r1 + (uint64_t)h1 * r0 + (uint64_t)h2 * s4 +
             (uint64_t)h3 * s3 + (uint64_t)h4 * s2;
        d2 = (uint64_t)h0 * r2 + (uint64_t)h1 * r1 + (uint64_t)h2 * r0 +
             (uint64_t)h3 * s4 + (uint64_t)h4 * s3;
        d3 = (uint64_t)h0 * r3 + (uint64_t)h1 * r2 + (uint64_t)h2 * r1 +
             (uint64_t)h3 * r0 + (uint64_t)h4 * s4;
        d4 = (uint64_t)h0 * r4 + (uint64_t)h1 * r3 + (uint64_t)h2 * r2 +
             (uint64_t)h3 * r1 + (uint64_t)h4 * r0;

        c = (uint32_t)(d0 >> 26);
        h0 = (uint32_t)d0 & 0x3ffffffu;
        d1 += c;
        c = (uint32_t)(d1 >> 26);
        h1 = (uint32_t)d1 & 0x3ffffffu;
        d2 += c;
        c = (uint32_t)(d2 >> 26);
        h2 = (uint32_t)d2 & 0x3ffffffu;
        d3 += c;
        c = (uint32_t)(d3 >> 26);
        h3 = (uint32_t)d3 & 0x3ffffffu;
        d4 += c;
        c = (uint32_t)(d4 >> 26);
        h4 = (uint32_t)d4 & 0x3ffffffu;
        h0 += c * 5;
        c = h0 >> 26;
        h0 &= 0x3ffffffu;
        h1 += c;

        m += 16;
        bytes -= 16;
    }

    st->h[0] = h0;
    st->h[1] = h1;
    st->h[2] = h2;
    st->h[3] = h3;
    st->h[4] = h4;
}

static void poly1305_update(poly1305_state *st, const uint8_t *m, size_t bytes) {
    if (st->leftover) {
        size_t want = 16 - st->leftover;
        if (want > bytes) want = bytes;
        memcpy(st->buffer + st->leftover, m, want);
        bytes -= want;
        m += want;
        st->leftover += want;
        if (st->leftover < 16) return;
        poly1305_blocks(st, st->buffer, 16);
        st->leftover = 0;
    }
    if (bytes >= 16) {
        size_t want = bytes & ~(size_t)15;
        poly1305_blocks(st, m, want);
        m += want;
        bytes -= want;
    }
    if (bytes) {
        memcpy(st->buffer, m, bytes);
        st->leftover = bytes;
    }
}

static void poly1305_finish(poly1305_state *st, uint8_t mac[16]) {
    uint32_t h0 = st->h[0];
    uint32_t h1 = st->h[1];
    uint32_t h2 = st->h[2];
    uint32_t h3 = st->h[3];
    uint32_t h4 = st->h[4];
    uint32_t g0;
    uint32_t g1;
    uint32_t g2;
    uint32_t g3;
    uint32_t g4;
    uint32_t c;
    uint32_t mask;
    uint64_t f;

    if (st->leftover) {
        size_t i = st->leftover;
        st->buffer[i++] = 1;
        for (; i < 16; i++) st->buffer[i] = 0;
        h0 += load32(&st->buffer[0]) & 0x3ffffffu;
        h1 += (load32(&st->buffer[3]) >> 2) & 0x3ffffffu;
        h2 += (load32(&st->buffer[6]) >> 4) & 0x3ffffffu;
        h3 += (load32(&st->buffer[9]) >> 6) & 0x3ffffffu;
        h4 += load32(&st->buffer[12]) >> 8;
    }

    c = h1 >> 26; h1 &= 0x3ffffffu; h2 += c;
    c = h2 >> 26; h2 &= 0x3ffffffu; h3 += c;
    c = h3 >> 26; h3 &= 0x3ffffffu; h4 += c;
    c = h4 >> 26; h4 &= 0x3ffffffu; h0 += c * 5;
    c = h0 >> 26; h0 &= 0x3ffffffu; h1 += c;

    g0 = h0 + 5; c = g0 >> 26; g0 &= 0x3ffffffu;
    g1 = h1 + c; c = g1 >> 26; g1 &= 0x3ffffffu;
    g2 = h2 + c; c = g2 >> 26; g2 &= 0x3ffffffu;
    g3 = h3 + c; c = g3 >> 26; g3 &= 0x3ffffffu;
    g4 = h4 + c - (1u << 26);

    mask = (g4 >> 31) - 1u;
    g0 &= mask;
    g1 &= mask;
    g2 &= mask;
    g3 &= mask;
    g4 &= mask;
    mask = ~mask;
    h0 = (h0 & mask) | g0;
    h1 = (h1 & mask) | g1;
    h2 = (h2 & mask) | g2;
    h3 = (h3 & mask) | g3;
    h4 = (h4 & mask) | g4;

    h0 = (h0 | (h1 << 26));
    h1 = ((h1 >> 6) | (h2 << 20));
    h2 = ((h2 >> 12) | (h3 << 14));
    h3 = ((h3 >> 18) | (h4 << 8));

    f = (uint64_t)h0 + st->pad[0]; h0 = (uint32_t)f;
    f = (uint64_t)h1 + st->pad[1] + (f >> 32); h1 = (uint32_t)f;
    f = (uint64_t)h2 + st->pad[2] + (f >> 32); h2 = (uint32_t)f;
    f = (uint64_t)h3 + st->pad[3] + (f >> 32); h3 = (uint32_t)f;

    store32(mac + 0, h0);
    store32(mac + 4, h1);
    store32(mac + 8, h2);
    store32(mac + 12, h3);
}

static void poly1305_pad16(poly1305_state *st, size_t length) {
    static const uint8_t zeros[16] = {0};
    if (length % 16) poly1305_update(st, zeros, 16 - (length % 16));
}

static void poly1305_mac(
    poly1305_state *st,
    const uint8_t *aad,
    size_t aad_len,
    const uint8_t *ciphertext,
    size_t ciphertext_len,
    uint8_t mac[16]) {
    uint8_t lengths[16];

    poly1305_update(st, aad, aad_len);
    poly1305_pad16(st, aad_len);
    poly1305_update(st, ciphertext, ciphertext_len);
    poly1305_pad16(st, ciphertext_len);
    store64(lengths, (uint64_t)aad_len);
    store64(lengths + 8, (uint64_t)ciphertext_len);
    poly1305_update(st, lengths, 16);
    poly1305_finish(st, mac);
}

static void poly1305_key(uint8_t key[32], const uint8_t chacha_key[32], const uint8_t nonce[12]) {
    uint32_t key_words[8];
    uint32_t nonce_words[3];
    uint8_t block[64];
    int i;

    for (i = 0; i < 8; i++) key_words[i] = load32(chacha_key + 4 * i);
    for (i = 0; i < 3; i++) nonce_words[i] = load32(nonce + 4 * i);
    chacha20_block(key_words, 0, nonce_words, block);
    memcpy(key, block, 32);
}

int chacha20poly1305_open(
    const uint8_t *key,
    const uint8_t *nonce,
    const uint8_t *aad,
    size_t aad_len,
    const uint8_t *in,
    size_t in_len,
    uint8_t *out) {
    uint8_t poly_key[32];
    poly1305_state st;
    uint8_t mac[16];
    uint8_t diff = 0;
    size_t ciphertext_len;
    int i;

    if (in_len < 16) return -1;
    ciphertext_len = in_len - 16;

    poly1305_key(poly_key, key, nonce);
    poly1305_init(&st, poly_key);
    poly1305_mac(&st, aad, aad_len, in, ciphertext_len, mac);

    for (i = 0; i < 16; i++) diff |= (uint8_t)(mac[i] ^ in[ciphertext_len + (size_t)i]);
    if (diff != 0) return -1;

    chacha20_xor(key, nonce, 1, in, ciphertext_len, out);
    return (int)ciphertext_len;
}

int chacha20poly1305_seal(
    const uint8_t *key,
    const uint8_t *nonce,
    const uint8_t *aad,
    size_t aad_len,
    const uint8_t *plaintext,
    size_t plaintext_len,
    uint8_t *out) {
    uint8_t poly_key[32];
    poly1305_state st;
    uint8_t mac[16];

    chacha20_xor(key, nonce, 1, plaintext, plaintext_len, out);
    poly1305_key(poly_key, key, nonce);
    poly1305_init(&st, poly_key);
    poly1305_mac(&st, aad, aad_len, out, plaintext_len, mac);
    memcpy(out + plaintext_len, mac, 16);
    return (int)(plaintext_len + 16);
}
