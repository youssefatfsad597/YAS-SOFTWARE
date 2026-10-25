package com.edl.flasher.protocol

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager

/** Reads USB-descriptor-level info from a device connected in MTP / File
 *  Transfer mode. No ADB and no extra permissions beyond normal USB host
 *  access are required for the VID/PID/interface info; manufacturer, product
 *  and serial strings need the standard USB permission grant. */
object MtpInfoReader {

    private const val USB_CLASS_STILL_IMAGE = 6 // PTP/MTP devices commonly expose this class

    fun findMtpDevices(context: Context): List<UsbDevice> {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        return usbManager.deviceList.values.filter { dev ->
            (0 until dev.interfaceCount).any { i ->
                val intf = dev.getInterface(i)
                intf.interfaceClass == USB_CLASS_STILL_IMAGE ||
                    (intf.interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC && intf.interfaceSubclass == 0xFF)
            }
        }
    }

    /** Every device that could carry an AT/modem port: MTP devices first, then any device that
     *  exposes a CDC / vendor serial-style interface (modem or diag USB modes), so the tools also
     *  work when the phone is NOT in MTP mode. Samsung Download Mode and Qualcomm EDL are excluded. */
    fun findModemDevices(context: Context): List<UsbDevice> {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val mtp = findMtpDevices(context)
        val others = usbManager.deviceList.values.filter { dev ->
            mtp.none { it.deviceName == dev.deviceName } &&
                !(dev.vendorId == 0x04E8 && (dev.productId == 0x685D || dev.productId == 0x6601)) &&
                !(dev.vendorId == 0x05C6 && dev.productId == 0x9008) &&
                (0 until dev.interfaceCount).any { i ->
                    val intf = dev.getInterface(i)
                    val eps = (0 until intf.endpointCount).map { intf.getEndpoint(it) }
                    val bulkPair = eps.any { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_IN } &&
                        eps.any { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_OUT }
                    val isAdb = intf.interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC && intf.interfaceSubclass == 0x42 && intf.interfaceProtocol == 0x01
                    bulkPair && !isAdb && (intf.interfaceClass == UsbConstants.USB_CLASS_COMM ||
                        intf.interfaceClass == UsbConstants.USB_CLASS_CDC_DATA ||
                        intf.interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC)
                }
        }
        return mtp + others
    }

    fun describe(context: Context, dev: UsbDevice): String {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val hasPermission = usbManager.hasPermission(dev)
        val sb = StringBuilder()
        sb.appendLine("Vendor ID: 0x${dev.vendorId.toString(16).uppercase()}")
        sb.appendLine("Product ID: 0x${dev.productId.toString(16).uppercase()}")
        sb.appendLine("Device Name: ${dev.deviceName}")
        if (hasPermission) {
            sb.appendLine("Manufacturer: ${runCatching { dev.manufacturerName }.getOrNull() ?: "Unknown"}")
            sb.appendLine("Product: ${runCatching { dev.productName }.getOrNull() ?: "Unknown"}")
            sb.appendLine("Serial: ${runCatching { dev.serialNumber }.getOrNull() ?: "Unavailable"}")
        } else {
            sb.appendLine("Grant USB permission to read Manufacturer/Product/Serial.")
        }
        sb.appendLine("Interfaces: ${dev.interfaceCount}")
        return sb.toString()
    }
}
