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
            """ZlpAwareHost="1" SkipStorageInit="0" />"""
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
            transport.writeZlp()
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
            transport.writeZlp()
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
            transport.writeZlp()
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
        checkErased(lun, startSector, numSectors)
    }

    /** Reads back the start of an erased range so an ACK that did nothing is reported instead of hidden. */
    private fun checkErased(lun: Int, startSector: Long, numSectors: Long) {
        val probe = min(numSectors, 64L)
        if (probe <= 0) return
        val out = java.io.ByteArrayOutputStream()
        if (runCatching { readSectors(lun, startSector, probe, out) }.isFailure) {
            logger("Erase check skipped (read-back failed).")
            return
        }
        val data = out.toByteArray()
        if (data.isEmpty()) return
        val first = data[0]
        val uniform = data.all { it == first }
        if (uniform && (first == 0.toByte() || first == 0xFF.toByte())) {
            logger("Erase verified: LUN $lun LBA $startSector reads back as ${if (first == 0.toByte()) "0x00" else "0xFF"}.")
        } else {
            logger("WARNING: loader ACKed <erase> but LUN $lun LBA $startSector still contains data. Erase had NO effect.")
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
            logger("[Target] $text")
        }
    }

    private fun buildXml(innerTag: String): String {
        return """<?xml version="1.0" encoding="UTF-8" ?><data>$innerTag</data>"""
    }
}
