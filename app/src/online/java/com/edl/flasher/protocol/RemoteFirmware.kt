package com.edl.flasher.protocol

import android.content.Context
import com.edl.flasher.auth.DeviceIdentity
import com.edl.flasher.auth.LicenseService
import com.edl.flasher.auth.NativeCore
import com.edl.flasher.auth.RemoteFirmwareClient
import com.edl.flasher.auth.TamperGuard
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/**
 * Server-hosted counterpart to EmbeddedFirmware.kt. Files live in the private
 * 'yas-firmware' Storage bucket, encrypted ONCE at upload time (tools/upload_firmware.py)
 * with a key this same app re-derives natively -- see NativeCore.kt and
 * app/src/main/cpp/yas_native.c. Unlike embedded assets, every download re-checks the
 * full license/device/ban/kill-switch state server-side (authorize_firmware_download in
 * supabase/03_firmware_storage.sql), so a suspended or expired account gets no key
 * regardless of what a patched local APK does.
 *
 * Usage:
 *   val items = RemoteFirmware.catalog(context, licenseService)        // for a picker UI
 *   RemoteFirmware.openStream(context, licenseService, "x216b/sec_efs.bin").use { stream ->
 *       // read plaintext bytes; throws if denied, or IOException if the integrity
 *       // check at the end of the stream fails (wrong key / corrupted / tampered)
 *   }
 */
object RemoteFirmware {

    class DownloadDenied(val status: String) : Exception(status)

    suspend fun catalog(context: Context, licenseService: LicenseService): List<RemoteFirmwareClient.CatalogItem> =
        RemoteFirmwareClient(licenseService).catalog()

    /** Opens a decrypting stream for one catalog slug (e.g. "x216b/sec_efs.bin"). Throws
     *  [DownloadDenied] if the server refuses (see 03_firmware_storage.sql's status
     *  strings: not_authenticated/pending/suspended/expired/force_logout/device_banned/
     *  device_not_registered/file_not_found), or IOException for a network/integrity
     *  failure. The returned stream's sha256 is verified against the server-recorded
     *  plaintext hash as it is consumed -- a mismatch throws IOException on the read
     *  that crosses the end of the stream (or on close() if the stream is fully drained
     *  there), so NEVER treat a partially-read stream as verified. */
    suspend fun openStream(context: Context, licenseService: LicenseService, slug: String): InputStream {
        val deviceId = DeviceIdentity.deviceId(context, licenseService)
        val androidId = DeviceIdentity.androidId(context, licenseService)
        val widevineId = DeviceIdentity.widevineId(context, licenseService)

        // Sent WITH the request, exactly like SensitiveOperationGate: when non-null, the
        // server bans this device immediately and denies the download in the same call
        // (see yas_auto_ban_device in 02_operation_auth.sql).
        val tamperReason = TamperGuard.currentIndicators(context)

        val grant = RemoteFirmwareClient(licenseService).requestDownload(slug, deviceId, androidId, tamperReason, widevineId)
        if (!grant.granted) throw DownloadDenied(grant.status)

        val serverKey = NativeCore.deriveFirmwareKeyNative(grant.keyMaterialHex)
            ?: throw IOException("native key derivation unavailable")

        val rawStream = licenseService.downloadAuthenticated(
            "/storage/v1/object/authenticated/yas-firmware/" + grant.storagePath
        )
        val decrypted = FirmwareCipher.decryptingStream(context, rawStream, serverKey)
        return Sha256VerifyingInputStream(decrypted, grant.sha256Plaintext)
    }

    suspend fun readAllBytes(context: Context, licenseService: LicenseService, slug: String): ByteArray =
        openStream(context, licenseService, slug).use { it.readBytes() }
}

/** Wraps a decrypted stream, hashing bytes as they're read and comparing against the
 *  server-recorded plaintext SHA-256 once the underlying stream reports EOF. Catches a
 *  wrong/stale key_material_hex, a corrupted upload, or a tampered-with proxy in transit
 *  -- any of which would otherwise surface only as "the flashed partition doesn't boot",
 *  far from the actual cause. */
private class Sha256VerifyingInputStream(
    private val source: InputStream,
    private val expectedHex: String
) : InputStream() {
    private val digest = MessageDigest.getInstance("SHA-256")
    private var verified = false

    private fun verifyIfDone(n: Int) {
        if (n < 0 && !verified) {
            verified = true
            if (expectedHex.isNotBlank()) {
                val actualHex = digest.digest().joinToString("") { "%02x".format(it) }
                if (!actualHex.equals(expectedHex, ignoreCase = true)) {
                    throw IOException("Firmware integrity check failed (expected $expectedHex, got $actualHex) -- do not flash this data.")
                }
            }
        }
    }

    override fun read(): Int {
        val b = source.read()
        if (b >= 0) digest.update(b.toByte())
        verifyIfDone(b)
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = source.read(b, off, len)
        if (n > 0) digest.update(b, off, n)
        verifyIfDone(n)
        return n
    }

    override fun close() = source.close()
}
