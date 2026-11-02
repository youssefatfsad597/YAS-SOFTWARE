package com.edl.flasher.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Wire-format constants for the standard ADB host<->device protocol. */
object AdbProtocolConst {
    const val A_CNXN = 0x4e584e43
    const val A_OPEN = 0x4e45504f
    const val A_OKAY = 0x59414b4f
    const val A_CLSE = 0x45534c43
    const val A_WRTE = 0x45545257
    const val A_AUTH = 0x48545541

    const val AUTH_TOKEN = 1
    const val AUTH_SIGNATURE = 2
    const val AUTH_RSAPUBLICKEY = 3

    const val A_VERSION = 0x01000000
    const val MAX_PAYLOAD = 256 * 1024
}

/** One framed ADB packet: a fixed 24-byte header, optionally followed by [payload]. */
class AdbMessage(
    val command: Int,
    val arg0: Int,
    val arg1: Int,
    val payload: ByteArray = ByteArray(0)
) {
    fun encodeHeader(): ByteArray {
        val buf = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(command)
        buf.putInt(arg0)
        buf.putInt(arg1)
        buf.putInt(payload.size)
        buf.putInt(checksum(payload))
        buf.putInt(command.inv())
        return buf.array()
    }

    companion object {
        fun checksum(data: ByteArray): Int {
            var sum = 0
            for (b in data) sum += (b.toInt() and 0xFF)
            return sum
        }

        /** Returns [command, arg0, arg1, dataLength, dataChecksum, magic]. */
        fun decodeHeader(bytes: ByteArray): IntArray {
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            return intArrayOf(buf.int, buf.int, buf.int, buf.int, buf.int, buf.int)
        }
    }
}
