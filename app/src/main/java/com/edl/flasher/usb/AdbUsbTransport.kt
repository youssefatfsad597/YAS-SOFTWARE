package com.edl.flasher.usb

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.io.Closeable
import java.io.IOException
import kotlin.math.min

/**
 * Minimal USB transport for the standard ADB protocol (interface class 0xFF,
 * subclass 0x42, protocol 0x01 -- the same descriptor every "USB debugging"
 * capable Android device exposes). This only moves raw bytes; the ADB framing,
 * auth and shell logic live in [com.edl.flasher.protocol.AdbClient].
 */
class AdbUsbTransport(private val context: Context) : Closeable {

    companion object {
        private const val ADB_CLASS = 0xFF
        private const val ADB_SUBCLASS = 0x42
        private const val ADB_PROTOCOL = 0x01
        private const val USB_CHUNK_LIMIT = 16384
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var connection: UsbDeviceConnection? = null
    private var usbInterface: UsbInterface? = null
    private var epIn: UsbEndpoint? = null
    private var epOut: UsbEndpoint? = null

    var device: UsbDevice? = null
        private set

    val isOpen: Boolean
        get() = connection != null && epIn != null && epOut != null

    fun findAdbCapableDevices(): List<UsbDevice> =
        usbManager.deviceList.values.filter { dev -> hasAdbInterface(dev) }

    private fun hasAdbInterface(dev: UsbDevice): Boolean {
        for (i in 0 until dev.interfaceCount) {
            val intf = dev.getInterface(i)
            if (intf.interfaceClass == ADB_CLASS &&
                intf.interfaceSubclass == ADB_SUBCLASS &&
                intf.interfaceProtocol == ADB_PROTOCOL
            ) return true
        }
        return false
    }

    fun hasPermission(dev: UsbDevice): Boolean = usbManager.hasPermission(dev)

    fun requestPermission(dev: UsbDevice, pendingIntent: android.app.PendingIntent) {
        usbManager.requestPermission(dev, pendingIntent)
    }

    fun open(dev: UsbDevice): Boolean {
        close()
        for (i in 0 until dev.interfaceCount) {
            val intf = dev.getInterface(i)
            if (intf.interfaceClass != ADB_CLASS ||
                intf.interfaceSubclass != ADB_SUBCLASS ||
                intf.interfaceProtocol != ADB_PROTOCOL
            ) continue

            var foundIn: UsbEndpoint? = null
            var foundOut: UsbEndpoint? = null
            for (j in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(j)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (ep.direction == UsbConstants.USB_DIR_IN) foundIn = ep else foundOut = ep
            }

            if (foundIn != null && foundOut != null) {
                val conn = usbManager.openDevice(dev) ?: return false
                if (!conn.claimInterface(intf, true)) {
                    conn.close()
                    return false
                }
                connection = conn
                usbInterface = intf
                epIn = foundIn
                epOut = foundOut
                device = dev
                return true
            }
        }
        return false
    }

    @Throws(IOException::class)
    fun write(data: ByteArray, timeoutMs: Int = 10000) {
        val conn = connection ?: throw IOException("ADB USB transport not open")
        val out = epOut ?: throw IOException("ADB OUT endpoint missing")
        var sent = 0
        while (sent < data.size) {
            val chunk = min(USB_CHUNK_LIMIT, data.size - sent)
            val n = conn.bulkTransfer(out, data, sent, chunk, timeoutMs)
            if (n < 0) throw IOException("ADB bulk write failed at offset $sent")
            sent += n
        }
    }

    @Throws(IOException::class)
    fun readExact(length: Int, timeoutMs: Int = 10000): ByteArray {
        val conn = connection ?: throw IOException("ADB USB transport not open")
        val inEp = epIn ?: throw IOException("ADB IN endpoint missing")
        val result = ByteArray(length)
        var got = 0
        while (got < length) {
            val tmp = ByteArray(min(USB_CHUNK_LIMIT, length - got))
            val n = conn.bulkTransfer(inEp, tmp, tmp.size, timeoutMs)
            if (n < 0) throw IOException("ADB bulk read failed at offset $got")
            if (n > 0) {
                System.arraycopy(tmp, 0, result, got, n)
                got += n
            }
        }
        return result
    }

    override fun close() {
        runCatching {
            usbInterface?.let { connection?.releaseInterface(it) }
            connection?.close()
        }
        connection = null
        usbInterface = null
        epIn = null
        epOut = null
        device = null
    }
}
