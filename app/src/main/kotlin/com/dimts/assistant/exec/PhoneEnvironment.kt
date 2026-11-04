package com.dimts.assistant.exec

import android.app.ActivityManager
import android.app.KeyguardManager
import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import com.dimts.assistant.AppRuntime
import com.dimts.assistant.access.DimtsAccessibilityService
import com.dimts.core.command.Catalog
import com.dimts.core.script.Builtins
import com.dimts.core.script.Environment
import com.dimts.core.script.Value
import com.dimts.core.session.SessionState

class PhoneEnvironment(private val rt: AppRuntime) : Environment {
    @Volatile var batteryLevel = -1
    @Volatile var charging = false
    @Volatile var plugged = "none"
    @Volatile var temperature = Double.NaN
    @Volatile var health = "unknown"
    @Volatile var callState = "idle"
    @Volatile var callContact = ""
    @Volatile var callNumber = ""
    @Volatile var torchOn = false

    private val ctx: Context get() = rt.app

    fun updateBattery(intent: Intent?) {
        intent ?: return
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level >= 0 && scale > 0) batteryLevel = level * 100 / scale
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        plugged = when (intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)) {
            BatteryManager.BATTERY_PLUGGED_AC -> "ac"
            BatteryManager.BATTERY_PLUGGED_USB -> "usb"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
            else -> "none"
        }
        val temp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        if (temp != Int.MIN_VALUE) temperature = temp / 10.0
        health = when (intent.getIntExtra(BatteryManager.EXTRA_HEALTH, 0)) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "good"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheat"
            BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over_voltage"
            BatteryManager.BATTERY_HEALTH_COLD -> "cold"
            BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "failure"
            else -> "unknown"
        }
    }

    private val provided: Set<String> = Catalog.states.map { it.name }.toSet() - Builtins.NAMES

    override fun names(): Set<String> = provided

    private fun num(v: Number): Value = Value.Num(v.toDouble())

    private fun safe(block: () -> Value?): Value? = try {
        block()
    } catch (e: Exception) {
        null
    }

    override fun lookup(name: String): Value? = when (name) {
        "battery.level" -> if (batteryLevel >= 0) num(batteryLevel) else null
        "battery.charging" -> Value.Bool(charging)
        "battery.plugged" -> Value.Str(plugged)
        "battery.temperature" -> if (temperature.isNaN()) null else num(temperature)
        "battery.health" -> Value.Str(health)
        "battery.saver" -> safe { Value.Bool(power().isPowerSaveMode) }

        "wifi.connected" -> safe { Value.Bool(transports().contains(NetworkCapabilities.TRANSPORT_WIFI)) }
        "mobile.connected" -> safe { Value.Bool(transports().contains(NetworkCapabilities.TRANSPORT_CELLULAR)) }
        "network.connected" -> safe { Value.Bool(hasInternet()) }
        "network.type" -> safe { Value.Str(networkType()) }
        "airplane.mode" -> safe { Value.Bool(Settings.Global.getInt(ctx.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1) }
        "bluetooth.on" -> safe { Value.Bool((ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter?.isEnabled == true) }
        "headphones.connected" -> safe { Value.Bool(headphones()) }

        "screen.awake" -> safe { Value.Bool(power().isInteractive) }
        "screen.locked" -> safe { Value.Bool((ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked) }
        "screen.brightness" -> safe { num(Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS) * 100 / 255) }
        "app.current" -> Value.Str(DimtsAccessibilityService.currentPackage)

        "volume" -> safe { num(streamPercent(AudioManager.STREAM_MUSIC)) }
        "volume.ring" -> safe { num(streamPercent(AudioManager.STREAM_RING)) }
        "volume.alarm" -> safe { num(streamPercent(AudioManager.STREAM_ALARM)) }
        "volume.notification" -> safe { num(streamPercent(AudioManager.STREAM_NOTIFICATION)) }
        "ringer.mode" -> safe {
            Value.Str(
                when (audio().ringerMode) {
                    AudioManager.RINGER_MODE_SILENT -> "silent"
                    AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
                    else -> "normal"
                },
            )
        }
        "dnd.mode" -> safe {
            Value.Str(
                when ((ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).currentInterruptionFilter) {
                    NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "priority"
                    NotificationManager.INTERRUPTION_FILTER_ALARMS -> "alarms"
                    NotificationManager.INTERRUPTION_FILTER_NONE -> "none"
                    else -> "off"
                },
            )
        }
        "audio.playing" -> safe { Value.Bool(audio().isMusicActive) }
        "flashlight.on" -> Value.Bool(torchOn)

        "call.state" -> Value.Str(callState)
        "call.contact" -> Value.Str(callContact)
        "call.number" -> Value.Str(callNumber)

        "memory.free" -> safe {
            val info = ActivityManager.MemoryInfo()
            (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
            num(info.availMem * 100 / info.totalMem.coerceAtLeast(1))
        }
        "storage.free" -> safe {
            val fs = StatFs(android.os.Environment.getDataDirectory().path)
            num(fs.availableBytes * 100 / fs.totalBytes.coerceAtLeast(1))
        }
        "uptime.minutes" -> num(SystemClock.elapsedRealtime() / 60_000)
        "device.model" -> Value.Str(Build.MODEL)
        "android.version" -> Build.VERSION.RELEASE.substringBefore('.').toDoubleOrNull()?.let { Value.Num(it) }

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

    private fun power() = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager

    private fun audio() = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private fun connectivity() = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private fun capabilities(): NetworkCapabilities? = try {
        val cm = connectivity()
        cm.getNetworkCapabilities(cm.activeNetwork)
    } catch (e: SecurityException) {
        null
    }

    private fun transports(): Set<Int> {
        val caps = capabilities() ?: return emptySet()
        val all = listOf(
            NetworkCapabilities.TRANSPORT_WIFI, NetworkCapabilities.TRANSPORT_CELLULAR,
            NetworkCapabilities.TRANSPORT_ETHERNET, NetworkCapabilities.TRANSPORT_VPN,
        )
        return all.filter { caps.hasTransport(it) }.toSet()
    }

    private fun hasInternet(): Boolean = capabilities()?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

    private fun networkType(): String {
        val t = transports()
        return when {
            NetworkCapabilities.TRANSPORT_WIFI in t -> "wifi"
            NetworkCapabilities.TRANSPORT_CELLULAR in t -> "mobile"
            NetworkCapabilities.TRANSPORT_ETHERNET in t -> "ethernet"
            NetworkCapabilities.TRANSPORT_VPN in t -> "vpn"
            else -> "none"
        }
    }

    private fun headphones(): Boolean {
        val wanted = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_USB_HEADSET,
        )
        return audio().getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in wanted }
    }

    private fun streamPercent(stream: Int): Int {
        val am = audio()
        val max = am.getStreamMaxVolume(stream).coerceAtLeast(1)
        return am.getStreamVolume(stream) * 100 / max
    }
}
