package com.edl.flasher.protocol

import android.content.Context
import java.io.InputStream

/**
 * X216B service files (Firehose loader + the 3 partition images) bundled directly
 * inside the APK as assets, so the "X216B Wipe & Write" button works with a single
 * tap and never requires the user to manually pick any file.
 *
 * These files are copied into app/src/main/assets/x216b/ at build time by the
 * "Copy Embedded X216B Firmware Assets" step in this workflow, from firmware/x216b/
 * in the repository. They must be stored UNCOMPRESSED in the APK (see the
 * androidResources { noCompress += listOf("elf","bin") } block in app/build.gradle.kts)
 * so that AssetFileDescriptor can report an exact length and large files (like
 * sec_efs.bin) can be streamed without loading the whole file into RAM.
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

    /** Exact uncompressed size via AssetFileDescriptor. Returns -1 if unavailable. */
    fun sizeOf(context: Context, name: String): Long =
        runCatching { context.assets.openFd(path(name)).use { it.length } }.getOrDefault(-1L)

    fun openStream(context: Context, name: String): InputStream =
        context.assets.open(path(name))

    fun readAllBytes(context: Context, name: String): ByteArray =
        context.assets.open(path(name)).use { it.readBytes() }

    fun describeStatus(context: Context): String {
        val missing = ALL_FILES.filterNot { exists(context, it) }
        if (missing.isNotEmpty()) {
            return "MISSING: ${missing.joinToString(", ")} -- commit these files under firmware/x216b/ in the repo and rebuild."
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
