package com.dimts.assistant.ui

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import com.dimts.assistant.R
import com.dimts.assistant.runtime
import com.dimts.assistant.util.LogLine
import com.dimts.core.script.Level
import com.dimts.core.script.Repl

/** The black terminal. Everything typed here goes through the same Shell that voice commands and rules use. */
class MainActivity : Activity(), UiBridge {

    private val rt by lazy { runtime }
    private lateinit var scroll: ScrollView
    private lateinit var output: TextView
    private lateinit var prompt: TextView
    private lateinit var input: EditText
    private val text = SpannableStringBuilder()

    private val repl by lazy { Repl { rt.submit(it) } }
    private val history = ArrayList<String>()
    private var historyPos = 0
    private var lastLine = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        scroll = findViewById(R.id.scroll)
        output = findViewById(R.id.output)
        prompt = findViewById(R.id.prompt)
        input = findViewById(R.id.input)

        input.setOnEditorActionListener { _, actionId, event ->
            val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE || enter) {
                submitLine(); true
            } else false
        }
        findViewById<Button>(R.id.btn_tab).setOnClickListener { complete() }
        findViewById<Button>(R.id.btn_up).setOnClickListener { browse(-1) }
        findViewById<Button>(R.id.btn_down).setOnClickListener { browse(+1) }
        findViewById<Button>(R.id.btn_esc).setOnClickListener {
            if (repl.inBlock) { repl.abort(); rt.log(Level.WARN, "block cancelled") }
            input.setText(""); refreshPrompt()
        }
        findViewById<Button>(R.id.btn_mic).setOnClickListener { runLine("start voice_assistant on android") }

        // Replay history, then follow new lines.
        rt.logBus.listener = { line -> append(line) }
        for (l in rt.logBus.snapshot()) append(l)
        if (rt.logBus.snapshot().isEmpty()) {
            rt.log(Level.OK, "DIMTS console. Type  help  for all commands,  permissions  to see what is allowed.")
            if (!rt.settings.firstRunDone) {
                rt.settings.firstRunDone = true
                rt.log(Level.INFO, "First time: type  grant all  and follow Android's questions.")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        rt.ui = this
        rt.uiVisible = true
        rt.logBus.listener = { line -> append(line) }
        refreshPrompt()
    }

    override fun onPause() {
        rt.uiVisible = false
        if (rt.ui === this) rt.ui = null
        rt.logBus.listener = null
        super.onPause()
    }

    // ---- input ------------------------------------------------------------------------------

    private fun submitLine() {
        val line = input.text.toString()
        input.setText("")
        runLine(line)
    }

    private fun runLine(line: String) {
        rt.log(Level.INPUT, prompt.text.toString() + line)
        if (line.isNotBlank()) {
            if (history.lastOrNull() != line) history.add(line)
            lastLine = line
        }
        historyPos = history.size
        repl.line(line)
        refreshPrompt()
        // Keep the indentation going inside a block, like a Python console.
        if (repl.inBlock && line.isNotBlank()) {
            val indent = line.takeWhile { it == ' ' || it == '\t' } +
                if (line.trimEnd().endsWith(":") || Regex("#.*$").replace(line, "").trimEnd().endsWith(":")) "    " else ""
            input.setText(indent)
            input.setSelection(indent.length)
        }
    }

    private fun refreshPrompt() {
        prompt.text = repl.prompt
    }

    private fun browse(delta: Int) {
        if (history.isEmpty()) return
        historyPos = (historyPos + delta).coerceIn(0, history.size)
        val t = if (historyPos == history.size) "" else history[historyPos]
        input.setText(t)
        input.setSelection(t.length)
    }

    private fun complete() {
        val cur = input.text.toString()
        if (cur.isBlank()) return
        val names = rt.shell.registry.allNames().filter { it.startsWith(cur.trimStart()) }.distinct()
        when {
            names.isEmpty() -> return
            names.size == 1 -> setInput(names[0] + " ")
            else -> {
                var p = names[0]
                for (n in names) p = p.commonPrefixWith(n)
                if (p.length > cur.trimStart().length) setInput(p)
                rt.log(Level.INFO, names.take(12).joinToString("   "))
            }
        }
    }

    private fun setInput(t: String) {
        input.setText(t)
        input.setSelection(t.length)
    }

    // ---- output -----------------------------------------------------------------------------

    private fun colorOf(level: Level): Int = when (level) {
        Level.INPUT -> Color.parseColor("#00E676")
        Level.INFO -> Color.parseColor("#9E9E9E")
        Level.OK -> Color.parseColor("#69F0AE")
        Level.WARN -> Color.parseColor("#FFD54F")
        Level.ERROR -> Color.parseColor("#FF5252")
        Level.OUT -> Color.parseColor("#E0E0E0")
    }

    private fun append(line: LogLine) {
        val start = text.length
        text.append(line.text).append('\n')
        text.setSpan(ForegroundColorSpan(colorOf(line.level)), start, text.length - 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (text.length > 120_000) text.delete(0, text.length - 100_000)
        output.text = text
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    // ---- UiBridge ---------------------------------------------------------------------------

    override fun clearConsole() {
        rt.logBus.clear()
        text.clear()
        output.text = text
    }

    override fun closeConsole() {
        finish()
    }

    override fun requestPopupPermissions(perms: List<String>) {
        requestPermissions(perms.toTypedArray(), REQ_PERMS)
    }

    override fun openSettingsPage(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: Exception) {
            rt.log(Level.ERROR, "could not open that settings page: ${e.javaClass.simpleName}")
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMS) return
        var blocked = false
        for (i in permissions.indices) {
            val ok = grantResults.getOrNull(i) == PackageManager.PERMISSION_GRANTED
            val name = permissions[i].substringAfterLast('.').lowercase()
            rt.log(if (ok) Level.OK else Level.WARN, (if (ok) "granted: " else "denied: ") + name)
            if (!ok && !shouldShowRequestPermissionRationale(permissions[i])) blocked = true
        }
        if (blocked) {
            rt.log(Level.WARN, "Android will not ask again for some of these. Opening the app's settings page - switch them on there.")
            openSettingsPage(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
            )
        } else if (rt.perms.missingPopupPerms().isNotEmpty() || rt.perms.missingRequired().isNotEmpty()) {
            rt.log(Level.INFO, "Type  grant all  again for the next missing item, or  permissions  to see the list.")
        } else {
            rt.log(Level.OK, "All required permissions are granted.")
        }
    }

    private companion object {
        const val REQ_PERMS = 42
    }
}
