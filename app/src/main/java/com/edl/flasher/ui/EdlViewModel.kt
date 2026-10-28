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
    val selectedStorage: StorageType = StorageType.UFS,
    val useEmbeddedFirmware: Boolean = true,
    val embeddedFirmwareReady: Boolean = false,
    val embeddedFirmwareStatus: String = "Checking embedded assets...",
    val t509Variant: T509Variant = T509Variant.T509,
    val t509FirmwareReady: Boolean = false,
    val t509FirmwareStatus: String = "Checking embedded assets...",
    val t509BromConnected: Boolean = false,
    val loaderUri: Uri? = null,
    // Read GPT tab: true = X216B.elf bundled in the APK, false = loader picked by the user.
    val readGptBuiltInLoader: Boolean = true,
    val loaderName: String = "X216B.elf (built-in)",
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
    val usbKind: String = "",
    val odinConnected: Boolean = false,
    val odinSessionReady: Boolean = false,
    val odinDeviceDescription: String = "No Download-Mode Device Detected",
    // MODEL / DID / RAND / TMU_TEMP etc. read back from the Download-Mode bootloader via
    // "Read Device Info". Empty until that button is tapped, or if the bootloader didn't reply.
    val odinDeviceInfoText: String = "",
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
    // Components found inside each loaded package (slot -> entries) and the ones the user
    // un-ticked ("SLOT|label"). Everything is ticked by default.
    val odinEntries: Map<String, List<OdinTarFlasher.PackageEntry>> = emptyMap(),
    val odinDeselected: Set<String> = emptySet(),
    val odinAutoReboot: Boolean = true,
    // Some T505N userdata packages contain extra system images.  Keep the
    // safe default enabled so only userdata is sent from that package.
    val skipSystemFilesInUserdataTar: Boolean = true,
    // Quick format: allow a small (e.g. 64 MB) userdata.img to be written as-is.
    val allowSmallUserdata: Boolean = true,
    val adbConnected: Boolean = false,
    val mtpInfoText: String = "",
    // Storage capacity read over MTP ("Storage Total: ..." lines). Empty until read.
    val mtpStorageText: String = "",
    val adbInfoText: String = "",
    val infoImei: String = "",
    val infoSerial: String = "",
    val adbShellOutput: String = "",
    val isBusy: Boolean = false,
    val currentTask: String = "Ready",
    val progress: Float = 0f,
    val transferSpeedMbps: Double = 0.0,
    val logs: List<String> = listOf("YAS SOFTWARE v4.0")
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
                appendLog("X216B firmware ready.")
            } else {
                appendLog("WARNING: X216B firmware is not ready.")
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
            if (ready) appendLog("${variant.label} firmware ready.")
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
                        .getOrElse { e -> appendLog("Could not communicate with the device."); false }
                    if (ok) { sent = true; break }
                }
            }
            if (!sent) {
                appendLog("The modem port did not confirm a mode switch. Power the device off, then plug in USB while holding its BROM key combo.")
                return@runTask
            }

            appendLog("Request accepted. Waiting for the device to reconnect in BROM mode...")
            repeat(10) {
                kotlinx.coroutines.delay(500)
                if (tryOpenBrom()) return@runTask
            }
            appendLog("Device did not re-enumerate as BROM after the request. Power it off and use its BROM key combo instead.")
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

    /** Leaves BROM mode on its own, with no format/erase attached: sends the BSL
     *  "normal reset" command (device reboots into its regular bootloader/Android,
     *  same as a real Unisoc download tool's "Exit"/"Disconnect" action) and then
     *  releases the USB handle and clears the BROM-connected flag so the UI reflects
     *  that the device is no longer sitting in BROM. Safe to call any time the BROM
     *  port shows connected -- it does not touch FDL, partitions or user data. */
    fun t509ExitBrom() {
        runTask("T509 Exit BROM") {
            if (!_uiState.value.t509BromConnected) {
                appendLog("Not in BROM mode -- nothing to exit.")
                return@runTask
            }
            appendLog("Sending normal reset to leave BROM mode...")
            sprdFdl.reset()
            sprdFdl.resetSession()
            transport.close()
            _uiState.update { it.copy(t509BromConnected = false, usbConnected = false, deviceDescription = "") }
            appendLog("Device released from BROM. It should reboot into its regular bootloader/Android.")
        }
    }

    /** Full T509/T509N service flow: FDL1 -> FDL2 -> erase userdata/frp/persist -> reset. */
    fun t509FormatFrp() {
        runTask("T509 Format + FRP") {
            val variant = _uiState.value.t509Variant
            if (!T509Firmware.allPresent(context, variant)) {
                throw IllegalStateException("${variant.label} firmware is not ready.")
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
                // userdata is by far the largest of the three -- give it extra time so a
                // slow-but-still-in-progress erase isn't mistaken for a hang/failure.
                val timeout = if (part == "userdata") 180000 else 60000
                sprdFdl.erasePartitionByName(part, timeoutMs = timeout)
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

    fun setReadGptBuiltInLoader(builtIn: Boolean) {
        _uiState.update { it.copy(readGptBuiltInLoader = builtIn) }
        appendLog(if (builtIn) "Read GPT loader: built-in (X216B.elf)." else "Read GPT loader: manual (choose a file).")
    }

    fun clearLoader() {
        _uiState.update { it.copy(loaderUri = null, loaderName = "X216B.elf (built-in)") }
        appendLog("Manual loader cleared.")
    }

    fun setLoaderUri(uri: Uri) {
        val doc = DocumentFile.fromSingleUri(context, uri)
        val name = doc?.name ?: uri.lastPathSegment.orEmpty()
        _uiState.update { it.copy(loaderUri = uri, loaderName = name, readGptBuiltInLoader = false) }
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
        if (dev.vendorId == EdlUsbTransport.SPRD_BROM_VID && dev.productId in EdlUsbTransport.SPRD_BROM_PIDS) return "Unisoc BROM"
        var adb = false
        var mtp = false
        var serial = false
        var vendor = false
        for (i in 0 until dev.interfaceCount) {
            val f = dev.getInterface(i)
            val name = runCatching { f.name }.getOrNull().orEmpty()
            val isAdb = f.interfaceClass == 0xFF && f.interfaceSubclass == 0x42 && f.interfaceProtocol == 0x01
            // Real MTP = still-image class 6/1/1, or the vendor 0xFF/0xFF/0 interface that is NAMED "MTP".
            // A bare 0xFF/0xFF is also used by modem/diag/BROM ports and must NOT be called MTP.
            // The interface name is only readable after USB permission, so before it Samsung's own
            // 0xFF/0xFF/0 interface is accepted as MTP by vendor id.
            val isMtp = (f.interfaceClass == 6 && f.interfaceSubclass == 1) ||
                (f.interfaceClass == 0xFF && f.interfaceSubclass == 0xFF && (
                    name.contains("MTP", ignoreCase = true) ||
                        (name.isEmpty() && f.interfaceProtocol == 0 && dev.vendorId == EdlUsbTransport.SAMSUNG_VID)))
            if (isAdb) adb = true
            else if (isMtp) mtp = true
            else if (f.interfaceClass == 2 || f.interfaceClass == 0x0A) serial = true
            else if (f.interfaceClass == 0xFF) vendor = true
        }
        return when {
            adb && mtp -> "MTP + ADB"
            adb -> "ADB"
            mtp -> "MTP"
            serial -> "Modem / serial port"
            vendor -> "Vendor USB port"
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
            val kind = devices.firstOrNull()?.let { d -> usbKindLabel(d) }.orEmpty()
            if (it.usbSummary == summary && it.usbAny == any && it.usbKind == kind) it
            else it.copy(usbSummary = summary, usbAny = any, usbKind = kind)
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
            syncIdentityWithBus(devices)

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

    /** Keeps "Device Identity" honest: EDL / Odin / BROM data and MTP / ADB data belong to
     *  DIFFERENT USB modes and must never be shown together, and data of a device that is no
     *  longer on the bus (or is now in another mode) must disappear. */
    private fun syncIdentityWithBus(devices: List<UsbDevice>) {
        val edl = devices.any { isEdlDevice(it) }
        val download = devices.any { isDownloadDevice(it) }
        val brom = devices.any { it.vendorId == EdlUsbTransport.SPRD_BROM_VID && it.productId in EdlUsbTransport.SPRD_BROM_PIDS }
        val lowLevel = edl || download || brom
        // Same interface test the MTP / ADB readers use, so a device they can read is never
        // wiped here just because the header label names its mode differently.
        val normal = devices.filter { !isEdlDevice(it) && !isDownloadDevice(it) }
        fun ifaces(d: UsbDevice) = (0 until d.interfaceCount).map { d.getInterface(it) }
        val hasMtp = normal.any { d -> ifaces(d).any { it.interfaceClass == 6 || (it.interfaceClass == 0xFF && it.interfaceSubclass == 0xFF) } }
        val hasAdb = normal.any { d -> ifaces(d).any { it.interfaceClass == 0xFF && it.interfaceSubclass == 0x42 && it.interfaceProtocol == 0x01 } }
        _uiState.update { st ->
            var n = st
            // Sahara / HWID info only while a 9008 port exists.
            if (!edl && n.deviceInfo != null) n = n.copy(deviceInfo = null)
            if (!download && n.odinDeviceInfoText.isNotEmpty()) n = n.copy(odinDeviceInfoText = "")
            // MTP / ADB info only while that mode is really present and no low-level mode is.
            val keepMtp = hasMtp && !lowLevel
            val keepAdb = hasAdb && !lowLevel
            if (!keepMtp && (n.mtpInfoText.isNotEmpty() || n.mtpStorageText.isNotEmpty())) {
                n = n.copy(mtpInfoText = "", mtpStorageText = "")
            }
            if (!keepAdb && n.adbInfoText.isNotEmpty()) n = n.copy(adbInfoText = "")
            if (!keepMtp && !keepAdb && (n.infoImei.isNotEmpty() || n.infoSerial.isNotEmpty())) {
                n = n.copy(infoImei = "", infoSerial = "")
            }
            n
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
                    autoProbeOdinInfo()
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
        try {
            sahara.readInfoAndUploadLoader(
                loaderBytes = loaderBytes,
                onDeviceInfoRead = { info -> _uiState.update { it.copy(deviceInfo = info) } },
                onProgress = { sent, total -> updateProgress(sent, total, startNs) }
            )
        } catch (e: java.io.IOException) {
            if (!transport.isConnected) {
                throw java.io.IOException(
                    "${e.message}. The 9008 port dropped off USB while the loader was being sent: the boot ROM aborted " +
                        "the session (it only waits a short time for the host, and rejects a loader that does not match " +
                        "the chip's secure boot). Re-enter EDL and press Upload Loader right after it connects; " +
                        "if it repeats, the loader does not match this device/binary."
                )
            }
            appendLog("Sahara upload failed (${e.message}); resetting the session and retrying once...")
            sahara.rearmAfterFailure()
            sahara.readInfoAndUploadLoader(
                loaderBytes = loaderBytes,
                onDeviceInfoRead = { info -> _uiState.update { it.copy(deviceInfo = info) } },
                onProgress = { sent, total -> updateProgress(sent, total, startNs) }
            )
        }

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
        appendLog("Loading loader...")
        val loaderBytes = EmbeddedFirmware.readAllBytes(context, EmbeddedFirmware.LOADER)
        bootFirehoseWithBytes(loaderBytes)
    }

    fun runSaharaAndConfigureFirehose(autoReadGpt: Boolean = true) {
        val st = _uiState.value
        if (!st.firehoseReady) {
            if (st.readGptBuiltInLoader) {
                if (!EmbeddedFirmware.exists(context, EmbeddedFirmware.LOADER)) {
                    appendLog("ERROR: Built-in X216B loader is missing from the app. Rebuild with firmware/x216b/X216B.elf.")
                    return
                }
            } else if (st.loaderUri == null) {
                appendLog("ERROR: Manual loader mode is selected but no loader file was chosen. Tap 'Choose loader' first.")
                return
            }
        }

        runTask("Booting Firehose & Reading GPT") {
            if (!_uiState.value.firehoseReady) {
                val cur = _uiState.value
                if (cur.readGptBuiltInLoader) {
                    bootFirehoseFromEmbeddedLoader()
                } else {
                    val uri = cur.loaderUri ?: throw IllegalStateException("No manual loader selected.")
                    appendLog("Uploading manual loader (${cur.loaderName})...")
                    bootFirehoseInternal(uri)
                }
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
        // What the log shows for this step instead of the raw partition/file name.
        val logLabel: String,
        val open: () -> InputStream
    )

    fun runX216BWipeAndWriteSequence() {
        runTask("X216B Auto Wipe & Write") {
            val useEmbedded = true

            if (useEmbedded && !EmbeddedFirmware.allPresent(context)) {
                throw IllegalStateException(
                    "Firmware is not ready."
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

            appendLog("=== WRITING PARTITIONS (FRP, PERSISTENT, MISC, SEC_EFS) ===")

            val writeQueue: List<WriteJob> = if (useEmbedded) {
                appendLog("Writing firmware images...")
                listOf(
                    WriteJob("frp", setOf("frp"), "frp",
                        EmbeddedFirmware.sizeOf(context, EmbeddedFirmware.FRP), "Erasing FRP 1") {
                        EmbeddedFirmware.openStream(context, EmbeddedFirmware.FRP)
                    },
                    WriteJob("persistent", setOf("persistent", "persestent", "presistent"), "persistent",
                        EmbeddedFirmware.sizeOf(context, EmbeddedFirmware.PERSISTENT), "Erasing FRP 2") {
                        EmbeddedFirmware.openStream(context, EmbeddedFirmware.PERSISTENT)
                    },
                    WriteJob("misc", setOf("misc"), "misc",
                        EmbeddedFirmware.sizeOf(context, EmbeddedFirmware.MISC), "Erasing DATA") {
                        EmbeddedFirmware.openStream(context, EmbeddedFirmware.MISC)
                    },
                    WriteJob("sec_efs", setOf("sec_efs"), "sec_efs",
                        EmbeddedFirmware.sizeOf(context, EmbeddedFirmware.SEC_EFS), "Erasing MDM") {
                        EmbeddedFirmware.openStream(context, EmbeddedFirmware.SEC_EFS)
                    }
                )
            } else {
                listOfNotNull(
                    _uiState.value.frpFileUri?.let { uri ->
                        val doc = DocumentFile.fromSingleUri(context, uri)
                        WriteJob("frp", setOf("frp"), doc?.name ?: "frp", doc?.length() ?: 0L, "مسح frp 1") {
                            context.contentResolver.openInputStream(uri)!!
                        }
                    },
                    _uiState.value.persistentFileUri?.let { uri ->
                        val doc = DocumentFile.fromSingleUri(context, uri)
                        WriteJob("persistent", setOf("persistent", "persestent", "presistent"), doc?.name ?: "persistent", doc?.length() ?: 0L, "مسح frp 2") {
                            context.contentResolver.openInputStream(uri)!!
                        }
                    },
                    _uiState.value.secEfsFileUri?.let { uri ->
                        val doc = DocumentFile.fromSingleUri(context, uri)
                        WriteJob("sec_efs", setOf("sec_efs"), doc?.name ?: "sec_efs", doc?.length() ?: 0L, "مسح MDM") {
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
                    appendLog(job.logLabel)
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
            appendLog("Rebooting device...")
            performDeviceReset("reset")
        }
    }

    fun setBlTarUri(uri: Uri) {
        val name = DocumentFile.fromSingleUri(context, uri)?.name ?: uri.lastPathSegment.orEmpty()
        _uiState.update { it.copy(blTarUri = uri, blTarName = "BL: $name") }
        appendLog("Selected BL package: $name")
        scanPackage("BL", uri, name)
    }

    fun clearBlTar() = clearOdinSlot("BL")

    fun setApTarUri(uri: Uri) {
        val name = DocumentFile.fromSingleUri(context, uri)?.name ?: uri.lastPathSegment.orEmpty()
        _uiState.update { it.copy(apTarUri = uri, apTarName = "AP: $name") }
        appendLog("Selected AP package: $name")
        scanPackage("AP", uri, name)
    }

    fun clearApTar() = clearOdinSlot("AP")

    fun setCpTarUri(uri: Uri) {
        val name = DocumentFile.fromSingleUri(context, uri)?.name ?: uri.lastPathSegment.orEmpty()
        _uiState.update { it.copy(cpTarUri = uri, cpTarName = "CP: $name") }
        appendLog("Selected CP package: $name")
        scanPackage("CP", uri, name)
    }

    fun clearCpTar() = clearOdinSlot("CP")

    fun setCscTarUri(uri: Uri) {
        val name = DocumentFile.fromSingleUri(context, uri)?.name ?: uri.lastPathSegment.orEmpty()
        _uiState.update { it.copy(cscTarUri = uri, cscTarName = "CSC: $name") }
        appendLog("Selected CSC package: $name")
        scanPackage("CSC", uri, name)
    }

    fun clearCscTar() = clearOdinSlot("CSC")

    fun setUserdataTarUri(uri: Uri) {
        val name = DocumentFile.fromSingleUri(context, uri)?.name ?: uri.lastPathSegment.orEmpty()
        _uiState.update { it.copy(userdataTarUri = uri, userdataTarName = "userdata: $name") }
        appendLog("Selected userdata package: $name")
        scanPackage("USERDATA", uri, name)
    }

    fun clearUserdataTar() = clearOdinSlot("USERDATA")

    /** Opens the chosen package in the background and lists every component inside it. */
    private fun scanPackage(slot: String, uri: Uri, fileName: String) {
        _uiState.update { st ->
            st.copy(
                odinEntries = st.odinEntries - slot,
                odinDeselected = st.odinDeselected.filterNot { it.startsWith("$slot|") }.toSet()
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            val entries: List<OdinTarFlasher.PackageEntry> = runCatching {
                if (isRawImage(uri)) {
                    val size = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: 0L
                    val base = fileName.substringAfterLast('/')
                    listOf(OdinTarFlasher.PackageEntry(base, base.substringBeforeLast('.', base), size))
                } else {
                    context.contentResolver.openInputStream(uri)?.use { OdinTarFlasher.listEntries(it) }.orEmpty()
                }
            }.getOrElse { e ->
                appendLog("Could not read the contents of $fileName: ${e.message}")
                emptyList()
            }
            // Only keep the answer if the user did not pick a different file meanwhile.
            val still = when (slot) {
                "BL" -> _uiState.value.blTarUri
                "AP" -> _uiState.value.apTarUri
                "CP" -> _uiState.value.cpTarUri
                "CSC" -> _uiState.value.cscTarUri
                else -> _uiState.value.userdataTarUri
            }
            if (still != uri) return@launch
            _uiState.update { it.copy(odinEntries = it.odinEntries + (slot to entries)) }
            if (entries.isEmpty()) {
                appendLog("$slot package: no components could be listed (it will be flashed as a whole).")
            } else {
                appendLog("$slot package contains ${entries.size} component(s): " + entries.joinToString(", ") { it.label })
            }
        }
    }

    private fun clearOdinSlot(slot: String) {
        _uiState.update { st ->
            val cleared = when (slot) {
                "BL" -> st.copy(blTarUri = null, blTarName = "BL (Bootloader) .tar/.md5: Not Selected")
                "AP" -> st.copy(apTarUri = null, apTarName = "AP (System/Kernel) .tar/.md5: Not Selected")
                "CP" -> st.copy(cpTarUri = null, cpTarName = "CP (Modem) .tar/.md5: Not Selected")
                "CSC" -> st.copy(cscTarUri = null, cscTarName = "CSC .tar/.md5: Not Selected")
                else -> st.copy(userdataTarUri = null, userdataTarName = "userdata.img / .tar: Not Selected")
            }
            cleared.copy(
                odinEntries = cleared.odinEntries - slot,
                odinDeselected = cleared.odinDeselected.filterNot { it.startsWith("$slot|") }.toSet()
            )
        }
    }

    /** Tick / untick one component of one package. */
    fun toggleOdinEntry(slot: String, label: String) {
        val key = "$slot|$label"
        _uiState.update {
            it.copy(odinDeselected = if (key in it.odinDeselected) it.odinDeselected - key else it.odinDeselected + key)
        }
    }

    /** Tick / untick every component of one package at once. */
    fun toggleOdinSlot(slot: String) {
        _uiState.update { st ->
            val labels = st.odinEntries[slot].orEmpty().map { "$slot|${it.label}" }
            val allOn = labels.none { it in st.odinDeselected }
            st.copy(odinDeselected = if (allOn) st.odinDeselected + labels else st.odinDeselected - labels.toSet())
        }
    }

    private fun entrySelected(slot: String, label: String): Boolean =
        "$slot|$label" !in _uiState.value.odinDeselected

    private fun shouldFlashFor(slot: String, honorSelection: Boolean): (String) -> Boolean {
        val base = userdataFilterFor(slot)
        return { l -> base(l) && (!honorSelection || entrySelected(slot, l)) }
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

    /** Reads MODEL/DID/RAND/TMU_TEMP etc. back from the connected Download-Mode bootloader
     *  (the RAND field is the per-session random key the bootloader hands out at handshake
     *  time; it only shows up here once this has actually been read). Connects first if
     *  needed. Marked experimental in [OdinDeviceInfo.probe] because not every bootloader
     *  replies to the DVIF request, so the interface is always closed and reopened afterwards
     *  to leave the session clean for a normal Odin flash. */
    fun readOdinDeviceInfo() {
        runTask("Reading Download-Mode Device Info") {
            // DVIF only works on a FRESH handle, before the ODIN/LOKE handshake. If a session is
            // already open, close it cleanly first (end-session, no reboot) and reopen the interface.
            if (_uiState.value.odinSessionReady && transport.isConnected) {
                runCatching { odin.shutdown(reboot = false) }
            }
            transport.close()
            _uiState.update { it.copy(odinConnected = false, odinSessionReady = false) }
            openOdinTransportOnly()
            val info = OdinDeviceInfo.probe(transport, ::appendLog)
            publishOdinInfo(info)
            if (info.isEmpty) {
                // No answer: leave the USB handle in a clean state for the normal ODIN session.
                transport.close()
                _uiState.update { it.copy(odinConnected = false, odinSessionReady = false) }
            }
            openOdinConnectionIfNeeded()
        }
    }

    private fun publishOdinInfo(info: OdinDeviceInfo) {
        if (info.isEmpty) return
        _uiState.update { it.copy(odinDeviceInfoText = info.toDisplayText()) }
        val randNote = if (info.rand.isNotEmpty()) "RAND present" else "RAND not reported by this bootloader"
        appendLog("Device info: ${info.fields.size} field(s) read ($randNote).")
        info.fields["MODEL"]?.let { appendLog("Download Mode device model: $it") }
    }

    /** Opens just the USB interface (no ODIN handshake, no PIT) so DVIF can be sent first. */
    private suspend fun openOdinTransportOnly() {
        if (transport.isConnected) return
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

    /** Reads DVIF right after a fresh connect (like Android Utility does) -- best effort, never fails the connect. */
    private suspend fun autoProbeOdinInfo() {
        if (_uiState.value.odinSessionReady || !transport.isConnected) return
        val info = kotlinx.coroutines.withContext(Dispatchers.IO) { OdinDeviceInfo.probe(transport, ::appendLog) }
        publishOdinInfo(info)
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
                autoProbeOdinInfo()
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
        appendLog(
            "USERDATA TRACE: PIT entry id=${part.id} name='${part.name}' fileName='${part.fileName}' " +
                "binType=${part.binType} devType=${part.devType} attribute=${part.attribute} " +
                "wireBlockSize=${part.wireBlockSize} partitionSizeBytes=${part.partitionSizeBytes} " +
                "imageSize=$size quickFormat=${reason != null}"
        )
        val start = System.nanoTime()
        var lastLoggedPct = -1
        try {
            context.contentResolver.openInputStream(uri)?.use { ins ->
                // Normal windowed transfer (same as every other partition) -- a working
                // third-party Odin tool flashes this same file successfully using normal
                // windowing, and on-device testing showed the single-block path
                // (singleSequence=true) getting rejected with "secure check failed"
                // (ack=-5) on the final ack, so it is never forced here anymore.
                odin.flashImage(part, ins, size, singleSequence = false, tolerateFinalAckWarning = (reason != null)) { done, total ->
                    updateProgress(done, total, start)
                    if (total > 0L) {
                        val pct = ((done * 100L) / total).toInt()
                        if (pct >= lastLoggedPct + 10 || done == total) {
                            lastLoggedPct = pct
                            appendLog("USERDATA TRACE: $pct% ($done / $total bytes)")
                        }
                    }
                }
            } ?: throw IllegalStateException("Cannot open userdata image.")
        } catch (e: IOException) {
            val elapsedMs = (System.nanoTime() - start) / 1_000_000
            appendLog(
                "USERDATA FLASH ERROR: write to PIT partition '${part.name.ifBlank { part.fileName }}' " +
                    "(id=${part.id}) failed after ${elapsedMs}ms: ${e.message}. " +
                    "Record this message to decide on a device-specific bypass later."
            )
            throw e
        }
    }

    /**
     * Real ODIN requires the combined size of every partition that will be
     * flashed to be sent once, before the first partition's transfer begins.
     * So every selected package is opened twice: once to sum sizes (matched
     * against this device's own PIT table), then again to actually stream
     * the data.
     */
    private suspend fun flashTarsWithOdin(allSlots: List<Pair<String, Uri>>, honorSelection: Boolean = true) {
        // Drop packages whose components were all un-ticked.
        val slots = allSlots.filter { (slot, _) ->
            val entries = _uiState.value.odinEntries[slot].orEmpty()
            val keep = !honorSelection || entries.isEmpty() || entries.any { entrySelected(slot, it.label) }
            if (!keep) appendLog("Skipping $slot: nothing selected inside it.")
            keep
        }
        if (slots.isEmpty()) throw IllegalStateException("Nothing is selected to flash. Tick at least one component.")
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
                        sizingStream, pit, shouldFlashFor(label, honorSelection),
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
                    shouldFlash = shouldFlashFor(label, honorSelection),
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

    /**
     * One-tap "Format T505N" for a Samsung tablet in Download Mode: flashes ONLY the
     * userdata image from the selected userdata.tar / userdata.img, then closes the
     * session and reboots so Android recreates the data partition.
     */
    fun formatT505N() {
        runTask("Format T505N (Download Mode)") {
            val uri = _uiState.value.userdataTarUri
                ?: throw IllegalStateException("Select the userdata.tar / userdata.img first.")

            // Safety: never let a package with extra system images touch anything but userdata.
            _uiState.update { it.copy(skipSystemFilesInUserdataTar = true) }

            appendLog("=== FORMAT T505N: ${_uiState.value.userdataTarName} ===")
            flashTarsWithOdin(listOf("USERDATA" to uri), honorSelection = false)

            odin.shutdown(reboot = true)
            transport.close()
            _uiState.update {
                it.copy(
                    odinConnected = false,
                    odinSessionReady = false,
                    odinDeviceDescription = "No Download-Mode Device Detected"
                )
            }
            appendLog("Format finished. The tablet is rebooting; the first boot can take a few minutes.")
        }
    }

    /** T505N tab: the user just picked the userdata file, so select it and format right away. */
    fun formatT505NWithUri(uri: Uri) {
        setUserdataTarUri(uri)
        formatT505N()
    }

    /**
     * "Test eMMC Write": finds out WHY an eMMC ignores writes in EDL. Reads storage info, then runs the
     * one-sector invert/read-back/restore probe on a disposable sector (userdata) with several loader
     * "dialects" (ZLP on/off, payload size, minimal vs full <program> attributes).
     */
    fun diagnoseStorageWrite() {
        runTask("Testing storage write") {
            check(_uiState.value.firehoseReady) { "Start Firehose first (Upload Loader + Read GPT)." }
            val parts = _uiState.value.partitions
            val target = parts.firstOrNull { it.name.equals("userdata", true) }
                ?: parts.firstOrNull { it.name.equals("cache", true) }
                ?: throw IllegalStateException("No userdata/cache partition in the GPT to test on. Read the GPT first.")
            val sector = target.firstLba + 2048L.coerceAtMost(target.numSectors / 2)
            appendLog("Write test on '${target.name}' LUN ${target.lun} sector $sector (original data is restored).")

            firehose.getStorageInfo(target.lun)

            data class Dialect(val name: String, val payload: Int, val zlp: Boolean, val full: Boolean, val skipStorageInit: Boolean = false)
            val dialects = listOf(
                Dialect("default (ZLP, 64K, minimal)", 65536, true, false),
                Dialect("ZLP, 16K, minimal", 16384, true, false),
                Dialect("no-ZLP, 16K, minimal", 16384, false, false),
                Dialect("ZLP, 16K, full attributes", 16384, true, true),
                Dialect("no-ZLP, 4K, full attributes", 4096, false, true),
                // Some Samsung-signed loaders reject/ignore the very first <program> after <configure>
                // unless storage init is explicitly skipped (it was already done by the loader itself on boot).
                Dialect("no-ZLP, 16K, full attrs, SkipStorageInit", 16384, false, true, skipStorageInit = true)
            )
            val storage = _uiState.value.selectedStorage
            var anyWorks = false
            var hung = false
            val verdicts = mutableListOf<FirehoseProtocol.WriteVerdict>()
            for (d in dialects) {
                val cfg = firehose.reconfigure(storage, d.payload, d.zlp, d.skipStorageInit)
                if (cfg.verdict == FirehoseProtocol.ReconfigureVerdict.HUNG) {
                    hung = true
                    appendLog(
                        "[${d.name}] <configure> got no reply at all (${cfg.detail}) -- the loader is already wedged " +
                            "from an earlier command in this session. Sending more commands now would only make it " +
                            "worse, so the remaining dialects are skipped. Unplug/replug and power-cycle the device " +
                            "before trying again."
                    )
                    break
                }
                if (cfg.verdict == FirehoseProtocol.ReconfigureVerdict.NAKED) {
                    appendLog("[${d.name}] loader refused <configure>; skipped.")
                    continue
                }
                val r = firehose.writeSelfTest(target.lun, sector, d.full)
                verdicts += r.verdict
                appendLog("[${d.name}] -> ${r.verdict}: ${r.detail}")
                if (r.verdict == FirehoseProtocol.WriteVerdict.WRITES_WORK) { anyWorks = true; break }
                if (r.verdict == FirehoseProtocol.WriteVerdict.HUNG) {
                    hung = true
                    appendLog(
                        "The loader stopped responding after this write attempt -- sending it more commands " +
                            "would only make things worse, so the remaining dialects are skipped."
                    )
                    break
                }
            }
            appendLog(
                when {
                    anyWorks -> "RESULT: writes work with the dialect above. Flash using it (the app keeps it configured)."
                    hung && verdicts.isEmpty() ->
                        "RESULT: the loader stopped answering <configure> itself, before any write was even attempted " +
                            "in this run -- it was already wedged from something earlier in this session (e.g. a previous " +
                            "manual erase/program attempt). This is why the test 'takes forever': every remaining dialect " +
                            "would also wait out its timeout for nothing. Power-cycle the tablet (unplug, hold Power+Vol " +
                            "Down ~10s) and re-enter the loader fresh, then run this test again as the FIRST command you " +
                            "send -- don't read/erase/program anything before it."
                    hung ->
                        "RESULT: the loader/controller stopped answering the moment a real write command was sent " +
                            "(the GPT still reads fine, only writes freeze it). That is a hardware-level symptom, not a " +
                            "protocol or loader-choice problem: either the eMMC itself has failed/is dying, or it is " +
                            "latched into a write-protected state the chip enforces at the bus level before the loader " +
                            "even gets a chance to answer. Trying another .elf loader will very likely hang the same way. " +
                            "The device may need a hard reset (hold Power + Vol Down ~10s) to come back after this. " +
                            "The only test that can tell write-protect apart from a dead chip is an eMMC ISP tool reading " +
                            "EXT_CSD (USER_WP / PERM_WP bits, PRE_EOL_INFO, device life-time estimate); either outcome " +
                            "there means the eMMC needs replacing, not a software fix."
                    verdicts.isNotEmpty() && verdicts.all { it == FirehoseProtocol.WriteVerdict.SILENTLY_IGNORED } ->
                        "RESULT: the loader ACKs every write but the eMMC never changes. That is not a protocol problem: " +
                            "the eMMC is write-protected (power-on/permanent WP) or has dropped into read-only mode " +
                            "(worn-out/failed chip). Erase cannot help. Needs an eMMC ISP tool to read EXT_CSD " +
                            "(USER_WP, PRE_EOL_INFO, life-time estimate); permanent WP / EOL read-only means replacing the eMMC."
                    verdicts.isNotEmpty() && verdicts.all { it == FirehoseProtocol.WriteVerdict.REJECTED } ->
                        "RESULT: the loader NAKs writes cleanly and quickly. Likely a restricted/signed programmer or the " +
                            "wrong variant for this binary: try another SDM662 programmer for the same device/binary."
                    else -> "RESULT: mixed/unclear (${verdicts.joinToString()}). Send me this log."
                }
            )
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
            performDeviceReset(mode)
        }
    }

    /** Sends <power> and waits for the device to leave EDL. Shared by [resetDevice] and any other
     *  task (e.g. [runX216BWipeAndWriteSequence]) that needs to reboot the phone as its last step --
     *  callers that are already inside a [runTask] block call this directly instead of [resetDevice],
     *  since nesting runTask calls would no-op because isBusy is already true. */
    private suspend fun performDeviceReset(mode: String = "reset") {
        if (!_uiState.value.firehoseReady) {
            appendLog("ERROR: Firehose not ready. Cannot send power command.")
            return
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
                val devices = MtpInfoReader.findModemDevices(context)
                val mtpNames = MtpInfoReader.findMtpDevices(context).map { it.deviceName }.toSet()
                if (devices.isEmpty()) {
                    _uiState.update {
                        it.copy(
                            mtpInfoText = "No device found. Connect the phone by USB (MTP or a mode that includes MODEM).",
                            mtpStorageText = "",
                            infoImei = "", infoSerial = ""
                        )
                    }
                    return@runTask
                }
                val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
                var serial = ""
                var storageAll = ""
                val sections = ArrayList<String>()
                val imeis = LinkedHashSet<String>()
                for (listed in devices) {
                    val sb = StringBuilder()
                    val hadPermission = transport.hasPermission(listed)
                    val granted = transport.ensurePermission(listed)
                    // Right after the permission dialog the phone's MTP responder is still
                    // settling: reading immediately is what made the FIRST read incomplete.
                    if (granted && !hadPermission) delay(900)
                    // Use the freshest handle (the device may have re-enumerated meanwhile).
                    val dev = usbManager.deviceList.values.firstOrNull { it.deviceName == listed.deviceName } ?: listed
                    var model = ""
                    var maker = ""
                    var firmware = ""
                    var usbSerial = ""
                    var modemSerial = ""
                    if (granted) {
                        // Real device serial (USB serial-number descriptor) -- the source of truth.
                        usbSerial = runCatching { dev.serialNumber }.getOrNull().orEmpty()
                        maker = runCatching { dev.manufacturerName }.getOrNull().orEmpty()
                        model = runCatching { dev.productName }.getOrNull().orEmpty()
                        // Only real MTP devices are read over MTP (slow); modem-only modes skip straight to AT.
                        val mtp = if (dev.deviceName in mtpNames) runCatching { MtpDeviceInfoReader.read(usbManager, dev) }.getOrNull() else null
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
                    // ---- Storage capacity (MTP GetStorageInfo) ----
                    var storageText = ""
                    if (granted && dev.deviceName in mtpNames) {
                        val storages = runCatching { MtpDeviceInfoReader.readStorage(usbManager, dev) }
                            .getOrDefault(emptyList())
                        storageText = MtpDeviceInfoReader.describeStorage(storages)
                        if (storageText.isNotBlank() && storageAll.isBlank()) storageAll = storageText
                    }
                    // ---- Modem info over the phone's AT port (IMEI, serial, model, firmware, ...) ----
                    var atText = ""
                    if (granted) {
                        val atCmds = listOf(
                            "AT+CGSN", "AT+GSN", "AT+IMEINUM", "AT+DEVCONINFO",
                            "AT+CGMI", "AT+CGMM", "AT+CGMR", "AT+GMI", "AT+GMM", "AT+GMR",
                            "ATI", "AT+GCAP", "AT+CBC", "AT+CSQ", "AT+CFUN?", "AT+CPIN?"
                        )
                        // The modem port is often not ready on the very first query: retry once.
                        var atTry: Map<String, String> = emptyMap()
                        for (attempt in 0 until 2) {
                            atTry = runCatching { AtCommandTransport(context).use { it.queryReadOnly(dev, atCmds) } }
                                .getOrDefault(emptyMap())
                            val complete = atTry.containsKey("AT+DEVCONINFO") ||
                                atTry.keys.any { it.contains("GSN") }
                            if (complete) break
                            delay(700)
                        }
                        val at: Map<String, String> = atTry
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
                            modemSerial = kv["SN"].orEmpty().takeIf { it.isNotBlank() && it != "NA" && it != "null" }.orEmpty()

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
                    // The modem serial is what AT+SERIALNO rewrites, so it wins; the USB descriptor serial is the fallback.
                    val shownSerial = modemSerial.ifBlank { usbSerial }
                    if (shownSerial.isNotBlank()) serial = shownSerial
                    sb.appendLine("Manufacturer: ${maker.ifBlank { "Unknown" }}")
                    sb.appendLine("Model: ${model.ifBlank { "Unknown" }}")
                    sb.appendLine("Serial (SN): ${shownSerial.ifBlank { "Unavailable" }}")
                    if (modemSerial.isNotBlank() && usbSerial.isNotBlank() && !modemSerial.equals(usbSerial, true)) sb.appendLine("USB descriptor serial: $usbSerial")
                    sb.appendLine("IMEI: ${if (imeis.isEmpty()) "Unavailable" else imeis.joinToString(" / ")}")
                    sb.appendLine("--- Firmware ---")
                    sb.appendLine("MTP device version: ${firmware.ifBlank { "-" }}")
                    if (storageText.isNotBlank()) {
                        sb.appendLine("--- Storage ---")
                        sb.appendLine(storageText)
                    }
                    sb.appendLine("--- USB ---")
                    sb.append(MtpInfoReader.describe(context, dev).trimEnd())
                    sb.append("\n--- Modem ---\n").append(atText)
                    if (!granted) sb.append("\nUSB permission was not granted, so the serial / firmware could not be read.")
                    sections.add(sb.toString())
                }
                _uiState.update {
                    it.copy(
                        mtpInfoText = sections.joinToString("\n\n"),
                        mtpStorageText = storageAll,
                        infoImei = imeis.joinToString("\n"),
                        infoSerial = serial
                    )
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
            val devices = MtpInfoReader.findModemDevices(context)
            if (devices.isEmpty()) {
                appendLog("ERROR: No device with a modem port found. Connect the phone by USB and select a mode that includes MODEM (*#0808#).")
                return@runTask
            }
            var done = false
            for (dev in devices) {
                val ok = runCatching {
                    AtCommandTransport(context).use { send(it, dev) }
                }.getOrElse { e ->
                    appendLog("Could not communicate with the device.")
                    false
                }
                if (ok) { done = true; break }
            }
            if (done) {
                appendLog("Request accepted. The device is switching to $label; wait a few seconds, it connects automatically.")
            } else {
                appendLog("No reply and the device stayed on the USB bus. This USB mode probably has no AT port (on some Samsung phones: *#0808# -> pick a mode with MODEM).")
            }
        }
    }

    /** "Show Info Popup": makes the connected phone display its USB-accessory popup (title +
     *  "view" button that opens [uri] in the phone's browser). Works over plain USB. */
    fun showPhoneInfoPopup(uri: String) = runTask("Open Browser") {
        val devices = MtpInfoReader.findModemDevices(context)
        if (devices.isEmpty()) {
            appendLog("ERROR: No device found. Connect the phone by USB (MTP / File Transfer mode).")
            return@runTask
        }
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        for (listed in devices) {
            if (!transport.ensurePermission(listed)) { appendLog("USB permission denied."); continue }
            val dev = usbManager.deviceList.values.firstOrNull { it.deviceName == listed.deviceName } ?: listed
            val result = runCatching { UsbAccessoryPopup.show(usbManager, dev, "YAS SOFTWARE", uri) }
                .getOrDefault(UsbAccessoryPopup.Result.FAILED)
            when (result) {
                UsbAccessoryPopup.Result.SHOWN -> { appendLog("Popup sent. Check the phone screen (the USB connection restarts)."); return@runTask }
                UsbAccessoryPopup.Result.NOT_SUPPORTED -> appendLog("This device does not support USB accessory mode.")
                UsbAccessoryPopup.Result.FAILED -> appendLog("Could not send the popup to the device.")
            }
        }
    }

    fun atRebootDevice() = runTask("AT Reboot (MTP)") {
        val devices = MtpInfoReader.findModemDevices(context)
        if (devices.isEmpty()) {
            appendLog("ERROR: No device with a modem port found. Connect the phone by USB and select a mode that includes MODEM (*#0808#).")
            return@runTask
        }
        var rebooted = false
        for (dev in devices) {
            val ok = runCatching { AtCommandTransport(context).use { it.tryReboot(dev) { m -> appendLog(m) } } }
                .getOrElse { e -> appendLog("Could not communicate with the device."); false }
            if (ok) { rebooted = true; break }
        }
        if (rebooted) appendLog("Reboot confirmed (device left the USB bus).")
        else appendLog("Reboot NOT confirmed. Use 'ADB -> Reboot' if USB debugging is on.")
    }

    /** "Restore Storage": formats the phone (userdata wipe) with AT commands while it is
     *  in MTP mode and reboots it, so userdata is re-created at the full original size. */
    fun atFormatDevice() = runTask("Format MTP") {
        val devices = MtpInfoReader.findModemDevices(context)
        if (devices.isEmpty()) {
            appendLog("ERROR: No device with a modem port found. Connect the phone by USB and select a mode that includes MODEM (*#0808#).")
            return@runTask
        }
        appendLog("Restoring storage... all user data will be erased.")
        var ok = false
        for (dev in devices) {
            ok = runCatching { AtCommandTransport(context).use { at -> at.onProgress = { f -> setTaskProgress(f) }; at.formatAndReboot(dev) { m -> appendLog(m) } } }
                .getOrElse { e -> appendLog("Could not communicate with the device."); false }
            if (ok) break
        }
        if (ok) {
            setTaskProgress(1f)
            appendLog("Format started. The phone is rebuilding userdata at its full size and will reboot; the first boot can take a few minutes. Afterwards connect in MTP mode and tap Read Info.")
            _uiState.update { it.copy(mtpInfoText = "", mtpStorageText = "", infoImei = "", infoSerial = "") }
        } else {
            appendLog("Format NOT confirmed. Make sure the phone is unlocked, USB mode includes MODEM (*#0808# on Samsung), then retry.")
        }
    }

    /** Writes a new Samsung-style serial number (11 characters, A-Z / 0-9) to the device over
     *  its AT port (MTP mode, no ADB / root needed): the classic 5-step Samsung AT+SERIALNO
     *  sequence ([AtCommandTransport.changeSerialSequence]), then a normal reboot. */
    fun changeSamsungSerial(newSerial: String) {
        val sn = newSerial.trim().uppercase()
        if (!Regex("^[A-Z0-9]{11}$").matches(sn)) {
            appendLog("ERROR: Serial must be exactly 11 characters, letters A-Z and digits only.")
            return
        }
        runTask("Change Serial") {
            appendLog("Changing the serial number...")
            val mtpDevices = MtpInfoReader.findModemDevices(context)
            if (mtpDevices.isEmpty()) {
                appendLog("ERROR: No device with a modem port found. Connect the phone by USB and select a mode that includes MODEM (*#0808#).")
                return@runTask
            }
            var written = false
            var rebooted = false
            for (dev in mtpDevices) {
                written = runCatching {
                    AtCommandTransport(context).use { at -> at.onProgress = { f -> setTaskProgress(f * 0.6f) }; at.changeSerialSequence(dev, sn) { m -> appendLog(m) } }
                }.getOrElse { e -> appendLog("Could not communicate with the device."); false }
                if (!written) continue
                appendLog("The modem accepted the new serial. Rebooting...")
                rebooted = runCatching {
                    AtCommandTransport(context).use { at -> at.onProgress = { f -> setTaskProgress(0.6f + f * 0.2f) }; at.rebootVerified(dev) { m -> appendLog(m) } }
                }.getOrElse { e -> appendLog("Could not communicate with the device."); false }
                break
            }
            if (!written) {
                appendLog("ERROR: Serial was NOT changed. The modem port did not acknowledge the write (wrong USB mode, or this firmware does not allow it). On Samsung dial *#0808#, pick a mode that includes MODEM, then retry.")
                return@runTask
            }
            if (!rebooted) {
                appendLog("WARNING: The serial was written but the phone did not reboot. Restart it manually (hold Power + Vol Down for 10 s), reconnect in MTP mode and tap Read Info to confirm.")
                _uiState.update { it.copy(mtpInfoText = "", mtpStorageText = "", infoImei = "", infoSerial = "") }
                return@runTask
            }
            // Wait for the phone to come back, then read the serial back from the modem to verify.
            appendLog("Waiting for the phone to come back...")
            setTaskProgress(0.8f)
            var verified = ""
            val deadline = System.currentTimeMillis() + 120_000
            while (System.currentTimeMillis() < deadline && verified.isEmpty()) {
                delay(3000)
                setTaskProgress((0.8f + 0.19f * (1f - (deadline - System.currentTimeMillis()) / 120_000f)).coerceIn(0.8f, 0.99f))
                val back = MtpInfoReader.findModemDevices(context)
                if (back.isEmpty()) continue
                delay(4000) // let the modem port come up
                val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
                for (listed in back) {
                    val dev = usbManager.deviceList.values.firstOrNull { it.deviceName == listed.deviceName } ?: listed
                    val value = runCatching { AtCommandTransport(context).use { it.readModemSerial(dev, 3) } }.getOrDefault("")
                    if (value.isNotBlank()) { verified = value; break }
                }
            }
            _uiState.update { it.copy(mtpInfoText = "", mtpStorageText = "", infoImei = "", infoSerial = "") }
            when {
                verified.equals(sn, true) -> appendLog("SUCCESS: serial is now $verified.")
                verified.isNotBlank() -> appendLog("WARNING: the phone still reports serial $verified instead of $sn. This firmware may not allow changing it.")
                else -> appendLog("The phone is back but its serial could not be read. Allow USB access, set a USB mode with MODEM (*#0808#), then tap Read Info to check.")
            }
        }
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

    /** Runs a multi-step ADB script (e.g. Remove Knox) quietly: the commands are never echoed to the
     *  console or the shell panel -- the person only sees a loading bar with a percentage. */
    fun runAdbScript(title: String, script: String) {
        val steps = script.split(';').map { it.trim() }.filter { it.isNotEmpty() }
        if (steps.isEmpty()) return
        runTask(title) {
            appendLog("$title started. Please wait...")
            if (!adbClient.connect()) {
                _uiState.update { it.copy(adbConnected = false) }
                appendLog("ERROR: Could not connect over USB debugging. Enable USB Debugging and accept the prompt on the device.")
                return@runTask
            }
            _uiState.update { it.copy(adbConnected = true) }
            var failed = 0
            steps.forEachIndexed { i, step ->
                val out = runCatching { adbClient.shell(step, timeoutMs = 20000) }.getOrDefault("")
                if (!step.startsWith("reboot") && Regex("exception|unknown package|not found|error", RegexOption.IGNORE_CASE).containsMatchIn(out)) failed++
                setTaskProgress((i + 1).toFloat() / steps.size)
            }
            setTaskProgress(1f)
            appendLog(if (failed == 0) "$title completed successfully." else "$title completed. Some components are not present on this device and were skipped.")
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
            adbClient.reboot(target)
            appendLog("Reboot command sent. Device should switch to $label shortly.")
            adbClient.close()
            _uiState.update { it.copy(adbConnected = false) }
        }
    }

    private fun setTaskProgress(f: Float) {
        _uiState.update { it.copy(progress = f.coerceIn(0f, 1f)) }
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
