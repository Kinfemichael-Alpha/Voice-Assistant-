package com.dimts.assistant.exec

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.app.NotificationManager.INTERRUPTION_FILTER_ALARMS
import android.app.NotificationManager.INTERRUPTION_FILTER_ALL
import android.app.NotificationManager.INTERRUPTION_FILTER_NONE
import android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.MediaStore
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
import com.dimts.core.script.Level
import com.dimts.core.script.Origin
import com.dimts.core.script.Value

/** Everything the console can make the phone do. One `when` branch per command id in the registry. */
class PhoneHost(private val rt: AppRuntime) : DeviceHost {
    private val ctx: Context get() = rt.app
    private var torchOn: Boolean
        get() = rt.env.torchOn
        set(value) { rt.env.torchOn = value }
    private var pending: List<Pair<String, String>> = emptyList()

    @Volatile private var fromVoice = false

    override fun perform(spec: CommandSpec, args: List<String>, origin: Origin): CommandResult = try {
        fromVoice = origin == Origin.VOICE
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
        "pick" -> pick(a.firstOrNull())
        "apps" -> listApps(a.joinToString(" "))
        "dial" -> dial(a.joinToString(""))
        "camera" -> camera(a.firstOrNull())
        "email" -> email(a)
        "event" -> calendarEvent(a.joinToString(" "))
        "youtube" -> youtube(a.joinToString(" "))
        "paste" -> paste()
        "note" -> addNote(a.joinToString(" "))
        "notes" -> listNotes()
        "notes.clear" -> clearNotes()
        "export" -> exportScript()
        "import" -> importScript()
        "power" -> power(a.firstOrNull())
        "panel" -> if (needService()?.quickSettings() == true) ok() else err("the accessibility service is off - type: grant accessibility")
        "splitscreen" -> if (needService()?.splitScreen() == true) ok() else err("the accessibility service is off - type: grant accessibility")
        "net.wifi", "net.data", "net.bluetooth", "net.airplane", "net.hotspot", "net.location", "net.powersave" -> network(id, a.firstOrNull())
        "alarm.set" -> setAlarm(a)
        "timer.set" -> setTimer(a)
        "copy" -> copyText(a.joinToString(" "))
        "share" -> shareText(a.joinToString(" "))
        "map" -> showMap(a.joinToString(" ").trim())
        "brightness" -> brightness(a.firstOrNull())
        "rotate" -> rotate(a.firstOrNull())
        "dnd" -> dnd(a.firstOrNull())
        "vibrate" -> vibrate(a.firstOrNull()?.toLongOrNull() ?: 400L)

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
        "volume" -> volume(a)
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
        val exact = apps.filter { it.first.equals(query, true) }
        if (exact.size == 1) {
            pending = emptyList()
            return launchApp(exact[0])
        }
        val labels = Fuzzy.rank(query, apps.map { it.first }, 5)
        val options = labels.mapNotNull { label -> apps.firstOrNull { it.first == label } }
        if (options.isEmpty()) {
            pending = emptyList()
            return err("no app is named or sounds like '$query'")
        }
        pending = options
        val list = options.mapIndexed { i, o -> "  ${i + 1}. ${o.first}" }.joinToString("\n")
        val spoken = options.mapIndexed { i, o -> "${i + 1} ${o.first}" }.joinToString(", ")
        if (fromVoice) rt.speaker.speak("Did you mean $spoken? Say pick and a number.")
        return ok("no app is called exactly '$query'. Did you mean:\n$list\nChoose with:  pick <number>")
    }

    private fun launchApp(app: Pair<String, String>): CommandResult {
        val intent = ctx.packageManager.getLaunchIntentForPackage(app.second) ?: return err("'${app.first}' cannot be opened")
        launch(intent)
        return ok("opening ${app.first}" + backgroundNote())
    }

    private class Tile(val title: String, val state: String?, val labels: List<String>)

    private val tiles = mapOf(
        "net.wifi" to Tile("Wi-Fi", "wifi.enabled", listOf("Wi-Fi", "Wi\u2011Fi", "WiFi")),
        "net.data" to Tile("Mobile data", "data.enabled", listOf("Mobile data", "Cellular data")),
        "net.bluetooth" to Tile("Bluetooth", "bluetooth.on", listOf("Bluetooth")),
        "net.airplane" to Tile("Airplane mode", "airplane.mode", listOf("Flight mode", "Airplane mode")),
        "net.hotspot" to Tile("Hotspot", null, listOf("Mobile Hotspot", "Hotspot")),
        "net.location" to Tile("Location", "location.enabled", listOf("Location")),
        "net.powersave" to Tile("Power saving", "battery.saver", listOf("Power saving", "Battery saver")),
    )

    private fun tileState(tile: Tile): Boolean? = tile.state?.let { (rt.env.lookup(it) as? Value.Bool)?.v }

    private fun network(id: String, mode: String?): CommandResult {
        val tile = tiles[id] ?: return err("unknown switch")
        val m = (mode ?: "toggle").lowercase()
        if (m != "on" && m != "off" && m != "toggle") return err("usage: ${tile.title.lowercase()} [on|off|toggle]")
        val service = DimtsAccessibilityService.instance
            ?: return err("the accessibility service is off - type: grant accessibility")
        val current = tileState(tile)
        val want = when (m) {
            "on" -> true
            "off" -> false
            else -> current?.not()
        }
        if (current != null && want == current) return ok("${tile.title} is already " + if (current) "on" else "off")
        if (!service.quickSettings()) return err("could not open Quick settings on this phone")
        rt.handler.postDelayed({ pressTile(service, tile, want, 0) }, 900)
        return ok("switching ${tile.title}" + (want?.let { if (it) " on" else " off" } ?: "") + " through Quick settings ...")
    }

    private fun pressTile(service: DimtsAccessibilityService, tile: Tile, want: Boolean?, attempt: Int) {
        val hit = tile.labels.any { service.tapText(it) }
        if (!hit) {
            if (attempt < 2) {
                service.swipePanel()
                rt.handler.postDelayed({ pressTile(service, tile, want, attempt + 1) }, 700)
            } else {
                service.back()
                rt.log(Level.ERROR, "the '${tile.title}' tile was not found in Quick settings - add it with the panel's edit button, or type: settings")
            }
            return
        }
        rt.handler.postDelayed({
            service.back()
            val now = tileState(tile)
            when {
                now == null -> rt.log(Level.OK, "${tile.title}: tile pressed")
                want == null || now == want -> rt.log(Level.OK, "${tile.title} is now " + if (now) "on" else "off")
                else -> rt.log(Level.WARN, "${tile.title} did not change (it is still " + (if (now) "on" else "off") + ") - Android may need a moment or a confirmation")
            }
        }, 1500)
    }

    private fun needService(): DimtsAccessibilityService? = DimtsAccessibilityService.instance

    private fun listApps(filter: String): CommandResult {
        val pm = ctx.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val names = pm.queryIntentActivities(launcher, 0).map { it.loadLabel(pm).toString() }.distinct().sortedBy { it.lowercase() }
        val shown = if (filter.isBlank()) names else names.filter { it.contains(filter, true) }
        if (shown.isEmpty()) return err("no app matches '$filter'")
        return ok("${shown.size} app(s):\n" + shown.joinToString("\n") { "  $it" })
    }

    private fun dial(number: String): CommandResult {
        if (number.none { it.isDigit() }) return err("usage: keypad <number>")
        launch(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number))))
        return ok("dialer opened" + backgroundNote())
    }

    private fun camera(mode: String?): CommandResult {
        val action = if (mode.equals("video", true)) MediaStore.INTENT_ACTION_VIDEO_CAMERA else MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA
        val intent = Intent(action)
        if (mode.equals("selfie", true)) {
            intent.putExtra("android.intent.extras.CAMERA_FACING", 1)
            intent.putExtra("android.intent.extra.USE_FRONT_CAMERA", true)
        }
        launch(intent)
        return ok("camera opened" + backgroundNote())
    }

    private fun email(args: List<String>): CommandResult {
        val to = args.firstOrNull() ?: return err("usage: email <address> [subject]")
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + Uri.encode(to)))
        if (args.size > 1) intent.putExtra(Intent.EXTRA_SUBJECT, args.drop(1).joinToString(" "))
        launch(intent)
        return ok("email opened" + backgroundNote())
    }

    private fun calendarEvent(title: String): CommandResult {
        if (title.isBlank()) return err("usage: event <title>")
        launch(Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI).putExtra(CalendarContract.Events.TITLE, title))
        return ok("calendar opened" + backgroundNote())
    }

    private fun youtube(query: String): CommandResult {
        if (query.isBlank()) return err("usage: youtube <search>")
        launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(query))))
        return ok("searching YouTube" + backgroundNote())
    }

    private fun clipboardText(): String? {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0).coerceToText(ctx)?.toString()
    }

    private fun paste(): CommandResult = clipboardText()?.takeIf { it.isNotBlank() }?.let { ok(it) } ?: err("the clipboard is empty (or Android hides it while Ignis is in the background)")

    private val notePrefs get() = ctx.getSharedPreferences("ignis_notes", Context.MODE_PRIVATE)

    private fun noteLines(): List<String> = notePrefs.getString("notes", "")!!.split('\n').filter { it.isNotBlank() }

    private fun addNote(text: String): CommandResult {
        if (text.isBlank()) return err("usage: note <text>")
        val stamp = java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.US).format(java.util.Date())
        val lines = noteLines() + "$stamp  ${text.replace('\n', ' ')}"
        notePrefs.edit().putString("notes", lines.joinToString("\n")).apply()
        return ok("note ${lines.size} saved")
    }

    private fun listNotes(): CommandResult {
        val lines = noteLines()
        if (lines.isEmpty()) return ok("no notes yet  (note buy bread)")
        return ok(lines.mapIndexed { i, l -> "${i + 1}. $l" }.joinToString("\n"))
    }

    private fun clearNotes(): CommandResult {
        val n = noteLines().size
        notePrefs.edit().remove("notes").apply()
        return ok("$n note(s) deleted")
    }

    private fun exportScript(): CommandResult {
        val engine = rt.shell.engine
        val parts = engine.derivedStates().map { it.source } + engine.actionDefs().map { it.source } + engine.rules().map { it.source }
        if (parts.isEmpty()) return err("nothing to export yet - define a rule, state or action first")
        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("ignis script", parts.joinToString("\n")))
        return ok("${parts.size} item(s) copied to the clipboard")
    }

    private fun importScript(): CommandResult {
        val text = clipboardText()?.takeIf { it.isNotBlank() } ?: return err("the clipboard is empty")
        rt.handler.post { rt.shell.submit(text) }
        return ok("running ${text.lines().size} line(s) from the clipboard ...")
    }

    private fun power(arg: String?): CommandResult {
        val service = needService() ?: return err("the accessibility service is off - type: grant accessibility")
        val labels = when (arg?.lowercase()) {
            null, "menu" -> emptyList()
            "off" -> listOf("Power off", "Shut down", "Switch off")
            "restart" -> listOf("Restart", "Reboot")
            else -> return err("usage: power [menu|off|restart]")
        }
        if (!service.powerMenu()) return err("could not open the power menu")
        if (labels.isEmpty()) return ok("power menu opened")
        rt.handler.postDelayed({
            if (labels.any { service.tapText(it) }) {
                rt.handler.postDelayed({ labels.any { service.tapText(it) } }, 1000)
            } else {
                rt.log(Level.ERROR, "the '${labels.first()}' button was not found in the power menu")
            }
        }, 1000)
        return ok("pressing ${labels.first()} ...")
    }

    private fun pick(arg: String?): CommandResult {
        if (pending.isEmpty()) return err("nothing to choose from - there are no options right now")
        val n = arg?.toIntOrNull() ?: 1
        if (n < 1 || n > pending.size) return err("choose a number from 1 to ${pending.size}")
        val chosen = pending[n - 1]
        pending = emptyList()
        return launchApp(chosen)
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
            "nfc" -> Settings.ACTION_NFC_SETTINGS
            "date" -> Settings.ACTION_DATE_SETTINGS
            "language" -> Settings.ACTION_LOCALE_SETTINGS
            "storage" -> Settings.ACTION_INTERNAL_STORAGE_SETTINGS
            "privacy" -> Settings.ACTION_PRIVACY_SETTINGS
            "security" -> Settings.ACTION_SECURITY_SETTINGS
            "vpn" -> Settings.ACTION_VPN_SETTINGS
            "roaming" -> Settings.ACTION_DATA_ROAMING_SETTINGS
            "cast" -> Settings.ACTION_CAST_SETTINGS
            "night" -> Settings.ACTION_NIGHT_DISPLAY_SETTINGS
            "wireless" -> Settings.ACTION_WIRELESS_SETTINGS
            "sync" -> Settings.ACTION_SYNC_SETTINGS
            "info" -> Settings.ACTION_DEVICE_INFO_SETTINGS
            "input" -> Settings.ACTION_INPUT_METHOD_SETTINGS
            "usage" -> Settings.ACTION_USAGE_ACCESS_SETTINGS
            else -> return err("unknown settings page '$page' (type: help settings)")
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
            ?: return err("the accessibility service is off - type: grant accessibility   (then switch 'Ignis' on)")
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

    private val streams = mapOf(
        "music" to AudioManager.STREAM_MUSIC,
        "ring" to AudioManager.STREAM_RING,
        "alarm" to AudioManager.STREAM_ALARM,
        "notification" to AudioManager.STREAM_NOTIFICATION,
        "call" to AudioManager.STREAM_VOICE_CALL,
        "system" to AudioManager.STREAM_SYSTEM,
    )

    private fun volume(args: List<String>): CommandResult {
        val am = audio()
        val named = args.firstOrNull()?.lowercase()?.let { streams[it] }
        val stream = named ?: AudioManager.STREAM_MUSIC
        val arg = (if (named != null) args.getOrNull(1) else args.firstOrNull())?.lowercase()
        val usage = "usage: volume [music|ring|alarm|notification|call|system] <up|down|0-100|mute|unmute>"
        when (arg) {
            "up" -> am.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            "down" -> am.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            "mute" -> am.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
            "unmute" -> am.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
            else -> {
                val n = arg?.removeSuffix("%")?.toIntOrNull()
                if (n == null || n !in 0..100) return err(usage)
                val max = am.getStreamMaxVolume(stream)
                am.setStreamVolume(stream, Math.round(n * max / 100f), AudioManager.FLAG_SHOW_UI)
            }
        }
        val max = am.getStreamMaxVolume(stream).coerceAtLeast(1)
        return ok("volume ${am.getStreamVolume(stream) * 100 / max}%")
    }

    private fun parseClock(a: List<String>): Triple<Int, Int, Int>? {
        val first = a.firstOrNull()?.lowercase() ?: return null
        val m = Regex("^(\\d{1,2})(?::(\\d{2}))?(am|pm)?$").matchEntire(first) ?: return null
        var used = 1
        var suffix = m.groupValues[3]
        if (suffix.isEmpty()) {
            val next = a.getOrNull(1)?.lowercase()
            if (next == "am" || next == "pm") { suffix = next; used = 2 }
        }
        var h = m.groupValues[1].toInt()
        val min = m.groupValues[2].ifEmpty { "0" }.toInt()
        if (suffix == "pm" && h < 12) h += 12
        if (suffix == "am" && h == 12) h = 0
        if (h !in 0..23 || min !in 0..59) return null
        return Triple(h, min, used)
    }

    private fun setAlarm(a: List<String>): CommandResult {
        val t = parseClock(a) ?: return err("usage: alarm <time> [label]   (example: alarm 7:30 am wake up)")
        val label = a.drop(t.third).joinToString(" ")
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, t.first)
            .putExtra(AlarmClock.EXTRA_MINUTES, t.second)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (label.isNotEmpty()) intent.putExtra(AlarmClock.EXTRA_MESSAGE, label)
        launch(intent)
        return ok("alarm set for %d:%02d".format(t.first, t.second) + if (label.isNotEmpty()) " ($label)" else "")
    }

    private val durationUnits = mapOf(
        "s" to 1L, "sec" to 1L, "secs" to 1L, "second" to 1L, "seconds" to 1L,
        "m" to 60L, "min" to 60L, "mins" to 60L, "minute" to 60L, "minutes" to 60L,
        "h" to 3600L, "hr" to 3600L, "hrs" to 3600L, "hour" to 3600L, "hours" to 3600L,
    )

    private fun setTimer(a: List<String>): CommandResult {
        var total = 0L
        var i = 0
        while (i < a.size) {
            val n = a[i].toLongOrNull() ?: break
            val factor = a.getOrNull(i + 1)?.lowercase()?.let { durationUnits[it] }
            if (factor != null) {
                total += n * factor
                i += 2
            } else {
                if (i == 0) { total += n * 60; i = 1 }
                break
            }
        }
        if (total <= 0L || total > 86_400L) return err("usage: timer <duration> [label]   (example: timer 10 min tea)")
        val label = a.drop(i).joinToString(" ")
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, total.toInt())
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (label.isNotEmpty()) intent.putExtra(AlarmClock.EXTRA_MESSAGE, label)
        launch(intent)
        return ok("timer started: ${total / 60} min ${total % 60} s" + if (label.isNotEmpty()) " ($label)" else "")
    }

    private fun copyText(text: String): CommandResult {
        if (text.isEmpty()) return err("usage: copy <text>")
        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("dimts", text))
        return ok("copied")
    }

    private fun shareText(text: String): CommandResult {
        if (text.isEmpty()) return err("usage: share <text>")
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        launch(Intent.createChooser(send, null))
        return ok("share sheet opened" + backgroundNote())
    }

    private fun showMap(place: String): CommandResult {
        if (place.isEmpty()) return err("usage: map <place>")
        launch(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(place))))
        return ok("showing $place" + backgroundNote())
    }

    private fun brightness(arg: String?): CommandResult {
        if (!rt.perms.canWriteSettings()) return err("needs 'modify system settings' access - type: grant write_settings")
        val resolver = ctx.contentResolver
        if (arg.equals("auto", true)) {
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
            return ok("brightness: auto")
        }
        val n = arg?.removeSuffix("%")?.toIntOrNull()
        if (n == null || n !in 0..100) return err("usage: brightness <0-100|auto>")
        Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, Math.round(n * 255 / 100f))
        return ok("brightness $n%")
    }

    private fun rotate(arg: String?): CommandResult {
        if (!rt.perms.canWriteSettings()) return err("needs 'modify system settings' access - type: grant write_settings")
        val on = when (arg?.lowercase()) {
            "on" -> 1
            "off" -> 0
            else -> return err("usage: rotate <on|off>")
        }
        Settings.System.putInt(ctx.contentResolver, Settings.System.ACCELEROMETER_ROTATION, on)
        return ok("auto-rotate " + if (on == 1) "on" else "off")
    }

    private fun dnd(arg: String?): CommandResult {
        if (!rt.perms.hasDndAccess()) return err("needs Do-Not-Disturb access - type: grant dnd")
        val filter = when (arg?.lowercase()) {
            "on" -> INTERRUPTION_FILTER_NONE
            "alarms" -> INTERRUPTION_FILTER_ALARMS
            "priority" -> INTERRUPTION_FILTER_PRIORITY
            "off" -> INTERRUPTION_FILTER_ALL
            else -> return err("usage: dnd <on|off|alarms|priority>")
        }
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).setInterruptionFilter(filter)
        return ok("do not disturb: $arg")
    }

    @Suppress("DEPRECATION")
    private fun vibrate(ms: Long): CommandResult {
        val time = ms.coerceIn(20L, 5000L)
        val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= 31) {
            (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        if (!vibrator.hasVibrator()) return err("this phone cannot vibrate")
        vibrator.vibrate(VibrationEffect.createOneShot(time, VibrationEffect.DEFAULT_AMPLITUDE))
        return ok()
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
