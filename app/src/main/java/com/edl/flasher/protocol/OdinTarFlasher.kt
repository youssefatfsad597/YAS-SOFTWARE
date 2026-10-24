package com.edl.flasher.protocol

import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset

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
                logger("Skipping '${header.name}': no matching PIT entry on this device.")
                skipEntry(tarInput, header.size)
                continue
            }
            if (header.size <= 0L) {
                logger("Skipping '$label': empty entry.")
                skipEntry(tarInput, header.size)
                continue
            }
            if (!shouldFlash(label)) {
                logger("Skipping '$label' (not selected).")
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

            logger("Found '${header.name}' -> flashing PIT partition '${part.name.ifBlank { part.fileName }}' (id=${part.id}, ${header.size} bytes)")
            val bounded = BoundedInputStream(tarInput, header.size)
            val quickFormat = allowUndersizedUserdata && undersizedReason != null
            odin.flashImage(part, bounded, header.size, singleSequence = quickFormat, tolerateFinalAckWarning = quickFormat) { done, total ->
                onPartitionProgress(label, done, total)
            }
            bounded.drainRemaining()
            skipPaddingOnly(tarInput, header.size)
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
