package com.edl.flasher.protocol

import android.content.Context
import java.io.InputStream

/**
 * OFFLINE EDITION: X216B service files are bundled UNENCRYPTED, directly as
 * assets (app/src/offline/assets/x216b/), and read straight off disk -- no
 * server-derived key, no FirmwareCipher, no network of any kind.
 *
 * `serverKey` is accepted only to keep the EXACT same call signature as the
 * online build's EmbeddedFirmware, so EdlViewModel.kt (shared between both
 * flavors) needs no changes; the parameter is simply ignored here.
 */
object EmbeddedFirmware {
    private const val ASSET_DIR = "x216b"

    const val LOADER = "X216B.elf"
    const val FRP = "frp.bin"
    const val SEC_EFS = "sec_efs.bin"
    const val PERSISTENT = "persistent.bin"
    const val MISC = "misc.bin"

    val ALL_FILES = listOf(LOADER, FRP, SEC_EFS, PERSISTENT, MISC)

    private fun path(name: String) = "$ASSET_DIR/$name"

    fun exists(context: Context, name: String): Boolean =
        runCatching { context.assets.open(path(name)).close() }.isSuccess

    fun allPresent(context: Context): Boolean =
        ALL_FILES.all { exists(context, it) }

    fun sizeOf(context: Context, name: String): Long =
        runCatching { context.assets.openFd(path(name)).use { it.length } }.getOrDefault(-1L)

    fun openStream(context: Context, name: String, serverKey: ByteArray): InputStream =
        context.assets.open(path(name))

    fun readAllBytes(context: Context, name: String, serverKey: ByteArray): ByteArray =
        openStream(context, name, serverKey).use { it.readBytes() }

    fun describeStatus(context: Context): String {
        val missing = ALL_FILES.filterNot { exists(context, it) }
        if (missing.isNotEmpty()) {
            return "MISSING: ${missing.joinToString(", ")} -- commit these files under app/src/offline/assets/x216b/ and rebuild."
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
