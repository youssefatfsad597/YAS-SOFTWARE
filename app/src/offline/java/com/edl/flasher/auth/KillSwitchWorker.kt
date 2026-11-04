package com.edl.flasher.auth

import android.app.Application

/** OFFLINE EDITION: no account, no server, nothing to force-logout. */
object KillSwitchWorker {
    fun schedule(app: Application) {}
}
