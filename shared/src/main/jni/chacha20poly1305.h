#ifndef DIPLAY_CHACHA20POLY1305_H
#define DIPLAY_CHACHA20POLY1305_H

#include <stddef.h>
#include <stdint.h>

/* RFC 8439 AEAD_CHACHA20_POLY1305: 32-byte key, 12-byte nonce, 16-byte tag. */

/* 解密 in_len 字节（含 16 字节 tag）到 out，返回明文长度；认证失败返回 -1。 */
int chacha20poly1305_open(
    const uint8_t *key,
    const uint8_t *nonce,
    const uint8_t *aad,
    size_t aad_len,
    const uint8_t *in,
    size_t in_len,
    uint8_t *out);

/* 加密 plaintext_len 字节到 out（含 16 字节 tag），返回总长度。 */
int chacha20poly1305_seal(
    const uint8_t *key,
    const uint8_t *nonce,
    const uint8_t *aad,
    size_t aad_len,
    const uint8_t *plaintext,
    size_t plaintext_len,
    uint8_t *out);

#endif
