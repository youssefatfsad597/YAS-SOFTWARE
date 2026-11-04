package com.edl.flasher.auth

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

/**
 * OFFLINE EDITION AuthGate: no login screen, no license check, no server call
 * of any kind. Reports "authorized" immediately and renders [content].
 *
 * Signature is IDENTICAL to the online AuthGate on purpose -- MainActivity.kt
 * is shared between both flavors and calls this exact function signature, so
 * it does not need a single line changed between editions.
 */
@Composable
fun AuthGate(
    service: LicenseService,
    recoveryToken: String? = null,
    recoveryError: String? = null,
    onRecoveryDone: () -> Unit = {},
    onAuthorizationChanged: (Boolean) -> Unit,
    content: @Composable () -> Unit
) {
    LaunchedEffect(Unit) { onAuthorizationChanged(true) }
    content()
}
