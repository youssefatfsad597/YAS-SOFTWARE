package com.edl.flasher.auth

/**
 * Thin JNI bridge to libyas_core.so (app/src/main/cpp). All of the former
 * OperationKeyClient/FirmwareCipher key material, HKDF derivation and AES-256
 * implementation now live ONLY in native code -- this object holds no
 * secrets itself, just the `external fun` declarations.
 *
 * A decompiled APK shows these as native method stubs with no body; the
 * actual logic requires disassembling libyas_core.so instead of reading
 * Kotlin bytecode. See yas_native.c's header comment for the honest limits
 * of what this buys you.
 */
internal object NativeCore {
    init {
        System.loadLibrary("yas_core")
    }

    /** Was OperationKeyClient.deriveFirmwareKey's HMAC step. Returns null on malformed input. */
    external fun deriveFirmwareKeyNative(keyMaterialHex: String): ByteArray?

    /** Was FirmwareCipher.deriveBoundKey. certSha256Hex MUST already be lowercase hex. */
    external fun deriveBoundKeyNative(certSha256Hex: String, serverKey: ByteArray): ByteArray?

    /**
     * One AES-256-CTR block-aligned chunk. CTR is symmetric (same call
     * encrypts and decrypts). `iv` is the current 16-byte counter state;
     * see [CtrCounter] for safely advancing it between chunks of a stream
     * without ever reusing a counter value under the same key.
     */
    external fun aesCtrXcryptNative(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray?
}

/**
 * Advances a 16-byte big-endian CTR counter block by a whole number of
 * 16-byte AES blocks -- mirrors javax.crypto's CTR increment semantics
 * exactly (same rule aesCtrXcryptNative's native half uses internally per
 * 16-byte block), so chunking a large file into multiple
 * aesCtrXcryptNative() calls produces byte-identical output to one call
 * over the whole buffer.
 */
internal object CtrCounter {
    fun advance(iv: ByteArray, blocks: Long) {
        var remaining = blocks
        while (remaining > 0) {
            incrementOnce(iv)
            remaining--
        }
    }

    private fun incrementOnce(iv: ByteArray) {
        for (i in iv.indices.reversed()) {
            if (iv[i] == 0xFF.toByte()) {
                iv[i] = 0
            } else {
                iv[i] = (iv[i] + 1).toByte()
                return
            }
        }
    }
}
