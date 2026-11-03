package com.unscreen

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = context.getSharedPreferences("unscreen", Context.MODE_PRIVATE)
            if (prefs.getBoolean("boot", false)) {
                FlipService.start(context)
            }
        }
    }
}
