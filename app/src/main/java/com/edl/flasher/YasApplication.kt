package com.edl.flasher

import android.app.Application
import com.edl.flasher.auth.KillSwitchWorker

/**
 * Custom Application so the background license re-check (KillSwitchWorker) starts the
 * moment the process is created -- not only when MainActivity happens to be opened.
 * This is what lets an admin's "قطع فوري" (force logout) button in the dashboard take
 * effect within ~15 minutes even if the app is just sitting open in the background,
 * instead of only being checked the next time the user restarts the app.
 */
class YasApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        KillSwitchWorker.schedule(this)
    }
}
