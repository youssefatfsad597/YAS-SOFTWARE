package com.edl.flasher.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.*
import android.os.Build
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.Closeable
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.math.min

class EdlUsbTransport(private val context: Context) : Closeable {

    companion object {
        const val QCOM_VID = 0x05C6
        const val EDL_PID = 0x9008
        const val SAMSUNG_VID = 0x04E8
        val SAMSUNG_DOWNLOAD_MODE_PIDS = listOf(0x6601, 0x685D, 0x68C3)
        // Unisoc / Spreadtrum boot ROM (BROM) USB identity, exposed for a few hundred ms
        // right after the SoC powers on with no valid FDL yet. T509 enumerates here.
        const val SPRD_BROM_VID = 0x1782
        val SPRD_BROM_PIDS = listOf(0x4D00, 0x4D01)
        const val ACTION_USB_PERMISSION = "com.yas.flashedl.USB_PERMISSION"
        private const val USB_CHUNK_LIMIT = 16384
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var connection: UsbDeviceConnection? = null
    private var usbInterface: UsbInterface? = null
    private var epIn: UsbEndpoint? = null
    private var epOut: UsbEndpoint? = null

    private var device: UsbDevice? = null
    private var detachReceiver: BroadcastReceiver? = null

    private fun isDeviceAttached(): Boolean {
        val dev = device ?: return false
        return usbManager.deviceList.containsKey(dev.deviceName)
    }

    // True only while the handle is open AND the physical device is still on the bus.
    val isConnected: Boolean
        get() {
            if (connection == null || epIn == null || epOut == null) return false
            if (!isDeviceAttached()) {
                close()
                return false
            }
            return true
        }

    fun registerDetachListener(
        onDetached: () -> Unit,
        onAttached: (UsbDevice) -> Unit = {},
        onOtherDetached: () -> Unit = {}
    ) {
        if (detachReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val action = intent.action
                if (action != UsbManager.ACTION_USB_DEVICE_DETACHED &&
                    action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
                val gone: UsbDevice? =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    }
                if (action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
                    val att = gone ?: return
                    // Same device re-enumerated without us seeing a detach: drop the stale handle.
                    if (att.deviceName == device?.deviceName) close()
                    onAttached(att)
                } else if (gone == null || gone.deviceName == device?.deviceName) {
                    close()
                    onDetached()
                } else {
                    onOtherDetached()
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        detachReceiver = receiver
    }

    fun unregisterDetachListener() {
        detachReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        detachReceiver = null
    }

    fun findEdlDevice(): UsbDevice? {
        return usbManager.deviceList.values.firstOrNull { device ->
            device.vendorId == QCOM_VID && device.productId == EDL_PID
        }
    }

    fun findSamsungDownloadModeDevice(): UsbDevice? {
        return usbManager.deviceList.values.firstOrNull { device ->
            device.vendorId == SAMSUNG_VID && device.productId in SAMSUNG_DOWNLOAD_MODE_PIDS
        }
    }

    fun findSprdBromDevice(): UsbDevice? {
        return usbManager.deviceList.values.firstOrNull { device ->
            device.vendorId == SPRD_BROM_VID && device.productId in SPRD_BROM_PIDS
        }
    }

    fun listDevices(): List<UsbDevice> = usbManager.deviceList.values.toList()

    fun hasPermission(device: UsbDevice): Boolean = usbManager.hasPermission(device)

    /** Asks the user for USB permission once (no-op when it is already granted). */
    suspend fun ensurePermission(device: UsbDevice): Boolean {
        if (usbManager.hasPermission(device)) return true
        return requestUsbPermission(device)
    }

    suspend fun requestPermissionAndOpen(device: UsbDevice): Boolean {
        if (!usbManager.hasPermission(device)) {
            val granted = requestUsbPermission(device)
            if (!granted) return false
        }
        return openDevice(device)
    }

    // Only ONE permission dialog may be in flight at a time. Every caller (auto-scan, watcher,
    // Rescan, EDL buttons) goes through this lock and re-checks hasPermission once it gets in,
    // so a second caller never stacks a second dialog on top of the first one.
    private val permissionMutex = Mutex()
    private val deniedAtMs = mutableMapOf<String, Long>()

    private suspend fun requestUsbPermission(device: UsbDevice): Boolean =
        permissionMutex.withLock {
            if (usbManager.hasPermission(device)) return@withLock true
            if (!usbManager.deviceList.containsKey(device.deviceName)) return@withLock false
            val deniedAt = deniedAtMs[device.deviceName]
            if (deniedAt != null && System.currentTimeMillis() - deniedAt < 3000L) return@withLock false
            val granted = askUsbPermission(device)
            if (granted) deniedAtMs.remove(device.deviceName)
            else deniedAtMs[device.deviceName] = System.currentTimeMillis()
            granted
        }

    private suspend fun askUsbPermission(device: UsbDevice): Boolean =
        suspendCancellableCoroutine { cont ->
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    if (intent.action != ACTION_USB_PERMISSION) return
                    val answeredFor: UsbDevice? =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                        }
                    // Ignore answers that belong to a different device.
                    if (answeredFor != null && answeredFor.deviceName != device.deviceName) return
                    runCatching { context.unregisterReceiver(this) }
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) ||
                        usbManager.hasPermission(device)
                    if (cont.isActive) cont.resume(granted)
                }
            }
            val filter = IntentFilter(ACTION_USB_PERMISSION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }

            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_ALLOW_UNSAFE_IMPLICIT_INTENT
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE
            } else {
                0
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                device.deviceName.hashCode(),
                Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
                flags or PendingIntent.FLAG_UPDATE_CURRENT
            )
            usbManager.requestPermission(device, pendingIntent)

            cont.invokeOnCancellation {
                runCatching { context.unregisterReceiver(receiver) }
            }
        }

    private fun openDevice(device: UsbDevice): Boolean {
        close()
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            var foundIn: UsbEndpoint? = null
            var foundOut: UsbEndpoint? = null

            for (j in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(j)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (ep.direction == UsbConstants.USB_DIR_IN) foundIn = ep
                    else if (ep.direction == UsbConstants.USB_DIR_OUT) foundOut = ep
                }
            }

            if (foundIn != null && foundOut != null) {
                val conn = usbManager.openDevice(device) ?: return false
                if (!conn.claimInterface(intf, true)) {
                    conn.close()
                    return false
                }
                connection = conn
                this.device = device
                usbInterface = intf
                epIn = foundIn
                epOut = foundOut
                return true
            }
        }
        return false
    }

    @Throws(IOException::class)
    fun write(data: ByteArray, offset: Int = 0, length: Int = data.size - offset, timeoutMs: Int = 60000) {
        val conn = connection ?: throw IOException("USB connection not open")
        val outEndpoint = epOut ?: throw IOException("Bulk OUT endpoint missing")

        var bytesSent = 0
        while (bytesSent < length) {
            val chunk = min(USB_CHUNK_LIMIT, length - bytesSent)
            val transferred = conn.bulkTransfer(outEndpoint, data, offset + bytesSent, chunk, timeoutMs)
            if (transferred < 0) {
                if (!isDeviceAttached()) {
                    close()
                    throw IOException("USB device disconnected")
                }
                throw IOException("USB Bulk Write failed at offset ${offset + bytesSent} (ret=$transferred)")
            }
            bytesSent += transferred
        }
    }

    @Throws(IOException::class)
    fun read(buffer: ByteArray, offset: Int = 0, maxLength: Int = buffer.size - offset, timeoutMs: Int = 60000): Int {
        val conn = connection ?: throw IOException("USB connection not open")
        val inEndpoint = epIn ?: throw IOException("Bulk IN endpoint missing")

        val transferred = conn.bulkTransfer(inEndpoint, buffer, offset, min(USB_CHUNK_LIMIT, maxLength), timeoutMs)
        if (transferred < 0) {
            if (!isDeviceAttached()) {
                close()
                throw IOException("USB device disconnected")
            }
            throw IOException("USB Bulk Read timed out or failed (ret=$transferred)")
        }
        return transferred
    }

    /** Zero-length packet: tells a ZLP-aware Firehose target that this payload transfer has ended. */
    @Throws(IOException::class)
    fun writeZlp(timeoutMs: Int = 5000) {
        val conn = connection ?: throw IOException("USB connection not open")
        val outEndpoint = epOut ?: throw IOException("Bulk OUT endpoint missing")
        val r = conn.bulkTransfer(outEndpoint, ByteArray(0), 0, 0, timeoutMs)
        if (r < 0 && !isDeviceAttached()) {
            close()
            throw IOException("USB device disconnected")
        }
    }

    @Throws(IOException::class)
    fun readFully(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size - offset, timeoutMs: Int = 60000) {
        var totalRead = 0
        while (totalRead < length) {
            val toRead = min(USB_CHUNK_LIMIT, length - totalRead)
            val readNow = read(buffer, offset + totalRead, toRead, timeoutMs)
            if (readNow == 0) continue
            totalRead += readNow
        }
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
    }
}
