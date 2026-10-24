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
import com.edl.flasher.usb.AtCommandTransport
import com.edl.flasher.usb.EdlUsbTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
    val selectedStorage: StorageType = StorageType.UFS,
    val useEmbeddedFirmware: Boolean = true,
    val embeddedFirmwareReady: Boolean = false,
    val embeddedFirmwareStatus: String = "Checking embedded assets...",
    val t509Variant: T509Variant = T509Variant.T509,
    val t509FirmwareReady: Boolean = false,
    val t509FirmwareStatus: String = "Checking embedded assets...",
    val t509BromConnected: Boolean = false,
    val loaderUri: Uri? = null,
    val loaderName: String = "Select Custom Loader (.elf / .mbn)",
    val frpFileUri: Uri? = null,
    val frpFileName: String = "FRP Image: Not Selected",
    val secEfsFileUri: Uri? = null,
    val secEfsFileName: String = "SEC_EFS Image: Not Selected",
    val persistentFileUri: Uri? = null,
    val persistentFileName: String = "PERSISTENT Image: Not Selected",
    val rawProgramUri: Uri? = null,
    val rawProgramName: String = "Select rawprogram*.xml",
    val patchXmlUri: Uri? = null,
    val patchXmlName: String = "Select patch*.xml (Optional)",
    val firmwareDirUri: Uri? = null,
    val firmwareDirName: String = "Select Firmware Folder",
    val partitions: List<PartitionEntry> = emptyList(),
    val selectedPartKeys: Set<String> = emptySet(),
    val selectedLunFilter: Int = -1,
    val odinConnected: Boolean = false,
    val odinSessionReady: Boolean = false,
    val odinDeviceDescription: String = "No Download-Mode Device Detected",
    val blTarUri: Uri? = null,
    val blTarName: String = "BL (Bootloader) .tar/.md5: Not Selected",
    val apTarUri: Uri? = null,
    val apTarName: String = "AP (System/Kernel) .tar/.md5: Not Selected",
    val cpTarUri: Uri? = null,
    val cpTarName: String = "CP (Modem) .tar/.md5: Not Selected",
    val cscTarUri: Uri? = null,
    val cscTarName: String = "CSC .tar/.md5: Not Selected",
    val userdataTarUri: Uri? = null,
    val userdataTarName: String = "userdata.img / .tar: Not Selected",
    val odinAutoReboot: Boolean = true,
    // Some T505N userdata packages contain extra system images.  Keep the
    // safe default enabled so only userdata is sent from that package.
    val skipSystemFilesInUserdataTar: Boolean = true,
    // Quick format: allow a small (e.g. 64 MB) userdata.img to be written as-is.
    val allowSmallUserdata: Boolean = true,
    val adbConnected: Boolean = false,
    val mtpInfoText: String = "",
    val adbInfoText: String = "",
    val infoImei: String = "",
    val infoSerial: String = "",
    val adbShellOutput: String = "",
    val isBusy: Boolean = false,
    val currentTask: String = "Ready",
    val progress: Float = 0f,
    val transferSpeedMbps: Double = 0.0,
    val logs: List<String> = listOf("Welcome to YAS SOFTWARE v4.0 (Embedded X216B Firmware + Samsung Odin)")
)

class EdlViewModel(application: Application) : AndroidViewModel(application) {

    private val context get() = getApplication<Application>().applicationContext
    private val transport = EdlUsbTransport(context)
    private val sahara = SaharaProtocol(transport, ::appendLog)
    private val firehose = FirehoseProtocol(transport, ::appendLog)
    private val odin = OdinProtocol(transport, ::appendLog)
    private val sprdFdl = SprdFdlProtocol(transport, ::appendLog)
    @Volatile private var quietInfoRead = false
    private val adbClient = AdbClient(context) { msg -> if (!quietInfoRead) appendLog(msg) }

    private val _uiState = MutableStateFlow(EdlUiState())
    val uiState: StateFlow<EdlUiState> = _uiState.asStateFlow()

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val scanMutex = kotlinx.coroutines.sync.Mutex()
    private val knownUsb = mutableSetOf<String>()

    init {
        checkEmbeddedFirmware()
        checkT509Firmware(T509Variant.T509)
        transport.registerDetachListener(
            onDetached = { onUsbDetached(); scanUsb() },
            onAttached = { scanUsb() },
            onOtherDetached = { scanUsb() }
        )
        // Watcher: picks up every USB attach / re-enumeration (e.g. after rebooting into EDL)
        // so the cable never has to be re-plugged.
        viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                try {
                    scanUsbNow()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // keep watching
                }
                delay(1000)
            }
        }
    }

    /** Called on app start/resume. */
    fun autoConnect() {
        scanUsb()
    }

    fun scanUsb() {
        viewModelScope.launch(Dispatchers.IO) { scanUsbNow() }
    }

    private fun onUsbDetached() {
        sahara.resetSession()
        // Forget every previously-seen device so the next scan treats any
        // currently attached device (including the one that was just
        // unplugged, if it immediately re-enumerates) as a brand-new
        // connection and asks for USB permission again.
        viewModelScope.launch(Dispatchers.IO) {
            scanMutex.lock()
            try {
                knownUsb.clear()
            } finally {
                scanMutex.unlock()
            }
        }
        _uiState.update {
            it.copy(
                usbConnected = false,
                firehoseReady = false,
                odinConnected = false,
                odinSessionReady = false,
                deviceDescription = "Device disconnected",
                odinDeviceDescription = "Device disconnected"
            )
        }
        appendLog("USB device disconnected. Scanning for the newly connected device...")
    }

    fun appendLog(msg: String) {
        val shown = LogSanitizer.clean(msg) ?: return
        val stamp = timeFormat.format(Date())
        _uiState.update { state ->
            state.copy(logs = (state.logs + "[$stamp] $shown").takeLast(600))
        }
    }

    fun clearLogs() {
        _uiState.update { it.copy(logs = emptyList()) }
    }

    fun checkEmbeddedFirmware() {
        viewModelScope.launch(Dispatchers.IO) {
            val ready = EmbeddedFirmware.allPresent(context)
            val status = EmbeddedFirmware.describeStatus(context)
            _uiState.update { it.copy(embeddedFirmwareReady = ready, embeddedFirmwareStatus = status) }
            if (ready) {
                appendLog("Embedded X216B firmware detected in APK: $status")
            } else {
                appendLog("WARNING: $status")
            }
        }
    }

    fun setUseEmbeddedFirmware(enabled: Boolean) {
        _uiState.update { it.copy(useEmbeddedFirmware = enabled) }
        appendLog(
            if (enabled) "Switched to EMBEDDED firmware mode (automatic, no manual files needed)."
            else "Switched to CUSTOM/manual firmware mode (advanced override)."
        )
    }

    fun checkT509Firmware(variant: T509Variant) {
        viewModelScope.launch(Dispatchers.IO) {
            val ready = T509Firmware.allPresent(context, variant)
            val status = T509Firmware.describeStatus(context, variant)
            _uiState.update { it.copy(t509Variant = variant, t509FirmwareReady = ready, t509FirmwareStatus = status) }
            if (ready) appendLog("Embedded ${variant.label} firmware detected in APK: $status")
        }
    }

    fun setT509Variant(variant: T509Variant) {
        checkT509Firmware(variant)
        appendLog("T509 variant set to ${variant.label}.")
    }

    /** Attempts to drop the connected device into Unisoc BROM mode -- no ADB needed.
     *  1) If it is already enumerated as a BROM device, just open and handshake it.
     *  2) Otherwise, if it is enumerated as MTP, try the AT port (same technique as
     *     the MTP tab's "Download Mode" button, with Unisoc-specific commands) and
     *     re-scan for a few seconds in case it re-enumerates as BROM.
     *  No software command is universal across Unisoc basebands, so if neither works
     *  the fallback stays the hardware key-combo + USB plug-in, same as any other
     *  Unisoc repair tool. */
    fun t509ForceBrom() {
        runTask("T509 Force BROM") {
            suspend fun tryOpenBrom(): Boolean {
                val bromDevice = transport.findSprdBromDevice() ?: return false
                val opened = transport.requestPermissionAndOpen(bromDevice)
                if (!opened) return false
                sprdFdl.connect()
                _uiState.update { it.copy(t509BromConnected = true, usbConnected = true, deviceDescription = "Unisoc BROM") }
                appendLog("Device is in BROM mode and handshaked.")
                return true
            }

            if (tryOpenBrom()) return@runTask

            val mtpDevices = MtpInfoReader.findMtpDevices(context)
            if (mtpDevices.isEmpty()) {
                appendLog("No BROM device and no MTP device found. Power the device off, then plug in USB while holding its BROM key combo.")
                return@runTask
            }

            var sent = false
            AtCommandTransport(context).use { at ->
                for (dev in mtpDevices) {
                    val ok = runCatching { at.tryRebootToBrom(dev) { m -> appendLog(m) } }
                        .getOrElse { e -> appendLog("AT error: ${e.message}"); false }
                    if (ok) { sent = true; break }
                }
            }
            if (!sent) {
                appendLog("The AT port did not confirm a mode switch. Power the device off, then plug in USB while holding its BROM key combo.")
                return@runTask
            }

            appendLog("AT command accepted -- waiting for the device to re-enumerate as BROM...")
            repeat(10) {
                kotlinx.coroutines.delay(500)
                if (tryOpenBrom()) return@runTask
            }
            appendLog("Device did not re-enumerate as BROM after the AT command. Power it off and use its BROM key combo instead.")
        }
    }

    /** Recovers a stuck/out-of-sync BROM handshake: closes the USB link, forgets any
     *  previous session state, reopens the (already BROM-mode) device fresh, and
     *  retries the handshake with the buffer drained between attempts. This fixes a
     *  desynced handshake -- it does NOT put a device into BROM mode by itself; the
     *  device still has to already be enumerating as BROM (via "Force BROM" or the
     *  hardware key combo) before this button can do anything. */
    fun t509FixHandshake() {
        runTask("T509 Fix Handshake") {
            appendLog("Resetting BROM session state...")
            sprdFdl.resetSession()
            transport.close()
            _uiState.update { it.copy(t509BromConnected = false, usbConnected = false) }

            val bromDevice = transport.findSprdBromDevice()
                ?: throw IllegalStateException("No BROM device detected. The device must already be enumerating as BROM -- use 'Force BROM' or its hardware key combo first.")
            val opened = transport.requestPermissionAndOpen(bromDevice)
            if (!opened) throw IllegalStateException("Could not reopen the BROM USB interface.")

            sprdFdl.connect(maxAttempts = 5)
            _uiState.update { it.copy(t509BromConnected = true, usbConnected = true, deviceDescription = "Unisoc BROM") }
            appendLog("Handshake confirmed.")
        }
    }

    /** Full T509/T509N service flow: FDL1 -> FDL2 -> erase userdata/frp/persist -> reset. */
    fun t509FormatFrp() {
        runTask("T509 Format + FRP") {
            val variant = _uiState.value.t509Variant
            if (!T509Firmware.allPresent(context, variant)) {
                throw IllegalStateException("Embedded ${variant.label} firmware missing: ${T509Firmware.describeStatus(context, variant)}")
            }
            if (!_uiState.value.t509BromConnected) {
                val bromDevice = transport.findSprdBromDevice()
                    ?: throw IllegalStateException("No Unisoc BROM device detected. Use 'Force BROM' first.")
                val opened = transport.requestPermissionAndOpen(bromDevice)
                if (!opened) throw IllegalStateException("Could not open the BROM USB interface.")
                sprdFdl.connect()
                _uiState.update { it.copy(t509BromConnected = true, usbConnected = true, deviceDescription = "Unisoc BROM") }
            }

            appendLog("=== STEP 1: Uploading ${variant.label} FDL1/FDL2 ===")
            sprdFdl.uploadFdl1(T509Firmware.readAllBytes(context, variant, T509Firmware.FDL1))
            sprdFdl.uploadFdl2(T509Firmware.readAllBytes(context, variant, T509Firmware.FDL2))

            appendLog("=== STEP 2: Erasing userdata / FRP / persist ===")
            listOf("userdata", "frp", "persist").forEachIndexed { idx, part ->
                sprdFdl.erasePartitionByName(part)
                _uiState.update { it.copy(progress = (idx + 1).toFloat() / 3f) }
            }

            appendLog("=== STEP 3: Rebooting device ===")
            sprdFdl.reset()
            transport.close()
            _uiState.update { it.copy(t509BromConnected = false, usbConnected = false) }
            appendLog("Operation completed successfully.")
        }
    }

    fun setStorageType(type: StorageType) {
        _uiState.update { it.copy(selectedStorage = type) }
        appendLog("Storage type set to ${type.name} (${type.sectorSize}B/sector)")
    }

    fun setLunFilter(lun: Int) {
        _uiState.update { it.copy(selectedLunFilter = lun) }
    }

    fun togglePartitionSelection(key: String) {
        _uiState.update { state ->
            val next = state.selectedPartKeys.toMutableSet()
            if (!next.add(key)) next.remove(key)
            state.copy(selectedPartKeys = next)
        }
    }

    fun selectAllVisiblePartitions(visibleKeys: List<String>) {
        _uiState.update { state ->
            state.copy(selectedPartKeys = state.selectedPartKeys + visibleKeys)
        }
    }

    fun clearPartitionSelection() {
        _uiState.update { it.copy(selectedPartKeys = emptySet()) }
    }

    fun setLoaderUri(uri: Uri) {
        val doc = DocumentFile.fromSingleUri(context, uri)
        val name = doc?.name ?: uri.lastPathSegment.orEmpty()
        _uiState.update { it.copy(loaderUri = uri, loaderName = name) }
        appendLog("Custom loader selected: $name")
    }

    fun setFrpFileUri(uri: Uri) {
        val name = DocumentFile.fromSingleUri(context, uri)?.name ?: uri.lastPathSegment.orEmpty()
        _uiState.update { it.copy(frpFileUri = uri, frpFileName = "FRP: $name") }
        appendLog("Selected custom FRP write image: $name")
    }

    fun setSecEfsFileUri(uri: Uri) {
        val name = DocumentFile.fromSingleUri(context, uri)?.name ?: uri.lastPathSegment.orEmpty()
        _uiState.update { it.copy(secEfsFileUri = uri, secEfsFileName = "SEC_EFS: $name") }
        appendLog("Selected custom SEC_EFS write image: $name")
    }

    fun setPersistentFileUri(uri: Uri) {
        val name = DocumentFile.fromSingleUri(context, uri)?.name ?: uri.lastPathSegment.orEmpty()
        _uiState.update { it.copy(persistentFileUri = uri, persistentFileName = "PERSISTENT: $name") }
        appendLog("Selected custom PERSISTENT write image: $name")
    }

    fun autoDetectX216BFolder(treeUri: Uri) {
        val treeDoc = DocumentFile.fromTreeUri(context, treeUri) ?: return
        val files = treeDoc.listFiles()
        var foundCount = 0

        files.forEach { file ->
            val rawName = file.name.orEmpty()
            val name = rawName.lowercase(Locale.US)
            when {
                (name.endsWith(".elf") || name.endsWith(".mbn")) -> {
                    _uiState.update { it.copy(loaderUri = file.uri, loaderName = rawName) }
                    appendLog("Auto-loaded Loader: $rawName")
                    foundCount++
                }
                name.startsWith("frp") && !name.endsWith(".xml") -> {
                    _uiState.update { it.copy(frpFileUri = file.uri, frpFileName = "FRP: $rawName") }
                    appendLog("Auto-loaded FRP Image: $rawName")
                    foundCount++
                }
                name.startsWith("sec_efs") && !name.endsWith(".xml") -> {
                    _uiState.update { it.copy(secEfsFileUri = file.uri, secEfsFileName = "SEC_EFS: $rawName") }
                    appendLog("Auto-loaded SEC_EFS Image: $rawName")
                    foundCount++
                }
                (name.startsWith("persistent") || name.startsWith("persestent") || name.startsWith("presistent")) &&
                    !name.endsWith(".xml") -> {
                    _uiState.update { it.copy(persistentFileUri = file.uri, persistentFileName = "PERSISTENT: $rawName") }
                    appendLog("Auto-loaded PERSISTENT Image: $rawName")
                    foundCount++
                }
            }
        }
        appendLog("Folder scan complete: matched $foundCount custom service files.")
    }

    fun setRawProgramUri(uri: Uri) {
        val name = DocumentFile.fromSingleUri(context, uri)?.name ?: uri.lastPathSegment.orEmpty()
        _uiState.update { it.copy(rawProgramUri = uri, rawProgramName = name) }
        appendLog("RawProgram XML: $name")
    }

    fun setPatchXmlUri(uri: Uri) {
        val name = DocumentFile.fromSingleUri(context, uri)?.name ?: uri.lastPathSegment.orEmpty()
        _uiState.update { it.copy(patchXmlUri = uri, patchXmlName = name) }
        appendLog("Patch XML: $name")
    }

    fun setFirmwareDirUri(uri: Uri) {
        val name = DocumentFile.fromTreeUri(context, uri)?.name ?: uri.lastPathSegment.orEmpty()
        _uiState.update { it.copy(firmwareDirUri = uri, firmwareDirName = name) }
        appendLog("Firmware Directory: $name")
    }

    /** Manual "Rescan": re-processes every attached USB device (asks for permission again). */
    fun connectUsb() {
        viewModelScope.launch(Dispatchers.IO) {
            scanMutex.lock()
            try {
                knownUsb.clear()
            } finally {
                scanMutex.unlock()
            }
            scanUsbNow()
        }
    }

    private fun isEdlDevice(dev: UsbDevice): Boolean =
        dev.vendorId == EdlUsbTransport.QCOM_VID && dev.productId == EdlUsbTransport.EDL_PID

    private fun isDownloadDevice(dev: UsbDevice): Boolean =
        dev.vendorId == EdlUsbTransport.SAMSUNG_VID && dev.productId in EdlUsbTransport.SAMSUNG_DOWNLOAD_MODE_PIDS

    private fun usbKindLabel(dev: UsbDevice): String {
        if (isEdlDevice(dev)) return "Qualcomm EDL 9008"
        if (isDownloadDevice(dev)) return "Samsung Download Mode"
        var adb = false
        var mtp = false
        for (i in 0 until dev.interfaceCount) {
            val f = dev.getInterface(i)
            if (f.interfaceClass == 0xFF && f.interfaceSubclass == 0x42 && f.interfaceProtocol == 0x01) adb = true
            if (f.interfaceClass == 6 || (f.interfaceClass == 0xFF && f.interfaceSubclass == 0xFF)) mtp = true
        }
        return when {
            adb && mtp -> "MTP + ADB"
            adb -> "ADB"
            mtp -> "MTP"
            else -> "USB Device"
        }
    }

    private fun describeUsb(dev: UsbDevice): String {
        val id = String.format(Locale.US, "%04X:%04X", dev.vendorId, dev.productId)
        val name = if (transport.hasPermission(dev)) {
            runCatching { dev.productName }.getOrNull()?.takeIf { it.isNotBlank() }
        } else null
        return buildString {
            append(usbKindLabel(dev))
            if (name != null) append(" - ").append(name)
            append(" [").append(id).append("]")
        }
    }

    private fun publishUsbHeader(devices: List<UsbDevice>) {
        val summary = if (devices.isEmpty()) "No USB device connected"
        else devices.joinToString("\n") { describeUsb(it) }
        val any = devices.isNotEmpty()
        _uiState.update {
            if (it.usbSummary == summary && it.usbAny == any) it
            else it.copy(usbSummary = summary, usbAny = any)
        }
    }

    private suspend fun scanUsbNow() {
        if (!scanMutex.tryLock()) return
        try {
            val devices = transport.listDevices()
            val names = devices.map { it.deviceName }.toSet()
            // Forget unplugged devices so that plugging them in again counts as a NEW connection.
            knownUsb.retainAll(names)
            publishUsbHeader(devices)

            if (_uiState.value.isBusy) return
            for (dev in devices) {
                if (dev.deviceName in knownUsb) continue
                knownUsb.add(dev.deviceName)
                handleNewUsbDevice(dev)
                publishUsbHeader(transport.listDevices())
            }
        } finally {
            scanMutex.unlock()
        }
    }

    private suspend fun handleNewUsbDevice(dev: UsbDevice) {
        appendLog("USB device connected: ${describeUsb(dev)}")
        if (!transport.hasPermission(dev)) {
            // Android may be showing its own "open with this app" dialog (or auto-granting the
            // permission) right now. Wait a moment so our request never stacks on top of it.
            delay(800)
            if (transport.listDevices().none { it.deviceName == dev.deviceName }) return
        }
        val granted = transport.ensurePermission(dev)
        if (!granted) {
            appendLog("USB permission denied for ${usbKindLabel(dev)}. Not asking again until a new USB device is connected.")
            return
        }
        when {
            isEdlDevice(dev) -> {
                if (transport.isConnected) return
                if (transport.requestPermissionAndOpen(dev)) {
                    sahara.resetSession()
                    _uiState.update {
                        it.copy(
                            usbConnected = true,
                            firehoseReady = false,
                            deviceDescription = "Qualcomm HS-USB QDLoader 9008",
                            odinConnected = false,
                            odinSessionReady = false
                        )
                    }
                    appendLog("9008 connected automatically (USB Bulk IN/OUT ready).")
                } else {
                    appendLog("ERROR: could not open the 9008 USB interface (busy).")
                }
            }

            isDownloadDevice(dev) -> {
                if (transport.isConnected) return
                if (transport.requestPermissionAndOpen(dev)) {
                    _uiState.update {
                        it.copy(
                            odinConnected = true,
                            odinSessionReady = false,
                            odinDeviceDescription = "Samsung Download Mode (Odin Protocol)",
                            usbConnected = false,
                            firehoseReady = false
                        )
                    }
                    appendLog("Download-Mode device connected automatically.")
                } else {
                    appendLog("ERROR: could not open the Download-Mode USB interface (busy).")
                }
            }

            else -> appendLog("Recognized: ${describeUsb(dev)}")
        }
    }

    fun readSaharaDeviceInfo() {
        runTask("Reading Sahara Chip Info") {
            val info = sahara.probeDeviceInfo()
            _uiState.update { it.copy(deviceInfo = info) }
        }
    }

    private fun bootFirehoseWithBytes(loaderBytes: ByteArray) {
        val startNs = System.nanoTime()
        sahara.readInfoAndUploadLoader(
            loaderBytes = loaderBytes,
            onDeviceInfoRead = { info -> _uiState.update { it.copy(deviceInfo = info) } },
            onProgress = { sent, total -> updateProgress(sent, total, startNs) }
        )

        val storage = _uiState.value.selectedStorage
        val configured = firehose.configure(storage)
        _uiState.update { it.copy(firehoseReady = configured) }
    }

    private fun bootFirehoseInternal(loaderUri: Uri) {
        val loaderBytes = context.contentResolver.openInputStream(loaderUri)?.use { it.readBytes() }
            ?: throw IllegalStateException("Cannot read selected loader file")
        bootFirehoseWithBytes(loaderBytes)
    }

    private fun bootFirehoseFromEmbeddedLoader() {
        appendLog("Loading embedded ${EmbeddedFirmware.LOADER} from app assets (no manual pick needed)...")
        val loaderBytes = EmbeddedFirmware.readAllBytes(context, EmbeddedFirmware.LOADER)
        bootFirehoseWithBytes(loaderBytes)
    }

    fun runSaharaAndConfigureFirehose(autoReadGpt: Boolean = true) {
        val loaderUri = _uiState.value.loaderUri
        if (!_uiState.value.firehoseReady && loaderUri == null) {
            appendLog("ERROR: Select a loader (.elf / .mbn) first.")
            return
        }

        runTask("Booting Firehose & Reading GPT") {
            if (!_uiState.value.firehoseReady) {
                appendLog("Uploading loader (${_uiState.value.loaderName})...")
                bootFirehoseInternal(loaderUri!!)
            }
            if (_uiState.value.firehoseReady && autoReadGpt) {
                scanAllGptInternal()
            }
        }
    }

    private data class WriteJob(
        val label: String,
        val aliases: Set<String>,
        val displayName: String,
        val sizeBytes: Long,
        val open: () -> InputStream
    )

    fun runX216BWipeAndWriteSequence() {
        runTask("X216B Auto Wipe & Write") {
            val useEmbedded = true

            if (useEmbedded && !EmbeddedFirmware.allPresent(context)) {
                throw IllegalStateException(
                    "Embedded firmware missing from APK assets: ${EmbeddedFirmware.describeStatus(context)}"
                )
            }

            if (!transport.isConnected) {
                val device = transport.findEdlDevice()
                    ?: throw IllegalStateException("No 9008 device detected on USB!")
                val opened = transport.requestPermissionAndOpen(device)
                if (!opened) throw IllegalStateException("Could not open USB 9008 interface.")
                sahara.resetSession()
                _uiState.update {
                    it.copy(
                        usbConnected = true,
                        deviceDescription = "Qualcomm HS-USB QDLoader 9008"
                    )
                }
            }

            if (!_uiState.value.firehoseReady) {
                if (useEmbedded) {
                    bootFirehoseFromEmbeddedLoader()
                } else {
                    val loaderUri = _uiState.value.loaderUri
                        ?: throw IllegalStateException("Please select a custom loader first, or enable embedded firmware!")
                    appendLog("Uploading loader (${_uiState.value.loaderName})...")
                    bootFirehoseInternal(loaderUri)
                }
            }

            appendLog("Reading Full GPT Partition Table...")
            scanAllGptInternal()

            val currentParts = _uiState.value.partitions
            if (currentParts.isEmpty()) {
                throw IllegalStateException("GPT Partition Table is empty! Check storage type (UFS/eMMC).")
            }

            val eraseTargets = listOf(
                "userdata" to setOf("userdata"),
                "persistent" to setOf("persistent", "persestent", "presistent"),
                "metadata" to setOf("metadata"),
                "cache" to setOf("cache"),
                "sec_efs" to setOf("sec_efs"),
                "frp" to setOf("frp")
            )

            appendLog("=== STEP 1: Erasing partitions (${eraseTargets.size} items) ===")
            eraseTargets.forEachIndexed { idx, (label, aliases) ->
                appendLog("Erasing ${idx + 1}/${eraseTargets.size}...")
                val matched = currentParts.filter { part ->
                    aliases.any { alias -> part.name.equals(alias, ignoreCase = true) }
                }
                if (matched.isEmpty()) {
                    appendLog("WARN: '$label' not found in GPT, skipping.")
                } else {
                    matched.forEach { part ->
                        val sectorsToErase = if (part.name.equals("userdata", ignoreCase = true) && part.numSectors > 16384L) {
                            16384L
                        } else {
                            part.numSectors
                        }
                        firehose.eraseSectors(part.lun, part.firstLba, sectorsToErase)
                    }
                }
                _uiState.update { it.copy(progress = (idx + 1).toFloat() / 9f) }
            }
            appendLog("Erase step completed successfully.")

            appendLog("=== STEP 2: WRITING PARTITIONS (FRP, SEC_EFS, PERSISTENT) ===")

            val writeQueue: List<WriteJob> = if (useEmbedded) {
                appendLog("Using EMBEDDED images bundled inside the APK (frp.bin, sec_efs.bin, persistent.bin) -- nothing to pick manually.")
                listOf(
                    WriteJob("frp", setOf("frp"), EmbeddedFirmware.FRP,
                        EmbeddedFirmware.sizeOf(context, EmbeddedFirmware.FRP)) {
                        EmbeddedFirmware.openStream(context, EmbeddedFirmware.FRP)
                    },
                    WriteJob("sec_efs", setOf("sec_efs"), EmbeddedFirmware.SEC_EFS,
                        EmbeddedFirmware.sizeOf(context, EmbeddedFirmware.SEC_EFS)) {
                        EmbeddedFirmware.openStream(context, EmbeddedFirmware.SEC_EFS)
                    },
                    WriteJob("persistent", setOf("persistent", "persestent", "presistent"), EmbeddedFirmware.PERSISTENT,
                        EmbeddedFirmware.sizeOf(context, EmbeddedFirmware.PERSISTENT)) {
                        EmbeddedFirmware.openStream(context, EmbeddedFirmware.PERSISTENT)
                    }
                )
            } else {
                listOfNotNull(
                    _uiState.value.frpFileUri?.let { uri ->
                        val doc = DocumentFile.fromSingleUri(context, uri)
                        WriteJob("frp", setOf("frp"), doc?.name ?: "frp", doc?.length() ?: 0L) {
                            context.contentResolver.openInputStream(uri)!!
                        }
                    },
                    _uiState.value.secEfsFileUri?.let { uri ->
                        val doc = DocumentFile.fromSingleUri(context, uri)
                        WriteJob("sec_efs", setOf("sec_efs"), doc?.name ?: "sec_efs", doc?.length() ?: 0L) {
                            context.contentResolver.openInputStream(uri)!!
                        }
                    },
                    _uiState.value.persistentFileUri?.let { uri ->
                        val doc = DocumentFile.fromSingleUri(context, uri)
                        WriteJob("persistent", setOf("persistent", "persestent", "presistent"), doc?.name ?: "persistent", doc?.length() ?: 0L) {
                            context.contentResolver.openInputStream(uri)!!
                        }
                    }
                )
            }

            if (!useEmbedded && writeQueue.size < 3) {
                appendLog("NOTE: Custom mode -- ${3 - writeQueue.size} file(s) were not selected and will be skipped.")
            }

            writeQueue.forEachIndexed { idx, job ->
                val targetPart = currentParts.firstOrNull { part ->
                    job.aliases.any { alias -> part.name.equals(alias, ignoreCase = true) }
                }
                if (targetPart == null) {
                    appendLog("ERROR: Partition '${job.label}' not found in GPT! Cannot write.")
                } else {
                    val startNs = System.nanoTime()
                    appendLog("Writing ${idx + 1}/${writeQueue.size}...")
                    job.open().use { input ->
                        firehose.writePartitionStream(
                            lun = targetPart.lun,
                            startSector = targetPart.firstLba,
                            maxPartitionSectors = targetPart.numSectors,
                            inputStream = input,
                            totalStreamBytes = job.sizeBytes
                        ) { done, total ->
                            updateProgress(done, total, startNs)
                        }
                    }
                }
            }

            appendLog("Operation completed successfully.")
        }
    }

    fun setBlTarUri(uri: Uri) {
        val name = DocumentFile.fromSingleUri(context, uri)?.name ?: uri.lastPathSegment.orEmpty()
        _uiState.update { it.copy(blTarUri = uri, blTarName = "BL: $name") }
        appendLog("Selected BL package: $name")
    }

    fun setApTarUri(uri: Uri) {
        val name = DocumentFile.fromSingleUri(context, uri)?.name ?: uri.lastPathSegment.orEmpty()
        _uiState.update { it.copy(apTarUri = uri, apTarName = "AP: $name") }
        appendLog("Selected AP package: $name")
    }

    fun setCpTarUri(uri: Uri) {
        val name = DocumentFile.fromSingleUri(context, uri)?.name ?: uri.lastPathSegment.orEmpty()
        _uiState.update { it.copy(cpTarUri = uri, cpTarName = "CP: $name") }
        appendLog("Selected CP package: $name")
    }

    fun setCscTarUri(uri: Uri) {
        val name = DocumentFile.fromSingleUri(context, uri)?.name ?: uri.lastPathSegment.orEmpty()
        _uiState.update { it.copy(cscTarUri = uri, cscTarName = "CSC: $name") }
        appendLog("Selected CSC package: $name")
    }

    fun setUserdataTarUri(uri: Uri) {
        val name = DocumentFile.fromSingleUri(context, uri)?.name ?: uri.lastPathSegment.orEmpty()
        _uiState.update { it.copy(userdataTarUri = uri, userdataTarName = "userdata: $name") }
        appendLog("Selected userdata package: $name")
    }

    fun setSkipSystemFilesInUserdataTar(enabled: Boolean) {
        _uiState.update { it.copy(skipSystemFilesInUserdataTar = enabled) }
        appendLog(
            if (enabled) {
                "USERDATA package filter enabled: system partitions will be skipped."
            } else {
                "USERDATA package filter disabled: every recognized image will be sent."
            }
        )
    }

    fun setAllowSmallUserdata(enabled: Boolean) {
        _uiState.update { it.copy(allowSmallUserdata = enabled) }
        appendLog(
            if (enabled) "Quick format enabled: small userdata.img will be flashed as-is."
            else "Quick format disabled: undersized userdata images are skipped."
        )
    }

    fun setOdinAutoReboot(enabled: Boolean) {
        _uiState.update { it.copy(odinAutoReboot = enabled) }
    }

    /** Sends the ODIN session-close + reboot command, exiting Download Mode
     *  without flashing anything. Connects first if not already connected. */
    fun exitDownloadMode() {
        runTask("Exit Download Mode") {
            openOdinConnectionIfNeeded()
            appendLog("Exiting Download Mode...")
            odin.shutdown(reboot = true)
            transport.close()
            _uiState.update {
                it.copy(
                    odinConnected = false,
                    odinSessionReady = false,
                    odinDeviceDescription = "No Download-Mode Device Detected"
                )
            }
            appendLog("Operation completed successfully.")
        }
    }

    fun connectSamsungDownloadMode() {
        viewModelScope.launch(Dispatchers.IO) {
            val device = transport.findSamsungDownloadModeDevice()
            if (device == null) {
                _uiState.update {
                    it.copy(
                        odinConnected = false,
                        odinSessionReady = false,
                        odinDeviceDescription = "No Download-Mode Device Detected"
                    )
                }
                appendLog("No Samsung Download-Mode device detected on OTG.")
                return@launch
            }

            appendLog("Detected Samsung Download-Mode device (${device.deviceName})")
            val opened = transport.requestPermissionAndOpen(device)
            if (opened) {
                _uiState.update {
                    it.copy(
                        odinConnected = true,
                        odinSessionReady = false,
                        odinDeviceDescription = "Samsung Download Mode (Odin Protocol)",
                        usbConnected = false,
                        firehoseReady = false
                    )
                }
                appendLog("USB connected to Download-Mode device.")
            } else {
                appendLog("ERROR: USB permission denied or interface busy.")
            }
        }
    }

    private fun ensureOdinSession() {
        if (!_uiState.value.odinSessionReady) {
            odin.connect()
            _uiState.update { it.copy(odinSessionReady = true) }
        }
    }

    private suspend fun openOdinConnectionIfNeeded() {
        if (!transport.isConnected) {
            val device = transport.findSamsungDownloadModeDevice()
                ?: throw IllegalStateException("No Download-Mode device detected on USB!")
            val opened = transport.requestPermissionAndOpen(device)
            if (!opened) throw IllegalStateException("Could not open USB Download-Mode interface.")
            _uiState.update {
                it.copy(
                    odinConnected = true,
                    odinDeviceDescription = "Samsung Download Mode (Odin Protocol)",
                    usbConnected = false,
                    firehoseReady = false
                )
            }
        }
        ensureOdinSession()
    }

    private fun userdataFilterFor(label: String): (String) -> Boolean = { partitionLabel ->
        if (label != "USERDATA" || !_uiState.value.skipSystemFilesInUserdataTar) {
            true
        } else {
            // A few T505N packages include system-side images in
            // userdata.tar. Never skip userdata itself; skip only
            // well-known system/boot/vendor/product partitions.
            val systemPartitions = setOf(
                "system", "system_ext", "vendor", "product", "odm",
                "vendor_dlkm", "system_dlkm", "odm_dlkm", "boot",
                "vendor_boot", "init_boot", "dtbo", "vbmeta",
                "vbmeta_system", "vbmeta_vendor", "recovery", "super",
                "cache", "metadata"
            )
            partitionLabel.lowercase(Locale.US) !in systemPartitions
        }
    }

    /** True when [uri] is a bare .img (not a tar): no "ustar" magic at offset 257. */
    private fun isRawImage(uri: Uri): Boolean {
        val name = DocumentFile.fromSingleUri(context, uri)?.name.orEmpty().lowercase(Locale.US)
        if (name.endsWith(".tar") || name.endsWith(".md5") || name.endsWith(".tar.md5")) return false
        if (name.endsWith(".img")) return true
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { ins ->
                val head = ByteArray(262)
                var n = 0
                while (n < head.size) { val r = ins.read(head, n, head.size - n); if (r < 0) break; n += r }
                n < 262 || String(head, 257, 5, Charsets.US_ASCII) != "ustar"
            } ?: false
        }.getOrDefault(false)
    }

    private fun rawImageSize(uri: Uri, pit: OdinPitTable): Long {
        pit.findByFileName("userdata.img")
            ?: throw IllegalStateException("This device's PIT has no userdata.img entry.")
        return context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
            ?: throw IllegalStateException("Cannot read the size of the selected image.")
    }

    /** Flashes a bare userdata.img (e.g. the 64 MB quick-format image) to the PIT's userdata partition. */
    private fun flashRawImage(uri: Uri, pit: OdinPitTable) {
        val part = pit.findByFileName("userdata.img")
            ?: throw IllegalStateException("This device's PIT has no userdata.img entry.")
        val size = rawImageSize(uri, pit)
        val reason = OdinTarFlasher.undersizedUserdataReason("userdata", size, part)
        if (reason != null && !_uiState.value.allowSmallUserdata) {
            throw IllegalStateException("Refusing small userdata.img: $reason (enable Quick format to allow it).")
        }
        if (reason != null) appendLog("Quick-format mode: sending small userdata.img ($size bytes) on purpose.")
        appendLog("Flashing raw userdata.img -> PIT '${part.name}' (id=${part.id}, $size bytes)")
        val start = System.nanoTime()
        context.contentResolver.openInputStream(uri)?.use { ins ->
            odin.flashImage(part, ins, size, singleSequence = (reason != null), tolerateFinalAckWarning = (reason != null)) { done, total -> updateProgress(done, total, start) }
        } ?: throw IllegalStateException("Cannot open userdata image.")
    }

    /**
     * Real ODIN requires the combined size of every partition that will be
     * flashed to be sent once, before the first partition's transfer begins.
     * So every selected package is opened twice: once to sum sizes (matched
     * against this device's own PIT table), then again to actually stream
     * the data.
     */
    private suspend fun flashTarsWithOdin(slots: List<Pair<String, Uri>>) {
        openOdinConnectionIfNeeded()
        val pit = odin.requirePit()

        appendLog("Computing total transfer size against this device's PIT...")
        var total = 0L
        for ((label, uri) in slots) {
            context.contentResolver.openInputStream(uri)?.use { sizingStream ->
                total += if (isRawImage(uri)) {
                    rawImageSize(uri, pit)
                } else {
                    OdinTarFlasher.sumFlashableSize(
                        sizingStream, pit, userdataFilterFor(label),
                        allowUndersizedUserdata = _uiState.value.allowSmallUserdata
                    )
                }
            } ?: throw IllegalStateException("Cannot open $label file.")
        }
        if (total <= 0L) {
            throw IllegalStateException(
                "Nothing inside the selected package(s) matches a partition in this device's PIT table."
            )
        }
        appendLog("Total bytes to flash: $total")
        odin.sendTotalSize(total)

        var partitionStartNs = System.nanoTime()
        var lastPartition = ""
        for ((label, uri) in slots) {
            appendLog("Opening $label package...")
            if (isRawImage(uri)) {
                flashRawImage(uri, pit)
                continue
            }
            context.contentResolver.openInputStream(uri)?.use { tarInput ->
                val flashed = OdinTarFlasher.flashTarStream(
                    tarInput = tarInput,
                    pit = pit,
                    odin = odin,
                    logger = ::appendLog,
                    onPartitionProgress = { partName, done, partTotal ->
                        if (partName != lastPartition) {
                            lastPartition = partName
                            partitionStartNs = System.nanoTime()
                        }
                        updateProgress(done, partTotal, partitionStartNs)
                    },
                    shouldFlash = userdataFilterFor(label),
                    allowUndersizedUserdata = _uiState.value.allowSmallUserdata
                )
                if (flashed.isEmpty()) {
                    appendLog("WARN: No recognizable/matching partition images found inside the $label package.")
                }
            } ?: throw IllegalStateException("Cannot open $label file.")
        }
    }

    fun runOdinFlashSequence() {
        runTask("Samsung Odin Flash") {
            val slots = listOf(
                "BL" to _uiState.value.blTarUri,
                "AP" to _uiState.value.apTarUri,
                "CP" to _uiState.value.cpTarUri,
                "CSC" to _uiState.value.cscTarUri,
                "USERDATA" to _uiState.value.userdataTarUri
            ).filter { it.second != null }.map { it.first to it.second!! }

            if (slots.isEmpty()) {
                throw IllegalStateException("No .tar files selected. Pick at least one of BL/AP/CP/CSC/USERDATA.")
            }

            appendLog("=== STARTING ODIN FLASH SEQUENCE (${slots.size} package(s)) ===")
            flashTarsWithOdin(slots)

            appendLog("Operation completed successfully.")
            odin.shutdown(reboot = _uiState.value.odinAutoReboot)
            transport.close()
            _uiState.update {
                it.copy(
                    odinConnected = false,
                    odinSessionReady = false,
                    odinDeviceDescription = "No Download-Mode Device Detected"
                )
            }
        }
    }

    fun runFlashUserdataTarOnly() {
        runTask("Flash userdata.tar") {
            val uri = _uiState.value.userdataTarUri
                ?: throw IllegalStateException("Please select userdata.tar first!")

            appendLog("=== FLASHING ${_uiState.value.userdataTarName} ===")
            flashTarsWithOdin(listOf("USERDATA" to uri))

            odin.shutdown(reboot = _uiState.value.odinAutoReboot)
            appendLog("Operation completed successfully.")
        }
    }

    fun readAllGptPartitions() {
        runTask("Reading Full GPT Table") {
            scanAllGptInternal()
        }
    }

    private fun scanAllGptInternal() {
        val allPartitions = mutableListOf<PartitionEntry>()
        val isUfs = _uiState.value.selectedStorage == StorageType.UFS
        val maxLuns = if (isUfs) 6 else 1

        appendLog("Scanning full GPT across ${if (isUfs) "LUN 0..5 (UFS)" else "LUN 0 (eMMC)"}...")
        for (lun in 0 until maxLuns) {
            val lunParts = runCatching { GptParser.readGptForLun(firehose, lun) }
                .getOrDefault(emptyList())
            if (lunParts.isNotEmpty()) {
                appendLog("LUN $lun: Read ${lunParts.size} partitions.")
                allPartitions.addAll(lunParts)
            }
        }

        _uiState.update {
            it.copy(
                partitions = allPartitions,
                selectedPartKeys = emptySet()
            )
        }
        appendLog("Full GPT Loaded: Total ${allPartitions.size} partitions ready.")
    }

    fun backupPartition(partition: PartitionEntry, destUri: Uri) {
        runTask("Reading '${partition.name}'") {
            val startNs = System.nanoTime()
            appendLog("Reading partition '${partition.name}' (LUN ${partition.lun}, LBA ${partition.firstLba}..${partition.lastLba})...")
            context.contentResolver.openOutputStream(destUri)?.use { out ->
                firehose.readSectors(
                    lun = partition.lun,
                    startSector = partition.firstLba,
                    numSectors = partition.numSectors,
                    output = out
                ) { done, total ->
                    updateProgress(done, total, startNs)
                }
            }
            appendLog("Operation completed successfully.")
        }
    }

    fun flashSinglePartition(partition: PartitionEntry, sourceImageUri: Uri) {
        runTask("Writing '${partition.name}'") {
            val doc = DocumentFile.fromSingleUri(context, sourceImageUri)
            val totalBytes = doc?.length() ?: 0L
            val startNs = System.nanoTime()

            appendLog("Writing '${doc?.name}' -> partition '${partition.name}' (LUN ${partition.lun})...")
            context.contentResolver.openInputStream(sourceImageUri)?.use { input ->
                firehose.writePartitionStream(
                    lun = partition.lun,
                    startSector = partition.firstLba,
                    maxPartitionSectors = partition.numSectors,
                    inputStream = input,
                    totalStreamBytes = totalBytes
                ) { done, total ->
                    updateProgress(done, total, startNs)
                }
            }
            appendLog("Operation completed successfully.")
        }
    }

    fun eraseSinglePartition(partition: PartitionEntry) {
        runTask("Erasing '${partition.name}'") {
            appendLog("Erasing '${partition.name}' (LUN ${partition.lun}, ${partition.numSectors} sectors)...")
            firehose.eraseSectors(partition.lun, partition.firstLba, partition.numSectors)
            appendLog("Operation completed successfully.")
        }
    }

    fun eraseSelectedPartitions() {
        val selectedKeys = _uiState.value.selectedPartKeys
        val targets = _uiState.value.partitions.filter { it.idKey in selectedKeys }
        if (targets.isEmpty()) {
            appendLog("No partitions selected to erase.")
            return
        }

        runTask("Erasing ${targets.size} Selected Partitions") {
            targets.forEachIndexed { idx, part ->
                appendLog("[${idx + 1}/${targets.size}] Erasing '${part.name}' (LUN ${part.lun})...")
                firehose.eraseSectors(part.lun, part.firstLba, part.numSectors)
                _uiState.update { it.copy(progress = (idx + 1).toFloat() / targets.size.toFloat()) }
            }
            _uiState.update { it.copy(selectedPartKeys = emptySet()) }
            appendLog("Operation completed successfully.")
        }
    }

    fun resetFrpLock() {
        val frpTargets = _uiState.value.partitions.filter {
            it.name.equals("frp", ignoreCase = true) ||
            it.name.equals("config", ignoreCase = true) ||
            it.name.equals("persistent", ignoreCase = true)
        }
        if (frpTargets.isEmpty()) {
            appendLog("ERROR: Read GPT first! Could not find 'frp' or 'config' partition.")
            return
        }
        runTask("Resetting FRP Lock") {
            frpTargets.forEach { part ->
                appendLog("Erasing FRP partition '${part.name}' on LUN ${part.lun}...")
                firehose.eraseSectors(part.lun, part.firstLba, part.numSectors)
            }
            appendLog("Operation completed successfully.")
        }
    }

    fun factoryResetUserdata() {
        val targets = _uiState.value.partitions.filter {
            it.name.equals("userdata", ignoreCase = true) ||
            it.name.equals("metadata", ignoreCase = true) ||
            it.name.equals("cache", ignoreCase = true)
        }
        if (targets.isEmpty()) {
            appendLog("ERROR: Read GPT first! Could not find 'userdata' partition.")
            return
        }
        runTask("Factory Reset (Wiping Userdata)") {
            targets.forEach { part ->
                val sectorsToWipe = if (part.name.equals("userdata", true) && part.numSectors > 8192) 8192L else part.numSectors
                appendLog("Formatting '${part.name}' (LUN ${part.lun})...")
                firehose.eraseSectors(part.lun, part.firstLba, sectorsToWipe)
            }
            appendLog("Operation completed successfully.")
        }
    }

    fun flashFullFirmwarePackage() {
        val rawUri = _uiState.value.rawProgramUri
        val dirUri = _uiState.value.firmwareDirUri
        if (rawUri == null || dirUri == null) {
            appendLog("ERROR: Select both rawprogram*.xml and the Firmware Folder first.")
            return
        }
        if (!_uiState.value.firehoseReady) {
            appendLog("ERROR: Firehose is not ready. Connect the 9008 device and run Sahara + Firehose from the 'Read GPT' tab first.")
            return
        }

        runTask("Flashing Full XML Firmware") {
            val treeDoc = DocumentFile.fromTreeUri(context, dirUri)
                ?: throw IllegalStateException("Cannot access firmware folder")
            val fileMap = treeDoc.listFiles().associateBy { it.name.orEmpty() }

            val ops = context.contentResolver.openInputStream(rawUri)?.use {
                FirmwareXmlParser.parseRawProgramXml(it)
            }.orEmpty()
            if (ops.isEmpty()) {
                throw IllegalStateException("No <program> entries with a filename were found in ${_uiState.value.rawProgramName}. Is this a rawprogram*.xml?")
            }

            appendLog("Loaded ${ops.size} flash operations from ${_uiState.value.rawProgramName}.")

            var written = 0
            var skipped = 0
            ops.forEachIndexed { index, op ->
                if (op.startSector < 0L) {
                    appendLog("Skipping ${op.label} (${op.filename}): start_sector '${op.startSectorRaw}' is relative to the disk end and is not supported.")
                    skipped++
                    return@forEachIndexed
                }
                val imgDoc = fileMap[op.filename]
                if (imgDoc == null) {
                    appendLog("Skipping ${op.label} (${op.filename} not found)")
                    skipped++
                    return@forEachIndexed
                }

                appendLog("[${index + 1}/${ops.size}] Writing ${op.label} (${op.filename})...")
                val startNs = System.nanoTime()
                val fileLen = imgDoc.length()

                val input = context.contentResolver.openInputStream(imgDoc.uri)
                    ?: throw IllegalStateException("Cannot open ${op.filename}")
                input.use {
                    firehose.writePartitionStream(
                        lun = op.physicalPartitionNumber,
                        startSector = op.startSector,
                        maxPartitionSectors = op.numPartitionSectors,
                        inputStream = it,
                        totalStreamBytes = fileLen
                    ) { done, total ->
                        updateProgress(done, total, startNs)
                    }
                }
                written++
            }

            if (written == 0) {
                throw IllegalStateException("Nothing was written: none of the image files listed in the XML were found in the selected folder.")
            }

            _uiState.value.patchXmlUri?.let { patchUri ->
                val patches = context.contentResolver.openInputStream(patchUri)?.use {
                    FirmwareXmlParser.parsePatchXml(it)
                }.orEmpty()

                appendLog("Applying ${patches.size} disk patches...")
                patches.forEach { attrs -> firehose.applyPatch(attrs) }
                appendLog("Patches applied.")
            }

            appendLog("Operation completed: $written written, $skipped skipped.")
        }
    }

    fun resetDevice(mode: String = "reset") {
        runTask("Resetting Device ($mode)") {
            if (!_uiState.value.firehoseReady) {
                appendLog("ERROR: Firehose not ready. Cannot send power command.")
                return@runTask
            }
            appendLog("Sending '$mode' command...")
            var accepted = false
            try {
                accepted = firehose.powerControl(mode)
            } catch (e: Exception) {
                appendLog("Warning: ${e.message}")
            }

            if (accepted) {
                appendLog("Reset accepted: device is rebooting now (no need to unplug the cable).")
                // Keep the USB session open until the phone has really left the 9008 bus.
                // Closing it right after the ACK can leave some loaders half-reset, which is
                // what used to force a cable pull. If it is still in EDL, ask again.
                var gone = waitForEdlToLeave(6000)
                var attempt = 1
                while (!gone && attempt < 3 && transport.isConnected) {
                    attempt++
                    appendLog("Device is still in EDL. Re-sending '$mode' (attempt $attempt of 3)...")
                    runCatching { firehose.powerControl(mode) }
                    gone = waitForEdlToLeave(5000)
                }
                if (!gone) {
                    appendLog("WARNING: The device stayed in EDL after the reset. Hold Power + Vol Down for about 10 seconds to force the restart.")
                }
            } else {
                appendLog("WARNING: the loader refused <power>. It will not reboot from software; hold Power + Vol Down ~10s.")
            }

            transport.close()
            _uiState.update {
                it.copy(
                    usbConnected = false,
                    firehoseReady = false,
                    partitions = emptyList(),
                    deviceDescription = "No 9008 Device Detected"
                )
            }
            // Always forget the old handle: whatever shows up next (normal boot, Download Mode,
            // or 9008 again) is treated as a brand-new connection, so no cable re-plug is needed.
            scanMutex.lock()
            try {
                knownUsb.clear()
            } finally {
                scanMutex.unlock()
            }
            appendLog("Device reset command sent. Waiting for the device to come back (no need to unplug the cable)...")
            rescanUsbAfterReset()
        }
    }

    /** True once no Qualcomm 9008 device is left on the USB bus; false if it is still there at the timeout. */
    private suspend fun waitForEdlToLeave(timeoutMs: Long): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (transport.listDevices().none { isEdlDevice(it) }) return true
            delay(500)
        }
        return false
    }

    /** USB attach events that arrive while a task is running are ignored by scanUsbNow() (busy),
     *  so after a reset we poll for a few seconds until the device shows up again and gets reconnected. */
    private fun rescanUsbAfterReset() {
        viewModelScope.launch(Dispatchers.IO) {
            for (i in 1..20) {
                delay(1000)
                scanUsbNow()
                val st = _uiState.value
                if (st.usbConnected || st.odinConnected) break
            }
        }
    }

    /** Reads info from a device connected in MTP / File Transfer mode, no ADB needed:
     *  1) USB descriptors + MTP GetDeviceInfo (manufacturer, model, firmware, serial)
     *  2) READ-ONLY AT queries (IMEI, serial, model, firmware) over the phone's modem/CDC
     *     port, when the current USB mode exposes one. Nothing is ever written to the device.
     *  The serial shown is the USB serial-number descriptor (same as adb/fastboot); the AT
     *  serial is only used when the descriptor is blank. */
    fun readMtpInfo() {
        runTask("Reading MTP Device Info") {
            // Information reads are silent on purpose: the result goes to the Device Info panel only.
            try {
                val devices = MtpInfoReader.findMtpDevices(context)
                if (devices.isEmpty()) {
                    _uiState.update {
                        it.copy(
                            mtpInfoText = "No MTP device found. Connect the phone in 'File Transfer / MTP' mode.",
                            infoImei = "", infoSerial = ""
                        )
                    }
                    return@runTask
                }
                val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
                var serial = ""
                val sections = ArrayList<String>()
                val imeis = LinkedHashSet<String>()
                for (dev in devices) {
                    val sb = StringBuilder()
                    val granted = transport.ensurePermission(dev)
                    var model = ""
                    var maker = ""
                    var firmware = ""
                    var usbSerial = ""
                    if (granted) {
                        // Real device serial (USB serial-number descriptor) -- the source of truth.
                        usbSerial = runCatching { dev.serialNumber }.getOrNull().orEmpty()
                        maker = runCatching { dev.manufacturerName }.getOrNull().orEmpty()
                        model = runCatching { dev.productName }.getOrNull().orEmpty()
                        val mtp = runCatching { MtpDeviceInfoReader.read(usbManager, dev) }.getOrNull()
                        if (mtp != null) {
                            if (mtp.manufacturer.isNotBlank()) maker = mtp.manufacturer
                            if (mtp.model.isNotBlank()) model = mtp.model
                            firmware = mtp.deviceVersion
                            // NOTE: mtp.serial is intentionally NOT used to overwrite usbSerial --
                            // on many Samsung devices it is a different, vendor-specific value,
                            // not the real serial number.
                            if (usbSerial.isBlank() && mtp.serial.isNotBlank()) usbSerial = mtp.serial
                        }
                    }
                    // ---- Modem info over the phone's AT port (IMEI, serial, model, firmware, ...) ----
                    var atText = ""
                    if (granted) {
                        val atCmds = listOf(
                            "AT+CGSN", "AT+GSN", "AT+IMEINUM", "AT+DEVCONINFO",
                            "AT+CGMI", "AT+CGMM", "AT+CGMR", "AT+GMI", "AT+GMM", "AT+GMR",
                            "ATI", "AT+GCAP", "AT+CBC", "AT+CSQ", "AT+CFUN?", "AT+CPIN?"
                        )
                        val at = runCatching { AtCommandTransport(context).use { it.queryReadOnly(dev, atCmds) } }
                            .getOrDefault(emptyMap())
                        if (at.isEmpty()) {
                            atText = "No modem port answered in this USB mode.\n" +
                                "Some phones only expose it in a modem/diag USB configuration " +
                                "(on some Samsung phones: dial *#0808# and pick a mode that includes MODEM)."
                        } else {
                            fun v(vararg keys: String): String {
                                for (k in keys) {
                                    val txt = at[k].orEmpty().lines()
                                        .map { it.trim().replace(Regex("^\\+[A-Za-z]+:\\s*"), "") }
                                        .filter { it.isNotEmpty() }
                                        .joinToString(" | ")
                                    if (txt.isNotBlank()) return txt
                                }
                                return ""
                            }
                            // Samsung AT+DEVCONINFO answers with KEY(value) pairs.
                            val devRaw = at["AT+DEVCONINFO"].orEmpty()
                            val kv = LinkedHashMap<String, String>()
                            Regex("([A-Z0-9_]+)\\(([^)]*)\\)").findAll(devRaw)
                                .forEach { kv[it.groupValues[1]] = it.groupValues[2].replace(Regex("\\s+"), "").trim() }

                            val found = LinkedHashSet<String>()
                            kv["IMEI"]?.takeIf { it.length in 14..16 && it.all { c -> c.isDigit() } }?.let { found.add(it) }
                            listOf("AT+CGSN", "AT+GSN", "AT+IMEINUM").forEach { k ->
                                Regex("(?<![0-9])[0-9]{15}(?![0-9])").findAll(at[k].orEmpty()).forEach { found.add(it.value) }
                            }
                            // Last resort: look for IMEI(...) anywhere in any reply (covers shifted/merged replies).
                            if (found.isEmpty()) {
                                Regex("IMEI\\(([0-9]{14,16})\\)").findAll(at.values.joinToString("\n"))
                                    .forEach { found.add(it.groupValues[1]) }
                            }
                            imeis.addAll(found)
                            if (usbSerial.isBlank()) kv["SN"]?.takeIf { it.isNotBlank() }?.let { usbSerial = it }

                            val sbAt = StringBuilder()
                            fun line(label: String, value: String) {
                                if (value.isNotBlank() && value != "null" && value != "NA") sbAt.appendLine("$label: $value")
                            }
                            fun group(title: String) { if (sbAt.isNotEmpty()) sbAt.appendLine(); sbAt.appendLine("[$title]") }

                            group("Identity")
                            line("Model", kv["MN"].orEmpty().ifBlank { v("AT+CGMM", "AT+GMM") })
                            line("Base model", kv["BASE"].orEmpty().takeIf { it != "UNKNOWN" }.orEmpty())
                            line("Manufacturer", v("AT+CGMI", "AT+GMI"))
                            line("Serial", kv["SN"].orEmpty())
                            line("IMEI", found.joinToString(" / "))
                            line("Unique number", kv["UN"].orEmpty())
                            line("Phone number", kv["PN"].orEmpty())

                            group("Firmware")
                            fun versionLines(prefix: String, raw: String) {
                                val parts = raw.split("/").filter { it.isNotBlank() }
                                val names = listOf("AP", "CSC", "CP", "Build")
                                if (parts.size <= 1) { line(prefix, raw); return }
                                parts.forEachIndexed { i, part -> line("$prefix ${names.getOrElse(i) { "#${i + 1}" }}", part) }
                            }
                            versionLines("Version", kv["VER"].orEmpty())
                            if (kv["HIDVER"] != kv["VER"]) versionLines("Hidden", kv["HIDVER"].orEmpty())
                            line("Baseband", v("AT+CGMR", "AT+GMR"))
                            line("Modem ID", v("ATI"))
                            line("Capabilities", v("AT+GCAP"))

                            group("Region")
                            line("Product code", kv["PRD"].orEmpty())
                            line("Country", kv["CC"].orEmpty())
                            line("OMC / CSC", kv["OMCCODE"].orEmpty())
                            line("MCC / MNC", listOf(kv["MCC"].orEmpty(), kv["MNC"].orEmpty()).filter { it.isNotBlank() }.joinToString(" / "))
                            line("AID", kv["AID"].orEmpty())

                            group("Status")
                            line("Connections", kv["CON"].orEmpty().replace(",", ", "))
                            line("Lock", kv["LOCK"].orEmpty())
                            line("Limit", kv["LIMIT"].orEmpty())
                            line("SDP", kv["SDP"].orEmpty())
                            line("Partitions (HVID)", kv["HVID"].orEmpty().replace(",", ", ").replace(":", " = "))
                            line("Battery (+CBC)", v("AT+CBC"))
                            line("Signal (+CSQ)", v("AT+CSQ"))
                            line("Radio (+CFUN)", v("AT+CFUN?"))
                            line("SIM status", v("AT+CPIN?"))
                            val shown = setOf("MN","BASE","SN","UN","PN","VER","HIDVER","PRD","CC","OMCCODE","MCC","MNC","AID","CON","LOCK","LIMIT","SDP","HVID","IMEI")
                            val extra = kv.filterKeys { it !in shown }
                            if (extra.isNotEmpty()) { group("Other"); extra.forEach { (k, value) -> line(k, value) } }
                            atText = sbAt.toString().replace(Regex("\\[[A-Za-z ]+\\]\\n(?=\\n|\\[|$)"), "").replace(Regex("\\n{3,}"), "\n\n").trim().ifBlank { "Modem answered but returned no readable info." }
                        }
                    }
                    if (usbSerial.isNotBlank()) serial = usbSerial
                    sb.appendLine("Manufacturer: ${maker.ifBlank { "Unknown" }}")
                    sb.appendLine("Model: ${model.ifBlank { "Unknown" }}")
                    sb.appendLine("Serial (SN): ${usbSerial.ifBlank { "Unavailable" }}")
                    sb.appendLine("IMEI: ${if (imeis.isEmpty()) "Unavailable" else imeis.joinToString(" / ")}")
                    sb.appendLine("--- Firmware ---")
                    sb.appendLine("MTP device version: ${firmware.ifBlank { "-" }}")
                    sb.appendLine("--- USB ---")
                    sb.append(MtpInfoReader.describe(context, dev).trimEnd())
                    sb.append("\n--- Modem ---\n").append(atText)
                    if (!granted) sb.append("\nUSB permission was not granted, so the serial / firmware could not be read.")
                    sections.add(sb.toString())
                }
                _uiState.update {
                    it.copy(mtpInfoText = sections.joinToString("\n\n"), infoImei = imeis.joinToString("\n"), infoSerial = serial)
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(mtpInfoText = "Could not read device info: ${e.message}") }
            }
        }
    }

    /** Sends a mode-switch AT command (reboot / Download Mode) over the phone's modem port
     *  while it is in MTP mode. No ADB needed. */
    private fun runAtModeAction(title: String, label: String, send: suspend (AtCommandTransport, UsbDevice) -> Boolean) {
        runTask(title) {
            val devices = MtpInfoReader.findMtpDevices(context)
            if (devices.isEmpty()) {
                appendLog("ERROR: No MTP device found. Connect the phone in 'File Transfer / MTP' mode.")
                return@runTask
            }
            var done = false
            for (dev in devices) {
                val ok = runCatching {
                    AtCommandTransport(context).use { send(it, dev) }
                }.getOrElse { e ->
                    appendLog("AT error: ${e.message}")
                    false
                }
                if (ok) { done = true; break }
            }
            if (done) {
                appendLog("AT command accepted. The device should switch to $label shortly.")
            } else {
                appendLog("The device did not confirm. It may still be switching; if nothing happens, this USB mode has no AT port (on some Samsung phones: *#0808# -> pick a mode with MODEM).")
            }
        }
    }

    fun atRebootDevice() = runTask("AT Reboot (MTP)") {
        val devices = MtpInfoReader.findMtpDevices(context)
        if (devices.isEmpty()) {
            appendLog("ERROR: No MTP device found. Connect the phone in 'File Transfer / MTP' mode.")
            return@runTask
        }
        var rebooted = false
        for (dev in devices) {
            val ok = runCatching { AtCommandTransport(context).use { it.tryReboot(dev) { m -> appendLog(m) } } }
                .getOrElse { e -> appendLog("AT error: ${e.message}"); false }
            if (ok) { rebooted = true; break }
        }
        if (rebooted) appendLog("Reboot confirmed (device left the USB bus).")
        else appendLog("Reboot NOT confirmed. Use 'ADB -> Reboot' if USB debugging is on.")
    }

    fun atToDownloadMode() = runAtModeAction("AT -> Download Mode (MTP)", "Download Mode") { at, dev ->
        at.tryRebootToDownloadMode(dev) { appendLog(it) }
    }

    /** Connects over the standard ADB protocol (same handshake a PC's adb uses --
     *  the target still shows its normal "Allow USB debugging?" prompt on first use)
     *  and reads basic build/device properties via 'getprop'. */
    fun readAdbInfo() {
        runTask("Reading ADB Device Info") {
        quietInfoRead = true
            if (!adbClient.connect()) {
                _uiState.update {
                    it.copy(
                        adbConnected = false,
                        adbInfoText = "ADB connection failed. Enable USB Debugging and accept the prompt on the device."
                    )
                }
                return@runTask
            }
            val keys = listOf(
                "ro.product.manufacturer", "ro.product.model", "ro.product.name", "ro.product.device",
                "ro.board.platform", "ro.build.version.release", "ro.build.version.sdk",
                "ro.build.version.security_patch", "ro.serialno",
                "ro.boot.verifiedbootstate", "ro.boot.flash.locked", "ro.boot.vbmeta.device_state",
                "ro.oem_unlock_supported", "ro.crypto.state", "ro.crypto.type", "ro.boot.warranty_bit",
                "ro.build.display.id", "ro.build.PDA", "ro.bootloader", "gsm.version.baseband",
                "ro.csc.sales_code", "ro.build.version.incremental", "ro.build.type", "ro.build.tags",
                "ro.build.date", "ro.build.fingerprint",
                "ril.imei", "persist.radio.imei", "ro.ril.oem.imei"
            )
            val raw = adbClient.shell("for p in ${keys.joinToString(" ")}; do echo \"\$p=\$(getprop \$p)\"; done")
            val props = raw.lines().mapNotNull { line ->
                val i = line.indexOf('=')
                if (i <= 0) null else line.substring(0, i).trim() to line.substring(i + 1).trim()
            }.toMap()
            fun p(k: String): String = props[k]?.takeIf { it.isNotBlank() } ?: "-"

            var imei: String? = null
            for (cmd in listOf("service call iphonesubinfo 1", "service call iphonesubinfo 1 s16 com.android.shell")) {
                imei = parseParcelDigits(adbClient.shell(cmd))
                if (imei != null) break
            }
            if (imei == null) {
                imei = listOf("ril.imei", "persist.radio.imei", "ro.ril.oem.imei")
                    .mapNotNull { k -> props[k]?.filter { c -> c.isDigit() }?.takeIf { d -> d.length >= 14 } }
                    .firstOrNull()
            }

            val summary = buildString {
                appendLine("Model: ${p("ro.product.manufacturer")} ${p("ro.product.model")} (${p("ro.product.name")})")
                appendLine("Device: ${p("ro.product.device")} | Platform: ${p("ro.board.platform")}")
                appendLine("Android: ${p("ro.build.version.release")} (SDK ${p("ro.build.version.sdk")})")
                appendLine("Security Patch: ${p("ro.build.version.security_patch")}")
                appendLine("IMEI: ${imei ?: "Unavailable (Wi-Fi only or restricted)"}")
                appendLine("Serial: ${p("ro.serialno")}")
                appendLine("--- Protection ---")
                appendLine("Verified Boot: ${p("ro.boot.verifiedbootstate")}")
                appendLine("Bootloader Locked: ${p("ro.boot.flash.locked")} (vbmeta: ${p("ro.boot.vbmeta.device_state")})")
                appendLine("OEM Unlock Supported: ${p("ro.oem_unlock_supported")}")
                appendLine("Encryption: ${p("ro.crypto.state")} / ${p("ro.crypto.type")}")
                appendLine("Knox Warranty Bit: ${p("ro.boot.warranty_bit")}")
                appendLine("--- Firmware ---")
                appendLine("Build ID: ${p("ro.build.display.id")}")
                appendLine("AP / PDA: ${p("ro.build.PDA")}")
                appendLine("Bootloader: ${p("ro.bootloader")}")
                appendLine("Baseband: ${p("gsm.version.baseband")}")
                appendLine("CSC: ${p("ro.csc.sales_code")}")
                appendLine("Incremental: ${p("ro.build.version.incremental")}")
                appendLine("Build Type / Tags: ${p("ro.build.type")} / ${p("ro.build.tags")}")
                appendLine("Build Date: ${p("ro.build.date")}")
                append("Fingerprint: ${p("ro.build.fingerprint")}")
            }
            // (silent: info reads never write to the log)
            _uiState.update {
                it.copy(
                    adbConnected = true,
                    adbInfoText = summary,
                    infoImei = imei.orEmpty(),
                    infoSerial = p("ro.serialno").takeIf { s -> s != "-" }.orEmpty()
                )
            }
        }
    }

    /** Extracts the digits from the quoted ASCII column of a `service call` Parcel dump (IMEI). */
    private fun parseParcelDigits(out: String): String? {
        val sb = StringBuilder()
        Regex("'([^']*)'").findAll(out).forEach { m ->
            m.groupValues[1].forEach { c -> if (c.isDigit()) sb.append(c) }
        }
        val d = sb.toString()
        return if (d.length >= 14) d.take(15) else null
    }

    /** Sends a manually typed command to the device's ADB shell and appends the output. */
    fun runAdbShell(command: String) {
        var cmd = command.trim()
        if (cmd.isEmpty()) return
        // Accept pasted PC-style commands such as "adb shell getprop".
        cmd = cmd.removePrefix("adb ").trim().removePrefix("shell ").trim()
        if (cmd.isEmpty()) return
        runTask("ADB Shell") {
            if (!adbClient.connect()) {
                _uiState.update {
                    it.copy(
                        adbConnected = false,
                        adbShellOutput = (it.adbShellOutput + "\n\$ " + cmd + "\nADB connection failed. Enable USB Debugging and accept the prompt on the device.").takeLast(8000)
                    )
                }
                return@runTask
            }
            val out = adbClient.shell(cmd, timeoutMs = 30000).trimEnd()
            _uiState.update {
                it.copy(
                    adbConnected = true,
                    adbShellOutput = (it.adbShellOutput + "\n\$ " + cmd + "\n" + out.ifEmpty { "(no output)" }).trim().takeLast(8000)
                )
            }
        }
    }

    /** Reboots the connected device into the requested target mode over ADB.
     *  'target' follows the standard adb reboot targets: "" (normal), "recovery",
     *  "download" (Samsung Odin/Download mode), "edl" (Qualcomm chips that expose it).
     *  The device itself decides whether a given target is supported. */
    fun adbRebootTo(target: String, label: String) {
        runTask("ADB Reboot -> $label") {
            if (!adbClient.connect()) {
                appendLog("ERROR: could not establish ADB connection (is USB Debugging enabled and authorized?).")
                return@runTask
            }
            appendLog("Sending 'reboot:$target' ...")
            adbClient.reboot(target)
            appendLog("Reboot command sent. Device should switch to $label shortly.")
            adbClient.close()
            _uiState.update { it.copy(adbConnected = false) }
        }
    }

    private fun updateProgress(doneBytes: Long, totalBytes: Long, startTimeNs: Long) {
        if (totalBytes <= 0L) return
        val elapsedSec = ((System.nanoTime() - startTimeNs) / 1_000_000_000.0).coerceAtLeast(0.001)
        val mbps = (doneBytes / (1024.0 * 1024.0)) / elapsedSec
        val fraction = (doneBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
        _uiState.update {
            it.copy(progress = fraction, transferSpeedMbps = mbps)
        }
    }

    private fun runTask(taskTitle: String, block: suspend () -> Unit) {
        if (_uiState.value.isBusy) return
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update {
                it.copy(isBusy = true, currentTask = taskTitle, progress = 0f, transferSpeedMbps = 0.0)
            }
            try {
                block()
            } catch (e: Exception) {
                appendLog("ERROR [$taskTitle]: ${e.message}")
            } finally {
                quietInfoRead = false
                _uiState.update {
                    it.copy(isBusy = false, currentTask = "Ready")
                }
                // USB attach/detach events that arrived while busy were skipped: catch up now.
                scanUsb()
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        transport.unregisterDetachListener()
        transport.close()
        adbClient.close()
    }
}
