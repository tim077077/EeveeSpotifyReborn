package com.slidemill.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restart the engine after a reboot or an app update, so schedules keep running. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            // After a crash, wait for the user to open the app and see it, instead of crashing in a loop.
            if (Crash.pending(context) == null) runCatching { EngineService.start(context) }
        }
    }
}
