package com.dimts.core.command

data class StateSpec(
    val name: String,
    val kind: String,
    val group: String,
    val summary: String,
    val values: String = "",
)

data class EventSpec(val name: String, val summary: String)

object Catalog {
    val groups: List<Pair<String, String>> = listOf(
        "time" to "TIME & DATE",
        "battery" to "BATTERY & POWER",
        "network" to "NETWORK",
        "screen" to "SCREEN & APPS",
        "sound" to "SOUND",
        "phone" to "PHONE & CALLS",
        "system" to "DEVICE & SYSTEM",
        "assistant" to "ASSISTANT",
    )

    private fun s(name: String, kind: String, group: String, summary: String, values: String = "") =
        StateSpec(name, kind, group, summary, values)

    val states: List<StateSpec> = listOf(
        s("time", "time", "time", "Current time of day.", "compare: time > 10 pm,  time between 10 pm and 6 am"),
        s("hour", "number", "time", "Current hour, 0-23."),
        s("minute", "number", "time", "Current minute, 0-59."),
        s("day", "text", "time", "Day of the week.", "monday ... sunday  (mon, tue ... also work)"),
        s("date", "number", "time", "Day of the month, 1-31."),
        s("month", "number", "time", "Month of the year, 1-12."),
        s("year", "number", "time", "Current year, for example 2026."),
        s("random", "number", "time", "A new random number from 0 to 99 every time it is read."),

        s("battery.level", "number", "battery", "Battery charge in percent.", "0 - 100"),
        s("battery.charging", "yes/no", "battery", "True while the phone is charging or full."),
        s("battery.plugged", "text", "battery", "What the phone is plugged into.", "none, ac, usb, wireless"),
        s("battery.temperature", "number", "battery", "Battery temperature in degrees Celsius."),
        s("battery.health", "text", "battery", "Battery health reported by Android.", "good, overheat, dead, over_voltage, cold, failure, unknown"),
        s("battery.saver", "yes/no", "battery", "Battery Saver (power-save mode) is on."),

        s("wifi.connected", "yes/no", "network", "Connected to a Wi-Fi network."),
        s("mobile.connected", "yes/no", "network", "Using mobile data right now."),
        s("network.connected", "yes/no", "network", "Any internet connection at all."),
        s("network.type", "text", "network", "Type of the active connection.", "wifi, mobile, ethernet, vpn, none"),
        s("airplane.mode", "yes/no", "network", "Airplane mode is on."),
        s("bluetooth.on", "yes/no", "network", "Bluetooth is switched on."),
        s("headphones.connected", "yes/no", "network", "Wired or Bluetooth headphones / speaker connected."),

        s("screen.awake", "yes/no", "screen", "The screen is on."),
        s("screen.locked", "yes/no", "screen", "The lock screen is showing."),
        s("screen.brightness", "number", "screen", "Screen brightness in percent.", "0 - 100"),
        s("app.current", "text", "screen", "Package name of the app in front (needs the accessibility service).", "test with: app.current contains chrome"),

        s("volume", "number", "sound", "Media volume in percent.", "0 - 100"),
        s("volume.ring", "number", "sound", "Ringtone volume in percent.", "0 - 100"),
        s("volume.alarm", "number", "sound", "Alarm volume in percent.", "0 - 100"),
        s("volume.notification", "number", "sound", "Notification volume in percent.", "0 - 100"),
        s("ringer.mode", "text", "sound", "Ringer mode.", "normal, vibrate, silent"),
        s("dnd.mode", "text", "sound", "Do Not Disturb filter.", "off, priority, alarms, none"),
        s("audio.playing", "yes/no", "sound", "Music or a video is playing."),
        s("flashlight.on", "yes/no", "sound", "The flashlight is on (when switched on by Dimts)."),

        s("call.state", "text", "phone", "State of the phone line.", "idle, ringing, offhook"),
        s("call.contact", "text", "phone", "Contact name of the other side of the current call."),
        s("call.number", "text", "phone", "Number of the other side of the current call."),

        s("memory.free", "number", "system", "Free RAM in percent."),
        s("storage.free", "number", "system", "Free internal storage in percent."),
        s("uptime.minutes", "number", "system", "Minutes since the phone started."),
        s("device.model", "text", "system", "Phone model name."),
        s("android.version", "number", "system", "Android version, for example 14."),

        s("assistant.state", "text", "assistant", "What the voice assistant is doing.", "off, listening, loading, active, confirming"),
        s("listening", "yes/no", "assistant", "The key-sound listener is on."),
    )

    val events: List<EventSpec> = listOf(
        EventSpec("call.incoming", "a call starts ringing"),
        EventSpec("call.answered", "you answered an incoming call (Android cannot tell when the OTHER person answers your outgoing call)"),
        EventSpec("call.started", "you dialled out"),
        EventSpec("call.ended", "a call finished"),
        EventSpec("call.missed", "an incoming call ended without being answered"),
        EventSpec("key.heard", "your key sound was heard"),
        EventSpec("assistant.started", "the voice assistant loaded"),
        EventSpec("assistant.stopped", "the voice assistant unloaded"),
        EventSpec("screen.on", "the screen turned on"),
        EventSpec("screen.off", "the screen turned off"),
        EventSpec("screen.unlocked", "the phone was unlocked"),
        EventSpec("power.connected", "a charger was plugged in"),
        EventSpec("power.disconnected", "the charger was removed"),
        EventSpec("battery.low", "Android's low-battery warning"),
        EventSpec("battery.okay", "the battery recovered from low"),
        EventSpec("boot", "the phone finished starting"),
    )

    fun state(name: String): StateSpec? = states.firstOrNull { it.name.equals(name, ignoreCase = true) }

    fun event(name: String): EventSpec? = events.firstOrNull { it.name.equals(name, ignoreCase = true) }

    fun groupTitle(key: String): String? = groups.firstOrNull { it.first == key }?.second

    fun phoneStateNames(): Set<String> = states.filter { it.group != "time" }.map { it.name }.toSet()
}
