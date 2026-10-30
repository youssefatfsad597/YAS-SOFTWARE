package com.edl.flasher.protocol

import com.edl.flasher.usb.EdlUsbTransport
import java.io.IOException

/**
 * Minimal Unisoc / Spreadtrum boot-ROM (BROM) + FDL1/FDL2 downloader, used by the
 * T509 / T509N tab.
 *
 * This talks the classic SPRD "BSL" HDLC framing that every public Unisoc flash
 * tool (research-download style) is built on:
 *   - every frame is wrapped in 0x7E ... 0x7E
 *   - inside a frame, any literal 0x7E is sent as 0x7D 0x5E and any literal 0x7D
 *     is sent as 0x7D 0x5D (byte stuffing)
 *   - a frame carries a big-endian 2-byte command/response id, a big-endian 2-byte
 *     payload length, the payload itself, then a 2-byte checksum
 *
 * NOTE: the boot ROM on a freshly-powered-on chip is normally silent until it sees
 * a specific wake pattern on the data lines (what research-download tools send as
 * repeated 0x7E "sync" bytes at a known baud before the handshake). The exact
 * command ids below follow the widely-documented BSL command set, but Unisoc varies
 * small details (ack ids, checksum seed) between SoC generations. If [connect] logs
 * a response that doesn't look like [REP_ACK]/[REP_VER], paste that raw hex back so
 * the ids here can be calibrated against this specific chip -- same iterative process
 * used to tune the Sahara/Firehose byte offsets elsewhere in this app.
 */
class SprdFdlProtocol(
    private val transport: EdlUsbTransport,
    private val log: (String) -> Unit,
    /** Where the boot ROM should place FDL1 / FDL2 in RAM. These are SoC specific (0 is never
     *  valid). Values below come from this device's own address.txt (T509N): FDL1 = 0x5500,
     *  FDL2 = 0x9EFFFE00 -- the FDL2 address happened to already match the old generic default,
     *  which is why only FDL1 changed here. If upload still fails at START_DATA, recalibrate
     *  against a fresh address dump for the exact chip in hand. */
    var fdl1LoadAddr: Int = 0x5500,
    var fdl2LoadAddr: Int = 0x9EFFFE00.toInt()
) {
    companion object {
        private const val HDLC_FLAG = 0x7E
        private const val HDLC_ESCAPE = 0x7D
        private const val HDLC_ESCAPE_MASK = 0x20

        // BSL command ids (host -> device)
        private const val CMD_CONNECT = 0x0000
        private const val CMD_START_DATA = 0x0001
        private const val CMD_MIDST_DATA = 0x0002
        private const val CMD_END_DATA = 0x0003
        private const val CMD_EXEC_DATA = 0x0004
        private const val CMD_NORMAL_RESET = 0x0005
        private const val CMD_ERASE_FLASH = 0x000A
        private const val CMD_CHECK_BAUD = 0x007E
        private const val PART_NAME_BYTES = 72 // 36 UTF-16LE chars

        // BSL response ids (device -> host)
        private const val REP_ACK = 0x0080
        private const val REP_VER = 0x0081
        private const val REP_INVALID_CMD = 0x0082
        private const val REP_UNKNOWN_CMD = 0x0083
        private const val REP_OPERATION_FAILED = 0x0084

        private const val DATA_CHUNK_SIZE = 1024
    }

    /** BROM frames carry CRC16-CCITT; once FDL1 is running the frames carry the 16-bit
     *  ones-complement sum instead. */
    @Volatile private var useCrc16 = true

    private val rxPending = java.io.ByteArrayOutputStream()

    private fun crc16(bytes: ByteArray): Int {
        var crc = 0
        for (b in bytes) {
            crc = crc xor ((b.toInt() and 0xFF) shl 8)
            repeat(8) { crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1 }
            crc = crc and 0xFFFF
        }
        return crc
    }

    private fun frameCrc(bytes: ByteArray): Int = if (useCrc16) crc16(bytes) else checksum(bytes)

    private fun checksum(bytes: ByteArray): Int {
        var sum = 0
        var i = 0
        while (i + 1 < bytes.size) {
            sum += ((bytes[i].toInt() and 0xFF) shl 8) or (bytes[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < bytes.size) sum += (bytes[i].toInt() and 0xFF) shl 8
        while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        return sum.inv() and 0xFFFF
    }

    private fun stuff(raw: ByteArray): ByteArray {
        val out = ArrayList<Byte>(raw.size + 8)
        out.add(HDLC_FLAG.toByte())
        for (b in raw) {
            val v = b.toInt() and 0xFF
            if (v == HDLC_FLAG || v == HDLC_ESCAPE) {
                out.add(HDLC_ESCAPE.toByte())
                out.add((v xor HDLC_ESCAPE_MASK).toByte())
            } else {
                out.add(b)
            }
        }
        out.add(HDLC_FLAG.toByte())
        return out.toByteArray()
    }

    private fun sendFrame(cmdId: Int, payload: ByteArray) {
        val body = ByteArray(4 + payload.size)
        body[0] = (cmdId shr 8).toByte()
        body[1] = cmdId.toByte()
        body[2] = (payload.size shr 8).toByte()
        body[3] = payload.size.toByte()
        payload.copyInto(body, 4)
        val crc = frameCrc(body)
        val full = body + byteArrayOf((crc shr 8).toByte(), crc.toByte())
        transport.write(stuff(full))
    }

    /** Reads one de-stuffed HDLC frame, returning (responseId, payload).
     *  Reads whole USB packets (a 1-byte bulk read overflows the 512-byte packet and loses
     *  data) and keeps any bytes after the closing flag for the next call. */
    private fun recvFrame(timeoutMs: Int = 5000): Pair<Int, ByteArray> {
        val deadline = System.currentTimeMillis() + timeoutMs
        val buf = ByteArray(4096)
        val raw = ArrayList<Byte>(256)
        var started = false
        var done = false

        fun consume(v: Int): Boolean {
            if (!started) { if (v == HDLC_FLAG) started = true; return false }
            if (v == HDLC_FLAG) return raw.isNotEmpty() // empty = back-to-back flags, keep waiting
            raw.add(v.toByte()); return false
        }

        // bytes left over from the previous read first
        val leftover = rxPending.toByteArray(); rxPending.reset()
        var li = 0
        while (li < leftover.size && !done) { done = consume(leftover[li++].toInt() and 0xFF) }
        if (done && li < leftover.size) rxPending.write(leftover, li, leftover.size - li)

        while (!done && System.currentTimeMillis() < deadline) {
            val n = try {
                transport.read(buf, timeoutMs = 500)
            } catch (e: IOException) {
                if (!transport.isConnected) throw e
                continue // plain read timeout: keep waiting until the deadline
            }
            var i = 0
            while (i < n && !done) { done = consume(buf[i++].toInt() and 0xFF) }
            if (done && i < n) rxPending.write(buf, i, n - i)
        }
        if (!done) throw IOException("BROM: no response within ${timeoutMs}ms")

        val unstuffed = ArrayList<Byte>(raw.size)
        var i = 0
        while (i < raw.size) {
            val v = raw[i].toInt() and 0xFF
            if (v == HDLC_ESCAPE && i + 1 < raw.size) {
                unstuffed.add(((raw[i + 1].toInt() and 0xFF) xor HDLC_ESCAPE_MASK).toByte())
                i += 2
            } else {
                unstuffed.add(raw[i]); i += 1
            }
        }
        if (unstuffed.size < 6) throw IOException("BROM: short/garbled response (${unstuffed.size} bytes)")
        val id = ((unstuffed[0].toInt() and 0xFF) shl 8) or (unstuffed[1].toInt() and 0xFF)
        val len = ((unstuffed[2].toInt() and 0xFF) shl 8) or (unstuffed[3].toInt() and 0xFF)
        if (unstuffed.size < 6 + len) throw IOException("BROM: truncated response 0x${id.toString(16)} (len=$len, got ${unstuffed.size - 6})")
        val payload = ByteArray(len) { unstuffed[4 + it] }
        val bodyForCrc = unstuffed.subList(0, 4 + len).toByteArray()
        val gotCrc = ((unstuffed[4 + len].toInt() and 0xFF) shl 8) or (unstuffed[5 + len].toInt() and 0xFF)
        val wantCrc = frameCrc(bodyForCrc)
        if (gotCrc != wantCrc) {
            throw IOException(
                "BROM: checksum mismatch on response 0x${id.toString(16)} " +
                    "(got 0x${gotCrc.toString(16)}, expected 0x${wantCrc.toString(16)}, mode=${if (useCrc16) "CRC16" else "SUM"})"
            )
        }
        return id to payload
    }

    private fun expectAck(cmdId: Int, payload: ByteArray = ByteArray(0), timeoutMs: Int = 5000) {
        sendFrame(cmdId, payload)
        val (id, resp) = recvFrame(timeoutMs)
        when (id) {
            REP_ACK -> return
            REP_INVALID_CMD -> throw IOException("BROM: device reported INVALID_CMD for 0x${cmdId.toString(16)}")
            REP_UNKNOWN_CMD -> throw IOException("BROM: device reported UNKNOWN_CMD for 0x${cmdId.toString(16)}")
            REP_OPERATION_FAILED -> throw IOException("BROM: operation failed for 0x${cmdId.toString(16)}")
            else -> throw IOException(
                "BROM: unexpected response id 0x${id.toString(16)} (${resp.size} bytes) for cmd 0x${cmdId.toString(16)} " +
                    "-- paste this hex back to calibrate: ${resp.joinToString("") { "%02x".format(it) }}"
            )
        }
    }

    /** Wakes the boot ROM and performs the initial BSL handshake. Call right after
     *  opening the USB interface on a freshly (re)connected BROM-mode device. */
    @Volatile var isConnected: Boolean = false
        private set

    /** Reads and discards whatever is already sitting in the USB IN buffer, with no
     *  assumptions about its content. A half-finished previous handshake (one sync
     *  byte answered, the rest never read) is the single most common reason a retry
     *  gets an out-of-sync reply -- this clears that leftover state so the next
     *  attempt starts from a clean slate. A read timeout (nothing left to drain)
     *  ends the loop; it is the normal/expected way to detect "buffer is empty". */
    private fun drainStale() {
        val scratch = ByteArray(256)
        repeat(32) {
            try {
                val n = transport.read(scratch, timeoutMs = 150)
                if (n <= 0) return
            } catch (e: IOException) {
                return
            }
        }
    }

    /** Forgets any previous handshake state. Call this before re-opening the USB
     *  interface after a disconnect, same as SaharaProtocol.resetSession(). */
    fun resetSession() {
        isConnected = false
    }

    /** Performs the BSL handshake, retrying up to [maxAttempts] times. Each retry
     *  re-drains the buffer first (see [drainStale]) instead of assuming the previous
     *  attempt left the link in a known state -- this is the actual, verifiable fix
     *  for a handshake that got out of sync, as opposed to any specific byte sequence
     *  this app cannot guarantee for every Unisoc baseband. */
    /** Wakes the ROM/FDL: one 0x7E, answered by a REP_VER frame (version string). */
    private fun syncBaud(timeoutMs: Int = 1500) {
        rxPending.reset()
        transport.write(byteArrayOf(HDLC_FLAG.toByte()))
        val (id, resp) = recvFrame(timeoutMs)
        if (id != REP_VER) throw IOException("BROM: sync got 0x${id.toString(16)} instead of VER (${resp.size} bytes)")
        log("BROM: sync OK (${String(resp, Charsets.US_ASCII).trim { it.code < 32 }}).")
    }

    fun connect(maxAttempts: Int = 3) {
        useCrc16 = true
        drainStale()
        var lastError: Exception? = null
        for (attempt in 1..maxAttempts) {
            try {
                log("BROM: sync attempt $attempt/$maxAttempts...")
                syncBaud()
                log("BROM: handshake (CONNECT)...")
                expectAck(CMD_CONNECT)
                log("BROM: handshake OK.")
                isConnected = true
                return
            } catch (e: Exception) {
                lastError = e
                log("BROM: attempt $attempt failed (${e.message}).")
                if (attempt < maxAttempts) { log("BROM: draining stale bytes and retrying..."); drainStale() }
            }
        }
        isConnected = false
        throw IOException("BROM handshake failed after $maxAttempts attempt(s): ${lastError?.message}")
    }

    private fun uploadStage(label: String, bytes: ByteArray, loadAddr: Int) {
        log("BROM: uploading $label (${bytes.size} bytes) to 0x${loadAddr.toUInt().toString(16)}...")
        val startPayload = ByteArray(8)
        startPayload[0] = (loadAddr shr 24).toByte()
        startPayload[1] = (loadAddr shr 16).toByte()
        startPayload[2] = (loadAddr shr 8).toByte()
        startPayload[3] = loadAddr.toByte()
        startPayload[4] = (bytes.size shr 24).toByte()
        startPayload[5] = (bytes.size shr 16).toByte()
        startPayload[6] = (bytes.size shr 8).toByte()
        startPayload[7] = bytes.size.toByte()
        expectAck(CMD_START_DATA, startPayload)

        var sent = 0
        while (sent < bytes.size) {
            val chunk = bytes.copyOfRange(sent, minOf(sent + DATA_CHUNK_SIZE, bytes.size))
            expectAck(CMD_MIDST_DATA, chunk, timeoutMs = 10000)
            sent += chunk.size
            if (sent % (DATA_CHUNK_SIZE * 256) == 0) log("BROM: $label ${sent / 1024}KB / ${bytes.size / 1024}KB")
        }
        expectAck(CMD_END_DATA)
        expectAck(CMD_EXEC_DATA)
        log("BROM: $label uploaded and executing.")
    }

    /** Uploads FDL1, then re-syncs with it: FDL1 is a new program with its own link state
     *  (checksum framing instead of CRC16) and must be CONNECTed again before FDL2. */
    fun uploadFdl1(bytes: ByteArray) {
        uploadStage("FDL1", bytes, fdl1LoadAddr)
        Thread.sleep(500)
        useCrc16 = false
        drainStale()
        var last: Exception? = null
        repeat(3) {
            try {
                syncBaud(); expectAck(CMD_CONNECT); log("BROM: FDL1 handshake OK."); return
            } catch (e: Exception) { last = e; drainStale() }
        }
        throw IOException("FDL1 did not answer after exec: ${last?.message}")
    }

    fun uploadFdl2(bytes: ByteArray) = uploadStage("FDL2", bytes, fdl2LoadAddr)

    /** Erases one partition by name once FDL2 is running (FDL2 understands
     *  partition names the same way fastboot/Odin do -- "userdata", "frp", etc). */
    fun erasePartitionByName(name: String, timeoutMs: Int = 60000) {
        log("BROM: erasing '$name'...")
        // FDL2 expects the partition name as UTF-16LE in a fixed 72-byte field.
        val nameBytes = ByteArray(PART_NAME_BYTES)
        name.toByteArray(Charsets.UTF_16LE).copyInto(nameBytes, 0, 0, minOf(PART_NAME_BYTES - 2, name.length * 2))
        // "userdata" can be tens of GB on a modern device; 30s was tuned for small
        // partitions like frp/persist and was timing out (false failure, not a real
        // erase failure) on bigger ones. Bumped to 60s here and callers can still pass
        // a larger value explicitly for very large storage.
        expectAck(CMD_ERASE_FLASH, nameBytes, timeoutMs = timeoutMs)
        log("BROM: '$name' erased.")
    }

    fun reset() {
        log("BROM: sending reset...")
        runCatching { sendFrame(CMD_NORMAL_RESET, ByteArray(0)) }
    }
}
