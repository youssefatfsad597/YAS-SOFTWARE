package com.edl.flasher.protocol

import com.edl.flasher.usb.EdlUsbTransport
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SaharaProtocol(
    private val transport: EdlUsbTransport,
    private val logger: (String) -> Unit
) {
    companion object {
        const val CMD_HELLO_REQ = 0x01
        const val CMD_HELLO_RSP = 0x02
        const val CMD_READ_DATA = 0x03
        const val CMD_END_IMAGE_TX = 0x04
        const val CMD_DONE_REQ = 0x05
        const val CMD_DONE_RSP = 0x06
        const val CMD_CMD_READY = 0x0B
        const val CMD_SWITCH_MODE = 0x0C
        const val CMD_EXECUTE_REQ = 0x0D
        const val CMD_EXECUTE_RSP = 0x0E
        const val CMD_EXECUTE_DATA = 0x0F
        const val CMD_READ_DATA_64 = 0x12

        const val MODE_IMAGE_TX_PENDING = 0x00
        const val MODE_COMMAND = 0x03

        const val EXEC_CMD_SERIAL_NUM_READ = 0x01
        const val EXEC_CMD_MSM_HW_ID_READ = 0x02
        const val EXEC_CMD_OEM_PK_HASH_READ = 0x03
        const val EXEC_CMD_GET_SBL_SW_VERSION = 0x07
        const val EXEC_CMD_READ_CHIP_ID_V3 = 0x0A

        const val STATUS_SUCCESS = 0x00
    }

    private data class HelloPacket(
        val version: Int,
        val versionMin: Int,
        val maxCmdPacketSize: Int,
        val currentMode: Int
    )

    private var pendingHelloPacket: HelloPacket? = null

    fun resetSession() {
        pendingHelloPacket = null
    }

    @Throws(IOException::class)
    fun probeDeviceInfo(): SaharaDeviceInfo {
        val hello = pendingHelloPacket ?: readHelloReq()
        pendingHelloPacket = null

        logger("Entering Sahara Command Mode (0x03) [Protocol v${hello.version}]...")
        sendHelloRsp(hello.version, hello.versionMin, MODE_COMMAND)

        val rxBuf = ByteArray(1024)
        val readyLen = transport.read(rxBuf, timeoutMs = 10000)
        val readyBuf = ByteBuffer.wrap(rxBuf, 0, readyLen).order(ByteOrder.LITTLE_ENDIAN)
        val cmdId = readyBuf.int

        if (cmdId != CMD_CMD_READY) {
            throw IOException("Expected Sahara CMD_CMD_READY (0x0B), got 0x${cmdId.toString(16)}")
        }
        logger("Target is in Sahara Command Mode (CMD_READY received).")

        val serialBytes = executeClientCommand(EXEC_CMD_SERIAL_NUM_READ)
        val serialNum = if (serialBytes != null && serialBytes.size >= 4) {
            ByteBuffer.wrap(serialBytes).order(ByteOrder.LITTLE_ENDIAN).int.toUInt()
        } else 0u

        var msmId = 0u
        var oemId: UShort = 0u
        var modelId: UShort = 0u

        val hwIdBytes = executeClientCommand(EXEC_CMD_MSM_HW_ID_READ)
        if (hwIdBytes != null && hwIdBytes.size >= 8) {
            val hwBuf = ByteBuffer.wrap(hwIdBytes).order(ByteOrder.LITTLE_ENDIAN)
            msmId = hwBuf.int.toUInt()
            oemId = hwBuf.short.toUShort()
            modelId = hwBuf.short.toUShort()
        } else {
            val v3Bytes = executeClientCommand(EXEC_CMD_READ_CHIP_ID_V3)
            if (v3Bytes != null && v3Bytes.size >= 44) {
                val v3Buf = ByteBuffer.wrap(v3Bytes).order(ByteOrder.LITTLE_ENDIAN)
                msmId = v3Buf.getInt(36).toUInt()
                oemId = v3Buf.getShort(40).toUShort()
                modelId = v3Buf.getShort(42).toUShort()
            }
        }

        val fullHwIdHex = String.format("0x%08X%04X%04X", msmId.toInt(), oemId.toInt(), modelId.toInt())

        val pkHashBytes = executeClientCommand(EXEC_CMD_OEM_PK_HASH_READ)
        val pkHashHex = if (pkHashBytes != null) {
            SaharaDeviceInfo.normalizePkHash(pkHashBytes)
        } else {
            "Unavailable"
        }

        val sblBytes = executeClientCommand(EXEC_CMD_GET_SBL_SW_VERSION)
        val sblVersion = if (sblBytes != null && sblBytes.size >= 4) {
            ByteBuffer.wrap(sblBytes).order(ByteOrder.LITTLE_ENDIAN).int.toUInt()
        } else null

        val info = SaharaDeviceInfo(
            saharaVersion = hello.version,
            serialNumber = serialNum,
            msmHwId = msmId,
            oemId = oemId,
            modelId = modelId,
            fullHwIdHex = fullHwIdHex,
            pkHashHex = pkHashHex,
            sblVersion = sblVersion
        )

        logger("Serial Number : ${info.serialHex}")
        logger("MSM HWID      : ${info.msmIdHex} (${info.socName})")
        logger("OEM / Model   : ${info.oemName} | Model 0x${String.format("%04X", info.modelId.toInt())}")
        logger("OEM PK_HASH   : ${info.pkHashHex}")

        switchMode(MODE_IMAGE_TX_PENDING)
        pendingHelloPacket = readHelloReq()
        logger("Target switched to IMAGE_TX_PENDING and is ready for Firehose upload.")

        return info
    }

    @Throws(IOException::class)
    fun readInfoAndUploadLoader(
        loaderBytes: ByteArray,
        onDeviceInfoRead: (SaharaDeviceInfo) -> Unit,
        onProgress: (Long, Long) -> Unit
    ) {
        if (pendingHelloPacket == null) {
            val info = runCatching { probeDeviceInfo() }.getOrNull()
            if (info != null) onDeviceInfoRead(info)
        }
        uploadLoader(loaderBytes, onProgress)
    }

    @Throws(IOException::class)
    fun uploadLoader(loaderBytes: ByteArray, onProgress: (Long, Long) -> Unit) {
        val hello = pendingHelloPacket ?: readHelloReq()
        pendingHelloPacket = null

        sendHelloRsp(hello.version, hello.versionMin, MODE_IMAGE_TX_PENDING)
        logger("Streaming Firehose loader (${loaderBytes.size} bytes)...")

        val rxBuf = ByteArray(4096)
        var totalUploaded = 0L
        val totalSize = loaderBytes.size.toLong()

        while (true) {
            val readLen = transport.read(rxBuf, timeoutMs = 10000)
            val pkt = ByteBuffer.wrap(rxBuf, 0, readLen).order(ByteOrder.LITTLE_ENDIAN)
            val cmd = pkt.int
            val len = pkt.int

            when (cmd) {
                CMD_READ_DATA -> {
                    pkt.int
                    val dataOffset = pkt.int.toLong() and 0xFFFFFFFFL
                    val dataLength = pkt.int
                    sendLoaderSlice(loaderBytes, dataOffset.toInt(), dataLength)
                    totalUploaded += dataLength
                    onProgress(totalUploaded.coerceAtMost(totalSize), totalSize)
                }

                CMD_READ_DATA_64 -> {
                    pkt.long
                    val dataOffset = pkt.long
                    val dataLength = pkt.long
                    sendLoaderSlice(loaderBytes, dataOffset.toInt(), dataLength.toInt())
                    totalUploaded += dataLength
                    onProgress(totalUploaded.coerceAtMost(totalSize), totalSize)
                }

                CMD_END_IMAGE_TX -> {
                    val imageId = pkt.int
                    val status = pkt.int
                    if (status != STATUS_SUCCESS) {
                        throw IOException("Sahara END_IMAGE_TX error: status=0x${status.toString(16)}, imageId=$imageId")
                    }
                    logger("Sahara END_IMAGE_TX -> SUCCESS. Sending DONE_REQ...")
                    sendDoneRequest()
                    return
                }

                else -> throw IOException("Unexpected Sahara command: 0x${cmd.toString(16)} (len=$len)")
            }
        }
    }

    private fun executeClientCommand(clientCmd: Int): ByteArray? {
        val req = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(CMD_EXECUTE_REQ)
            putInt(12)
            putInt(clientCmd)
        }
        transport.write(req.array())

        val rxBuf = ByteArray(1024)
        val rspLen = runCatching { transport.read(rxBuf, timeoutMs = 5000) }.getOrDefault(-1)
        if (rspLen < 12) return null

        val rspBuf = ByteBuffer.wrap(rxBuf, 0, rspLen).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = rspBuf.int
        val pktLen = rspBuf.int
        if (cmd != CMD_EXECUTE_RSP || pktLen < 16) return null

        val echoedClientCmd = rspBuf.int
        val dataLength = rspBuf.int
        if (echoedClientCmd != clientCmd || dataLength <= 0 || dataLength > 4096) return null

        val dataTrigger = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(CMD_EXECUTE_DATA)
            putInt(12)
            putInt(clientCmd)
        }
        transport.write(dataTrigger.array())

        val payload = ByteArray(dataLength)
        transport.readFully(payload, 0, dataLength, timeoutMs = 10000)
        return payload
    }

    private fun switchMode(targetMode: Int) {
        val pkt = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(CMD_SWITCH_MODE)
            putInt(12)
            putInt(targetMode)
        }
        transport.write(pkt.array())
    }

    private fun readHelloReq(): HelloPacket {
        val rxBuf = ByteArray(4096)
        val len = transport.read(rxBuf, timeoutMs = 10000)
        val header = ByteBuffer.wrap(rxBuf, 0, len).order(ByteOrder.LITTLE_ENDIAN)
        val cmdId = header.int
        val packetLen = header.int
        if (cmdId != CMD_HELLO_REQ || packetLen != 48) {
            throw IOException("Expected Sahara HELLO_REQ (0x01, 48B), got cmd=0x${cmdId.toString(16)}, len=$packetLen.")
        }
        return HelloPacket(
            version = header.int,
            versionMin = header.int,
            maxCmdPacketSize = header.int,
            currentMode = header.int
        )
    }

    private fun sendHelloRsp(version: Int, versionMin: Int, mode: Int) {
        val helloRsp = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(CMD_HELLO_RSP)
            putInt(48)
            putInt(version)
            putInt(versionMin)
            putInt(STATUS_SUCCESS)
            putInt(mode)
            repeat(6) { putInt(0) }
        }
        transport.write(helloRsp.array())
    }

    /** Sahara RESET_REQ (0x07): asks the boot ROM to restart the handshake after a failed transfer. */
    fun rearmAfterFailure() {
        pendingHelloPacket = null
        runCatching {
            transport.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).apply { putInt(0x07); putInt(8) }.array(), timeoutMs = 3000)
            // best effort: drain whatever the ROM still had queued, then wait for a fresh HELLO_REQ
            runCatching { transport.read(ByteArray(4096), timeoutMs = 300) }
            pendingHelloPacket = readHelloReq()
        }.onFailure { logger("Sahara re-arm: no new HELLO_REQ (${it.message}).") }
    }

    private fun sendLoaderSlice(loaderBytes: ByteArray, offset: Int, length: Int) {
        try {
            sendLoaderSliceRaw(loaderBytes, offset, length)
        } catch (e: IOException) {
            throw IOException("Sahara: sending loader bytes offset=$offset len=$length failed (${e.message})")
        }
    }

    private fun sendLoaderSliceRaw(loaderBytes: ByteArray, offset: Int, length: Int) {
        if (offset < 0 || offset + length > loaderBytes.size) {
            val padded = ByteArray(length)
            val available = (loaderBytes.size - offset).coerceAtLeast(0)
            if (available > 0) System.arraycopy(loaderBytes, offset, padded, 0, available)
            transport.write(padded)
        } else {
            transport.write(loaderBytes, offset, length)
        }
    }

    private fun sendDoneRequest() {
        val doneReq = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(CMD_DONE_REQ)
            putInt(8)
        }
        transport.write(doneReq.array())

        val rxBuf = ByteArray(64)
        val len = transport.read(rxBuf, timeoutMs = 10000)
        val pkt = ByteBuffer.wrap(rxBuf, 0, len).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = pkt.int
        if (cmd != CMD_DONE_RSP) {
            throw IOException("Expected Sahara DONE_RSP (0x06), got 0x${cmd.toString(16)}")
        }
        logger("Sahara DONE_RSP received. Starting Firehose...")
    }
}
