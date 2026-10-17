package com.dante.medukeep

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Dbg.log(context, "BOOT_COMPLETED received")
        try {
            ContextCompat.startForegroundService(
                context, Intent(context, KeepAliveService::class.java)
            )
        } catch (_: Throwable) { }
    }
}
