package com.edl.flasher.protocol

import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.min

/**
 * Reads a standard POSIX/GNU .tar archive (such as Samsung's userdata.tar, or
 * any BL/AP/CP/CSC .tar.md5 firmware package) sequentially, matches every
 * image file found inside against the device's own PIT table by exact file
 * name (the same way real Odin/Heimdall/Brokkr do), and flashes each match
 * via [OdinProtocol]. Entries that don't match any PIT entry on this device
 * are skipped and logged -- nothing is guessed or forced onto an unrelated
 * partition.
 */
object OdinTarFlasher {

    private val IMAGE_EXTENSIONS = listOf(".img.lz4", ".img.ext4", ".img", ".bin.lz4", ".bin")

    /** If a userdata.img inside the package is smaller than this fraction of the device's
     *  OWN userdata partition (per its PIT entry), flashing it would leave the rest of the
     *  partition permanently unused -- the exact "32GB device shows 8GB after flashing"
     *  symptom. Below this ratio we treat the image as built for a different storage
     *  variant and skip it instead, so the device creates+formats a fresh filesystem at
     *  the FULL partition size on first boot (the same thing a factory reset from the
     *  device's own Settings does). */
    private const val MIN_USERDATA_SIZE_RATIO = 0.9

    /** Null = nothing wrong, flash it normally. Non-null = reason to skip it instead. */
    fun undersizedUserdataReason(label: String, imageSize: Long, part: OdinPitPartition): String? {
        if (!label.equals("userdata", ignoreCase = true)) return null
        val partBytes = part.partitionSizeBytes
        if (partBytes <= 0L) return null // PIT didn't report a size; nothing to compare against.
        if (imageSize >= (partBytes * MIN_USERDATA_SIZE_RATIO).toLong()) return null
        return "the image is $imageSize bytes but this device's userdata partition is " +
            "$partBytes bytes -- it looks like it was built for a different storage " +
            "capacity. Flashing it would leave most of the partition unusable."
    }

    /**
     * One component found inside a firmware package, annotated against this device's own PIT
     * table (when one is supplied) exactly the way real Odin resolves a destination before it
     * will show the entry as flashable: by exact (case-sensitive) file name match.
     *  - [pitPartitionId] / [pitPartitionName]: the matched PIT entry, or null if this file
     *    doesn't correspond to anything on this device -- real Odin silently ignores such files.
     *  - [pitPartitionSizeBytes]: the device's own declared size for that partition (0 if unknown).
     *  - [sizeMismatch]: true when the image is larger than the partition declares, or (for
     *    userdata) suspiciously smaller than it -- the same class of problem Odin's bootloader
     *    would reject with ack=-124 "image is too big for the partition".
     */
    data class PackageEntry(
        val name: String,
        val label: String,
        val size: Long,
        val pitPartitionId: Int? = null,
        val pitPartitionName: String? = null,
        val pitPartitionSizeBytes: Long = 0L,
        val sizeMismatch: Boolean = false
    )

    /**
     * Reads only the tar headers of [tarInput] (skipping data with seeks where possible) and returns
     * every regular file inside, so the UI can show the user what the package contains. When [pit]
     * is supplied, each entry is cross-matched against it the same way the flashing pass does, so
     * the file-selection UI can show, per file, exactly which PIT partition it will land on.
     * A bare image (not a tar) is handled by the caller.
     */
    @Throws(IOException::class)
    fun listEntries(tarInput: InputStream, pit: OdinPitTable? = null): List<PackageEntry> {
        val out = mutableListOf<PackageEntry>()
        while (true) {
            val header = readTarHeader(tarInput) ?: break
            if (header.typeFlag == '0' || header.typeFlag == '\u0000') {
                val base = header.name.substringAfterLast('/')
                if (base.isNotBlank()) {
                    val part = pit?.findByFileName(base)
                    val partBytes = part?.partitionSizeBytes ?: 0L
                    val mismatch = part != null && partBytes > 0L && (
                        header.size > partBytes ||
                            undersizedUserdataReason(displayLabel(base), header.size, part) != null
                        )
                    out.add(
                        PackageEntry(
                            name = header.name,
                            label = displayLabel(base),
                            size = header.size,
                            pitPartitionId = part?.id,
                            pitPartitionName = part?.name?.ifBlank { part.fileName },
                            pitPartitionSizeBytes = partBytes,
                            sizeMismatch = mismatch
                        )
                    )
                }
            }
            fastSkip(tarInput, header.size + (512 - (header.size % 512)) % 512)
        }
        return out
    }

    /** Result of checking a `.tar.md5` package's appended checksum line against its own content. */
    data class Md5CheckResult(
        /** False for a plain `.tar` with no trailer -- there is nothing to verify. */
        val hasTrailer: Boolean,
        val passed: Boolean,
        val expected: String? = null,
        val computed: String? = null
    )

    // No anchor at the start: the tar's own end-of-archive marker (two 512-byte zero blocks)
    // sits immediately before this line with no newline separator, so the match is found by
    // scanning for it, not by splitting on '\n'.
    private val MD5_TRAILER_LINE = Regex("""[0-9a-fA-F]{32}[ \t]{1,2}\S[^\r\n]*$""")

    /**
     * Re-implements the checksum pass real Odin always runs on a `.tar.md5` package before it
     * will flash it ("Checking MD5 finished Sucessfully.." / a hard-abort "Odin checksum fails."
     * on mismatch). Samsung builds these files as a plain .tar with one text line appended after
     * the tar data: the exact output of `md5sum package.tar` ("<32 hex chars>  package.tar\n").
     * MD5 is computed streaming, over the tar portion only -- the trailer line itself is excluded
     * by holding back a small tail buffer until we can see whether it's the trailer or still data.
     */
    @Throws(IOException::class)
    fun verifyPackageMd5(input: InputStream): Md5CheckResult {
        val digest = MessageDigest.getInstance("MD5")
        val holdBack = 512 // generous for "<32 hex>  <filename>\n" even with a long file name
        val tail = ByteArray(holdBack)
        var tailLen = 0
        val chunk = ByteArray(65536)
        while (true) {
            val r = input.read(chunk)
            if (r < 0) break
            var o = 0
            while (o < r) {
                val room = holdBack - tailLen
                if (room > 0) {
                    val take = min(room, r - o)
                    System.arraycopy(chunk, o, tail, tailLen, take)
                    tailLen += take
                    o += take
                } else {
                    digest.update(tail, 0, 1)
                    System.arraycopy(tail, 1, tail, 0, holdBack - 1)
                    tail[holdBack - 1] = chunk[o]
                    o++
                }
            }
        }
        // ISO-8859-1 maps every byte to exactly one char (no multi-byte decoding, no lossy
        // replacement), so a match's character offset is also its exact byte offset in [tail].
        val tailText = String(tail, 0, tailLen, Charsets.ISO_8859_1)
        val trimmed = tailText.trimEnd('\n', '\r')
        val match = MD5_TRAILER_LINE.find(trimmed)
        if (match == null) {
            // No recognizable trailer (a plain .tar): everything held back is tar data.
            digest.update(tail, 0, tailLen)
            return Md5CheckResult(hasTrailer = false, passed = true, computed = hex(digest.digest()))
        }
        val expected = match.value.substring(0, 32).lowercase(Locale.US)
        val trailerStartInTail = match.range.first
        if (trailerStartInTail > 0) digest.update(tail, 0, trailerStartInTail)
        val computed = hex(digest.digest())
        return Md5CheckResult(hasTrailer = true, passed = computed.equals(expected, ignoreCase = true), expected = expected, computed = computed)
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    /** "12.3 MB (12,912,640 bytes)" -- same two numbers Odin's own log lines show. */
    fun formatBytes(n: Long): String {
        val mb = n / (1024.0 * 1024.0)
        return "%.1f MB (%,d bytes)".format(Locale.US, mb, n)
    }

    private fun fastSkip(input: InputStream, count: Long) {
        var remaining = count
        val buf = ByteArray(8192)
        while (remaining > 0) {
            val s = input.skip(remaining)
            if (s > 0) { remaining -= s; continue }
            val r = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (r < 0) break
            remaining -= r
        }
    }

    /** Friendly label for logs/filters, e.g. "userdata.img" -> "userdata". Matching against the PIT uses the full name. */
    private fun displayLabel(baseName: String): String {
        for (ext in IMAGE_EXTENSIONS) {
            if (baseName.endsWith(ext, ignoreCase = true)) return baseName.substring(0, baseName.length - ext.length)
        }
        return baseName
    }

    /**
     * First pass: sums the sizes of entries that will actually be flashed (matches a PIT
     * partition on this device and passes [shouldFlash]), without touching any image bytes.
     * Real Odin must send the combined total size once, before flashing the first partition.
     */
    @Throws(IOException::class)
    fun sumFlashableSize(
        tarInput: InputStream,
        pit: OdinPitTable,
        shouldFlash: (String) -> Boolean = { true },
        allowUndersizedUserdata: Boolean = false
    ): Long {
        var total = 0L
        while (true) {
            val header = readTarHeader(tarInput) ?: break
            if (header.typeFlag != '0' && header.typeFlag != '\u0000') {
                skipEntry(tarInput, header.size)
                continue
            }
            val baseName = header.name.substringAfterLast('/')
            val label = displayLabel(baseName)
            val part = pit.findByFileName(baseName)
            if (part != null && header.size > 0L && shouldFlash(label) &&
                (allowUndersizedUserdata || undersizedUserdataReason(label, header.size, part) == null)
            ) {
                total += header.size
            }
            skipEntry(tarInput, header.size)
        }
        return total
    }

    @Throws(IOException::class)
    fun flashTarStream(
        tarInput: InputStream,
        pit: OdinPitTable,
        odin: OdinProtocol,
        logger: (String) -> Unit,
        onPartitionProgress: (String, Long, Long) -> Unit,
        shouldFlash: (String) -> Boolean = { true },
        allowUndersizedUserdata: Boolean = false
    ): List<String> {
        val flashed = mutableListOf<String>()
        while (true) {
            val header = readTarHeader(tarInput) ?: break
            if (header.typeFlag != '0' && header.typeFlag != '\u0000') {
                skipEntry(tarInput, header.size)
                continue
            }

            val baseName = header.name.substringAfterLast('/')
            val label = displayLabel(baseName)
            val part = pit.findByFileName(baseName)

            if (part == null) {
                logger("<ODIN> Skip '${header.name}': no PIT entry on this device matches that file name.")
                skipEntry(tarInput, header.size)
                continue
            }
            if (header.size <= 0L) {
                logger("<ODIN> Skip '$label': empty entry (0 bytes).")
                skipEntry(tarInput, header.size)
                continue
            }
            if (!shouldFlash(label)) {
                logger("<ODIN> Skip '$label': not ticked in the component list.")
                skipEntry(tarInput, header.size)
                continue
            }

            val undersizedReason = undersizedUserdataReason(label, header.size, part)
            if (undersizedReason != null && allowUndersizedUserdata) {
                logger("Quick-format mode: sending small '$label' (${header.size} bytes) on purpose. " +
                    "Storage may show smaller until a factory reset from Settings.")
            } else if (undersizedReason != null) {
                logger(
                    "Skipping '$label': $undersizedReason Not writing it -- do a factory " +
                        "data reset from the device after this flash finishes and Android " +
                        "will format userdata using the full partition size instead."
                )
                skipEntry(tarInput, header.size)
                continue
            }

            logger(
                "<ODIN> NAND Write Start.. '${part.name.ifBlank { part.fileName }}' " +
                    "[PIT id=${part.id} dev=${part.devType} bin=${part.binType}] " +
                    "from '${header.name}' (${formatBytes(header.size)})"
            )
            val bounded = BoundedInputStream(tarInput, header.size)
            val quickFormat = allowUndersizedUserdata && undersizedReason != null
            val isUserdata = label.equals("userdata", ignoreCase = true)

            if (isUserdata) {
                logger(
                    "USERDATA TRACE: PIT entry id=${part.id} name='${part.name}' fileName='${part.fileName}' " +
                        "binType=${part.binType} devType=${part.devType} attribute=${part.attribute} " +
                        "wireBlockSize=${part.wireBlockSize} partitionSizeBytes=${part.partitionSizeBytes} " +
                        "imageSize=${header.size} quickFormat=$quickFormat"
                )
            }

            var lastLoggedPct = -1
            val startNs = System.nanoTime()
            try {
                // Send in the device's normal windowed sequence (same as every other
                // partition) instead of forcing one giant block -- a working third-party
                // Odin tool flashes this same file successfully using normal windowing,
                // and on-device testing showed the single-block path (singleSequence=true)
                // getting rejected with "secure check failed" (ack=-5) on the final ack,
                // so the forced single-block send is never used here anymore.
                odin.flashImage(part, bounded, header.size, singleSequence = false, tolerateFinalAckWarning = quickFormat) { done, total ->
                    onPartitionProgress(label, done, total)
                    if (isUserdata && total > 0L) {
                        val pct = ((done * 100L) / total).toInt()
                        if (pct >= lastLoggedPct + 10 || done == total) {
                            lastLoggedPct = pct
                            logger("USERDATA TRACE: $pct% ($done / $total bytes)")
                        }
                    }
                }
            } catch (e: IOException) {
                if (isUserdata) {
                    val elapsedMs = (System.nanoTime() - startNs) / 1_000_000
                    logger(
                        "USERDATA FLASH ERROR: write to PIT partition '${part.name.ifBlank { part.fileName }}' " +
                            "(id=${part.id}) failed after ${elapsedMs}ms: ${e.message}. " +
                            "Record this message to decide on a device-specific bypass later."
                    )
                }
                throw e
            }
            bounded.drainRemaining()
            skipPaddingOnly(tarInput, header.size)
            val tookMs = (System.nanoTime() - startNs) / 1_000_000
            logger("<ODIN> RQT_CLOSE '${label}' -> RES OK (${formatBytes(header.size)} in ${tookMs}ms)")
            flashed.add(label)
        }
        return flashed
    }

    private data class TarHeader(val name: String, val size: Long, val typeFlag: Char)

    private fun readTarHeader(input: InputStream): TarHeader? {
        val block = ByteArray(512)
        var read = 0
        while (read < 512) {
            val r = input.read(block, read, 512 - read)
            if (r < 0) return null
            read += r
        }
        if (block.all { it == 0.toByte() }) return null // end-of-archive marker

        val name = String(block, 0, 100, Charset.forName("US-ASCII")).trim('\u0000', ' ')
        val sizeField = String(block, 124, 12, Charset.forName("US-ASCII")).trim('\u0000', ' ')
        val size = if (sizeField.isBlank()) 0L else sizeField.trim().toLong(8)
        val typeFlag = block[156].toInt().toChar()
        return TarHeader(name, size, typeFlag)
    }

    private fun skipEntry(input: InputStream, size: Long) {
        var remaining = size
        val buf = ByteArray(8192)
        while (remaining > 0) {
            val toRead = minOf(buf.size.toLong(), remaining).toInt()
            val r = input.read(buf, 0, toRead)
            if (r < 0) break
            remaining -= r
        }
        skipPaddingOnly(input, size)
    }

    private fun skipPaddingOnly(input: InputStream, size: Long) {
        val padding = (512 - (size % 512)) % 512
        var remaining = padding
        val buf = ByteArray(512)
        while (remaining > 0) {
            val toRead = minOf(buf.size.toLong(), remaining).toInt()
            val r = input.read(buf, 0, toRead)
            if (r < 0) break
            remaining -= r
        }
    }

    /** Lets the caller read at most [limit] bytes of [delegate] before signalling EOF. */
    private class BoundedInputStream(private val delegate: InputStream, private val limit: Long) : InputStream() {
        private var consumed = 0L

        override fun read(): Int {
            if (consumed >= limit) return -1
            val b = delegate.read()
            if (b >= 0) consumed++
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (consumed >= limit) return -1
            val toRead = minOf(len.toLong(), limit - consumed).toInt()
            val r = delegate.read(b, off, toRead)
            if (r > 0) consumed += r
            return r
        }

        fun drainRemaining() {
            val buf = ByteArray(8192)
            while (consumed < limit) {
                val toRead = minOf(buf.size.toLong(), limit - consumed).toInt()
                val r = delegate.read(buf, 0, toRead)
                if (r < 0) break
                consumed += r
            }
        }

        override fun close() {
            // Intentionally does not close the underlying tar stream.
        }
    }
}
