package com.dimts.assistant.exec

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import com.dimts.assistant.AppRuntime
import com.dimts.assistant.access.DimtsAccessibilityService
import com.dimts.core.script.Environment
import com.dimts.core.script.Value
import com.dimts.core.session.SessionState

/** The phone's current state, as names usable in conditions (`battery.level < 20`). */
class PhoneEnvironment(private val rt: AppRuntime) : Environment {
    @Volatile var batteryLevel = -1
    @Volatile var charging = false
    @Volatile var callState = "idle"
    @Volatile var callContact = ""
    @Volatile var callNumber = ""

    fun updateBattery(intent: Intent?) {
        intent ?: return
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level >= 0 && scale > 0) batteryLevel = level * 100 / scale
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    }

    override fun names(): Set<String> = setOf(
        "battery.level", "battery.charging", "call.state", "call.contact", "call.number",
        "app.current", "wifi.connected", "volume", "assistant.state", "listening",
    )

    override fun lookup(name: String): Value? = when (name) {
        "battery.level" -> if (batteryLevel >= 0) Value.Num(batteryLevel.toDouble()) else null
        "battery.charging" -> Value.Bool(charging)
        "call.state" -> Value.Str(callState)
        "call.contact" -> Value.Str(callContact)
        "call.number" -> Value.Str(callNumber)
        "app.current" -> Value.Str(DimtsAccessibilityService.currentPackage)
        "wifi.connected" -> Value.Bool(wifiConnected())
        "volume" -> Value.Num(musicVolumePercent().toDouble())
        "assistant.state" -> Value.Str(
            when (rt.machine.state) {
                SessionState.OFF -> "off"
                SessionState.LISTENING -> "listening"
                SessionState.WRITER_LOADING -> "loading"
                SessionState.WRITER_ACTIVE -> "active"
                SessionState.AWAITING_CONFIRM -> "confirming"
            },
        )
        "listening" -> Value.Bool(rt.machine.isListening)
        else -> null
    }

    private fun wifiConnected(): Boolean = try {
        val cm = rt.app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    } catch (e: SecurityException) {
        false
    }

    private fun musicVolumePercent(): Int {
        val am = rt.app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max
    }
}
