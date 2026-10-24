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
    private val log: (String) -> Unit
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
        private const val CMD_ERASE_FLASH = 0x0014

        // BSL response ids (device -> host)
        private const val REP_ACK = 0x0080
        private const val REP_VER = 0x0081
        private const val REP_INVALID_CMD = 0x0082
        private const val REP_UNKNOWN_CMD = 0x0083
        private const val REP_OPERATION_FAILED = 0x0084

        private const val FDL1_LOAD_ADDR = 0x00000000
        private const val FDL2_LOAD_ADDR = 0x00000000
        private const val DATA_CHUNK_SIZE = 4096
    }

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
        val crc = checksum(body)
        val full = body + byteArrayOf((crc shr 8).toByte(), crc.toByte())
        transport.write(stuff(full))
    }

    /** Reads one de-stuffed HDLC frame, returning (responseId, payload). */
    private fun recvFrame(timeoutMs: Int = 5000): Pair<Int, ByteArray> {
        val raw = ArrayList<Byte>(256)
        val buf = ByteArray(1)
        var started = false
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val n = transport.read(buf, timeoutMs = 500)
            if (n <= 0) continue
            val v = buf[0].toInt() and 0xFF
            if (!started) {
                if (v == HDLC_FLAG) started = true
                continue
            }
            if (v == HDLC_FLAG) {
                if (raw.isEmpty()) continue // leading flag of this frame
                break
            }
            raw.add(buf[0])
        }
        val unstuffed = ArrayList<Byte>(raw.size)
        var i = 0
        while (i < raw.size) {
            val v = raw[i].toInt() and 0xFF
            if (v == HDLC_ESCAPE && i + 1 < raw.size) {
                unstuffed.add(((raw[i + 1].toInt() and 0xFF) xor HDLC_ESCAPE_MASK).toByte())
                i += 2
            } else {
                unstuffed.add(raw[i])
                i += 1
            }
        }
        if (unstuffed.size < 4) throw IOException("BROM: short/garbled response (${unstuffed.size} bytes)")
        val id = ((unstuffed[0].toInt() and 0xFF) shl 8) or (unstuffed[1].toInt() and 0xFF)
        val len = ((unstuffed[2].toInt() and 0xFF) shl 8) or (unstuffed[3].toInt() and 0xFF)
        val payload = ByteArray(len.coerceAtMost((unstuffed.size - 4).coerceAtLeast(0)))
        for (j in payload.indices) payload[j] = unstuffed[4 + j]
        return id to payload
    }

    private fun expectAck(cmdId: Int, payload: ByteArray = ByteArray(0), timeoutMs: Int = 5000) {
        sendFrame(cmdId, payload)
        val (id, resp) = recvFrame(timeoutMs)
        when (id) {
            REP_ACK, REP_VER -> return
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
    fun connect(maxAttempts: Int = 3) {
        drainStale()
        var lastError: Exception? = null
        for (attempt in 1..maxAttempts) {
            try {
                log("BROM: sync attempt $attempt/$maxAttempts...")
                // Classic SPRD wake-up: a burst of lone 0x7E bytes until the chip answers.
                repeat(8) { transport.write(byteArrayOf(HDLC_FLAG.toByte())) }
                log("BROM: handshake (CONNECT)...")
                expectAck(CMD_CONNECT)
                log("BROM: handshake OK.")
                isConnected = true
                return
            } catch (e: Exception) {
                lastError = e
                log("BROM: attempt $attempt failed (${e.message}).")
                if (attempt < maxAttempts) {
                    log("BROM: draining stale bytes and retrying...")
                    drainStale()
                }
            }
        }
        isConnected = false
        throw IOException("BROM handshake failed after $maxAttempts attempt(s): ${lastError?.message}")
    }

    private fun uploadStage(label: String, bytes: ByteArray, loadAddr: Int) {
        log("BROM: uploading $label (${bytes.size} bytes)...")
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
            if (sent % (DATA_CHUNK_SIZE * 32) == 0) log("BROM: $label ${sent / 1024}KB / ${bytes.size / 1024}KB")
        }
        expectAck(CMD_END_DATA)
        expectAck(CMD_EXEC_DATA)
        log("BROM: $label uploaded and executing.")
    }

    fun uploadFdl1(bytes: ByteArray) = uploadStage("FDL1", bytes, FDL1_LOAD_ADDR)

    fun uploadFdl2(bytes: ByteArray) = uploadStage("FDL2", bytes, FDL2_LOAD_ADDR)

    /** Erases one partition by name once FDL2 is running (FDL2 understands
     *  partition names the same way fastboot/Odin do -- "userdata", "frp", etc). */
    fun erasePartitionByName(name: String) {
        log("BROM: erasing '$name'...")
        val nameBytes = name.toByteArray(Charsets.US_ASCII)
        expectAck(CMD_ERASE_FLASH, nameBytes, timeoutMs = 30000)
        log("BROM: '$name' erased.")
    }

    fun reset() {
        log("BROM: sending reset...")
        runCatching { sendFrame(CMD_NORMAL_RESET, ByteArray(0)) }
    }
}
