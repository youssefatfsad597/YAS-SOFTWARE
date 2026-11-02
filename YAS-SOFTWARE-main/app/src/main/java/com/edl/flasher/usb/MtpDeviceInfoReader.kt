package com.edl.flasher.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Reads the standard MTP "GetDeviceInfo" dataset (manufacturer, model, firmware/device
 *  version, serial) and the storage capacity (GetStorageIDs / GetStorageInfo) straight over USB.
 *  Read-only: nothing is ever written to the device. */
object MtpDeviceInfoReader {

    data class MtpInfo(
        val manufacturer: String,
        val model: String,
        val deviceVersion: String,
        val serial: String,
        val extensions: String
    )

    /** One MTP storage (internal storage / SD card). Sizes are in bytes, -1 = unknown. */
    data class StorageEntry(
        val description: String,
        val totalBytes: Long,
        val freeBytes: Long
    ) {
        val usedBytes: Long get() = if (totalBytes >= 0 && freeBytes >= 0) (totalBytes - freeBytes).coerceAtLeast(0) else -1
    }

    private const val OP_GET_DEVICE_INFO = 0x1001
    private const val OP_OPEN_SESSION = 0x1002
    private const val OP_CLOSE_SESSION = 0x1003
    private const val OP_GET_STORAGE_IDS = 0x1004
    private const val OP_GET_STORAGE_INFO = 0x1005
    private const val RESP_OK = 0x2001
    private const val RESP_SESSION_ALREADY_OPEN = 0x201E

    private class Port(
        val conn: UsbDeviceConnection,
        val intf: UsbInterface,
        val epIn: UsbEndpoint,
        val epOut: UsbEndpoint
    ) {
        var tid = 0
        // Bytes that arrived glued behind a finished container (belong to the next one).
        var pending = ByteArray(0)
    }

    private class Reply(val code: Int, val data: ByteArray?)

    // -------------------------------------------------------------------------------------
    //  Public API
    // -------------------------------------------------------------------------------------

    /** Reads GetDeviceInfo. Retries a few times because the phone's MTP responder is often not
     *  ready right after the USB permission was granted / the cable was plugged: that was why
     *  the very first read came back empty or half-filled. */
    fun read(usbManager: UsbManager, dev: UsbDevice, attempts: Int = 4): MtpInfo? {
        if (!usbManager.hasPermission(dev)) return null
        repeat(attempts) { n ->
            val info = withPort(usbManager, dev) { port -> getDeviceInfo(port) }
            if (info != null && (info.model.isNotBlank() || info.manufacturer.isNotBlank())) return info
            Thread.sleep(350L + n * 350L)
        }
        return null
    }

    /** Reads the storage list (capacity + free space). Empty list = nothing could be read. */
    fun readStorage(usbManager: UsbManager, dev: UsbDevice, attempts: Int = 3): List<StorageEntry> {
        if (!usbManager.hasPermission(dev)) return emptyList()
        repeat(attempts) { n ->
            val list = withPort(usbManager, dev) { port -> getStorages(port) }.orEmpty()
            if (list.isNotEmpty()) return list
            Thread.sleep(350L + n * 350L)
        }
        return emptyList()
    }

    fun formatBytes(bytes: Long): String {
        if (bytes < 0) return "-"
        val gb = bytes / (1024.0 * 1024.0 * 1024.0)
        val mb = bytes / (1024.0 * 1024.0)
        return when {
            gb >= 1.0 -> String.format(java.util.Locale.US, "%.2f GB", gb)
            mb >= 1.0 -> String.format(java.util.Locale.US, "%.1f MB", mb)
            else -> String.format(java.util.Locale.US, "%d KB", bytes / 1024)
        }
    }

    /** "Storage Total: ..." style lines (one block per storage) for the info panel. */
    fun describeStorage(list: List<StorageEntry>): String {
        val sb = StringBuilder()
        list.forEachIndexed { i, s ->
            val suffix = if (list.size > 1) " ${i + 1}" else ""
            if (s.description.isNotBlank()) sb.appendLine("Storage Name$suffix: ${s.description}")
            sb.appendLine("Storage Total$suffix: ${formatBytes(s.totalBytes)}")
            sb.appendLine("Storage Free$suffix: ${formatBytes(s.freeBytes)}")
            sb.appendLine("Storage Used$suffix: ${formatBytes(s.usedBytes)}")
        }
        return sb.toString().trimEnd()
    }

    // -------------------------------------------------------------------------------------
    //  Port handling
    // -------------------------------------------------------------------------------------

    private fun <T> withPort(usbManager: UsbManager, dev: UsbDevice, block: (Port) -> T?): T? {
        for (i in 0 until dev.interfaceCount) {
            val intf = dev.getInterface(i)
            val looksMtp = intf.interfaceClass == 6 ||
                (intf.interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC && intf.interfaceSubclass == 0xFF)
            if (!looksMtp) continue
            var epIn: UsbEndpoint? = null
            var epOut: UsbEndpoint? = null
            for (k in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(k)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (ep.direction == UsbConstants.USB_DIR_IN) epIn = ep else epOut = ep
            }
            if (epIn == null || epOut == null) continue
            val conn = usbManager.openDevice(dev) ?: continue
            try {
                if (!conn.claimInterface(intf, true)) continue
                val port = Port(conn, intf, epIn, epOut)
                // Give the responder a moment, then throw away anything left over from an
                // earlier aborted transfer so replies can never be shifted by one container.
                Thread.sleep(120)
                drain(port)
                val result = block(port)
                if (result != null) return result
            } finally {
                runCatching { conn.releaseInterface(intf) }
                conn.close()
            }
        }
        return null
    }

    private fun drain(port: Port) {
        val buf = ByteArray(16384)
        var guard = 0
        while (guard++ < 8 && port.conn.bulkTransfer(port.epIn, buf, buf.size, 100) > 0) { /* discard */ }
    }

    // -------------------------------------------------------------------------------------
    //  PTP/MTP transaction
    // -------------------------------------------------------------------------------------

    private fun transact(port: Port, code: Int, vararg params: Int): Reply? {
        port.tid++
        val len = 12 + params.size * 4
        val cmd = ByteBuffer.allocate(len).order(ByteOrder.LITTLE_ENDIAN)
        cmd.putInt(len)
        cmd.putShort(1)                 // container type: command
        cmd.putShort(code.toShort())
        cmd.putInt(port.tid)
        params.forEach { cmd.putInt(it) }
        if (port.conn.bulkTransfer(port.epOut, cmd.array(), len, 3000) < 0) return null

        var data: ByteArray? = null
        val buf = ByteArray(16384)
        var rounds = 0
        while (rounds++ < 4) {
            val first = readContainer(port, buf) ?: return null
            when (first.first) {
                2 -> data = first.second          // data container
                3 -> return Reply(first.third, data) // response container
                else -> return null
            }
        }
        return null
    }

    /** Returns (type, payload, code) for one complete container, or null on timeout. */
    private fun readContainer(port: Port, buf: ByteArray): Triple<Int, ByteArray, Int>? {
        val out = ByteArrayOutputStream()
        if (port.pending.isNotEmpty()) {
            out.write(port.pending)
            port.pending = ByteArray(0)
        }
        var expected = -1
        var guard = 0
        while (guard++ < 256) {
            if (expected < 0 && out.size() >= 4) {
                expected = ByteBuffer.wrap(out.toByteArray(), 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
            }
            if (expected in 12..out.size()) break
            val n = port.conn.bulkTransfer(port.epIn, buf, buf.size, 3000)
            if (n < 0) {
                if (out.size() == 0) return null
                break
            }
            if (n == 0) continue // zero-length packet that ends a transfer: not an error
            out.write(buf, 0, n)
        }
        val all = out.toByteArray()
        if (all.size < 12) return null
        val bb = ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN)
        bb.position(4)
        val type = bb.short.toInt()
        val code = bb.short.toInt() and 0xFFFF
        val end = if (expected in 12..all.size) expected else all.size
        if (all.size > end) port.pending = all.copyOfRange(end, all.size)
        return Triple(type, all.copyOfRange(12, end), code)
    }

    // -------------------------------------------------------------------------------------
    //  GetDeviceInfo
    // -------------------------------------------------------------------------------------

    private fun getDeviceInfo(port: Port): MtpInfo? {
        val reply = transact(port, OP_GET_DEVICE_INFO) ?: return null
        if (reply.code != RESP_OK) return null
        val payload = reply.data ?: return null
        val bb = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        return try {
            bb.short                       // StandardVersion
            bb.int                         // VendorExtensionID
            bb.short                       // VendorExtensionVersion
            val ext = readString(bb)       // VendorExtensionDesc
            bb.short                       // FunctionalMode
            repeat(5) { skipArray16(bb) }  // operations, events, props, capture, playback formats
            val manufacturer = readString(bb)
            val model = readString(bb)
            val version = readString(bb)
            val serial = readString(bb)
            MtpInfo(manufacturer, model, version, serial, ext)
        } catch (e: Exception) {
            null
        }
    }

    // -------------------------------------------------------------------------------------
    //  Storage (needs an MTP session)
    // -------------------------------------------------------------------------------------

    private fun getStorages(port: Port): List<StorageEntry>? {
        val open = transact(port, OP_OPEN_SESSION, 1) ?: return null
        if (open.code != RESP_OK && open.code != RESP_SESSION_ALREADY_OPEN) return null
        try {
            val ids = transact(port, OP_GET_STORAGE_IDS) ?: return null
            if (ids.code != RESP_OK) return null
            val idBuf = ByteBuffer.wrap(ids.data ?: return null).order(ByteOrder.LITTLE_ENDIAN)
            val count = idBuf.int
            if (count < 0 || count * 4 > idBuf.remaining()) return null
            val result = ArrayList<StorageEntry>()
            repeat(count) {
                val id = idBuf.int
                val info = transact(port, OP_GET_STORAGE_INFO, id) ?: return@repeat
                if (info.code != RESP_OK) return@repeat
                val d = ByteBuffer.wrap(info.data ?: return@repeat).order(ByteOrder.LITTLE_ENDIAN)
                runCatching {
                    d.short                        // StorageType
                    d.short                        // FilesystemType
                    d.short                        // AccessCapability
                    val total = d.long             // MaxCapacity
                    val free = d.long              // FreeSpaceInBytes
                    d.int                          // FreeSpaceInObjects
                    val desc = readString(d)
                    val label = readString(d)
                    val name = desc.ifBlank { label }
                    // 0xFFFFFFFFFFFFFFFF = "not reported" -> shows as negative in a signed Long.
                    result.add(StorageEntry(name, if (total < 0) -1 else total, if (free < 0) -1 else free))
                }
            }
            return result
        } finally {
            runCatching { transact(port, OP_CLOSE_SESSION) }
        }
    }

    // -------------------------------------------------------------------------------------
    //  Helpers
    // -------------------------------------------------------------------------------------

    private fun skipArray16(bb: ByteBuffer) {
        val count = bb.int
        if (count < 0 || count * 2 > bb.remaining()) throw IllegalStateException("bad array")
        bb.position(bb.position() + count * 2)
    }

    private fun readString(bb: ByteBuffer): String {
        val chars = bb.get().toInt() and 0xFF
        if (chars == 0) return ""
        val sb = StringBuilder()
        repeat(chars) { sb.append(bb.short.toInt().toChar()) }
        return sb.toString().trimEnd('\u0000').trim()
    }
}
