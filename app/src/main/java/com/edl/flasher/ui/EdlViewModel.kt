package com.edl.flasher.ui

import android.app.Application
import android.hardware.usb.UsbDevice
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.edl.flasher.protocol.*
import com.edl.flasher.auth.LicenseService
import com.edl.flasher.auth.SensitiveOperationGate
import android.content.Context
import android.hardware.usb.UsbManager
import com.edl.flasher.usb.MtpDeviceInfoReader
import com.edl.flasher.usb.UsbAccessoryPopup
import com.edl.flasher.usb.AtCommandTransport
import com.edl.flasher.usb.EdlUsbTransport
import kotlinx.coroutines.CompletableDeferred
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

/** Read GPT tab: which Firehose loader to upload.
 *  X216B / T505N = bundled in the APK (no file picker needed); MANUAL = user-picked file. */
enum class GptLoaderChoice { X216B, T505N, MANUAL }

/** A value the running task needs from the user mid-operation (e.g. the new serial/CSC to
 *  write, once the device's current info has already been read in the background). The UI
 *  shows this as a dialog whenever it is non-null, independent of [EdlUiState.isBusy] --
 *  the task is suspended on it and will not resume until [EdlViewModel.submitPromptInput]
 *  or [EdlViewModel.cancelPrompt] is called. */
data class PromptRequest(
    val id: String,
    val title: String,
    val message: String,
    val placeholder: String
)

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
    // T505N tab: "Format + FRP" / "Erase FRP" -- built-in Firehose loader + partition
    // images, same shape as embeddedFirmwareReady/embeddedFirmwareStatus for X216B.
    val t505nFirmwareReady: Boolean = false,
    val t505nFirmwareStatus: String = "Checking embedded assets...",
    val t509FirmwareReady: Boolean = false,
    val t509FirmwareStatus: String = "Checking embedded assets...",
    val t509BromConnected: Boolean = false,
    val loaderUri: Uri? = null,
    // Read GPT tab: which built-in loader (or manual file) to upload.
    val gptLoaderChoice: GptLoaderChoice = GptLoaderChoice.X216B,
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
    // Checksum pass real Odin always runs on a .tar.md5 before flashing it (slot -> result).
    // Absent while the check is still running; present once it finishes.
    val odinMd5: Map<String, OdinTarFlasher.Md5CheckResult> = emptyMap(),
    // Slots whose package is still being scanned (header listing + MD5) in the background.
    val odinScanning: Set<String> = emptySet(),
    val odinAutoReboot: Boolean = true,
    // Non-null while a running task is waiting on a value only the user can supply
    // (e.g. Change Serial's new SN, asked for mid-task after the current one was read).
    val pendingPrompt: PromptRequest? = null,
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

/**
 * Wraps a stream so every read throws once [isStale] turns true. A plain blocking
 * `InputStream.read()` loop (tar header scan, full-file MD5 digest) has no coroutine
 * suspension point, so `Job.cancel()` alone does NOT stop it -- the read keeps running to
 * completion regardless. This is what actually stops it: if the user re-picks a file for the
 * same slot while a previous multi-GB scan of the old file is still in flight, the next chunk
 * read of the stale scan throws immediately instead of continuing to hammer storage I/O in the
 * background alongside the new one. Without this, repeatedly picking/re-picking large Odin
 * packages stacks up concurrent full-file reads that can starve the app badly enough for it to
 * be killed -- which is what looked like the app randomly exiting.
 */
private class CancellableInputStream(
    private val delegate: InputStream,
    private val isStale: () -> Boolean
) : InputStream() {
    private fun checkStale() {
        if (isStale()) throw IOException("Scan superseded by a newer file pick for this slot.")
    }
    override fun read(): Int { checkStale(); return delegate.read() }
    override fun read(b: ByteArray): Int { checkStale(); return delegate.read(b) }
    override fun read(b: ByteArray, off: Int, len: Int): Int { checkStale(); return delegate.read(b, off, len) }
    override fun close() = delegate.close()
}

class EdlViewModel(application: Application) : AndroidViewModel(application) {

    private val context get() = getApplication<Application>().applicationContext
    private val transport = EdlUsbTransport(context)
    private val sahara = SaharaProtocol(transport, ::appendLog)
    private val firehose = FirehoseProtocol(transport, ::appendLog)
    private val odin = OdinProtocol(transport, ::appendLog)
    private val sprdFdl = SprdFdlProtocol(transport, ::appendLog)
    @Volatile private var quietInfoRead = false
    private val adbClient = AdbClient(context) { msg -> if (!quietInfoRead) appendLog(msg) }
    private val licenseService by lazy { LicenseService(context) }

    /** Asks the server to authorize ONE sensitive operation right before it runs (see
     *  SensitiveOperationGate / SETUP.md). Every function that flashes, erases, or
     *  otherwise touches the device's protected state must call this FIRST, inside its
     *  runTask{} block, so a denial throws and is logged exactly like any other
     *  precondition failure already in this file -- it never silently continues.
     *  Returns the server-derived key for operations that decrypt bundled firmware;
     *  callers that don't need a key (Odin/manual-file flows) can ignore the result. */
    private suspend fun requireOperationAuthorized(operation: String): ByteArray {
        return when (val outcome = SensitiveOperationGate.authorize(context, licenseService, operation)) {
            is SensitiveOperationGate.Outcome.Key -> outcome.key
            is SensitiveOperationGate.Outcome.Denied ->
                throw IllegalStateException("رفض الخادم العملية (${operation}): ${outcome.reason}")
        }
    }

    private val _uiState = MutableStateFlow(EdlUiState())
    val uiState: StateFlow<EdlUiState> = _uiState.asStateFlow()

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val scanMutex = kotlinx.coroutines.sync.Mutex()
    private val knownUsb = mutableSetOf<String>()

    init {
        checkEmbeddedFirmware()
        checkT505NFirmware()
        checkT509Firmware()
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
                delay(500)
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

    fun checkT505NFirmware() {
        viewModelScope.launch(Dispatchers.IO) {
            val ready = T505NFirmware.allPresent(context)
            val status = T505NFirmware.describeStatus(context)
            _uiState.update { it.copy(t505nFirmwareReady = ready, t505nFirmwareStatus = status) }
            if (ready) {
                appendLog("T505N firmware ready.")
            } else {
                appendLog("WARNING: T505N firmware is not ready.")
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

    fun checkT509Firmware() {
        viewModelScope.launch(Dispatchers.IO) {
            val ready = T509Firmware.allPresent(context)
            val status = T509Firmware.describeStatus(context)
            _uiState.update { it.copy(t509FirmwareReady = ready, t509FirmwareStatus = status) }
            if (ready) appendLog("T509 firmware ready.")
            else appendLog("WARNING: T509 firmware is not ready.")
        }
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

    /** Full T509 service flow: FDL1 -> FDL2 -> erase userdata/frp/persist -> reset. */
    fun t509FormatFrp() {
        runTask("T509 Format + FRP") {
            val opKey = requireOperationAuthorized("flash_frp_t509")
            if (!T509Firmware.allPresent(context)) {
                throw IllegalStateException("T509 firmware is not ready. ${T509Firmware.describeStatus(context)}")
            }
            if (!_uiState.value.t509BromConnected) {
                val bromDevice = transport.findSprdBromDevice()
                    ?: throw IllegalStateException("No Unisoc BROM device detected. Use 'Force BROM' first.")
                val opened = transport.requestPermissionAndOpen(bromDevice)
                if (!opened) throw IllegalStateException("Could not open the BROM USB interface.")
                sprdFdl.connect()
                _uiState.update { it.copy(t509BromConnected = true, usbConnected = true, deviceDescription = "Unisoc BROM") }
            }

            appendLog("=== STEP 1: Uploading FDL1/FDL2 ===")
            sprdFdl.uploadFdl1(T509Firmware.readAllBytes(context, T509Firmware.FDL1, opKey))
            sprdFdl.uploadFdl2(T509Firmware.readAllBytes(context, T509Firmware.FDL2, opKey))

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

    fun setGptLoaderChoice(choice: GptLoaderChoice) {
        _uiState.update { state ->
            // T505N's memory is eMMC (not UFS like X216B); switch the storage selector for them
            // automatically, same as the T505N tab's one-tap buttons, unless Firehose is already
            // up (changing storage mid-session needs a replug, so leave it alone then).
            val storage = if (choice == GptLoaderChoice.T505N && !state.firehoseReady) {
                StorageType.EMMC
            } else {
                state.selectedStorage
            }
            state.copy(gptLoaderChoice = choice, selectedStorage = storage)
        }
        appendLog(
            when (choice) {
                GptLoaderChoice.X216B -> "Read GPT loader: built-in X216B.elf."
                GptLoaderChoice.T505N -> "Read GPT loader: built-in T505N.elf (storage set to eMMC)."
                GptLoaderChoice.MANUAL -> "Read GPT loader: manual (choose a file)."
            }
        )
    }

    fun clearLoader() {
        _uiState.update { it.copy(loaderUri = null, loaderName = "X216B.elf (built-in)") }
        appendLog("Manual loader cleared.")
    }

    fun setLoaderUri(uri: Uri) {
        val doc = DocumentFile.fromSingleUri(context, uri)
        val name = doc?.name ?: uri.lastPathSegment.orEmpty()
        _uiState.update { it.copy(loaderUri = uri, loaderName = name, gptLoaderChoice = GptLoaderChoice.MANUAL) }
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

    private fun bootFirehoseFromEmbeddedLoader(serverKey: ByteArray) {
        appendLog("Loading loader...")
        val loaderBytes = EmbeddedFirmware.readAllBytes(context, EmbeddedFirmware.LOADER, serverKey)
        bootFirehoseWithBytes(loaderBytes)
    }

    fun runSaharaAndConfigureFirehose(autoReadGpt: Boolean = true) {
        val st = _uiState.value
        if (!st.firehoseReady) {
            when (st.gptLoaderChoice) {
                GptLoaderChoice.X216B -> if (!EmbeddedFirmware.exists(context, EmbeddedFirmware.LOADER)) {
                    appendLog("ERROR: Built-in X216B loader is missing from the app. Rebuild with firmware/x216b/X216B.elf.")
                    return
                }
                GptLoaderChoice.T505N -> if (!T505NFirmware.exists(context, T505NFirmware.LOADER)) {
                    appendLog("ERROR: Built-in T505N loader is missing from the app. Rebuild with firmware/t505n/T505N.elf.")
                    return
                }
                GptLoaderChoice.MANUAL -> if (st.loaderUri == null) {
                    appendLog("ERROR: Manual loader mode is selected but no loader file was chosen. Tap 'Choose loader' first.")
                    return
                }
            }
        }

        runTask("Booting Firehose & Reading GPT") {
            // Every path here either boots a licensed, encrypted embedded loader, or at
            // minimum reads the device's GPT -- both require a live, authorized session.
            val opKey = requireOperationAuthorized("boot_firehose_read_gpt")
            if (!_uiState.value.firehoseReady) {
                val cur = _uiState.value
                when (cur.gptLoaderChoice) {
                    GptLoaderChoice.X216B -> bootFirehoseFromEmbeddedLoader(opKey)
                    GptLoaderChoice.T505N -> bootFirehoseFromEmbeddedT505NLoader(opKey)
                    GptLoaderChoice.MANUAL -> {
                        val uri = cur.loaderUri ?: throw IllegalStateException("No manual loader selected.")
                        appendLog("Uploading manual loader (${cur.loaderName})...")
                        bootFirehoseInternal(uri)
                    }
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

    /** openInputStream() is declared nullable (the provider can return null, e.g. the file was
     *  deleted/moved or access was revoked after it was picked). A bare `!!` here used to throw
     *  a bare NullPointerException with no message, which runTask's catch block then logs as
     *  "ERROR [...]: null" -- unhelpful. This throws a clear, user-facing reason instead. */
    private fun openInputStreamOrThrow(uri: android.net.Uri): InputStream =
        context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("تعذر فتح الملف المحدد. تأكد أنه لسه موجود ولم يتم نقله أو حذفه، ثم اختره تاني.")

    fun runX216BWipeAndWriteSequence() {
        runTask("X216B Auto Wipe & Write") {
            val opKey = requireOperationAuthorized("flash_frp_x216b")
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
                    bootFirehoseFromEmbeddedLoader(opKey)
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
                        EmbeddedFirmware.openStream(context, EmbeddedFirmware.FRP, opKey)
                    },
                    WriteJob("persistent", setOf("persistent", "persestent", "presistent"), "persistent",
                        EmbeddedFirmware.sizeOf(context, EmbeddedFirmware.PERSISTENT), "Erasing FRP 2") {
                        EmbeddedFirmware.openStream(context, EmbeddedFirmware.PERSISTENT, opKey)
                    },
                    WriteJob("misc", setOf("misc"), "misc",
                        EmbeddedFirmware.sizeOf(context, EmbeddedFirmware.MISC), "Erasing DATA") {
                        EmbeddedFirmware.openStream(context, EmbeddedFirmware.MISC, opKey)
                    },
                    WriteJob("sec_efs", setOf("sec_efs"), "sec_efs",
                        EmbeddedFirmware.sizeOf(context, EmbeddedFirmware.SEC_EFS), "Erasing MDM") {
                        EmbeddedFirmware.openStream(context, EmbeddedFirmware.SEC_EFS, opKey)
                    }
                )
            } else {
                listOfNotNull(
                    _uiState.value.frpFileUri?.let { uri ->
                        val doc = DocumentFile.fromSingleUri(context, uri)
                        WriteJob("frp", setOf("frp"), doc?.name ?: "frp", doc?.length() ?: 0L, "مسح frp 1") {
                            openInputStreamOrThrow(uri)
                        }
                    },
                    _uiState.value.persistentFileUri?.let { uri ->
                        val doc = DocumentFile.fromSingleUri(context, uri)
                        WriteJob("persistent", setOf("persistent", "persestent", "presistent"), doc?.name ?: "persistent", doc?.length() ?: 0L, "مسح frp 2") {
                            openInputStreamOrThrow(uri)
                        }
                    },
                    _uiState.value.secEfsFileUri?.let { uri ->
                        val doc = DocumentFile.fromSingleUri(context, uri)
                        WriteJob("sec_efs", setOf("sec_efs"), doc?.name ?: "sec_efs", doc?.length() ?: 0L, "مسح MDM") {
                            openInputStreamOrThrow(uri)
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

    // =====================================================================================
    //  T505N tab: "Format + FRP" / "Erase FRP" (EDL 9008 / Firehose, built-in loader)
    // =====================================================================================
    // Same Qualcomm EDL/Firehose session as X216B and the Read GPT tab (one 9008 port, one
    // Firehose instance per app), just with the T505N's own embedded loader + images. The
    // T505N's storage is eMMC (not UFS like X216B), so both sequences force that before
    // booting the loader / reading the GPT.

    private fun bootFirehoseFromEmbeddedT505NLoader(serverKey: ByteArray) {
        appendLog("Loading T505N loader...")
        val loaderBytes = T505NFirmware.readAllBytes(context, T505NFirmware.LOADER, serverKey)
        bootFirehoseWithBytes(loaderBytes)
    }

    /** Opens the 9008 USB device if needed, forces eMMC storage, and boots the T505N loader
     *  into Firehose if it isn't already up. Shared by both T505N one-tap buttons below. */
    private suspend fun prepareT505NFirehose(serverKey: ByteArray) {
        if (!T505NFirmware.allPresent(context)) {
            throw IllegalStateException("T505N firmware is not ready. ${T505NFirmware.describeStatus(context)}")
        }

        if (!transport.isConnected) {
            val device = transport.findEdlDevice()
                ?: throw IllegalStateException("No 9008 device detected on USB!")
            val opened = transport.requestPermissionAndOpen(device)
            if (!opened) throw IllegalStateException("Could not open USB 9008 interface.")
            sahara.resetSession()
            _uiState.update {
                it.copy(usbConnected = true, deviceDescription = "Qualcomm HS-USB QDLoader 9008")
            }
        }

        if (_uiState.value.firehoseReady && _uiState.value.selectedStorage != StorageType.EMMC) {
            appendLog(
                "WARNING: Firehose is already configured for ${_uiState.value.selectedStorage}, " +
                    "but the T505N is eMMC. Unplug/replug the cable and run this button again so it " +
                    "can reconfigure, or GPT reads below will be wrong."
            )
        } else if (!_uiState.value.firehoseReady) {
            _uiState.update { it.copy(selectedStorage = StorageType.EMMC) }
        }

        if (!_uiState.value.firehoseReady) {
            bootFirehoseFromEmbeddedT505NLoader(serverKey)
        }
    }

    /** Writes frp(+persistent)(+misc) to the GPT partitions matching those names, in the
     *  same "FRP 1 / FRP 2 / DATA" step order and typo-tolerant aliases as X216B's sequence. */
    private suspend fun writeT505NPartitions(currentParts: List<PartitionEntry>, includeMisc: Boolean, serverKey: ByteArray) {
        val jobs = buildList {
            add(
                WriteJob("frp", setOf("frp"), "frp",
                    T505NFirmware.sizeOf(context, T505NFirmware.FRP), "Erasing FRP 1") {
                    T505NFirmware.openStream(context, T505NFirmware.FRP, serverKey)
                }
            )
            add(
                WriteJob("persistent", setOf("persistent", "persestent", "presistent"), "persistent",
                    T505NFirmware.sizeOf(context, T505NFirmware.PERSISTENT), "Erasing FRP 2") {
                    T505NFirmware.openStream(context, T505NFirmware.PERSISTENT, serverKey)
                }
            )
            if (includeMisc) {
                add(
                    WriteJob("misc", setOf("misc"), "misc",
                        T505NFirmware.sizeOf(context, T505NFirmware.MISC), "Erasing DATA") {
                        T505NFirmware.openStream(context, T505NFirmware.MISC, serverKey)
                    }
                )
            }
        }

        jobs.forEach { job ->
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
    }

    /** T505N tab: "Format + FRP" -- uploads the built-in T505N loader, reads the full GPT,
     *  then writes misc + frp + persistent, and reboots. No sec_efs/MDM image for this
     *  device (see [T505NFirmware]). */
    fun runT505NFormatFrpSequence() {
        runTask("T505N Format + FRP") {
            val opKey = requireOperationAuthorized("flash_frp_t505n")
            prepareT505NFirehose(opKey)

            appendLog("Reading Full GPT Partition Table...")
            scanAllGptInternal()
            val currentParts = _uiState.value.partitions
            if (currentParts.isEmpty()) {
                throw IllegalStateException("GPT Partition Table is empty! Check storage type (UFS/eMMC).")
            }

            appendLog("=== WRITING PARTITIONS (MISC, FRP, PERSISTENT) ===")
            appendLog("Writing firmware images...")
            writeT505NPartitions(currentParts, includeMisc = true, serverKey = opKey)

            appendLog("Operation completed successfully.")
            appendLog("Rebooting device...")
            performDeviceReset("reset")
        }
    }

    /** T505N tab: "Erase FRP" -- same loader + GPT read as "Format + FRP", but only writes
     *  frp + persistent (misc/DATA is left untouched), then reboots. */
    fun runT505NEraseFrpSequence() {
        runTask("T505N Erase FRP") {
            val opKey = requireOperationAuthorized("flash_frp_t505n")
            prepareT505NFirehose(opKey)

            appendLog("Reading Full GPT Partition Table...")
            scanAllGptInternal()
            val currentParts = _uiState.value.partitions
            if (currentParts.isEmpty()) {
                throw IllegalStateException("GPT Partition Table is empty! Check storage type (UFS/eMMC).")
            }

            appendLog("=== WRITING PARTITIONS (FRP, PERSISTENT) ===")
            appendLog("Writing firmware images...")
            writeT505NPartitions(currentParts, includeMisc = false, serverKey = opKey)

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

    // Bumped every time a new file is picked for a slot; a scan captures its own number at
    // launch and checks it against this map to tell whether it has been superseded.
    private val scanGeneration = HashMap<String, Int>()

    /** Opens the chosen package in the background: lists every component, matches each one
     *  against this device's PIT (when already connected), and -- for a .tar.md5 -- runs the
     *  same checksum pass real Odin runs before it will touch the device.
     *
     *  Safe to call repeatedly in quick succession (re-picking, picking the wrong file by
     *  mistake, ...): each call supersedes any still-running scan for the same slot, and a
     *  superseded scan's next read throws immediately instead of continuing a now-pointless
     *  multi-GB read in the background -- see [CancellableInputStream]. */
    private fun scanPackage(slot: String, uri: Uri, fileName: String) {
        val myGen = (scanGeneration[slot] ?: 0) + 1
        scanGeneration[slot] = myGen
        fun stale() = scanGeneration[slot] != myGen

        _uiState.update { st ->
            st.copy(
                odinEntries = st.odinEntries - slot,
                odinDeselected = st.odinDeselected.filterNot { it.startsWith("$slot|") }.toSet(),
                odinMd5 = st.odinMd5 - slot,
                odinScanning = st.odinScanning + slot
            )
        }
        val isMd5Package = fileName.endsWith(".md5", ignoreCase = true)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val pit = odin.pitTable // may be null if the device isn't connected yet; entries just show "unmatched" then
                val entries: List<OdinTarFlasher.PackageEntry> = runCatching {
                    if (isRawImage(uri)) {
                        val size = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: 0L
                        val base = fileName.substringAfterLast('/')
                        val part = pit?.findByFileName(base)
                        listOf(
                            OdinTarFlasher.PackageEntry(
                                name = base,
                                label = base.substringBeforeLast('.', base),
                                size = size,
                                pitPartitionId = part?.id,
                                pitPartitionName = part?.name?.ifBlank { part.fileName },
                                pitPartitionSizeBytes = part?.partitionSizeBytes ?: 0L
                            )
                        )
                    } else {
                        context.contentResolver.openInputStream(uri)
                            ?.let { CancellableInputStream(it, ::stale) }
                            ?.use { OdinTarFlasher.listEntries(it, pit) }
                            .orEmpty()
                    }
                }.getOrElse { e ->
                    if (!stale()) appendLog("Could not read the contents of $fileName: ${e.message}")
                    emptyList()
                }
                if (stale()) return@launch

                val md5Result: OdinTarFlasher.Md5CheckResult? = if (isMd5Package) {
                    appendLog("<ODIN> Check MD5.. do not unplug the cable while this runs ($fileName)")
                    runCatching {
                        context.contentResolver.openInputStream(uri)
                            ?.let { CancellableInputStream(it, ::stale) }
                            ?.use { OdinTarFlasher.verifyPackageMd5(it) }
                    }.getOrElse { e ->
                        if (!stale()) appendLog("<ODIN> Could not verify MD5 for $fileName: ${e.message}")
                        null
                    }
                } else null
                if (stale()) return@launch

                _uiState.update { st ->
                    st.copy(
                        odinEntries = st.odinEntries + (slot to entries),
                        odinMd5 = if (md5Result != null) st.odinMd5 + (slot to md5Result) else st.odinMd5
                    )
                }

                if (md5Result != null) {
                    when {
                        !md5Result.hasTrailer ->
                            appendLog("<ODIN> $fileName has no checksum trailer to verify (not a standard .tar.md5).")
                        md5Result.passed ->
                            appendLog("<ODIN> Checking MD5 finished Sucessfully.. (${md5Result.computed})")
                        else -> appendLog(
                            "<ODIN> Odin checksum fails!! expected=${md5Result.expected} " +
                                "computed=${md5Result.computed}. This package is corrupt or was renamed " +
                                "after download -- re-download it before flashing."
                        )
                    }
                }

                if (entries.isEmpty()) {
                    appendLog("$slot package: no components could be listed (it will be flashed as a whole).")
                } else {
                    val matched = entries.count { it.pitPartitionId != null }
                    val mismatched = entries.count { it.sizeMismatch }
                    val matchInfo = if (pit != null) " [$matched/${entries.size} match this device's PIT" +
                        (if (mismatched > 0) ", $mismatched size-mismatch]" else "]") else " [connect the device to verify PIT matches]"
                    appendLog("$slot package contains ${entries.size} component(s)$matchInfo: " + entries.joinToString(", ") { it.label })
                }
            } finally {
                // Only the scan that is still current clears the "scanning" flag -- a
                // superseded one must not clear the flag the newer scan just set.
                if (!stale()) _uiState.update { it.copy(odinScanning = it.odinScanning - slot) }
            }
        }
    }

    private fun clearOdinSlot(slot: String) {
        // Invalidate any scan still running for this slot so it stops hitting storage in the
        // background for a file that no longer applies (see scanPackage / CancellableInputStream).
        scanGeneration[slot] = (scanGeneration[slot] ?: 0) + 1
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
                odinDeselected = cleared.odinDeselected.filterNot { it.startsWith("$slot|") }.toSet(),
                odinMd5 = cleared.odinMd5 - slot,
                odinScanning = cleared.odinScanning - slot
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

        // Same hard-abort real Odin applies: a package whose checksum already failed is never
        // sent to the device, even partially.
        val failedMd5 = slots.map { it.first }.filter { _uiState.value.odinMd5[it]?.passed == false }
        if (failedMd5.isNotEmpty()) {
            throw IllegalStateException(
                "Odin checksum fails for: ${failedMd5.joinToString(", ")}. Re-download the package(s) before flashing."
            )
        }

        appendLog("<ODIN> SetupConnection..")
        openOdinConnectionIfNeeded()
        val pit = odin.requirePit()
        appendLog("<ODIN> Get PIT for mapping.. ${pit.partitions.size} partitions (cpu_bl_id='${pit.cpuBlId}')")

        appendLog("<ODIN> File analysis.. computing total transfer size against this device's PIT")
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
        appendLog("<ODIN> Firmware update start.. total ${OdinTarFlasher.formatBytes(total)} across ${slots.size} package(s)")
        odin.sendTotalSize(total)

        var partitionStartNs = System.nanoTime()
        var lastPartition = ""
        for ((label, uri) in slots) {
            appendLog("<ODIN> Opening $label package..")
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
            requireOperationAuthorized("flash_odin")
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

            appendLog("<ODIN> Odin engine starting.. ${slots.size} package(s) selected")
            flashTarsWithOdin(slots)

            appendLog("<ODIN> RQT_CLOSE !!  RES OK !!  All threads completed. (succeed ${slots.size} / failed 0)")
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
            requireOperationAuthorized("format_userdata")
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
            requireOperationAuthorized("flash_partition")
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
            requireOperationAuthorized("erase_partition")
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
            requireOperationAuthorized("erase_partition")
            targets.forEachIndexed { idx, part ->
                appendLog("[${idx + 1}/${targets.size}] Erasing '${part.name}' (LUN ${part.lun})...")
                firehose.eraseSectors(part.lun, part.firstLba, part.numSectors)
                _uiState.update { it.copy(progress = (idx + 1).toFloat() / targets.size.toFloat()) }
            }
            _uiState.update { it.copy(selectedPartKeys = emptySet()) }
            appendLog("Operation completed successfully.")
        }
    }

    // Keep this alias set identical to the "frp"/"persistent" aliases used by the X216B and
    // T505N write sequences above ("persestent"/"presistent" are real typos seen in some
    // devices' own GPT partition names) so "Erase FRP" matches the same partitions on the
    // same device that the dedicated per-device flows would.
    private val FRP_PARTITION_ALIASES = setOf("frp", "config", "persistent", "persestent", "presistent")
    private val USERDATA_PARTITION_ALIASES = setOf("userdata", "metadata", "cache")

    fun resetFrpLock() {
        val frpTargets = _uiState.value.partitions.filter { part ->
            FRP_PARTITION_ALIASES.any { alias -> part.name.equals(alias, ignoreCase = true) }
        }
        if (frpTargets.isEmpty()) {
            appendLog("ERROR: Read GPT first! Could not find an 'frp', 'config' or 'persistent' partition.")
            return
        }
        runTask("Resetting FRP Lock") {
            requireOperationAuthorized("flash_frp")
            frpTargets.forEach { part ->
                appendLog("Erasing FRP partition '${part.name}' on LUN ${part.lun}...")
                firehose.eraseSectors(part.lun, part.firstLba, part.numSectors)
            }
            appendLog("Operation completed successfully.")
        }
    }

    fun factoryResetUserdata() {
        val targets = _uiState.value.partitions.filter { part ->
            USERDATA_PARTITION_ALIASES.any { alias -> part.name.equals(alias, ignoreCase = true) }
        }
        if (targets.isEmpty()) {
            appendLog("ERROR: Read GPT first! Could not find a 'userdata', 'metadata' or 'cache' partition.")
            return
        }
        runTask("Factory Reset (Wiping Userdata)") {
            requireOperationAuthorized("factory_reset")
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
            requireOperationAuthorized("flash_full")
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
            performFullDeviceInfoRead()
        }
    }

    /**
     * Reads SN / IMEI / model / firmware / storage over MTP + the AT (modem) port -- exactly
     * what the "Read Info" button does -- and publishes it to the Device Info panel. Returns the
     * resolved serial ("" if none could be read).
     *
     * Shared by [readMtpInfo] and the Change Serial / Change CSC flows: both of those run this
     * same background read first, so the device's current identity is already on screen (and
     * passed into the prompt) by the time the user is asked for the new value.
     */
    private suspend fun performFullDeviceInfoRead(): String {
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
                return ""
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
            return serial
        } catch (e: Exception) {
            _uiState.update { it.copy(mtpInfoText = "Could not read device info: ${e.message}") }
            return ""
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
        requireOperationAuthorized("factory_reset")
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

    /** Changes the device's Samsung-style serial number (11 characters, A-Z / 0-9) over its AT
     *  port (MTP mode, no ADB / root needed), then reboots it so the change takes effect.
     *
     *  One-button flow: nothing has to be typed before pressing the button. The task connects
     *  on its own, reads ONLY the current serial in the background (fast -- not the full
     *  device-info read "Read Info" does), THEN pauses and asks the user for the new serial,
     *  showing them the current one as context. It does not wait for the phone to come back on
     *  the USB bus after rebooting -- the task reports success as soon as the write is
     *  acknowledged and the reboot command is sent.
     *
     * Progress stages:
     *   0%–15 %   Connect + read ONLY the current serial in the background (fast: a single
     *             AT+SERIALNO? query, not the full device-info read "Read Info" does)
     *   15%       Ask the user for the new serial (task is suspended here, not failed)
     *   15%–60 %  Write sequence (service-init + AT+SERIALNO=2,<SN>), 1 attempt
     *   65%–95 %  Reboot command sent
     */
    fun runChangeSerial() {
        runTask("Change Serial Number") {
            requireOperationAuthorized("change_serial")
            appendLog("=== CHANGE SERIAL NUMBER ===")

            setTaskProgress(0.02f)
            val mtpDevices = MtpInfoReader.findModemDevices(context)
            if (mtpDevices.isEmpty()) {
                appendLog("ERROR: No device with a modem port found.")
                appendLog("Connect the phone via USB -> dial *#0808# -> choose a mode that includes MODEM.")
                return@runTask
            }

            // ── Stage 1: Read ONLY the current serial (fast) -- not the full device info ──
            appendLog("Reading current serial...")
            var currentSerial = ""
            for (dev in mtpDevices) {
                currentSerial = runCatching {
                    AtCommandTransport(context).use { at -> at.readModemSerial(dev) }
                }.getOrElse { e ->
                    appendLog("Could not read current serial: ${e.message}")
                    ""
                }
                if (currentSerial.isNotBlank()) break
            }
            if (currentSerial.isNotBlank()) _uiState.update { it.copy(infoSerial = currentSerial) }
            appendLog(
                if (currentSerial.isNotBlank()) "Current serial: $currentSerial"
                else "Current serial: unreadable (continuing anyway)"
            )
            setTaskProgress(0.15f)

            // ── Stage 2: Ask the user for the new serial now that we know the old one ──
            val typed = requestUserText(
                id = "serial",
                title = "Enter New Serial",
                message = if (currentSerial.isNotBlank())
                    "Current serial is \"$currentSerial\". Enter the new 11-character serial to write (A-Z, 0-9)."
                else
                    "Enter the new 11-character serial to write (A-Z, 0-9).",
                placeholder = "e.g. R58N123ABCD"
            )
            if (typed == null) {
                appendLog("Cancelled: no serial entered.")
                return@runTask
            }
            val sn = typed.trim().uppercase()
            if (!Regex("^[A-Z0-9]{11}$").matches(sn)) {
                appendLog("ERROR: Serial must be exactly 11 characters, letters A-Z and digits only.")
                return@runTask
            }
            appendLog("Target serial: $sn")
            setTaskProgress(0.2f)

            // ── Stage 3: Write new serial (service-init + serial write with retries) ──
            appendLog("Writing new serial number...")
            var written = false
            for (dev in mtpDevices) {
                written = runCatching {
                    AtCommandTransport(context).use { at ->
                        at.onProgress = { f -> setTaskProgress(0.15f + f * 0.45f) }
                        at.changeSerialSequence(dev, sn, maxAttempts = 1, log = { msg -> appendLog(msg) })
                    }
                }.getOrElse { e ->
                    appendLog("ERROR: AT communication error: ${e.message}")
                    false
                }
                if (written) break
            }

            if (!written) {
                appendLog("ERROR: modem did not acknowledge the write.")
                appendLog("Possible reasons:")
                appendLog("- Wrong USB mode (dial *#0808# -> include MODEM)")
                appendLog("- Firmware rejects engineering writes")
                appendLog("- Device locked by Knox / corporate policy")
                return@runTask
            }
            appendLog("Modem acknowledged the serial write.")

            // ── Stage 4: Reboot so the new serial takes effect ─────────────────────
            setTaskProgress(0.65f)
            appendLog("Rebooting device...")
            for (dev in mtpDevices) {
                val ok = runCatching {
                    AtCommandTransport(context).use { at ->
                        at.onProgress = { f -> setTaskProgress(0.65f + f * 0.3f) }
                        at.rebootVerifiedWithPowerReset(dev, log = {})
                    }
                }.getOrDefault(false)
                if (ok) break
            }

            // ── Stage 5: Done — report success right away, no wait / re-read ──────
            setTaskProgress(1f)
            _uiState.update {
                it.copy(mtpInfoText = "", mtpStorageText = "", infoImei = "", infoSerial = sn)
            }
            appendLog("Operation completed successfully.")
            appendLog("Serial is now \"$sn\".")
        }
    }

    /** Writes a new 3-letter Sales Code (CSC / region code) to the device over its AT port
     *  (MTP mode, no ADB / root needed), then reboots it so the change takes effect.
     *
     *  One-button flow, same shape as Change Serial: nothing has to be typed before pressing
     *  the button. The task connects on its own, reads ONLY the current CSC in the background
     *  (fast -- not the full device-info read "Read Info" does), THEN pauses and asks the user
     *  for the new CSC. It does not wait for the phone to come back on the USB bus after
     *  rebooting -- the task reports success as soon as the write is acknowledged and the
     *  reboot command is sent.
     *
     * Progress stages:
     *   0%–15 %   Connect + read ONLY the current CSC in the background (fast: a single
     *             AT+PRECONFG=1,0 query, not the full device-info read "Read Info" does)
     *   15%       Ask the user for the new CSC (task is suspended here, not failed)
     *   15%–60 %  Write sequence (service-init + AT+PRECONFG), 1 attempt
     *   65%–95 %  Reboot command sent
     */
    fun runChangeCsc() {
        runTask("Change Sales Code (CSC)") {
            requireOperationAuthorized("change_csc")
            appendLog("=== CHANGE SALES CODE (CSC) ===")

            setTaskProgress(0.02f)
            val mtpDevices = MtpInfoReader.findModemDevices(context)
            if (mtpDevices.isEmpty()) {
                appendLog("ERROR: No device with a modem port found.")
                appendLog("Connect the phone via USB -> dial *#0808# -> choose a mode that includes MODEM.")
                return@runTask
            }

            // ── Stage 1: Read ONLY the current CSC (fast) -- not the full device info ──
            appendLog("Reading current CSC...")
            var currentCsc = ""
            for (dev in mtpDevices) {
                currentCsc = runCatching {
                    AtCommandTransport(context).use { at -> at.readModemCsc(dev) }
                }.getOrElse { e ->
                    appendLog("Could not read current CSC: ${e.message}")
                    ""
                }
                if (currentCsc.isNotBlank()) break
            }
            appendLog(
                if (currentCsc.isNotBlank()) "Current CSC: $currentCsc"
                else "Current CSC: unreadable (continuing anyway)"
            )
            setTaskProgress(0.15f)

            // ── Stage 2: Ask the user for the new CSC now that we know the old one ──
            val typed = requestUserText(
                id = "csc",
                title = "Enter New CSC",
                message = if (currentCsc.isNotBlank())
                    "Current CSC is \"$currentCsc\". Enter the new 3-letter Sales Code to write (A-Z)."
                else
                    "Enter the new 3-letter Sales Code to write (A-Z).",
                placeholder = "e.g. XSG"
            )
            if (typed == null) {
                appendLog("Cancelled: no CSC entered.")
                return@runTask
            }
            val csc = typed.trim().uppercase()
            if (!Regex("^[A-Z]{3}$").matches(csc)) {
                appendLog("ERROR: CSC must be exactly 3 letters (A-Z).")
                return@runTask
            }
            appendLog("Target CSC: $csc")
            setTaskProgress(0.2f)

            // ── Stage 3: Write new CSC (service-init + PRECONFG write with retries) ──
            appendLog("Writing new CSC...")
            var written = false
            for (dev in mtpDevices) {
                written = runCatching {
                    AtCommandTransport(context).use { at ->
                        at.onProgress = { f -> setTaskProgress(0.15f + f * 0.45f) }
                        at.changeCscSequence(dev, csc, maxAttempts = 1, log = { msg -> appendLog(msg) })
                    }
                }.getOrElse { e ->
                    appendLog("ERROR: AT communication error: ${e.message}")
                    false
                }
                if (written) break
            }

            if (!written) {
                appendLog("ERROR: modem did not acknowledge the CSC write.")
                appendLog("Possible reasons:")
                appendLog("- Wrong USB mode (dial *#0808# -> include MODEM)")
                appendLog("- Firmware rejects engineering writes")
                appendLog("- Device locked by Knox / corporate policy")
                return@runTask
            }
            appendLog("Modem acknowledged the CSC write.")

            // ── Stage 4: Reboot so the new CSC takes effect ─────────────────────────
            setTaskProgress(0.65f)
            appendLog("Rebooting device...")
            for (dev in mtpDevices) {
                val ok = runCatching {
                    AtCommandTransport(context).use { at ->
                        at.onProgress = { f -> setTaskProgress(0.65f + f * 0.3f) }
                        at.rebootVerifiedWithPowerReset(dev, log = {})
                    }
                }.getOrDefault(false)
                if (ok) break
            }

            // ── Stage 5: Done — report success right away, no wait / re-read ──────
            setTaskProgress(1f)
            appendLog("Operation completed successfully.")
            appendLog("CSC is now \"$csc\".")
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

    private var pendingPromptDeferred: CompletableDeferred<String?>? = null

    /** Suspends the running task and shows [PromptRequest] as a dialog, independent of the
     *  busy/progress UI. Returns the text the user entered, or null if they cancelled.
     *  Call only from inside a [runTask] block. */
    private suspend fun requestUserText(id: String, title: String, message: String, placeholder: String): String? {
        val deferred = CompletableDeferred<String?>()
        pendingPromptDeferred = deferred
        _uiState.update { it.copy(pendingPrompt = PromptRequest(id, title, message, placeholder)) }
        return try {
            deferred.await()
        } finally {
            pendingPromptDeferred = null
            _uiState.update { it.copy(pendingPrompt = null) }
        }
    }

    /** Called by the UI when the user submits the pending prompt's dialog. */
    fun submitPromptInput(text: String) {
        pendingPromptDeferred?.complete(text)
    }

    /** Called by the UI when the user dismisses/cancels the pending prompt's dialog. */
    fun cancelPrompt() {
        pendingPromptDeferred?.complete(null)
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
