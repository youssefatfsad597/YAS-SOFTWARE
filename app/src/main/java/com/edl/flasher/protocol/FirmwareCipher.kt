package com.edl.flasher.protocol

import android.content.Context
import com.edl.flasher.auth.TamperGuard
import java.io.InputStream
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Decrypts the embedded firmware assets (X216B.elf, *.bin, T509 fdl images) that are
 * AES-256-CTR encrypted at build time (see `.github/workflows/main.yml` /
 * `encrypt_firmware_assets.sh`) before being packed into `app/src/main/assets/`.
 *
 * File format on disk (what ships inside the APK): [16-byte IV][ciphertext, same length as
 * the original plaintext -- CTR mode adds no padding]. CTR is used (not GCM) specifically so
 * large files like sec_efs.bin (~20MB) can be streamed and decrypted on the fly instead of
 * being fully loaded into RAM.
 *
 * KEY BINDING: the AES key is not just the obfuscated KEY_MATERIAL below -- it is
 * SHA-256(KEY_MATERIAL || signingCertSha256). `signingCertSha256` is read from the
 * CURRENTLY RUNNING APK's real certificate via PackageManager at decrypt time (see
 * TamperGuard.currentCertSha256), and at build time the same hash is mixed in by
 * encrypt_firmware_assets.sh (passed the release keystore's real fingerprint, or the
 * committed debug keystore's fingerprint for debug builds). The two only produce the
 * same key when the APK now running is actually signed with the same certificate the
 * assets were encrypted for.
 *
 * This is deliberate: a boolean "is this tampered?" check (see TamperGuard/AuthGate) can
 * be patched to always return true with a one-byte bytecode edit, and the UI message it
 * gates is cosmetic once that happens. Binding the firmware key itself to the real
 * signing certificate means patching that boolean no longer helps -- the firmware assets
 * still decrypt to garbage under a different certificate, so the app's actual function
 * (flashing a device) stays broken for a re-signed/repackaged build regardless of which
 * UI checks were patched out.
 *
 * IMPORTANT / HONEST LIMITATION: this still raises the bar rather than closing it
 * completely -- a sufficiently determined reverse engineer who understands this scheme
 * could hardcode the correct (publicly computable, since certificates aren't secret)
 * original certificate hash in place of the runtime PackageManager lookup. It does,
 * however, turn "flip one boolean" into "find and correctly patch the key-derivation
 * call site with the right hash", which is a meaningfully higher bar. For the strongest
 * protection, pair this with Google Play Integrity API server-side verification instead
 * of relying on any purely client-side check.
 */
internal object FirmwareCipher {

    // --- Obfuscated AES-256 key material: combined with the signing cert hash below,
    // never used directly. Keep in sync with encrypt_firmware_assets.sh / the CI workflow.
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

    // The committed app/debug.keystore's own SHA-256 fingerprint (not secret -- the
    // keystore itself is committed in plaintext). Used only as the debug-build salt, so
    // local/CI debug builds keep working without a release keystore configured. Recompute
    // with: keytool -list -v -keystore app/debug.keystore -storepass android
    private const val DEBUG_CERT_SHA256 =
        "7cdc85e66fe80c5f7685ef2649940cc719908befcac713d79dcd1dbecd8e1b4"

    private const val IV_LEN = 16

    private fun reconstructKeyMaterial(): ByteArray {
        val mask = ByteArray(KEY_MASK.size) { i -> (KEY_MASK[i].toInt() xor PAD).toByte() }
        return ByteArray(KEY_MATERIAL.size) { i -> (KEY_MATERIAL[i].toInt() xor mask[i].toInt()).toByte() }
    }

    /** Final AES key = SHA-256(obfuscated material || lowercase-hex cert SHA-256 bytes). */
    private fun deriveKey(certSha256Hex: String): ByteArray {
        val material = reconstructKeyMaterial()
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(material)
        java.util.Arrays.fill(material, 0)
        digest.update(certSha256Hex.lowercase().toByteArray(Charsets.US_ASCII))
        return digest.digest()
    }

    /** Plaintext size of an encrypted asset, given the on-disk (encrypted) size. */
    fun plaintextSize(encryptedSize: Long): Long =
        if (encryptedSize < IV_LEN) -1L else encryptedSize - IV_LEN

    /**
     * Wraps a raw (encrypted) asset InputStream with a decrypting CipherInputStream.
     * Reads and consumes the leading 16-byte IV itself, then streams the rest through
     * AES/CTR so callers never hold the whole file in memory. `context` is required to
     * read this APK's real signing certificate (see class doc) -- it is NOT optional and
     * must not be defaulted to a fixed value, or the key-binding above does nothing.
     */
    fun decryptingStream(context: Context, rawAssetStream: InputStream): InputStream {
        val iv = ByteArray(IV_LEN)
        var read = 0
        while (read < IV_LEN) {
            val n = rawAssetStream.read(iv, read, IV_LEN - read)
            if (n < 0) throw java.io.IOException("Encrypted firmware asset is shorter than the IV header")
            read += n
        }
        val certHash = TamperGuard.currentCertSha256(context) ?: DEBUG_CERT_SHA256
        val keyBytes = deriveKey(certHash)
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(iv))
        java.util.Arrays.fill(keyBytes, 0) // best-effort: don't keep the raw key around longer than needed
        return CipherInputStream(rawAssetStream, cipher)
    }
}
