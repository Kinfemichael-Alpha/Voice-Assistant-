package com.dimts.assistant.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.dimts.assistant.runtime

/** The exact-alarm for the next time rule. Creating the process already ran the catch-up tick. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        context.runtime.onAlarm()
    }
}
