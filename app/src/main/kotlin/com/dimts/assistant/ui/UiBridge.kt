package com.dimts.assistant.ui

import android.content.Intent

/** What the background parts may ask of the screen, when it is open. */
interface UiBridge {
    fun clearConsole()
    fun closeConsole()
    fun requestPopupPermissions(perms: List<String>)
    fun openSettingsPage(intent: Intent)
}
