package com.edl.flasher.ui

import android.app.Application
import android.hardware.usb.UsbDevice
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.edl.flasher.protocol.*
import android.content.Context
import android.hardware.usb.UsbManager
import com.edl.flasher.usb.MtpDeviceInfoReader
import com.edl.flasher.usb.UsbAccessoryPopup
import com.edl.flasher.usb.AtCommandTransport
import com.edl.flasher.usb.EdlUsbTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.*

data class EdlUiState(
    val usbConnected: Boolean = false,
    val deviceDescription: String = "No 9008 Device Detected",
    val usbSummary: String = "No USB device connected",
    val usbAny: Boolean = false,
    val deviceInfo: SaharaDeviceInfo? = null,
    val firehoseReady: Boolean = false,
