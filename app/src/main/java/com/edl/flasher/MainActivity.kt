package com.edl.flasher

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import com.edl.flasher.protocol.PartitionEntry
import com.edl.flasher.ui.EdlMainScreen
import com.edl.flasher.ui.EdlViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: EdlViewModel by viewModels()

    override fun onResume() {
        super.onResume()
        viewModel.autoConnect()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // USB_DEVICE_ATTACHED delivered while the app is open: permission may already be
        // granted by the system, so just rescan instead of asking again.
        viewModel.autoConnect()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val state by viewModel.uiState.collectAsState()
                var pendingPartition by remember { mutableStateOf<PartitionEntry?>(null) }

                val loaderPicker = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument()
                ) { uri -> uri?.let(viewModel::setLoaderUri) }

                val frpFilePicker = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument()
                ) { uri -> uri?.let(viewModel::setFrpFileUri) }

                val secEfsFilePicker = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument()
                ) { uri -> uri?.let(viewModel::setSecEfsFileUri) }

                val persistentFilePicker = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument()
                ) { uri -> uri?.let(viewModel::setPersistentFileUri) }

                val x216bFolderPicker = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocumentTree()
                ) { uri -> uri?.let(viewModel::autoDetectX216BFolder) }

                val rawProgramPicker = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument()
                ) { uri -> uri?.let(viewModel::setRawProgramUri) }

                val patchXmlPicker = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument()
                ) { uri -> uri?.let(viewModel::setPatchXmlUri) }

                val firmwareDirPicker = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocumentTree()
                ) { uri -> uri?.let(viewModel::setFirmwareDirUri) }

                val blTarPicker = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument()
                ) { uri -> uri?.let(viewModel::setBlTarUri) }

                val apTarPicker = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument()
                ) { uri -> uri?.let(viewModel::setApTarUri) }

                val cpTarPicker = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument()
                ) { uri -> uri?.let(viewModel::setCpTarUri) }

                val cscTarPicker = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument()
                ) { uri -> uri?.let(viewModel::setCscTarUri) }

                val userdataTarPicker = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument()
                ) { uri -> uri?.let(viewModel::setUserdataTarUri) }

                val t505nFormatPicker = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument()
                ) { uri -> uri?.let(viewModel::formatT505NWithUri) }

                val backupSaver = rememberLauncherForActivityResult(
                    ActivityResultContracts.CreateDocument("application/octet-stream")
                ) { uri ->
                    val part = pendingPartition
                    if (uri != null && part != null) {
                        viewModel.backupPartition(part, uri)
                    }
                }

                val partitionImagePicker = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument()
                ) { uri ->
                    val part = pendingPartition
                    if (uri != null && part != null) {
                        viewModel.flashSinglePartition(part, uri)
                    }
                }

                EdlMainScreen(
                    state = state,
                    onConnectUsb = viewModel::connectUsb,
                    onProbeChipInfo = viewModel::readSaharaDeviceInfo,
                    onPickLoader = { loaderPicker.launch(arrayOf("*/*")) },
                    onPickFrpFile = { frpFilePicker.launch(arrayOf("*/*")) },
                    onPickSecEfsFile = { secEfsFilePicker.launch(arrayOf("*/*")) },
                    onPickPersistentFile = { persistentFilePicker.launch(arrayOf("*/*")) },
                    onPickX216BFolder = { x216bFolderPicker.launch(null) },
                    onRunX216BWipeAndWrite = viewModel::runX216BWipeAndWriteSequence,
                    onToggleEmbeddedFirmware = viewModel::setUseEmbeddedFirmware,
                    onSetT509Variant = viewModel::setT509Variant,
                    onForceBrom = viewModel::t509ForceBrom,
                    onFixHandshake = viewModel::t509FixHandshake,
                    onT509FormatFrp = viewModel::t509FormatFrp,
                    onStorageSelected = viewModel::setStorageType,
                    onRunSaharaAndFirehose = { viewModel.runSaharaAndConfigureFirehose(autoReadGpt = true) },
                    onReadGpt = viewModel::readAllGptPartitions,
                    onDiagnoseWrite = viewModel::diagnoseStorageWrite,
                    onLunFilterSelected = viewModel::setLunFilter,
                    onTogglePartSelection = viewModel::togglePartitionSelection,
                    onSelectAllParts = viewModel::selectAllVisiblePartitions,
                    onClearPartSelection = viewModel::clearPartitionSelection,
                    onEraseSelectedParts = viewModel::eraseSelectedPartitions,
                    onBackupPartition = { part ->
                        pendingPartition = part
                        backupSaver.launch("${part.name}_lun${part.lun}.bin")
                    },
                    onFlashPartition = { part ->
                        pendingPartition = part
                        partitionImagePicker.launch(arrayOf("*/*"))
                    },
                    onErasePartition = viewModel::eraseSinglePartition,
                    onResetFrp = viewModel::resetFrpLock,
                    onFactoryReset = viewModel::factoryResetUserdata,
                    onPickRawProgram = { rawProgramPicker.launch(arrayOf("text/xml", "application/xml", "*/*")) },
                    onPickPatchXml = { patchXmlPicker.launch(arrayOf("text/xml", "application/xml", "*/*")) },
                    onPickFirmwareDir = { firmwareDirPicker.launch(null) },
                    onFlashFirmwareXml = viewModel::flashFullFirmwarePackage,
                    onResetDevice = viewModel::resetDevice,
                    onClearLogs = viewModel::clearLogs,
                    onConnectOdinDevice = viewModel::connectSamsungDownloadMode,
                    onReadOdinDeviceInfo = viewModel::readOdinDeviceInfo,
                    onPickBlTar = { blTarPicker.launch(arrayOf("*/*")) },
                    onPickApTar = { apTarPicker.launch(arrayOf("*/*")) },
                    onPickCpTar = { cpTarPicker.launch(arrayOf("*/*")) },
                    onPickCscTar = { cscTarPicker.launch(arrayOf("*/*")) },
                    onPickUserdataTar = { userdataTarPicker.launch(arrayOf("*/*")) },
                    onRunOdinFlash = viewModel::runOdinFlashSequence,
                    onToggleOdinSlot = viewModel::toggleOdinSlot,
                    onToggleOdinEntry = viewModel::toggleOdinEntry,
                    onClearBlTar = viewModel::clearBlTar,
                    onClearApTar = viewModel::clearApTar,
                    onClearCpTar = viewModel::clearCpTar,
                    onClearCscTar = viewModel::clearCscTar,
                    onClearUserdataTar = viewModel::clearUserdataTar,
                    onFormatT505N = { t505nFormatPicker.launch(arrayOf("*/*")) },
                    onToggleOdinAutoReboot = viewModel::setOdinAutoReboot,
                    onExitDownloadMode = viewModel::exitDownloadMode,
                    onReadMtpInfo = viewModel::readMtpInfo,
                    onMtpRebootAt = viewModel::atRebootDevice,
                    onMtpDownloadAt = viewModel::atToDownloadMode,
                    onReadAdbInfo = viewModel::readAdbInfo,
                    onAdbReboot = viewModel::adbRebootTo,
                    onAdbShell = viewModel::runAdbShell,
                    onAdbScript = viewModel::runAdbScript,
                    onMtpFormatAt = viewModel::atFormatDevice,
                    onChangeSerial = viewModel::changeSamsungSerial
                )
            }
        }
    }
}
