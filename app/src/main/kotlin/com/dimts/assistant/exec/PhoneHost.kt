package com.dimts.assistant.exec

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.Settings
import android.telecom.TelecomManager
import android.telephony.SmsManager
import android.view.KeyEvent
import com.dimts.assistant.R
import com.dimts.assistant.AppRuntime
import com.dimts.assistant.access.DimtsAccessibilityService
import com.dimts.core.Fuzzy
import com.dimts.core.command.CommandSpec
import com.dimts.core.command.DeviceHost
import com.dimts.core.script.CommandResult
import com.dimts.core.script.Origin

/** Everything the console can make the phone do. One `when` branch per command id in the registry. */
class PhoneHost(private val rt: AppRuntime) : DeviceHost {
    private val ctx: Context get() = rt.app
    private var torchOn = false

    override fun perform(spec: CommandSpec, args: List<String>, origin: Origin): CommandResult = try {
        run(spec.id, args)
    } catch (e: SecurityException) {
        CommandResult.error("Android refused this (${e.message?.take(80)}). Type 'permissions' to see what is missing.")
    } catch (e: ActivityNotFoundException) {
        CommandResult.error("no app on this phone can do that")
    }

    override fun status(): String = rt.statusText()

    private fun err(m: String) = CommandResult.error(m)
    private fun ok(m: String? = null) = CommandResult.ok(m)

    private fun run(id: String, a: List<String>): CommandResult = when (id) {
        // ---- listening & assistant ----
        "listen.start" -> rt.startListening(a.firstOrNull())
        "listen.stop" -> rt.stopListening()
        "key.record" -> rt.recordKey(a.firstOrNull(), a.getOrNull(1))
        "key.use" -> rt.useKey(a)
        "key.list" -> ok(rt.keyListText())
        "key.delete" -> rt.deleteKey(a.firstOrNull())
        "key.test" -> rt.testKey(a.firstOrNull()?.toIntOrNull() ?: 20)
        "key.sensitivity" -> rt.sensitivity(a.firstOrNull())
        "assistant.start" -> rt.startAssistant()
        "assistant.stop" -> rt.stopAssistant()
        "assistant.language" -> rt.language(a.firstOrNull())
        "assistant.timeout" -> rt.timeout(a.firstOrNull())

        // ---- phone ----
        "call" -> call(a.joinToString(" ").trim())
        "hangup" -> endCall()
        "answer" -> answerCall()
        "sms" -> sms(a)
        "open" -> openApp(a.joinToString(" ").trim())
        "search" -> search(a.joinToString(" ").trim())
        "browse" -> browse(a.joinToString(" ").trim())
        "say" -> { rt.speaker.speak(a.joinToString(" ")); ok(a.joinToString(" ").let { "🔊 $it" }) }
        "notify" -> notify(a.joinToString(" "))
        "contact" -> contact(a.joinToString(" ").trim())
        "flashlight" -> flashlight(a.firstOrNull())
        "settings" -> settingsPage(a.firstOrNull())

        // ---- screen ----
        "back" -> global("Back") { it.back() }
        "home" -> global("Home") { it.home() }
        "recents" -> global("Recent apps") { it.recents() }
        "notifications" -> global("Notifications") { it.notifications() }
        "lock" -> global("Lock") { it.lockScreen() }
        "screenshot" -> global("Screenshot") { it.screenshot() }
        "scroll" -> dir(a.firstOrNull(), setOf("up", "down"), "scroll <up|down>") { s, d -> s.scroll(d) }
        "swipe" -> dir(a.firstOrNull(), setOf("left", "right", "up", "down"), "swipe <left|right|up|down>") { s, d -> s.swipe(d) }
        "tap" -> tap(a)
        "type" -> withAccess { s -> if (s.typeText(a.joinToString(" "))) ok() else err("no text field is focused") }
        "read.screen" -> withAccess { s ->
            val lines = s.readScreen()
            if (lines.isEmpty()) err("nothing readable on screen") else {
                rt.speaker.speak(lines.joinToString(". ").take(300))
                ok(lines.joinToString("\n"))
            }
        }

        // ---- sound ----
        "volume" -> volume(a.firstOrNull())
        "media.play" -> media(KeyEvent.KEYCODE_MEDIA_PLAY)
        "media.pause" -> media(KeyEvent.KEYCODE_MEDIA_PAUSE)
        "media.next" -> media(KeyEvent.KEYCODE_MEDIA_NEXT)
        "media.previous" -> media(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        "media.stop" -> media(KeyEvent.KEYCODE_MEDIA_STOP)
        "ringer" -> ringer(a.firstOrNull())

        // ---- system ----
        "permissions" -> ok(rt.perms.report() + "\n\nType 'grant all' to be asked for the missing ones.")
        "grant" -> rt.grant(a.firstOrNull())
        "clear" -> { rt.ui?.clearConsole() ?: rt.logBus.clear(); ok() }
        "exit" -> { rt.ui?.closeConsole(); ok() }
        else -> err("'$id' is not implemented on this phone")
    }

    // ---- calls and messages -------------------------------------------------------------------

    private class Contact(val name: String, val number: String)

    private fun isNumber(s: String) = Regex("^[+*#0-9][0-9 ()+\\-*#]{2,}$").matches(s)

    private fun lookupContacts(query: String): List<Contact> {
        if (!rt.perms.hasContacts()) return emptyList()
        val out = ArrayList<Contact>()
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$query%"),
            null,
        )?.use { c ->
            while (c.moveToNext() && out.size < 50) out.add(Contact(c.getString(0) ?: "", c.getString(1) ?: ""))
        }
        // best match first: exact name, then names that start with the query, then shortest name
        return out.sortedWith(compareBy({ !it.name.equals(query, true) }, { !it.name.startsWith(query, true) }, { it.name.length }))
    }

    /** Name for an incoming number (null if unknown or contacts are not allowed). */
    fun nameForNumber(number: String): String? {
        if (number.isBlank() || !rt.perms.hasContacts()) return null
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        return ctx.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    }

    private fun resolve(target: String): Pair<Contact?, String?> {
        if (isNumber(target)) return Contact(target, target) to null
        if (!rt.perms.hasContacts()) return null to "contacts permission is missing - type: grant contacts"
        val found = lookupContacts(target)
        if (found.isEmpty()) return null to "no contact named '$target'"
        return found.first() to null
    }

    private fun call(target: String): CommandResult {
        if (target.isEmpty()) return err("usage: call <contact|number>")
        if (!rt.perms.hasCallPhone()) return err("phone permission is missing - type: grant phone")
        val (contact, problem) = resolve(target)
        if (contact == null) return err(problem ?: "unknown contact")
        rt.noteDial(contact.name, contact.number)
        val tm = ctx.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        tm.placeCall(Uri.fromParts("tel", contact.number, null), Bundle())
        return ok("calling ${contact.name}" + if (contact.name != contact.number) " (${contact.number})" else "")
    }

    @Suppress("DEPRECATION")
    private fun endCall(): CommandResult {
        if (!rt.perms.hasAnswerCalls()) return err("phone permission is missing - type: grant phone")
        val tm = ctx.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        return if (tm.endCall()) ok("call ended") else err("there is no call to end")
    }

    @Suppress("DEPRECATION")
    private fun answerCall(): CommandResult {
        if (!rt.perms.hasAnswerCalls()) return err("phone permission is missing - type: grant phone")
        val tm = ctx.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        tm.acceptRingingCall()
        return ok("answering")
    }

    @Suppress("DEPRECATION")
    private fun sms(a: List<String>): CommandResult {
        if (a.size < 2) return err("usage: sms <contact|number> <text>")
        if (!rt.perms.hasSms()) return err("SMS permission is missing - type: grant sms")
        val (contact, problem) = resolve(a[0])
        if (contact == null) return err(problem ?: "unknown contact")
        val text = a.drop(1).joinToString(" ")
        val manager = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java) else SmsManager.getDefault()
        manager.sendMultipartTextMessage(contact.number, null, manager.divideMessage(text), null, null)
        return ok("message sent to ${contact.name}")
    }

    private fun contact(query: String): CommandResult {
        if (query.isEmpty()) return err("usage: contact <name>")
        if (!rt.perms.hasContacts()) return err("contacts permission is missing - type: grant contacts")
        val found = lookupContacts(query)
        if (found.isEmpty()) return err("no contact matches '$query'")
        return ok(found.take(8).joinToString("\n") { "${it.name}  ${it.number}" })
    }

    // ---- apps, web, system pages --------------------------------------------------------------

    private fun launch(intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
    }

    private fun backgroundNote(): String =
        if (!rt.uiVisible && !rt.perms.canDrawOverlays()) " (the app is in the background: if nothing appears, allow 'overlay' - see: permissions)" else ""

    private fun openApp(query: String): CommandResult {
        if (query.isEmpty()) return err("usage: open <app name>")
        val pm = ctx.packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
        val pick = apps.firstOrNull { it.first.equals(query, true) }
            ?: apps.firstOrNull { it.first.startsWith(query, true) }
            ?: apps.firstOrNull { it.first.contains(query, true) }
        if (pick == null) {
            val hint = Fuzzy.closest(query, apps.map { it.first })
            return err("no app named '$query'" + (hint?.let { " - did you mean '$it'?" } ?: ""))
        }
        val intent = pm.getLaunchIntentForPackage(pick.second) ?: return err("'${pick.first}' cannot be opened")
        launch(intent)
        return ok("opening ${pick.first}" + backgroundNote())
    }

    private fun search(query: String): CommandResult {
        if (query.isEmpty()) return err("usage: search <text>")
        try {
            launch(Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, query))
        } catch (e: ActivityNotFoundException) {
            launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(query))))
        }
        return ok("searching: $query" + backgroundNote())
    }

    private fun browse(url: String): CommandResult {
        if (url.isEmpty()) return err("usage: browse <url>")
        val full = if (url.contains("://")) url else "https://$url"
        launch(Intent(Intent.ACTION_VIEW, Uri.parse(full)))
        return ok("opening $full" + backgroundNote())
    }

    private fun settingsPage(page: String?): CommandResult {
        val action = when (page?.lowercase()) {
            null -> Settings.ACTION_SETTINGS
            "wifi" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "display" -> Settings.ACTION_DISPLAY_SETTINGS
            "sound" -> Settings.ACTION_SOUND_SETTINGS
            "battery" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
            "apps" -> Settings.ACTION_APPLICATION_SETTINGS
            "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "accessibility" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            "airplane" -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
            else -> return err("unknown settings page '$page' (try: wifi, bluetooth, display, sound, battery, apps, location, accessibility, airplane)")
        }
        launch(Intent(action))
        return ok("opening settings" + backgroundNote())
    }

    private fun notify(text: String): CommandResult {
        if (text.isEmpty()) return err("usage: notify <text>")
        if (!rt.perms.hasNotifications()) return err("notification permission is missing - type: grant notifications")
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, ctx.getString(R.string.notif_channel_messages), NotificationManager.IMPORTANCE_HIGH),
        )
        val n = buildNotification(text)
        nm.notify((System.nanoTime() % 100000).toInt() + 100, n)
        return ok("notification shown")
    }

    private fun buildNotification(text: String) =
        android.app.Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(ctx.getString(R.string.app_name))
            .setContentText(text)
            .setAutoCancel(true)
            .build()

    private fun flashlight(mode: String?): CommandResult {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull {
            val c = cm.getCameraCharacteristics(it)
            c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: return err("this phone has no flashlight")
        val on = when (mode?.lowercase()) {
            "on" -> true
            "off" -> false
            "toggle", null -> !torchOn
            else -> return err("usage: flashlight <on|off|toggle>")
        }
        cm.setTorchMode(id, on)
        torchOn = on
        return ok("flashlight " + if (on) "on" else "off")
    }

    // ---- screen control (accessibility service) -----------------------------------------------

    private fun withAccess(block: (DimtsAccessibilityService) -> CommandResult): CommandResult {
        val s = DimtsAccessibilityService.instance
            ?: return err("the accessibility service is off - type: grant accessibility   (then switch 'Dimts' on)")
        return block(s)
    }

    private fun global(label: String, action: (DimtsAccessibilityService) -> Boolean): CommandResult =
        withAccess { s -> if (action(s)) ok() else err("$label is not available on this Android version or screen") }

    private fun dir(
        d: String?,
        allowed: Set<String>,
        usage: String,
        action: (DimtsAccessibilityService, String) -> Boolean,
    ): CommandResult {
        val direction = d?.lowercase()
        if (direction == null || direction !in allowed) return err("usage: $usage")
        return withAccess { s -> if (action(s, direction)) ok() else err("the gesture was not accepted") }
    }

    private fun tap(a: List<String>): CommandResult {
        if (a.isEmpty()) return err("usage: tap <x> <y>  |  tap <text on screen>")
        val x = a.getOrNull(0)?.toFloatOrNull()
        val y = a.getOrNull(1)?.toFloatOrNull()
        return withAccess { s ->
            if (a.size == 2 && x != null && y != null) {
                if (s.tap(x, y)) ok() else err("the tap was not accepted")
            } else {
                val text = a.joinToString(" ")
                if (s.tapText(text)) ok() else err("nothing on screen says '$text'")
            }
        }
    }

    // ---- sound --------------------------------------------------------------------------------

    private fun audio() = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private fun volume(arg: String?): CommandResult {
        val am = audio()
        when (arg?.lowercase()) {
            "up" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            "down" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            "mute" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
            "unmute" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
            else -> {
                val n = arg?.removeSuffix("%")?.toIntOrNull()
                if (n == null || n !in 0..100) return err("usage: volume <up|down|0-100|mute|unmute>")
                val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(n * max / 100f), AudioManager.FLAG_SHOW_UI)
            }
        }
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return ok("volume ${am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max}%")
    }

    private fun media(code: Int): CommandResult {
        val am = audio()
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        return ok()
    }

    private fun ringer(mode: String?): CommandResult {
        val value = when (mode?.lowercase()) {
            "normal" -> AudioManager.RINGER_MODE_NORMAL
            "vibrate" -> AudioManager.RINGER_MODE_VIBRATE
            "silent" -> AudioManager.RINGER_MODE_SILENT
            else -> return err("usage: ringer <normal|vibrate|silent>")
        }
        return try {
            audio().ringerMode = value
            ok("ringer $mode")
        } catch (e: SecurityException) {
            err("silent mode needs Do-Not-Disturb access - type: grant dnd")
        }
    }

    companion object {
        private const val CHANNEL = "dimts_messages"
    }
}
