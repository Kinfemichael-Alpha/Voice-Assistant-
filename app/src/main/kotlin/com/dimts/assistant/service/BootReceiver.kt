package com.dimts.assistant.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.dimts.assistant.runtime

/** Reboot, app update, clock or time-zone change: run what came due and arm the next alarm. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val rt = context.runtime
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) rt.onBoot() else rt.onAlarm()
    }
}
