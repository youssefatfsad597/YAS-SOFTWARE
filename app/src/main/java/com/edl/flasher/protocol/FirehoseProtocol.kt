package com.edl.flasher.protocol

import com.edl.flasher.usb.EdlUsbTransport
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

enum class StorageType(val memoryName: String, val sectorSize: Int) {
    UFS("ufs", 4096),
    EMMC("emmc", 512)
}

data class FirehoseResponse(
    val ack: Boolean,
    val rawMode: Boolean,
    val maxPayloadSizeToTarget: Int?,
    val rawXml: String
)

private const val ERASE_FALLBACK_MAX_SECTORS = 131072L // 64 MiB at 512 B

class FirehoseProtocol(
    private val transport: EdlUsbTransport,
    private val logger: (String) -> Unit
) {
    var storageType: StorageType = StorageType.UFS
        private set

    var maxPayloadSize: Int = 1048576
        private set

    /** Payload limit the target mentioned in a <log> line (e.g. "larger than supported 16384"). */
    private var logSupportedPayload: Int? = null

    /** Mirrors the ZlpAwareHost attribute we announce in <configure>; when false no ZLP is sent after a payload. */
    var zlpAwareHost: Boolean = true
        private set

    /** Last <log> lines the target printed (cleared by [captureLogs]); used for diagnostics. */
    private val recentLogs = ArrayList<String>()

    /** Read back every write and compare CRCs, so a loader that ACKs without writing can't pass as success. */
    var verifyAfterWrite: Boolean = true

    @Throws(IOException::class)
    fun configure(storage: StorageType): Boolean {
        this.storageType = storage
        drainPendingLogs()

        // Many Samsung/Qualcomm loaders only accept a 16 KB payload and NAK anything bigger.
        // Jump to the size the target reports, otherwise step down until it ACKs.
        val fallbacks = listOf(65536, 16384, 8192, 4096)
        var candidate = maxPayloadSize
        for (attempt in 1..8) {
            logSupportedPayload = null
            val rsp = sendConfigureXml(storage, candidate)
            if (rsp.ack) {
                maxPayloadSize = rsp.maxPayloadSizeToTarget?.takeIf { it >= storage.sectorSize } ?: candidate
                logger("Firehose Ready: Memory=${storage.memoryName.uppercase()}, Sector=${storage.sectorSize}B, Payload=$maxPayloadSize")
                return true
            }
            val supportedAttr = Regex("""MaxPayloadSizeToTargetInBytesSupported="(\d+)"""")
                .find(rsp.rawXml)?.groupValues?.get(1)?.toIntOrNull()
            val hint = listOfNotNull(supportedAttr, logSupportedPayload, rsp.maxPayloadSizeToTarget)
                .firstOrNull { it >= storage.sectorSize && it < candidate }
            val next = hint ?: fallbacks.firstOrNull { it < candidate }
                ?: throw IOException("Firehose <configure> rejected even at $candidate bytes. Response: ${rsp.rawXml.take(300)}")
            logger("Target NAKed payload size $candidate; retrying with $next bytes...")
            candidate = next
        }
        throw IOException("Firehose <configure> negotiation failed after 8 attempts.")
    }

    private fun sendConfigureXml(storage: StorageType, payloadSize: Int): FirehoseResponse {
        val xml = buildXml(
            """<configure MemoryName="${storage.memoryName}" Verbose="0" AlwaysValidate="0" """ +
            """MaxDigestTableSizeInBytes="8192" MaxPayloadSizeToTargetInBytes="$payloadSize" """ +
            """ZlpAwareHost="${if (zlpAwareHost) 1 else 0}" SkipStorageInit="0" />"""
        )
        transport.write(xml.toByteArray(Charsets.UTF_8))
        return waitForResponse()
    }


    @Throws(IOException::class)
    fun readSectors(
        lun: Int,
        startSector: Long,
        numSectors: Long,
        output: OutputStream,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ) {
        val sectorSize = storageType.sectorSize
        val cmd = buildXml(
            """<read SECTOR_SIZE_IN_BYTES="$sectorSize" num_partition_sectors="$numSectors" """ +
            """physical_partition_number="$lun" start_sector="$startSector" />"""
        )
        transport.write(cmd.toByteArray(Charsets.UTF_8))

        val initialRsp = waitForResponse()
        if (!initialRsp.ack || !initialRsp.rawMode) {
            throw IOException("Firehose <read> NAK on LUN $lun, LBA $startSector")
        }

        val totalBytes = numSectors * sectorSize
        var bytesRemaining = totalBytes
        val buf = ByteArray(min(maxPayloadSize.toLong(), totalBytes).toInt().coerceAtLeast(sectorSize))

        while (bytesRemaining > 0) {
            val toRead = min(buf.size.toLong(), bytesRemaining).toInt()
            transport.readFully(buf, 0, toRead, timeoutMs = 60000)
            output.write(buf, 0, toRead)
            bytesRemaining -= toRead
            onProgress(totalBytes - bytesRemaining, totalBytes)
        }

        val finalRsp = waitForResponse()
        if (!finalRsp.ack) {
            throw IOException("Firehose <read> failed final ACK.")
        }
    }

    @Throws(IOException::class)
    fun writePartitionStream(
        lun: Int,
        startSector: Long,
        maxPartitionSectors: Long,
        inputStream: InputStream,
        totalStreamBytes: Long,
        onProgress: (Long, Long) -> Unit
    ) {
        val peekHeader = ByteArray(28)
        val peekLen = inputStream.read(peekHeader)
        if (peekLen <= 0) throw IOException("Selected image file is empty")

        val sparseHeader = if (peekLen == 28) SparseImageHeader.parse(peekHeader) else null
        if (sparseHeader != null) {
            logger("Sparse Image detected (${sparseHeader.totalChunks} chunks). Unsparsing...")
            writeSparseStream(lun, startSector, sparseHeader, inputStream, onProgress)
        } else {
            writeRawStream(lun, startSector, peekHeader.copyOf(peekLen), inputStream, totalStreamBytes, onProgress)
        }
    }

    private fun writeRawStream(
        lun: Int,
        startSector: Long,
        firstBytes: ByteArray,
        inputStream: InputStream,
        totalBytes: Long,
        onProgress: (Long, Long) -> Unit
    ) {
        val sectorSize = storageType.sectorSize
        val numSectors = (totalBytes + sectorSize - 1) / sectorSize
        val paddedTotalBytes = numSectors * sectorSize

        val cmd = buildXml(
            """<program SECTOR_SIZE_IN_BYTES="$sectorSize" num_partition_sectors="$numSectors" """ +
            """physical_partition_number="$lun" start_sector="$startSector" />"""
        )
        transport.write(cmd.toByteArray(Charsets.UTF_8))

        val rsp = waitForResponse()
        if (!rsp.ack || !rsp.rawMode) {
            throw IOException("Firehose <program> rejected on LUN $lun, start_sector $startSector: ${rsp.rawXml.take(300)}")
        }

        val buffer = ByteArray(maxPayloadSize)
        val crc = java.util.zip.CRC32()
        var bytesSent = 0L

        System.arraycopy(firstBytes, 0, buffer, 0, firstBytes.size)
        var bufOffset = firstBytes.size

        while (bytesSent < paddedTotalBytes) {
            val targetChunk = min(maxPayloadSize.toLong(), paddedTotalBytes - bytesSent).toInt()
            while (bufOffset < targetChunk) {
                val r = inputStream.read(buffer, bufOffset, targetChunk - bufOffset)
                if (r < 0) {
                    java.util.Arrays.fill(buffer, bufOffset, targetChunk, 0.toByte())
                    bufOffset = targetChunk
                } else {
                    bufOffset += r
                }
            }
            transport.write(buffer, 0, targetChunk, timeoutMs = 60000)
            if (zlpAwareHost) transport.writeZlp()
            crc.update(buffer, 0, targetChunk)
            bytesSent += targetChunk
            bufOffset = 0
            onProgress(min(bytesSent, totalBytes), totalBytes)
        }

        val finalRsp = waitForResponse()
        if (!finalRsp.ack) {
            throw IOException("Firehose <program> failed final ACK: ${finalRsp.rawXml.take(300)}")
        }
        if (verifyAfterWrite && paddedTotalBytes <= 128L * 1024 * 1024) {
            verifyWritten(lun, startSector, numSectors, crc.value)
        }
    }

    private fun verifyWritten(lun: Int, startSector: Long, numSectors: Long, expectedCrc: Long) {
        logger("Verifying write (read-back)...")
        val check = java.util.zip.CRC32()
        val sink = object : OutputStream() {
            override fun write(b: Int) { check.update(b) }
            override fun write(b: ByteArray, off: Int, len: Int) { check.update(b, off, len) }
        }
        readSectors(lun, startSector, numSectors, sink)
        if (check.value != expectedCrc) {
            throw IOException("Write verification FAILED on LUN $lun, LBA $startSector: target ACKed the write but flash content does not match. The loader is likely ignoring writes to this range.")
        }
        logger("Write verified OK (read-back matches).")
    }

    private fun writeSparseStream(
        lun: Int,
        baseStartSector: Long,
        header: SparseImageHeader,
        inputStream: InputStream,
        onProgress: (Long, Long) -> Unit
    ) {
        val sectorSize = storageType.sectorSize
        if (header.fileHdrSz > 28) {
            inputStream.skip((header.fileHdrSz - 28).toLong())
        }

        val totalOutputBytes = header.totalBlks.toLong() * header.blkSz
        var processedOutputBytes = 0L
        var currentSector = baseStartSector
        val sectorsPerBlock = header.blkSz / sectorSize

        for (chunkIdx in 0 until header.totalChunks) {
            val chunk = SparseChunkHeader.readFrom(inputStream, header.chunkHdrSz.toInt())
            val chunkOutputBytes = chunk.chunkSz.toLong() * header.blkSz
            val chunkSectors = chunk.chunkSz.toLong() * sectorsPerBlock

            when (chunk.chunkType) {
                SparseImageHeader.CHUNK_TYPE_RAW -> {
                    programRawChunkFromStream(lun, currentSector, chunkSectors, inputStream) { writtenInChunk ->
                        onProgress(processedOutputBytes + writtenInChunk, totalOutputBytes)
                    }
                    currentSector += chunkSectors
                    processedOutputBytes += chunkOutputBytes
                }

                SparseImageHeader.CHUNK_TYPE_FILL -> {
                    val fillBytes = ByteArray(4)
                    inputStream.read(fillBytes)
                    val fillPattern = ByteBuffer.wrap(fillBytes).order(ByteOrder.LITTLE_ENDIAN).int
                    if (fillPattern == 0) {
                        eraseSectors(lun, currentSector, chunkSectors)
                    } else {
                        programFillChunk(lun, currentSector, chunkSectors, fillBytes)
                    }
                    currentSector += chunkSectors
                    processedOutputBytes += chunkOutputBytes
                    onProgress(processedOutputBytes, totalOutputBytes)
                }

                SparseImageHeader.CHUNK_TYPE_DONT_CARE -> {
                    currentSector += chunkSectors
                    processedOutputBytes += chunkOutputBytes
                    onProgress(processedOutputBytes, totalOutputBytes)
                }

                SparseImageHeader.CHUNK_TYPE_CRC32 -> {
                    inputStream.skip(4)
                }
            }
        }
    }

    private fun programRawChunkFromStream(
        lun: Int,
        startSector: Long,
        numSectors: Long,
        inputStream: InputStream,
        onChunkProgress: (Long) -> Unit
    ) {
        val sectorSize = storageType.sectorSize
        val totalBytes = numSectors * sectorSize
        val cmd = buildXml(
            """<program SECTOR_SIZE_IN_BYTES="$sectorSize" num_partition_sectors="$numSectors" """ +
            """physical_partition_number="$lun" start_sector="$startSector" />"""
        )
        transport.write(cmd.toByteArray(Charsets.UTF_8))
        val rsp = waitForResponse()
        if (!rsp.ack || !rsp.rawMode) throw IOException("Sparse RAW chunk rejected at sector $startSector")

        val buf = ByteArray(maxPayloadSize)
        var sent = 0L
        while (sent < totalBytes) {
            val toSend = min(maxPayloadSize.toLong(), totalBytes - sent).toInt()
            var off = 0
            while (off < toSend) {
                val r = inputStream.read(buf, off, toSend - off)
                if (r < 0) throw IOException("Unexpected EOF inside sparse RAW chunk")
                off += r
            }
            transport.write(buf, 0, toSend, timeoutMs = 60000)
            if (zlpAwareHost) transport.writeZlp()
            sent += toSend
            onChunkProgress(sent)
        }

        val finalRsp = waitForResponse()
        if (!finalRsp.ack) throw IOException("Sparse RAW chunk failed final ACK")
    }

    private fun programFillChunk(lun: Int, startSector: Long, numSectors: Long, fill4Bytes: ByteArray) {
        val sectorSize = storageType.sectorSize
        val totalBytes = numSectors * sectorSize
        val cmd = buildXml(
            """<program SECTOR_SIZE_IN_BYTES="$sectorSize" num_partition_sectors="$numSectors" """ +
            """physical_partition_number="$lun" start_sector="$startSector" />"""
        )
        transport.write(cmd.toByteArray(Charsets.UTF_8))
        val rsp = waitForResponse()
        if (!rsp.ack || !rsp.rawMode) throw IOException("Sparse FILL chunk rejected")

        val buf = ByteArray(maxPayloadSize)
        for (i in buf.indices step 4) {
            System.arraycopy(fill4Bytes, 0, buf, i, 4)
        }

        var sent = 0L
        while (sent < totalBytes) {
            val toSend = min(maxPayloadSize.toLong(), totalBytes - sent).toInt()
            transport.write(buf, 0, toSend, timeoutMs = 60000)
            if (zlpAwareHost) transport.writeZlp()
            sent += toSend
        }
        val finalRsp = waitForResponse()
        if (!finalRsp.ack) throw IOException("Sparse FILL chunk failed final ACK")
    }

    @Throws(IOException::class)
    fun eraseSectors(lun: Int, startSector: Long, numSectors: Long) {
        val sectorSize = storageType.sectorSize
        val cmd = buildXml(
            """<erase SECTOR_SIZE_IN_BYTES="$sectorSize" num_partition_sectors="$numSectors" """ +
            """physical_partition_number="$lun" start_sector="$startSector" />"""
        )
        transport.write(cmd.toByteArray(Charsets.UTF_8))
        val rsp = waitForResponse()
        if (!rsp.ack) throw IOException("Firehose <erase> failed on LUN $lun, LBA $startSector: ${rsp.rawXml.take(300)}")
        val erased = checkErased(lun, startSector, numSectors)
        if (erased == false && storageType == StorageType.EMMC) {
            // Many eMMC programmers ACK <erase> but only issue a discard that leaves data readable.
            // Writing zeros is what the user actually wants (invalidates FS / crypto footer).
            val cap = min(numSectors, ERASE_FALLBACK_MAX_SECTORS)
            logger("Erase had no visible effect on eMMC -> zero-filling $cap sector(s) with <program> instead.")
            programZeros(lun, startSector, cap)
            if (checkErased(lun, startSector, cap) == false) {
                logger("WARNING: zero-fill did not change the data either. The eMMC is ignoring ALL writes (write-protected or read-only). Run 'Test eMMC Write' for a diagnosis.")
            }
        }
    }

    /** Reads back the start of an erased range so an ACK that did nothing is reported instead of hidden. */
    private fun checkErased(lun: Int, startSector: Long, numSectors: Long): Boolean? {
        val probe = min(numSectors, 64L)
        if (probe <= 0) return null
        val out = java.io.ByteArrayOutputStream()
        if (runCatching { readSectors(lun, startSector, probe, out) }.isFailure) {
            logger("Erase check skipped (read-back failed).")
            return null
        }
        val data = out.toByteArray()
        if (data.isEmpty()) return null
        val first = data[0]
        val uniform = data.all { it == first }
        if (uniform && (first == 0.toByte() || first == 0xFF.toByte())) {
            logger("Erase verified: LUN $lun LBA $startSector reads back as ${if (first == 0.toByte()) "0x00" else "0xFF"}.")
            return true
        }
        logger("WARNING: loader ACKed <erase> but LUN $lun LBA $startSector still contains data. Erase had NO effect.")
        return false
    }

    // ---------------------------------------------------------------------------------------
    // eMMC write diagnostics / loader-variant helpers
    // ---------------------------------------------------------------------------------------

    /** Programs [data] (must be a whole number of sectors) at [startSector]. [fullAttrs] sends the long
     *  rawprogram-style attribute set some loaders insist on instead of the minimal one. */
    @Throws(IOException::class)
    fun programBuffer(lun: Int, startSector: Long, data: ByteArray, fullAttrs: Boolean = false, timeoutMs: Int = 60000) {
        val sectorSize = storageType.sectorSize
        require(data.isNotEmpty() && data.size % sectorSize == 0) { "buffer must be whole sectors" }
        val numSectors = data.size / sectorSize
        transport.write(buildXml(programTag(lun, startSector, numSectors.toLong(), fullAttrs)).toByteArray(Charsets.UTF_8), timeoutMs = timeoutMs)
        val rsp = waitForResponse(timeoutMs = timeoutMs)
        if (!rsp.ack || !rsp.rawMode) {
            throw IOException("Firehose <program> rejected (LUN $lun, sector $startSector): ${rsp.rawXml.take(300)}")
        }
        var off = 0
        while (off < data.size) {
            val n = min(maxPayloadSize, data.size - off)
            transport.write(data, off, n, timeoutMs = timeoutMs)
            if (zlpAwareHost) transport.writeZlp()
            off += n
        }
        val fin = waitForResponse(timeoutMs = timeoutMs)
        if (!fin.ack) throw IOException("Firehose <program> failed final ACK: ${fin.rawXml.take(300)}")
    }

    private fun programTag(lun: Int, startSector: Long, numSectors: Long, fullAttrs: Boolean): String {
        val sectorSize = storageType.sectorSize
        return if (!fullAttrs) {
            """<program SECTOR_SIZE_IN_BYTES="$sectorSize" num_partition_sectors="$numSectors" """ +
                """physical_partition_number="$lun" start_sector="$startSector" />"""
        } else {
            val kb = numSectors * sectorSize / 1024
            """<program SECTOR_SIZE_IN_BYTES="$sectorSize" file_sector_offset="0" filename="" label="yas" """ +
                """num_partition_sectors="$numSectors" physical_partition_number="$lun" size_in_KB="$kb" """ +
                """sparse="false" start_byte_hex="0x${(startSector * sectorSize).toString(16)}" """ +
                """start_sector="$startSector" read_back_verify="false" />"""
        }
    }

    /** Streams [numSectors] zero sectors to [startSector] with one <program> command. */
    @Throws(IOException::class)
    fun programZeros(lun: Int, startSector: Long, numSectors: Long) {
        if (numSectors <= 0) return
        val sectorSize = storageType.sectorSize
        transport.write(buildXml(programTag(lun, startSector, numSectors, false)).toByteArray(Charsets.UTF_8))
        val rsp = waitForResponse()
        if (!rsp.ack || !rsp.rawMode) throw IOException("Zero-fill <program> rejected: ${rsp.rawXml.take(300)}")
        val total = numSectors * sectorSize
        val buf = ByteArray(maxPayloadSize)
        var sent = 0L
        while (sent < total) {
            val n = min(maxPayloadSize.toLong(), total - sent).toInt()
            transport.write(buf, 0, n, timeoutMs = 60000)
            if (zlpAwareHost) transport.writeZlp()
            sent += n
        }
        val fin = waitForResponse()
        if (!fin.ack) throw IOException("Zero-fill failed final ACK: ${fin.rawXml.take(300)}")
    }

    /** Sends <getstorageinfo> and returns whatever the loader printed (manufacturer, fw_version, total_blocks...). */
    fun getStorageInfo(lun: Int = 0): List<String> {
        synchronized(recentLogs) { recentLogs.clear() }
        drainPendingLogs()
        return try {
            transport.write(buildXml("""<getstorageinfo physical_partition_number="$lun" />""").toByteArray(Charsets.UTF_8))
            waitForResponse(timeoutMs = 8000)
            synchronized(recentLogs) { recentLogs.toList() }
        } catch (e: IOException) {
            logger("getstorageinfo failed: ${e.message}")
            emptyList()
        }
    }

    /** Re-sends <configure> with explicit knobs; used to try loader "dialects". */
    fun reconfigure(storage: StorageType, payload: Int, zlp: Boolean): Boolean {
        zlpAwareHost = zlp
        drainPendingLogs()
        val rsp = runCatching { sendConfigureXml(storage, payload) }.getOrNull() ?: return false
        if (rsp.ack) {
            storageType = storage
            maxPayloadSize = rsp.maxPayloadSizeToTarget?.takeIf { it in storage.sectorSize..payload } ?: payload
        }
        return rsp.ack
    }

    enum class WriteVerdict { WRITES_WORK, REJECTED, HUNG, SILENTLY_IGNORED, UNREADABLE, UNEXPECTED }

    data class WriteTestReport(val verdict: WriteVerdict, val detail: String)

    /**
     * Non-destructive write probe for ONE sector: reads it, writes the bit-inverted copy, reads it back,
     * then restores the original. Tells apart (a) loader NAKs the write, (b) loader ACKs but the eMMC does
     * not change (write-protected / read-only eMMC), (c) writes really work.
     */
    fun writeSelfTest(lun: Int, sector: Long, fullAttrs: Boolean): WriteTestReport {
        val ss = storageType.sectorSize
        // Short timeout on purpose: a loader that is about to hang gives no useful reply anyway, and
        // waiting the normal 60s per attempt (x5 dialects) is what froze the device for 5 minutes straight
        // on the SM-T505N -- every extra command sent to an already-wedged loader only makes it worse.
        val testTimeoutMs = 6000
        fun readOne(): ByteArray? {
            val out = java.io.ByteArrayOutputStream()
            return runCatching { readSectors(lun, sector, 1, out); out.toByteArray() }.getOrNull()
        }
        val original = readOne() ?: return WriteTestReport(WriteVerdict.UNREADABLE, "could not read sector $sector")
        if (original.size != ss) return WriteTestReport(WriteVerdict.UNREADABLE, "short read (${original.size} bytes)")
        val inverted = ByteArray(ss) { (original[it].toInt() xor 0xFF).toByte() }
        val startNs = System.nanoTime()
        try {
            programBuffer(lun, sector, inverted, fullAttrs, timeoutMs = testTimeoutMs)
        } catch (e: IOException) {
            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000
            // A clean NAK comes back in well under a second. Anything that took most of the timeout
            // means the loader/controller stopped answering -- a hang, not a rejection.
            return if (elapsedMs >= testTimeoutMs - 500) {
                WriteTestReport(WriteVerdict.HUNG, "no reply for ${elapsedMs}ms -- the loader stopped responding")
            } else {
                WriteTestReport(WriteVerdict.REJECTED, e.message.orEmpty())
            }
        }
        val after = readOne()
        // Always try to put the original back, whatever happened.
        runCatching { programBuffer(lun, sector, original, fullAttrs, timeoutMs = testTimeoutMs) }
        return when {
            after == null -> WriteTestReport(WriteVerdict.UNREADABLE, "read-back failed after write")
            after.contentEquals(inverted) -> WriteTestReport(WriteVerdict.WRITES_WORK, "sector changed as expected")
            after.contentEquals(original) -> WriteTestReport(WriteVerdict.SILENTLY_IGNORED, "loader ACKed the write but the sector is unchanged")
            else -> WriteTestReport(WriteVerdict.UNEXPECTED, "sector changed to unexpected content")
        }
    }

    @Throws(IOException::class)
    fun applyPatch(patchAttributes: Map<String, String>) {
        val attrs = patchAttributes.entries.joinToString(" ") { """${it.key}="${it.value}"""" }
        val cmd = buildXml("<patch $attrs />")
        transport.write(cmd.toByteArray(Charsets.UTF_8))
        val rsp = waitForResponse()
        if (!rsp.ack) throw IOException("Firehose <patch> failed: $attrs")
    }

    /** Sends <power>. Returns true if the target ACKed it (or the USB link dropped, i.e. it is already rebooting),
     *  false if the loader refused every variant. */
    @Throws(IOException::class)
    fun powerControl(mode: String = "reset"): Boolean {
        if (!transport.isConnected) return true
        drainPendingLogs()
        // Loaders differ: some want a delay attribute, some reject it. Try the common forms.
        val variants = listOf(
            """<power value="$mode" DelayInSeconds="1" />""",
            """<power DelayInSeconds="0" value="$mode" />""",
            """<power value="$mode" />"""
        )
        for ((i, v) in variants.withIndex()) {
            try {
                transport.write(buildXml(v).toByteArray(Charsets.UTF_8))
            } catch (e: IOException) {
                logger("USB link dropped while sending <power>: device is rebooting.")
                return true
            }
            val rsp = try {
                waitForResponse(timeoutMs = 3000)
            } catch (e: IOException) {
                if (!transport.isConnected) return true
                logger("No reply to <power> variant ${i + 1}.")
                continue
            }
            if (rsp.ack) {
                logger("Target ACKed <power $mode>.")
                return true
            }
            logger("Target NAKed <power $mode> (variant ${i + 1}): ${rsp.rawXml.take(200)}")
        }
        return false
    }

    private fun drainPendingLogs() {
        val buf = ByteArray(16384)
        while (true) {
            val r = runCatching { transport.read(buf, timeoutMs = 350) }.getOrDefault(-1)
            if (r <= 0) break
            val xml = String(buf, 0, r, Charsets.UTF_8)
            extractLogs(xml)
        }
    }

    private fun waitForResponse(timeoutMs: Int = 60000): FirehoseResponse {
        val buf = ByteArray(16384)
        val deadline = System.currentTimeMillis() + timeoutMs
        val accumulator = java.lang.StringBuilder()

        while (System.currentTimeMillis() < deadline) {
            val readLen = try {
                transport.read(buf, timeoutMs = 5000)
            } catch (e: IOException) {
                if (!transport.isConnected) {
                    throw IOException("USB device disconnected while waiting for Firehose response")
                }
                -1
            }
            if (readLen < 0) continue

            val chunk = String(buf, 0, readLen, Charsets.UTF_8)
            extractLogs(chunk)
            accumulator.append(chunk)

            val responseRegex = Regex("""<response\b[^>]*?(?:/>|>.*?</response>)""", RegexOption.DOT_MATCHES_ALL)
            val fullMatch = responseRegex.find(accumulator.toString())
            if (fullMatch != null) {
                val xmlText = fullMatch.value
                val ack = xmlText.contains("""value="ACK"""")
                val rawMode = xmlText.contains("""rawmode="true"""") || xmlText.contains("""rawmode="1"""")
                val maxPayloadRegex = Regex("""MaxPayloadSizeToTargetInBytes="(\d+)"""")
                val negotiatedPayload = maxPayloadRegex.find(xmlText)?.groupValues?.get(1)?.toIntOrNull()

                return FirehoseResponse(
                    ack = ack,
                    rawMode = rawMode,
                    maxPayloadSizeToTarget = negotiatedPayload,
                    rawXml = xmlText
                )
            }
        }
        throw IOException("Timed out waiting for Firehose XML <response> (buffer: ${accumulator.take(200)})")
    }

    private fun extractLogs(xmlChunk: String) {
        val logRegex = Regex("""<log\s+value="([^"]*)"\s*/>""")
        logRegex.findAll(xmlChunk).forEach { match ->
            val text = match.groupValues[1]
            Regex("""larger than supported (\d+)""").find(text)?.groupValues?.get(1)?.toIntOrNull()
                ?.let { logSupportedPayload = it }
            synchronized(recentLogs) { if (recentLogs.size < 200) recentLogs.add(text) }
            logger("[Target] $text")
        }
    }

    private fun buildXml(innerTag: String): String {
        return """<?xml version="1.0" encoding="UTF-8" ?><data>$innerTag</data>"""
    }
}
