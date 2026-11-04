package com.dimts.assistant.util

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.dimts.assistant.access.DimtsAccessibilityService

/** Everything the app asks for, why, and how to check/request it. */
class Permissions(private val ctx: Context) {

    enum class Kind { POPUP, SETTINGS_PAGE }

    class Item(
        val id: String,
        val title: String,
        val why: String,
        val kind: Kind,
        val required: Boolean,
        /** Runtime permissions to request (POPUP kind). */
        val perms: List<String> = emptyList(),
        val granted: () -> Boolean,
        /** Settings screen to open (SETTINGS_PAGE kind). */
        val settingsIntent: (() -> Intent)? = null,
    )

    private fun has(perm: String) = ctx.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED

    fun hasMicrophone() = has(Manifest.permission.RECORD_AUDIO)
    fun hasCallPhone() = has(Manifest.permission.CALL_PHONE)
    fun hasAnswerCalls() = has(Manifest.permission.ANSWER_PHONE_CALLS)
    fun hasContacts() = has(Manifest.permission.READ_CONTACTS)
    fun hasSms() = has(Manifest.permission.SEND_SMS)
    fun hasNotifications() = Build.VERSION.SDK_INT < 33 || has(Manifest.permission.POST_NOTIFICATIONS)

    fun canExactAlarms(): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        return (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()
    }

    fun accessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = ComponentName(ctx, DimtsAccessibilityService::class.java).flattenToString()
        return enabled.split(':').any { it.equals(me, ignoreCase = true) }
    }

    fun canDrawOverlays() = Settings.canDrawOverlays(ctx)

    val items: List<Item> = listOf(
        Item("microphone", "Microphone", "hear your key sound and commands", Kind.POPUP, true,
            listOf(Manifest.permission.RECORD_AUDIO), { hasMicrophone() }),
        Item("notifications", "Notifications", "show the 'listening' notice and messages from rules", Kind.POPUP, true,
            if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList(), { hasNotifications() }),
        Item("phone", "Phone", "call, answer and end calls, and know when a call starts or ends", Kind.POPUP, true,
            listOf(Manifest.permission.CALL_PHONE, Manifest.permission.ANSWER_PHONE_CALLS, Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CALL_LOG),
            { hasCallPhone() && hasAnswerCalls() && has(Manifest.permission.READ_PHONE_STATE) }),
        Item("contacts", "Contacts", "turn names like 'mom' into phone numbers", Kind.POPUP, true,
            listOf(Manifest.permission.READ_CONTACTS), { hasContacts() }),
        Item("sms", "SMS", "send text messages", Kind.POPUP, false,
            listOf(Manifest.permission.SEND_SMS), { hasSms() }),
        Item("accessibility", "Accessibility service", "tap, scroll, swipe, Back/Home and lock the screen", Kind.SETTINGS_PAGE, true,
            granted = { accessibilityEnabled() }, settingsIntent = { Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS) }),
        Item("alarms", "Exact alarms", "run time rules on the exact minute, even while the phone sleeps", Kind.SETTINGS_PAGE, true,
            granted = { canExactAlarms() },
            settingsIntent = {
                if (Build.VERSION.SDK_INT >= 31) Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}"))
                else Intent(Settings.ACTION_SETTINGS)
            }),
        Item("battery", "Unrestricted battery", "keep the key listener alive in the background", Kind.SETTINGS_PAGE, false,
            granted = { (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(ctx.packageName) },
            settingsIntent = { Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")) }),
        Item("overlay", "Display over other apps", "let rules open apps while the screen is off or another app is on top", Kind.SETTINGS_PAGE, false,
            granted = { canDrawOverlays() },
            settingsIntent = { Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")) }),
        Item("dnd", "Do Not Disturb access", "needed only for 'ringer silent'", Kind.SETTINGS_PAGE, false,
            granted = { (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).isNotificationPolicyAccessGranted },
            settingsIntent = { Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS) }),
    )

    fun report(): String = items.joinToString("\n") { i ->
        val mark = if (i.granted()) "[x]" else "[ ]"
        val need = if (i.required) "" else " (optional)"
        "$mark ${i.title.padEnd(24)} ${i.why}$need"
    }

    fun missingRequired(): List<Item> = items.filter { it.required && !it.granted() }

    fun find(id: String): Item? {
        val key = id.lowercase()
        return items.firstOrNull { it.id == key } ?: items.firstOrNull { it.id.startsWith(key) }
    }

    /** All pop-up permissions still missing (asked for together in one dialog). */
    fun missingPopupPerms(): List<String> =
        items.filter { it.kind == Kind.POPUP && !it.granted() }.flatMap { it.perms }.filter { !has(it) }.distinct()
}
