package com.edl.flasher.usb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager

/** Makes the phone show its built-in "No installed apps work with this accessory. Learn more
 *  about this accessory at <link>" popup, by switching it to Android Open Accessory (AOA)
 *  mode with a description and a URI. Pure USB (control transfers on endpoint 0): no ADB and no
 *  USB debugging needed. The text of the popup is the phone's own and follows its language;
 *  the "view" button opens [uri] in the phone's browser. */
object UsbAccessoryPopup {

    enum class Result { SHOWN, NOT_SUPPORTED, FAILED }

    private const val REQ_GET_PROTOCOL = 51
    private const val REQ_SEND_STRING = 52
    private const val REQ_START = 53
    private const val TYPE_IN = 0xC0   // device-to-host | vendor | device
    private const val TYPE_OUT = 0x40  // host-to-device | vendor | device

    private const val STR_MANUFACTURER = 0
    private const val STR_MODEL = 1
    private const val STR_DESCRIPTION = 2
    private const val STR_VERSION = 3
    private const val STR_URI = 4
    private const val STR_SERIAL = 5

    fun show(
        usbManager: UsbManager,
        dev: UsbDevice,
        title: String,
        uri: String,
        manufacturer: String = "YAS SOFTWARE",
        model: String = "YAS"
    ): Result {
        if (!usbManager.hasPermission(dev)) return Result.FAILED
        val conn = usbManager.openDevice(dev) ?: return Result.FAILED
        try {
            val ver = ByteArray(2)
            val n = conn.controlTransfer(TYPE_IN, REQ_GET_PROTOCOL, 0, 0, ver, 2, 2000)
            if (n < 2) return Result.NOT_SUPPORTED
            val protocol = (ver[1].toInt() and 0xFF shl 8) or (ver[0].toInt() and 0xFF)
            if (protocol < 1) return Result.NOT_SUPPORTED

            fun send(index: Int, value: String): Boolean {
                val bytes = (value + "\u0000").toByteArray(Charsets.UTF_8)
                return conn.controlTransfer(TYPE_OUT, REQ_SEND_STRING, 0, index, bytes, bytes.size, 2000) >= 0
            }
            // The popup's title is the accessory "description"; its link is the "URI".
            val ok = send(STR_MANUFACTURER, manufacturer) &&
                send(STR_MODEL, model) &&
                send(STR_DESCRIPTION, title) &&
                send(STR_VERSION, "1.0") &&
                send(STR_URI, uri) &&
                send(STR_SERIAL, "0001")
            if (!ok) return Result.FAILED
            // The phone re-enumerates as an accessory right after this (the cable "re-plugs").
            if (conn.controlTransfer(TYPE_OUT, REQ_START, 0, 0, null, 0, 2000) < 0) return Result.FAILED
            return Result.SHOWN
        } finally {
            conn.close()
        }
    }
}
