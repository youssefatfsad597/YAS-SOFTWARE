package com.edl.flasher.protocol

import android.content.Context
import java.io.InputStream

/**
 * T505N service files (Firehose loader + the 3 partition images) bundled directly
 * inside the APK as assets, mirroring [EmbeddedFirmware] for the X216B flow -- the
 * SM-T505N is a Qualcomm EDL (9008) / Firehose device just like the X216B, so it
 * reuses the exact same Firehose session/GPT state (see EdlViewModel), just with
 * its own embedded loader + partition images.
 *
 * Committed into the repo under firmware/t505n/ and copied into
 * app/src/main/assets/t505n/ as "<name>.enc" by the same AES-256-CTR encryption
 * step (tools/encrypt_firmware_assets.sh) used for X216B / T509. Decrypted
 * on the fly through FirmwareCipher so the raw files are never written to disk and
 * are not sitting plaintext inside the shipped APK.
 *
 * Unlike X216B, T505N has no sec_efs.bin (MDM) image: its two one-tap buttons only
 * ever touch misc / frp / persistent --
 *   - "Format + FRP"  -> misc + frp + persistent
 *   - "Erase FRP"     -> frp + persistent only
 */
object T505NFirmware {
    private const val ASSET_DIR = "t505n"
    private const val ENC_SUFFIX = ".enc"

    const val LOADER = "T505N.elf"
    const val FRP = "frp.bin"
    const val PERSISTENT = "persistent.bin"
    const val MISC = "misc.bin"

    val ALL_FILES = listOf(LOADER, FRP, PERSISTENT, MISC)

    private fun path(name: String) = "$ASSET_DIR/$name$ENC_SUFFIX"

    fun exists(context: Context, name: String): Boolean =
        runCatching { context.assets.open(path(name)).close() }.isSuccess

    fun allPresent(context: Context): Boolean =
        ALL_FILES.all { exists(context, it) }

    /** Exact plaintext (decrypted) size. Returns -1 if unavailable. */
    fun sizeOf(context: Context, name: String): Long =
        runCatching {
            FirmwareCipher.plaintextSize(context.assets.openFd(path(name)).use { it.length })
        }.getOrDefault(-1L)

    fun openStream(context: Context, name: String): InputStream =
        FirmwareCipher.decryptingStream(context.assets.open(path(name)))

    fun readAllBytes(context: Context, name: String): ByteArray =
        openStream(context, name).use { it.readBytes() }

    fun describeStatus(context: Context): String {
        val missing = ALL_FILES.filterNot { exists(context, it) }
        if (missing.isNotEmpty()) {
            return "MISSING: ${missing.joinToString(", ")} -- commit these files under firmware/t505n/ in the repo and rebuild."
        }
        val sizes = ALL_FILES.joinToString(", ") { "$it=${formatBytes(sizeOf(context, it))}" }
        return "Ready ($sizes)"
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 0) return "?"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        return if (mb >= 1.0) String.format("%.1fMB", mb) else String.format("%.0fKB", kb)
    }
}
