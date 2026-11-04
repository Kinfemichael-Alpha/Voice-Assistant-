package com.dimts.assistant.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import com.dimts.assistant.runtime

/** Turns Android's call-state broadcasts into call.incoming / call.answered / call.started / call.ended. */
class PhoneStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        @Suppress("DEPRECATION")
        val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
        context.runtime.onPhoneState(intent.getStringExtra(TelephonyManager.EXTRA_STATE), number)
    }
}
