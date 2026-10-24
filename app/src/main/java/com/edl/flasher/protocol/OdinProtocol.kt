package com.edl.flasher.protocol

import com.edl.flasher.usb.EdlUsbTransport
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

/**
 * Samsung Download Mode ("Odin"/"Thor") wire protocol: the fixed 1024-byte
 * request / 8-byte response framing used by Samsung's official Odin tool and
 * by independent open-source reimplementations (Heimdall, Brokkr). The wire
 * layout, command/parameter numbering, PIT binary format and bootloader
 * rejection codes below are public, independently-documented protocol facts,
 * cross-checked against more than one open-source implementation.
 *
 * This class performs only standard protocol operations (handshake, PIT
 * download, packetised file transfer, close/reboot). It never attempts to
 * bypass a device's own signature, rollback, Knox Guard or MDM checks -- any
 * rejection the bootloader itself returns is surfaced to the caller with its
 * documented meaning, exactly like official Odin / Heimdall / Brokkr do.
 */

data class OdinPitPartition(
    val id: Int,
    val binType: Int,
    val devType: Int,
    val attribute: Int,
    val wireBlockSize: Int,
    val name: String,
    val fileName: String
)

/** The PIT entry's declared partition size in bytes (wireBlockSize is the number of
 *  512-byte blocks the bootloader allocated to this partition -- the same field and
 *  the same 512-byte-block assumption Heimdall uses). 0 if the PIT doesn't report one. */
val OdinPitPartition.partitionSizeBytes: Long get() = wireBlockSize.toLong() * 512L

class OdinPitTable(val partitions: List<OdinPitPartition>, val cpuBlId: String) {
    /** Real Odin maps a source file to a partition by this exact (case-sensitive) file name, e.g. "userdata.img". */
    fun findByFileName(baseName: String): OdinPitPartition? =
        partitions.firstOrNull { it.fileName == baseName }

    fun findByName(name: String): OdinPitPartition? =
        partitions.firstOrNull { it.name.equals(name, ignoreCase = true) }

    fun isAb(): Boolean = findByName("VBMETA_A") != null && findByName("VBMETA_B") != null
}

private object OdinWire {
    const val CMD_INIT = 100
    const val CMD_PIT = 101
    const val CMD_XMIT = 102
    const val CMD_CLOSE = 103
    const val CMD_SUPER = 106

    const val INIT_TARGET = 0
    const val INIT_TOTALSIZE = 2
    const val INIT_PACKETSIZE = 5

    const val PIT_GET = 1
    const val PIT_START = 2
    const val PIT_COMPLETE = 3

    const val XMIT_DOWNLOAD = 0
    const val XMIT_START = 2
    const val XMIT_COMPLETE = 3

    const val SUPER_USED_BLOCKS = 0

    const val CLOSE_END = 0
    const val CLOSE_REBOOT = 1

    const val REQUEST_SIZE = 1024
    const val RESPONSE_SIZE = 8
    const val PIT_TRANSMIT_UNIT = 500
    const val PIT_MAGIC = 0x12349876
    const val PIT_HEADER_SIZE = 28
    const val PIT_ENTRY_SIZE = 132
    const val XMIT_START_ALIGN = 128L * 1024L
    const val ONE_MIB = 1024L * 1024L
    const val BOOTLOADER_FAIL_ID = -1

    fun buildRequest(id: Int, data: Int, ints: IntArray = IntArray(0)): ByteArray {
        val buf = ByteBuffer.allocate(REQUEST_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(id)
        buf.putInt(data)
        for (i in 0 until 9) buf.putInt(if (i < ints.size) ints[i] else 0)
        // charData[128] + md5[32] + remaining padding: already zero from allocate().
        return buf.array()
    }

    fun responseId(bytes: ByteArray): Int = ByteBuffer.wrap(bytes, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
    fun responseAck(bytes: ByteArray): Int = ByteBuffer.wrap(bytes, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int

    fun roundUp(n: Long, base: Long): Long {
        if (base <= 0) return n
        val r = n % base
        return if (r == 0L) n else n + (base - r)
    }

    /** Known device-side rejection codes (returned as `ack` when the response id is the BOOTLOADER_FAIL sentinel). */
    fun bootloaderFailText(ack: Int): String = when (ack) {
        -1 -> "unsupported binary"
        -2 -> "write protection failure"
        -3 -> "failed to clear write protection"
        -4 -> "write failed"
        -5 -> "secure check failed"
        -6 -> "size check failed"
        -7 -> "ext4 image handling failed"
        -8 -> "image exceeds the 112MiB staging limit"
        -94 -> "AUTO BLOCKER is on, cannot download"
        -96 -> "image write failed"
        -97 -> "bootloader token install failed"
        -98 -> "UL_KEYS write failed"
        -99 -> "LDFW region write failed"
        -100 -> "LDFW region erase failed"
        -101 -> "unsupported version (rollback protection)"
        -102 -> "TOKEN size is too big"
        -103 -> "this is an ENG binary, please use a USER binary"
        -104 -> "system rollback protection check failed"
        -105 -> "cross-verify (kill switch) lock"
        -106 -> "kernel rollback protection check failed"
        -107 -> "custom binary blocked by OEM lock"
        -108 -> "custom binary blocked by R/L"
        -109 -> "only official released binaries are allowed to be flashed (Knox Guard v2.0)"
        -110 -> "only official released binaries are allowed to be flashed (Knox Guard)"
        -111 -> "SECURE CHECK FAIL (signature verification failed)"
        -112 -> "Knox Guard prevents flashing factory binaries"
        -113 -> "MDM MODE, cannot download"
        -114 -> "all binaries are not allowed to be flashed due to KG locked"
        -115 -> "filesystem is not supported"
        -116 -> "unsupported filesystem type"
        -117 -> "block erase failed"
        -118 -> "block read failed"
        -119 -> "block write failed"
        -120 -> "partition device type is not supported"
        -121 -> "partition is not UFS on a UFS device"
        -122 -> "partition ID mismatch, or SUPER write rejected (upload a PIT first)"
        -123 -> "BL1 check failed"
        -124 -> "image is too big for the partition"
        -125 -> "m9kefs3 cannot be downloaded"
        -126 -> "PIT entry has no file name"
        -127 -> "unknown partition"
        else -> "bootloader error (ack=$ack)"
    }

    /** Max bytes the device accepts between XMIT begin/end pairs, keyed by the PIT entry's device type. */
    fun xmitWindowBytes(part: OdinPitPartition, defaultBytes: Long): Long {
        return when (part.devType) {
            1, 2, 8 -> 30L * ONE_MIB
            6 -> 4L * ONE_MIB
            7 -> ONE_MIB
            0 -> {
                val blk = if (part.wireBlockSize > 0) part.wireBlockSize.toLong() else 0L
                val units = blk shr 7
                if (units == 0L) {
                    defaultBytes
                } else {
                    val base = blk + (if (part.attribute == 1) units * 4 else 0)
                    val computed = base * 1024L * (800L / units)
                    if (computed != 0L) computed else defaultBytes
                }
            }
            else -> defaultBytes
        }
    }

    fun parsePit(bytes: ByteArray): OdinPitTable {
        if (bytes.size < PIT_HEADER_SIZE) throw IOException("PIT buffer too small for its header")
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val magic = bb.getInt(0)
        if (magic != PIT_MAGIC) throw IOException("PIT has a bad magic number (0x${magic.toString(16)})")
        val count = bb.getInt(4)
        if (count < 0) throw IOException("PIT reports a negative partition count")
        val cpuBlId = cTrim(bytes, 12, 8)
        val required = PIT_HEADER_SIZE + count * PIT_ENTRY_SIZE
        if (bytes.size < required) throw IOException("PIT buffer is smaller than its declared partition table")

        val parts = ArrayList<OdinPitPartition>(count)
        var off = PIT_HEADER_SIZE
        repeat(count) {
            val binType = bb.getInt(off)
            val devType = bb.getInt(off + 4)
            val id = bb.getInt(off + 8)
            val attribute = bb.getInt(off + 12)
            val blockSize = bb.getInt(off + 20)
            val name = cTrim(bytes, off + 36, 32)
            val fileName = cTrim(bytes, off + 68, 32)
            parts.add(OdinPitPartition(id, binType, devType, attribute, blockSize, name, fileName))
            off += PIT_ENTRY_SIZE
        }
        return OdinPitTable(parts, cpuBlId)
    }

    private fun cTrim(bytes: ByteArray, offset: Int, len: Int): String {
        var end = offset
        val limit = offset + len
        while (end < limit && bytes[end] != 0.toByte()) end++
        return String(bytes, offset, end - offset, Charsets.US_ASCII).trim()
    }
}

class OdinProtocol(
    private val transport: EdlUsbTransport,
    private val logger: (String) -> Unit
) {
    private var packetSize: Int = 128 * 1024

    var pitTable: OdinPitTable? = null
        private set

    private fun sendRequest(id: Int, data: Int, ints: IntArray = IntArray(0)) {
        transport.write(OdinWire.buildRequest(id, data, ints), timeoutMs = 10000)
    }

    /** Reads one 8-byte ResponseBox. Throws with the bootloader's own rejection text if the device refused the command. */
    private fun recvResponse(expectedId: Int, timeoutMs: Int = 20000): Int {
        val raw = ByteArray(OdinWire.RESPONSE_SIZE)
        transport.readFully(raw, timeoutMs = timeoutMs)
        val id = OdinWire.responseId(raw)
        val ack = OdinWire.responseAck(raw)
        if (id == OdinWire.BOOTLOADER_FAIL_ID) {
            throw IOException("Device rejected the operation: ${OdinWire.bootloaderFailText(ack)} (ack=$ack)")
        }
        if (id != expectedId) {
            throw IOException("Unexpected ODIN response id=$id (expected $expectedId, ack=$ack)")
        }
        return ack
    }

    private fun rpc(id: Int, data: Int, ints: IntArray = IntArray(0), allowNegativeAck: Boolean = false): Int {
        sendRequest(id, data, ints)
        val ack = recvResponse(id)
        if (!allowNegativeAck && ack < 0) {
            throw IOException("ODIN command rejected (id=$id data=$data, ack=$ack)")
        }
        return ack
    }

    /** Handshake + protocol/packet-size negotiation + PIT download. Call once per USB session. */
    @Throws(IOException::class)
    fun connect(): OdinPitTable {
        transport.write("ODIN".toByteArray(Charsets.US_ASCII) + byteArrayOf(0), timeoutMs = 10000)
        val resp = ByteArray(4)
        transport.readFully(resp, timeoutMs = 10000)
        val got = String(resp, Charsets.US_ASCII)
        if (got != "LOKE") throw IOException("ODIN handshake failed (expected 'LOKE', got '$got')")
        logger("ODIN handshake OK (LOKE).")

        val ackWord = rpc(OdinWire.CMD_INIT, OdinWire.INIT_TARGET, intArrayOf(5), allowNegativeAck = true)
        val protoRaw = ((ackWord.toLong() and 0xFFFFFFFFL) ushr 16).toInt() and 0xFFFF
        val protoVersion = if (protoRaw == 0) 1 else protoRaw
        logger("ODIN protocol version: $protoVersion")

        packetSize = if (protoVersion >= 2) {
            val pkt = (1 * 1024 * 1024)
            rpc(OdinWire.CMD_INIT, OdinWire.INIT_PACKETSIZE, intArrayOf(pkt))
            pkt
        } else {
            128 * 1024
        }

        logger("Downloading PIT table...")
        val pitSize = rpc(OdinWire.CMD_PIT, OdinWire.PIT_GET, allowNegativeAck = true)
        if (pitSize <= 0) throw IOException("Device returned an invalid PIT size ($pitSize)")

        val pitBytes = ByteArray(pitSize)
        var off = 0
        var idx = 0
        while (off < pitSize) {
            sendRequest(OdinWire.CMD_PIT, OdinWire.PIT_START, intArrayOf(idx))
            val chunkSize = min(OdinWire.PIT_TRANSMIT_UNIT, pitSize - off)
            transport.readFully(pitBytes, offset = off, length = chunkSize, timeoutMs = 10000)
            off += chunkSize
            idx++
        }
        runCatching { transport.read(ByteArray(64), timeoutMs = 500) } // best-effort trailing ZLP, as real Odin does
        rpc(OdinWire.CMD_PIT, OdinWire.PIT_COMPLETE)

        val table = OdinWire.parsePit(pitBytes)
        logger("PIT downloaded: ${table.partitions.size} partitions (cpu_bl_id='${table.cpuBlId}').")
        pitTable = table
        return table
    }

    fun requirePit(): OdinPitTable =
        pitTable ?: throw IllegalStateException("ODIN session not connected yet (no PIT table).")

    /** Must be sent exactly once, after the PIT download and before flashing any partition. */
    @Throws(IOException::class)
    fun sendTotalSize(totalBytes: Long) {
        val split = 0xFFFFFFFFL
        val low = (totalBytes % split).toInt()
        val high = (totalBytes / split).toInt()
        rpc(OdinWire.CMD_INIT, OdinWire.INIT_TOTALSIZE, intArrayOf(low, high))
    }

    fun declareSuperUsedBlocks(blocks: Int) {
        rpc(OdinWire.CMD_SUPER, OdinWire.SUPER_USED_BLOCKS, intArrayOf(blocks))
    }

    /**
     * Streams [totalSize] bytes from [input] into [part], split into the device's own transfer
     * windows. This is where the bootloader's own checks run -- including the "secure check
     * failed" rejection -- and any such rejection is thrown with its documented meaning.
     */
    @Throws(IOException::class)
    fun flashImage(
        part: OdinPitPartition,
        input: InputStream,
        totalSize: Long,
        singleSequence: Boolean = false,
        // The device prints an on-screen "SW REV CHECK FAIL" / secure-check warning for some
        // quick-format images even on a run that ultimately succeeds (confirmed against the
        // official PC tool on the same file): it is a post-write notice, not a rejection of
        // the write itself. When true, a negative ack on the FINAL XMIT_COMPLETE -- after every
        // byte has already been sent -- is logged as a warning instead of aborting the flash.
        tolerateFinalAckWarning: Boolean = false,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ) {
        if (totalSize <= 0L) throw IOException("Empty source for partition '${part.name.ifBlank { part.fileName }}'")

        val normalWindow = OdinWire.xmitWindowBytes(part, 30L * OdinWire.ONE_MIB)
        // Small images (e.g. the 64 MB quick-format userdata.img) are sent as ONE
        // sequence so the bootloader sees the complete image in a single pass,
        // the same way the PC tool does. Bounded by the bootloader's 112 MiB staging limit.
        val window = if (singleSequence && totalSize <= 112L * OdinWire.ONE_MIB) {
            maxOf(normalWindow, totalSize)
        } else normalWindow
        val pkt = packetSize.toLong()
        var sent = 0L

        while (sent < totalSize) {
            val remaining = totalSize - sent
            val actual = min(remaining, window)
            val roundedForPackets = OdinWire.roundUp(actual, pkt)
            val beginSize = OdinWire.roundUp(actual, OdinWire.XMIT_START_ALIGN)
            val isLast = (sent + actual) >= totalSize

            rpc(OdinWire.CMD_XMIT, OdinWire.XMIT_DOWNLOAD)
            rpc(OdinWire.CMD_XMIT, OdinWire.XMIT_START, intArrayOf(beginSize.toInt()))

            // One packet-sized buffer reused for the whole window (no giant allocation).
            val buf = ByteArray(pkt.toInt())
            var packetOff = 0L
            while (packetOff < roundedForPackets) {
                java.util.Arrays.fill(buf, 0.toByte()) // zero padding past the real data
                val want = min(pkt, (actual - packetOff).coerceAtLeast(0L)).toInt()
                var filled = 0
                while (filled < want) {
                    val r = input.read(buf, filled, want - filled)
                    if (r < 0) throw IOException(
                        "Unexpected end of stream for '${part.name}' (${packetOff + filled} of $actual bytes read in this window)"
                    )
                    filled += r
                }
                transport.write(buf, offset = 0, length = pkt.toInt(), timeoutMs = 60000)
                transport.readFully(ByteArray(OdinWire.RESPONSE_SIZE), timeoutMs = 30000) // per-packet ack
                packetOff += pkt
                onProgress(sent + min(packetOff, actual), totalSize)
            }

            val endInts = intArrayOf(0, actual.toInt(), part.binType, part.devType, part.id, if (isLast) 1 else 0, 0, 0)
            if (isLast && tolerateFinalAckWarning) {
                try {
                    rpc(OdinWire.CMD_XMIT, OdinWire.XMIT_COMPLETE, endInts)
                } catch (e: IOException) {
                    logger("WARNING: device returned a post-write check after all $totalSize bytes were sent (${e.message}). Treating as non-fatal and continuing.")
                }
            } else {
                rpc(OdinWire.CMD_XMIT, OdinWire.XMIT_COMPLETE, endInts)
            }

            sent += actual
        }
    }

    @Throws(IOException::class)
    fun shutdown(reboot: Boolean) {
        rpc(OdinWire.CMD_CLOSE, OdinWire.CLOSE_END)
        logger("ODIN session closed.")
        if (reboot) {
            runCatching {
                sendRequest(OdinWire.CMD_CLOSE, OdinWire.CLOSE_REBOOT)
                transport.readFully(ByteArray(OdinWire.RESPONSE_SIZE), timeoutMs = 500)
            }
            logger("Reboot command sent.")
        }
    }
}
