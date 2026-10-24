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
