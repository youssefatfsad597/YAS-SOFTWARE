#ifndef YAS_AES256CTR_H
#define YAS_AES256CTR_H

#include <stdint.h>
#include <stddef.h>

#define AES256_KEYLEN 32
#define AES_BLOCKLEN 16
#define AES256_KEYEXPSIZE 240 /* (Nr+1)*Nb*4 = 15*4*4 */

typedef struct {
    uint8_t RoundKey[AES256_KEYEXPSIZE];
    uint8_t Iv[AES_BLOCKLEN];
} AES256_ctx;

void AES256_init_ctx_iv(AES256_ctx *ctx, const uint8_t key[AES256_KEYLEN], const uint8_t iv[AES_BLOCKLEN]);

/* XORs buf (length bytes) in place with the AES-CTR keystream. Same call
 * encrypts and decrypts (CTR is symmetric). Matches FirmwareCipher.kt:
 * "AES/CTR/NoPadding" with a 16-byte big-endian counter block = IV. */
void AES256_CTR_xcrypt_buffer(AES256_ctx *ctx, uint8_t *buf, size_t length);

#endif
