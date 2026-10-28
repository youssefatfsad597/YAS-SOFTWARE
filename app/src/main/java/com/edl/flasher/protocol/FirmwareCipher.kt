package com.edl.flasher.protocol

import java.io.InputStream
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Decrypts the embedded firmware assets (X216B.elf, *.bin, T509/T509N fdl images) that are
 * AES-256-CTR encrypted at build time (see `.github/workflows/main.yml` /
 * `encrypt_firmware_assets.sh`) before being packed into `app/src/main/assets/`.
 *
 * File format on disk (what ships inside the APK): [16-byte IV][ciphertext, same length as
 * the original plaintext -- CTR mode adds no padding]. CTR is used (not GCM) specifically so
 * large files like sec_efs.bin (~20MB) can be streamed and decrypted on the fly instead of
 * being fully loaded into RAM.
 *
 * IMPORTANT / HONEST LIMITATION: this key ships inside the app, obfuscated (split across two
 * byte arrays combined with a mask + pad) so it is not a plain grep-able string in the APK's
 * resources or a `strings` dump. This raises the bar against someone casually unzipping the
 * APK and grabbing the raw firmware files -- it does NOT make extraction impossible for a
 * determined reverse engineer who decompiles and runs the app, since the device must be able
 * to decrypt the files itself to use them. Treat this as "not trivial to extract", not as an
 * unbreakable secret.
 */
internal object FirmwareCipher {

    // --- Obfuscated AES-256 key: KEY = KEY_MATERIAL[i] xor (KEY_MASK[i] xor PAD) ---
    // Keep these three values in sync with encrypt_firmware_assets.sh / the CI workflow.
    // Never log, print, or expose this reconstructed key.
    private val KEY_MATERIAL = byteArrayOf(
        -113, -99, -88, -50, -16, 14, -16, -43, -79, 62, 81, -12, -4, 10, -109, -37,
        37, -1, -98, 83, -67, 34, -52, -72, -68, -119, 64, -57, 63, -125, -58, -15
    )
    private val KEY_MASK = byteArrayOf(
        38, 41, 78, -118, 31, -86, -14, -103, 115, 66, 127, -128, -87, 0, -98, -76,
        94, 116, -22, -46, 56, -28, -30, -49, 124, -94, 21, -57, -64, 32, 68, -23
    )
    private const val PAD = 0x7E

    private const val IV_LEN = 16

    private fun reconstructKey(): ByteArray {
        val mask = ByteArray(KEY_MASK.size) { i -> (KEY_MASK[i].toInt() xor PAD).toByte() }
        return ByteArray(KEY_MATERIAL.size) { i -> (KEY_MATERIAL[i].toInt() xor mask[i].toInt()).toByte() }
    }

    /** Plaintext size of an encrypted asset, given the on-disk (encrypted) size. */
    fun plaintextSize(encryptedSize: Long): Long =
        if (encryptedSize < IV_LEN) -1L else encryptedSize - IV_LEN

    /**
     * Wraps a raw (encrypted) asset InputStream with a decrypting CipherInputStream.
     * Reads and consumes the leading 16-byte IV itself, then streams the rest through
     * AES/CTR so callers never hold the whole file in memory.
     */
    fun decryptingStream(rawAssetStream: InputStream): InputStream {
        val iv = ByteArray(IV_LEN)
        var read = 0
        while (read < IV_LEN) {
            val n = rawAssetStream.read(iv, read, IV_LEN - read)
            if (n < 0) throw java.io.IOException("Encrypted firmware asset is shorter than the IV header")
            read += n
        }
        val keyBytes = reconstructKey()
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(iv))
        java.util.Arrays.fill(keyBytes, 0) // best-effort: don't keep the raw key around longer than needed
        return CipherInputStream(rawAssetStream, cipher)
    }
}
