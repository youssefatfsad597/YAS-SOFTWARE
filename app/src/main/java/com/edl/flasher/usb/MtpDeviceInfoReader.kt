package com.edl.flasher.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbManager
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Reads the standard MTP "GetDeviceInfo" dataset (manufacturer, model, firmware/device
 *  version, serial) straight over USB. Read-only: no session is opened and nothing is written. */
object MtpDeviceInfoReader {

    data class MtpInfo(
        val manufacturer: String,
        val model: String,
        val deviceVersion: String,
        val serial: String,
        val extensions: String
    )

    fun read(usbManager: UsbManager, dev: UsbDevice): MtpInfo? {
        if (!usbManager.hasPermission(dev)) return null
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
                val info = getDeviceInfo(conn, epIn, epOut)
                if (info != null) return info
            } finally {
                runCatching { conn.releaseInterface(intf) }
                conn.close()
            }
        }
        return null
    }

    private fun getDeviceInfo(conn: UsbDeviceConnection, epIn: UsbEndpoint, epOut: UsbEndpoint): MtpInfo? {
        val cmd = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        cmd.putInt(12)
        cmd.putShort(1)          // container type: command
        cmd.putShort(0x1001)     // GetDeviceInfo
        cmd.putInt(1)            // transaction id
        if (conn.bulkTransfer(epOut, cmd.array(), 12, 3000) < 0) return null

        val out = ByteArrayOutputStream()
        val buf = ByteArray(16384)
        var expected = -1
        var guard = 0
        while (guard++ < 64) {
            val n = conn.bulkTransfer(epIn, buf, buf.size, 3000)
            if (n <= 0) break
            out.write(buf, 0, n)
            if (expected < 0 && out.size() >= 4) {
                expected = ByteBuffer.wrap(out.toByteArray(), 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
            }
            if (expected in 0..out.size()) break
        }
        // Consume the trailing response container so the interface is left clean.
        conn.bulkTransfer(epIn, buf, buf.size, 400)

        val data = out.toByteArray()
        if (data.size < 12) return null
        val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        bb.position(4)
        val type = bb.short.toInt()
        val code = bb.short.toInt() and 0xFFFF
        if (type != 2 || code != 0x1001) return null
        bb.position(12)
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
