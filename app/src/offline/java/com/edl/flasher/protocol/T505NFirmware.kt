package com.edl.flasher.protocol

import android.content.Context
import java.io.InputStream

/**
 * OFFLINE EDITION: T505N service files bundled UNENCRYPTED as assets
 * (app/src/offline/assets/t505n/), mirroring [EmbeddedFirmware]'s offline
 * version. `serverKey` is accepted only to match the online signature and
 * is ignored.
 */
object T505NFirmware {
    private const val ASSET_DIR = "t505n"

    const val LOADER = "T505N.elf"
    const val FRP = "frp.bin"
    const val PERSISTENT = "persistent.bin"
    const val MISC = "misc.bin"

    val ALL_FILES = listOf(LOADER, FRP, PERSISTENT, MISC)

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
            return "MISSING: ${missing.joinToString(", ")} -- commit these files under app/src/offline/assets/t505n/ and rebuild."
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
