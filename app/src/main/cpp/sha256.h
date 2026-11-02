#ifndef YAS_SHA256_H
#define YAS_SHA256_H

#include <stddef.h>
#include <stdint.h>

#define SHA256_BLOCK_SIZE 32

typedef struct {
    uint8_t data[64];
    uint32_t datalen;
    unsigned long long bitlen;
    uint32_t state[8];
} SHA256_CTX;

void sha256_init(SHA256_CTX *ctx);
void sha256_update(SHA256_CTX *ctx, const uint8_t data[], size_t len);
void sha256_final(SHA256_CTX *ctx, uint8_t hash[SHA256_BLOCK_SIZE]);

/* One-shot convenience wrapper. */
void sha256_hash(const uint8_t *data, size_t len, uint8_t out[SHA256_BLOCK_SIZE]);

/* HMAC-SHA256, RFC 2104. keylen may exceed the block size (it is hashed down first). */
void hmac_sha256(const uint8_t *key, size_t keylen,
                  const uint8_t *data, size_t datalen,
                  uint8_t out[SHA256_BLOCK_SIZE]);

#endif
