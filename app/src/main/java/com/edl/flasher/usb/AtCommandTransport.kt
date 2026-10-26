package com.edl.flasher.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import kotlinx.coroutines.delay
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

class AtCommandTransport(
    private val usbManager: UsbManager,
    private val device: UsbDevice
) {
    private var connection: UsbDeviceConnection? = null
    private var modemInterface: UsbInterface? = null
    private var controlInterface: UsbInterface? = null
    private var bulkIn: UsbEndpoint? = null
    private var bulkOut: UsbEndpoint? = null

    /**
     * Wake up MTP interface and claim the hidden SAMSUNG Mobile USB Modem interface.
     */
    fun connect(onLog: (String) -> Unit = {}): Boolean {
        val conn = usbManager.openDevice(device) ?: run {
            onLog("[-] Failed to open USB connection to device.")
            return false
        }
        connection = conn

        // 1. Wake up MTP interface first (Protocol Switching)
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (intf.interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE ||
                (intf.interfaceSubclass == 6 && intf.interfaceProtocol == 1)
            ) {
                conn.claimInterface(intf, true)
                conn.releaseInterface(intf)
                break
            }
        }

        // 2. Locate CDC-ACM control interface if present
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (intf.interfaceClass == UsbConstants.USB_CLASS_COMM) {
                controlInterface = intf
                conn.claimInterface(intf, true)
            }
        }

        // 3. Locate Modem Data interface with Bulk IN and Bulk OUT endpoints (excluding MTP and ADB)
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            val isAdb = intf.interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC &&
                    intf.interfaceSubclass == 0x42 && intf.interfaceProtocol == 0x01
            val isMtp = intf.interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE ||
                    (intf.interfaceSubclass == 6 && intf.interfaceProtocol == 1)

            if (isAdb || isMtp) continue

            var epIn: UsbEndpoint? = null
            var epOut: UsbEndpoint? = null

            for (j in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(j)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (ep.direction == UsbConstants.USB_DIR_IN) epIn = ep
                    else if (ep.direction == UsbConstants.USB_DIR_OUT) epOut = ep
                }
            }

            if (epIn != null && epOut != null) {
                if (conn.claimInterface(intf, true)) {
                    modemInterface = intf
                    bulkIn = epIn
                    bulkOut = epOut
                    break
                }
            }
        }

        if (modemInterface == null || bulkIn == null || bulkOut == null) {
            onLog("[-] SAMSUNG Mobile USB Modem interface not found.")
            close()
            return false
        }

        // 4. Initialize serial line coding (115200 8N1) and assert DTR/RTS
        initModemControlLines(conn, controlInterface?.id ?: modemInterface!!.id)
        onLog("[+] Connected to USB Modem port (Interface #${modemInterface!!.id}).")
        return true
    }

    private fun initModemControlLines(conn: UsbDeviceConnection, interfaceIndex: Int) {
        // SET_LINE_CODING (0x20): 115200 baud, 1 stop bit, no parity, 8 data bits
        val lineCoding = ByteBuffer.allocate(7).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(115200)
            put(0.toByte())
            put(0.toByte())
            put(8.toByte())
        }.array()
        conn.controlTransfer(0x21, 0x20, 0, interfaceIndex, lineCoding, lineCoding.size, 1000)

        // SET_CONTROL_LINE_STATE (0x22): Assert DTR (0x01) | RTS (0x02) = 0x03
        conn.controlTransfer(0x21, 0x22, 0x03, interfaceIndex, null, 0, 1000)
    }

    /**
     * Send raw AT command and read response from Bulk IN endpoint.
     */
    fun sendCommand(cmd: String, timeoutMs: Int = 2500): String {
        val conn = connection ?: return ""
        val outEp = bulkOut ?: return ""
        val inEp = bulkIn ?: return ""

        val payload = if (cmd.endsWith("\r\n")) cmd else "$cmd\r\n"
        val outBytes = payload.toByteArray(StandardCharsets.US_ASCII)

        val sent = conn.bulkTransfer(outEp, outBytes, outBytes.size, timeoutMs)
        if (sent < 0) return ""

        val responseBuilder = StringBuilder()
        val buffer = ByteArray(1024)
        val startTime = System.currentTimeMillis()

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val read = conn.bulkTransfer(inEp, buffer, buffer.size, 400)
            if (read > 0) {
                val chunk = String(buffer, 0, read, StandardCharsets.US_ASCII)
                responseBuilder.append(chunk)
                if (responseBuilder.contains("OK") || responseBuilder.contains("ERROR")) {
                    break
                }
            } else if (responseBuilder.isNotEmpty()) {
                break
            }
        }
        return responseBuilder.toString().trim()
    }

    /**
     * Change Serial Number in MTP mode using standard Samsung factory AT commands.
     */
    suspend fun changeSerialNumber(newSerial: String, onLog: (String) -> Unit): Boolean {
        val cleanSn = newSerial.trim().uppercase()
        if (cleanSn.length < 10) {
            onLog("[-] Invalid Serial Number (must be at least 11 characters).")
            return false
        }

        // Step 1: Handshake with modem (AT)
        onLog("[*] Checking modem connection (AT)...")
        var pong = false
        for (attempt in 1..3) {
            val resp = sendCommand("AT", 1500)
            if (resp.contains("OK", ignoreCase = true)) {
                pong = true
                break
            }
            delay(300)
        }

        if (!pong) {
            onLog("[-] Modem did not respond to AT command. Keep device on MTP screen.")
            return false
        }
        onLog("[+] Modem response: OK")

        // Step 2: Enable advanced factory mode (AT+SWATD=1)
        onLog("[*] Unlocking factory AT mode (AT+SWATD=1)...")
        val swatdResp = sendCommand("AT+SWATD=1", 2000)
        onLog("[i] SWATD response: ${swatdResp.ifEmpty { "Sent" }}")
        delay(200)

        // Step 3: Inject new Serial Number (AT+SERIALNO=2,SN)
        onLog("[*] Writing new Serial Number: $cleanSn ...")
        val serialCmd = "AT+SERIALNO=2,$cleanSn"
        val serialResp = sendCommand(serialCmd, 3000)
        onLog("[i] SERIALNO response: $serialResp")

        if (serialResp.contains("ERROR", ignoreCase = true)) {
            onLog("[-] Modem rejected Serial Number write (ERROR).")
            return false
        }

        delay(300)

        // Step 4: Commit NVRAM changes and reboot (AT+FACTORST=0,0)
        onLog("[*] Saving Serial Number and rebooting device (AT+FACTORST=0,0)...")
        sendCommand("AT+FACTORST=0,0", 2000)

        onLog("[✓] Serial Number successfully changed to $cleanSn! Rebooting device.")
        return true
    }

    fun close() {
        try {
            modemInterface?.let { connection?.releaseInterface(it) }
            controlInterface?.let { connection?.releaseInterface(it) }
            connection?.close()
        } catch (_: Exception) {
        } finally {
            connection = null
            modemInterface = null
            controlInterface = null
            bulkIn = null
            bulkOut = null
        }
    }
}
