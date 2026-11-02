package com.edl.flasher.protocol

import android.content.Context
import com.edl.flasher.auth.TamperGuard
import java.io.InputStream

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

    // The obfuscated KEY_MATERIAL/KEY_MASK and the SHA-256 derivation steps that used to
    // live here now live ONLY in libyas_core.so (see NativeCore.deriveBoundKeyNative /
    // yas_native.c). AES-256-CTR itself also runs natively (NativeCore.aesCtrXcryptNative)
    // instead of through javax.crypto, so no decrypted plaintext path nor key material
    // touches the JVM heap via a standard, easily-hooked java.security/javax.crypto call
    // that a Frida script can intercept by class/method name alone.

    // The committed app/debug.keystore's own SHA-256 fingerprint (not secret -- the
    // keystore itself is committed in plaintext). Used only as the debug-build salt, so
    // local/CI debug builds keep working without a release keystore configured. Recompute
    // with: keytool -list -v -keystore app/debug.keystore -storepass android
    private const val DEBUG_CERT_SHA256 =
        "7cdc85e66fe80c5f7685ef2649940cc719908befcac713d79dcd1dbecd8e1b4"

    private const val IV_LEN = 16

    // Chunk size for native AES-CTR calls while streaming a large asset (e.g.
    // sec_efs.bin, ~20MB) -- bounds peak extra memory from the JNI byte[] copies
    // without making an excessive number of native calls. Must be a multiple of
    // 16 (AES block size) so chunk boundaries always land on a block boundary
    // and the counter advances by a whole number of blocks between calls.
    internal const val CHUNK_SIZE = 256 * 1024 // 256 KiB, 16384 AES blocks -- must stay a multiple of 16

    /** Plaintext size of an encrypted asset, given the on-disk (encrypted) size. */
    fun plaintextSize(encryptedSize: Long): Long =
        if (encryptedSize < IV_LEN) -1L else encryptedSize - IV_LEN

    /**
     * Wraps a raw (encrypted) asset InputStream with a decrypting stream backed by
     * [NativeCore.aesCtrXcryptNative]. Reads and consumes the leading 16-byte IV itself,
     * then streams the rest in [CHUNK_SIZE] blocks so callers never hold the whole file
     * in memory. `context` is required to read this APK's real signing certificate (see
     * class doc).
     *
     * `serverKey` MUST come from a fresh, server-granted operation (see
     * SensitiveOperationGate.authorize / OperationKeyClient.deriveFirmwareKey). This is
     * deliberately the ONLY overload: there is no "decrypt with just the cert hash"
     * path left, so every call site is forced to obtain and pass a live server grant --
     * skipping the gate is a compile error, not just a UI choice someone could patch
     * around.
     */
    fun decryptingStream(context: Context, rawAssetStream: InputStream, serverKey: ByteArray): InputStream {
        val iv = ByteArray(IV_LEN)
        var read = 0
        while (read < IV_LEN) {
            val n = rawAssetStream.read(iv, read, IV_LEN - read)
            if (n < 0) throw java.io.IOException("Encrypted firmware asset is shorter than the IV header")
            read += n
        }
        val certHash = TamperGuard.currentCertSha256(context) ?: DEBUG_CERT_SHA256
        val keyBytes = com.edl.flasher.auth.NativeCore.deriveBoundKeyNative(certHash, serverKey)
            ?: throw java.io.IOException("native key derivation unavailable")
        return NativeCtrDecryptingStream(rawAssetStream, keyBytes, iv)
    }
}

/**
 * InputStream wrapper that pulls ciphertext from [source] in [FirmwareCipher]-sized
 * chunks and runs each through [com.edl.flasher.auth.NativeCore.aesCtrXcryptNative],
 * advancing the CTR counter between chunks via [com.edl.flasher.auth.CtrCounter] so the
 * output is byte-identical to decrypting the whole file in one call.
 */
private class NativeCtrDecryptingStream(
    private val source: InputStream,
    private val key: ByteArray,
    private val iv: ByteArray
) : InputStream() {
    private var plainBuffer: ByteArray = ByteArray(0)
    private var plainPos = 0
    private val readBuf = ByteArray(FirmwareCipher.CHUNK_SIZE)
    private var closed = false

    private fun fillIfNeeded(): Boolean {
        if (plainPos < plainBuffer.size) return true
        if (closed) return false

        // CRITICAL: InputStream.read(buf) is NOT guaranteed to fill buf even when more
        // data remains -- it may legally return any n in 1..buf.size. If an
        // intermediate (non-final) chunk were short, the next native call would start a
        // fresh 16-byte-aligned keystream block instead of continuing mid-block, silently
        // producing wrong plaintext for the rest of the file with no error anywhere. So:
        // loop until readBuf is completely full OR the source is actually exhausted.
        // Only the LAST chunk of a stream may be shorter than readBuf.size -- see
        // CtrCounter doc and the chunk-alignment note on CHUNK_SIZE above.
        var totalRead = 0
        while (totalRead < readBuf.size) {
            val n = source.read(readBuf, totalRead, readBuf.size - totalRead)
            if (n < 0) break
            totalRead += n
        }
        if (totalRead == 0) { closed = true; return false }
        if (totalRead < readBuf.size) closed = true // genuine EOF -- this is the final chunk

        val cipherChunk = if (totalRead == readBuf.size) readBuf else readBuf.copyOf(totalRead)
        val blocksInChunk = (totalRead + 15) / 16
        plainBuffer = com.edl.flasher.auth.NativeCore.aesCtrXcryptNative(key, iv, cipherChunk)
            ?: throw java.io.IOException("native AES-CTR decrypt failed")
        com.edl.flasher.auth.CtrCounter.advance(iv, blocksInChunk.toLong())
        plainPos = 0
        return plainBuffer.isNotEmpty()
    }

    override fun read(): Int {
        if (!fillIfNeeded()) return -1
        return plainBuffer[plainPos++].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (!fillIfNeeded()) return -1
        val n = minOf(len, plainBuffer.size - plainPos)
        System.arraycopy(plainBuffer, plainPos, b, off, n)
        plainPos += n
        return n
    }

    override fun close() {
        java.util.Arrays.fill(key, 0)
        source.close()
    }
}
