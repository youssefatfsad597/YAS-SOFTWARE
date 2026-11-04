package com.edl.flasher.auth

import android.content.Context

/**
 * OFFLINE EDITION gate: every sensitive operation is authorized locally,
 * with no server round-trip (no OperationAuthClient / OperationKeyClient).
 * The "key" returned is unused by this flavor's firmware readers
 * (EmbeddedFirmware / T505NFirmware / T509Firmware read their bundled
 * assets in plaintext here) -- it's kept only so EdlViewModel.kt's call
 * site (`requireOperationAuthorized`) compiles unchanged against the same
 * `Outcome` type the online build uses.
 */
object SensitiveOperationGate {

    sealed class Outcome {
        data class Key(val key: ByteArray) : Outcome()
        data class Denied(val reason: String) : Outcome()
    }

    suspend fun authorize(context: Context, licenseService: LicenseService, operation: String): Outcome =
        Outcome.Key(ByteArray(0))
}
