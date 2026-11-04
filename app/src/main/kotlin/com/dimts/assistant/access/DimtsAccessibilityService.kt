package com.dimts.assistant.access

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Hands for the assistant: global buttons, gestures, tapping by text and typing.
 * Android only allows this once the user switches the service on in Settings > Accessibility.
 */
class DimtsAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            event.packageName?.toString()?.let { currentPackage = it }
        }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    fun back() = performGlobalAction(GLOBAL_ACTION_BACK)
    fun home() = performGlobalAction(GLOBAL_ACTION_HOME)
    fun recents() = performGlobalAction(GLOBAL_ACTION_RECENTS)
    fun notifications() = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)

    fun lockScreen(): Boolean =
        Build.VERSION.SDK_INT >= 28 && performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)

    fun screenshot(): Boolean =
        Build.VERSION.SDK_INT >= 28 && performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)

    /** Moves a finger across the screen in [direction] (left/right/up/down). */
    fun swipe(direction: String): Boolean {
        val w = resources.displayMetrics.widthPixels.toFloat()
        val h = resources.displayMetrics.heightPixels.toFloat()
        val (x1, y1, x2, y2) = when (direction) {
            "left" -> listOf(w * 0.8f, h * 0.5f, w * 0.2f, h * 0.5f)
            "right" -> listOf(w * 0.2f, h * 0.5f, w * 0.8f, h * 0.5f)
            "up" -> listOf(w * 0.5f, h * 0.7f, w * 0.5f, h * 0.3f)
            "down" -> listOf(w * 0.5f, h * 0.3f, w * 0.5f, h * 0.7f)
            else -> return false
        }
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        return gesture(path, 300)
    }

    /** Scrolling down means the content moves up, so the finger moves up. */
    fun scroll(direction: String): Boolean = swipe(if (direction == "down") "up" else "down")

    fun tap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        return gesture(path, 60)
    }

    private fun gesture(path: Path, durationMs: Long): Boolean {
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    /** Taps the first on-screen element whose text matches. Returns false if none is found. */
    fun tapText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        try {
            val matches = root.findAccessibilityNodeInfosByText(text)
            val node = matches.firstOrNull { it.isVisibleToUser } ?: matches.firstOrNull() ?: return false
            var target: AccessibilityNodeInfo? = node
            while (target != null && !target.isClickable) target = target.parent
            if (target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            val r = Rect()
            node.getBoundsInScreen(r)
            return tap(r.exactCenterX(), r.exactCenterY())
        } finally {
            root.recycle()
        }
    }

    /** Types into the focused text field (appends to what is there). */
    fun typeText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        try {
            val field = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
            val current = field.text?.toString().orEmpty()
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, current + text)
            }
            return field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } finally {
            root.recycle()
        }
    }

    /** Visible text on screen, top to bottom, at most [limit] pieces. */
    fun readScreen(limit: Int = 60): List<String> {
        val root = rootInActiveWindow ?: return emptyList()
        val out = ArrayList<String>()
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null || out.size >= limit) return
            if (n.isVisibleToUser) {
                val t = n.text?.toString()?.trim().orEmpty().ifEmpty { n.contentDescription?.toString()?.trim().orEmpty() }
                if (t.isNotEmpty() && (out.isEmpty() || out.last() != t)) out.add(t)
            }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        try {
            walk(root)
        } finally {
            root.recycle()
        }
        return out
    }

    companion object {
        @Volatile var instance: DimtsAccessibilityService? = null
            private set

        @Volatile var currentPackage: String = ""
            private set
    }
}
