package com.dimts.assistant

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.dimts.assistant.audio.Beeper
import com.dimts.assistant.audio.KeyEnroller
import com.dimts.assistant.audio.KeyListener
import com.dimts.assistant.exec.PhoneEnvironment
import com.dimts.assistant.exec.PhoneHost
import com.dimts.assistant.service.AlarmReceiver
import com.dimts.assistant.service.ListenService
import com.dimts.assistant.speech.Speaker
import com.dimts.assistant.speech.Writer
import com.dimts.assistant.ui.UiBridge
import com.dimts.assistant.util.AndroidClock
import com.dimts.assistant.util.KeyStore
import com.dimts.assistant.util.LogBus
import com.dimts.assistant.util.Permissions
import com.dimts.assistant.util.Settings
import com.dimts.core.audio.SegmentReport
import com.dimts.core.command.Shell
import com.dimts.core.script.CommandResult
import com.dimts.core.script.Level
import com.dimts.core.script.LogSink
import com.dimts.core.script.Origin
import com.dimts.core.session.Effect
import com.dimts.core.session.KeyRole
import com.dimts.core.session.SessionConfig
import com.dimts.core.session.SessionInput
import com.dimts.core.session.SessionMachine
import com.dimts.core.session.SessionState
import com.dimts.core.voice.VoicePlanner
import java.io.File

/**
 * The one object that wires everything together. It lives as long as the process.
 * All state changes happen on the main thread (audio and speech callbacks hop onto it).
 *
 *   KeyListener (always on) --key heard--> SessionMachine --effects--> Writer / Speaker / Shell
 *   AlarmManager / events   -------------> Shell.engine (rules)
 */
class AppRuntime(val app: Application) {
    val handler = Handler(Looper.getMainLooper())
    val logBus = LogBus()
    val settings = Settings(app)
    val keyStore = KeyStore(app)
    val perms = Permissions(app)
    val speaker = Speaker(app, { settings.language }, handler)
    val beeper = Beeper()
    private val writer = Writer(app)
    val env = PhoneEnvironment(this)
    private val host = PhoneHost(this)

    var ui: UiBridge? = null
    @Volatile var uiVisible = false

    val shell = Shell(
        AndroidClock(), env, host,
        LogSink { level, text -> onLog(level, text) },
        versionName = BuildConfig.VERSION_NAME,
    )

    val machine = SessionMachine(
        VoicePlanner(shell.registry),
        { line -> shell.isRisky(line) },
        SessionConfig(idleTimeoutMs = settings.idleSeconds * 1000L),
    )

    // ---- logging ------------------------------------------------------------------------------

    private var voiceRun = false

    private fun onLog(level: Level, text: String) {
        logBus.add(level, text)
        if (voiceRun && level == Level.ERROR) speaker.speak(text.take(120))
    }

    fun log(level: Level, text: String) = onLog(level, text)

    // ---- start-up -----------------------------------------------------------------------------

    private val stateFile = File(app.filesDir, "state.txt")

    fun start() {
        try {
            if (stateFile.exists()) shell.load(stateFile.readText())
        } catch (e: Exception) {
            log(Level.WARN, "could not read saved rules: ${e.message}")
        }
        // Registered only after loading, so loading does not rewrite the file half-way.
        shell.engine.setOnChange { persistAndRearm() }
        registerSystemReceivers()
        refreshConfig()
        speaker.onIdle = { onSpeechIdle() }
        shell.engine.tick() // catch up on anything that came due while the process was not running
        rearm()
        if (settings.listeningWanted && perms.hasMicrophone()) {
            val r = startListening(null)
            if (!r.ok) log(Level.WARN, "could not resume listening: ${r.message}")
        }
    }

    private fun persistAndRearm() {
        try {
            stateFile.writeText(shell.save())
        } catch (e: Exception) {
            log(Level.WARN, "could not save rules: ${e.message}")
        }
        rearm()
    }

    // ---- console input ------------------------------------------------------------------------

    fun submit(text: String) {
        shell.submit(text, Origin.CONSOLE)
    }

    // ---- time rules: AlarmManager + a short in-process timer ------------------------------------

    private val engineTick = Runnable { shell.engine.tick(); rearm() }
    private val pollTick = Runnable { shell.engine.tick(); rearm() }

    fun onAlarm() {
        shell.engine.tick()
        rearm()
    }

    fun rearm() {
        val next = shell.engine.nextWakeMillis()
        val am = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(
            app, 1, Intent(app, AlarmReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        handler.removeCallbacks(engineTick)
        handler.removeCallbacks(pollTick)
        if (shell.engine.needsPolling()) handler.postDelayed(pollTick, POLL_MS)
        if (next == null) {
            am.cancel(pi)
            return
        }
        try {
            if (perms.canExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
        }
        val delay = next - System.currentTimeMillis()
        if (delay < 10 * 60_000L) handler.postDelayed(engineTick, maxOf(delay + 100, 500L))
    }

    // ---- system events (screen, power, battery) -------------------------------------------------

    private val systemReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> shell.engine.emit("screen.on")
                Intent.ACTION_SCREEN_OFF -> shell.engine.emit("screen.off")
                Intent.ACTION_POWER_CONNECTED -> { env.charging = true; shell.engine.emit("power.connected") }
                Intent.ACTION_POWER_DISCONNECTED -> { env.charging = false; shell.engine.emit("power.disconnected") }
                Intent.ACTION_BATTERY_CHANGED -> { env.updateBattery(intent); shell.engine.tick() }
                Intent.ACTION_USER_PRESENT -> shell.engine.emit("screen.unlocked")
                Intent.ACTION_BATTERY_LOW -> shell.engine.emit("battery.low")
                Intent.ACTION_BATTERY_OKAY -> shell.engine.emit("battery.okay")
            }
        }
    }

    private fun registerSystemReceivers() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_BATTERY_LOW)
            addAction(Intent.ACTION_BATTERY_OKAY)
        }
        val sticky = ContextCompat.registerReceiver(app, systemReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        env.updateBattery(sticky)
    }

    fun onBoot() {
        shell.engine.emit("boot")
        onAlarm()
    }

    // ---- calls ----------------------------------------------------------------------------------

    private var callPrev = "idle"
    private var lastDialName = ""
    private var lastDialNumber = ""
    private var lastDialAt = 0L
    private var incomingAnnounced = false
    private val announceIncoming = Runnable { announceIncomingNow() }

    fun noteDial(name: String, number: String) {
        lastDialName = name
        lastDialNumber = number
        lastDialAt = SystemClock.elapsedRealtime()
    }

    private fun announceIncomingNow() {
        if (incomingAnnounced) return
        incomingAnnounced = true
        handler.removeCallbacks(announceIncoming)
        shell.engine.emit("call.incoming")
    }

    /** From the PHONE_STATE broadcast. Android cannot tell when the OTHER side answers an outgoing call. */
    fun onPhoneState(state: String?, number: String?) {
        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                if (!number.isNullOrBlank()) {
                    env.callNumber = number
                    env.callContact = host.nameForNumber(number) ?: number
                }
                if (callPrev != "ringing") {
                    callPrev = "ringing"
                    env.callState = "ringing"
                    incomingAnnounced = false
                    if (number.isNullOrBlank()) handler.postDelayed(announceIncoming, 600) else announceIncomingNow()
                } else if (!number.isNullOrBlank()) {
                    announceIncomingNow()
                }
            }
            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                if (callPrev == "ringing") {
                    announceIncomingNow()
                    callPrev = "offhook"
                    env.callState = "offhook"
                    shell.engine.emit("call.answered")
                } else if (callPrev == "idle") {
                    callPrev = "offhook"
                    env.callState = "offhook"
                    val recent = SystemClock.elapsedRealtime() - lastDialAt < 60_000
                    env.callContact = if (recent) lastDialName else ""
                    env.callNumber = if (recent) lastDialNumber else ""
                    shell.engine.emit("call.started")
                }
            }
            TelephonyManager.EXTRA_STATE_IDLE -> {
                if (callPrev != "idle") {
                    handler.removeCallbacks(announceIncoming)
                    val missed = callPrev == "ringing"
                    callPrev = "idle"
                    env.callState = "idle"
                    if (missed) shell.engine.emit("call.missed")
                    shell.engine.emit("call.ended")
                    env.callContact = ""
                    env.callNumber = ""
                }
            }
        }
    }

    // ---- the four steps: session machine ----------------------------------------------------------

    private val machineTick = Runnable { dispatch(SessionInput.Tick) }
    private var serviceRequested = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var pendingListen = false
    private var recognizerFailures = 0

    fun dispatch(input: SessionInput) {
        val effects = machine.handle(input, SystemClock.elapsedRealtime())
        effects.forEach(::apply)
        handler.removeCallbacks(machineTick)
        machine.nextDeadline()?.let {
            handler.postDelayed(machineTick, maxOf(0L, it - SystemClock.elapsedRealtime()))
        }
        updateWakeLock()
        if (machine.state == SessionState.OFF) syncService()
    }

    private fun apply(e: Effect) {
        when (e) {
            is Effect.StartKeys -> applyKeys(e.roles)
            Effect.StopKeys -> keyListener.stop()
            Effect.LoadWriter -> {
                if (!writer.load()) {
                    log(Level.ERROR, "speech recognition is not available on this phone")
                    handler.post { dispatch(SessionInput.StopAssistant) }
                } else {
                    recognizerFailures = 0
                    handler.post { dispatch(SessionInput.WriterReady) }
                }
            }
            Effect.UnloadWriter -> {
                pendingListen = false
                writer.cancel()
                writer.destroy() // step 4: out of RAM
            }
            Effect.ListenForSpeech -> if (speaker.isSpeaking) pendingListen = true else listenNow()
            Effect.StopSpeech -> {
                pendingListen = false
                writer.cancel()
                speaker.stop()
            }
            is Effect.Speak -> speaker.speak(e.text)
            is Effect.Beep -> beeper.play(e.kind)
            is Effect.Run -> runVoice(e.line)
            is Effect.Emit -> shell.engine.emit(e.event)
            is Effect.Log -> log(e.level, e.text)
        }
    }

    private fun runVoice(line: String) {
        log(Level.INPUT, "voice> $line")
        voiceRun = true
        try {
            shell.submit(line, Origin.VOICE)
        } finally {
            voiceRun = false
        }
    }

    private fun onSpeechIdle() {
        if (pendingListen) {
            pendingListen = false
            if (machine.state == SessionState.WRITER_ACTIVE || machine.state == SessionState.AWAITING_CONFIRM) listenNow()
        }
    }

    private fun listenNow() {
        writer.listen(
            com.dimts.assistant.speech.Writer.tagFor(settings.language),
            onText = { text ->
                recognizerFailures = 0
                log(Level.INFO, "heard: \"$text\"")
                dispatch(SessionInput.Transcript(text))
            },
            onNothing = { handler.postDelayed({ dispatch(SessionInput.NothingHeard) }, 200) },
            onFail = { msg ->
                recognizerFailures++
                log(Level.ERROR, msg)
                if (recognizerFailures >= 3) dispatch(SessionInput.StopAssistant)
                else handler.postDelayed({ dispatch(SessionInput.NothingHeard) }, 500)
            },
        )
    }

    private fun updateWakeLock() {
        val active = machine.state == SessionState.WRITER_LOADING ||
            machine.state == SessionState.WRITER_ACTIVE || machine.state == SessionState.AWAITING_CONFIRM
        if (active) {
            val wl = wakeLock ?: (app.getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dimts:assistant").also { wakeLock = it }
            wl.acquire(120_000) // always times out by itself, so it can never leak
        } else if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
    }

    private fun syncService(): Boolean {
        val need = machine.state != SessionState.OFF
        if (need == serviceRequested) return true
        val intent = Intent(app, ListenService::class.java)
        return try {
            if (need) ContextCompat.startForegroundService(app, intent) else app.stopService(intent)
            serviceRequested = need
            true
        } catch (e: Exception) {
            log(Level.WARN, "could not start the background listener (${e.javaClass.simpleName}): open the app, then try again")
            false
        }
    }

    fun onServiceDestroyed() {
        serviceRequested = false
    }

    // ---- key sounds ---------------------------------------------------------------------------------

    private var keyRoles: Set<KeyRole> = emptySet()
    private var testing = false
    private val enroller = KeyEnroller(beeper, { block -> handler.post(block) }, { level, text -> log(level, text) })

    private val keyListener = KeyListener(
        onDetect = { d -> handler.post { onKeyDetected(d.keyName, d.distance) } },
        onReport = { r -> handler.post { onSegment(r) } },
        onError = { m -> handler.post { onKeyError(m) } },
    )

    private fun roleOf(name: String): KeyRole? = KeyRole.entries.firstOrNull { settings.role(it) == name }

    private fun onKeyDetected(name: String, distance: Double) {
        if (testing) {
            log(Level.OK, "MATCH '$name' (distance %.2f)".format(distance))
            return
        }
        val role = roleOf(name) ?: return
        log(Level.INFO, "key '$name' heard (${role.name.lowercase()})")
        dispatch(SessionInput.KeyHeard(role))
    }

    private fun onSegment(r: SegmentReport) {
        if (!testing) return
        val best = r.bestKey?.let { "closest '$it' %.2f / limit %.2f".format(r.distance, r.threshold) } ?: "different length from every key"
        log(Level.INFO, "sound (${r.frames} frames): $best" + if (r.matched) "  -> match" else "")
    }

    private fun onKeyError(message: String) {
        log(Level.ERROR, message)
        keyListener.stop()
        settings.listeningWanted = false
        dispatch(SessionInput.StopAssistant)
        dispatch(SessionInput.StopListening)
    }

    private fun applyKeys(roles: Set<KeyRole>) {
        val keys = roles.mapNotNull { r -> settings.role(r)?.let { keyStore.load(it) } }.distinctBy { it.name }
        if (keys.isEmpty()) {
            log(Level.ERROR, "no usable key recorded for ${roles.joinToString { it.name.lowercase() }} - type: record key <name>")
            return
        }
        keyRoles = roles
        keyListener.start(keys, settings.sensitivity)
    }

    private fun restartKeysIfRunning() {
        if (keyListener.isRunning && !testing && keyRoles.isNotEmpty()) applyKeys(keyRoles)
    }

    fun refreshConfig() {
        val confirmKeys = settings.role(KeyRole.CONFIRM)?.let { keyStore.exists(it) } == true &&
            settings.role(KeyRole.DISMISS)?.let { keyStore.exists(it) } == true
        machine.config = machine.config.copy(confirmByKeys = confirmKeys, idleTimeoutMs = settings.idleSeconds * 1000L)
    }

    // ---- commands implemented here (called by PhoneHost) ------------------------------------------------

    fun startListening(name: String?): CommandResult {
        if (!perms.hasMicrophone()) return CommandResult.error("microphone permission is missing - type: grant microphone")
        if (enroller.running) return CommandResult.error("a key is being recorded right now")
        if (name != null) {
            if (!keyStore.exists(name)) return CommandResult.error("no key named '$name' - record it first:  record key $name")
            settings.setRole(KeyRole.WAKE, name)
            refreshConfig()
        }
        val wake = settings.role(KeyRole.WAKE)
        if (wake == null || !keyStore.exists(wake)) {
            return CommandResult.error("no wake key yet - record one:  record key wake_word")
        }
        if (machine.state != SessionState.OFF && machine.isListening) return CommandResult.ok("already listening for '$wake'")
        // Start the foreground service first (Android needs it for background microphone use).
        if (machine.state == SessionState.OFF) {
            val before = serviceRequested
            serviceRequested = false
            val wasOff = true
            if (wasOff && !startServiceNow()) {
                serviceRequested = before
                return CommandResult.error("Android would not start the background listener - open this screen and try again")
            }
        }
        settings.listeningWanted = true
        dispatch(SessionInput.StartListening)
        return CommandResult.ok("listening for '$wake' - only the key listener is running; no model is loaded")
    }

    private fun startServiceNow(): Boolean {
        return try {
            ContextCompat.startForegroundService(app, Intent(app, ListenService::class.java))
            serviceRequested = true
            true
        } catch (e: Exception) {
            false
        }
    }

    fun stopListening(): CommandResult {
        settings.listeningWanted = false
        if (!machine.isListening && machine.state == SessionState.OFF) return CommandResult.ok("not listening")
        dispatch(SessionInput.StopListening)
        return CommandResult.ok("stopped listening")
    }

    fun startAssistant(): CommandResult {
        if (!perms.hasMicrophone()) return CommandResult.error("microphone permission is missing - type: grant microphone")
        if (machine.state != SessionState.OFF && machine.state != SessionState.LISTENING) return CommandResult.ok("the assistant is already running")
        if (machine.state == SessionState.OFF && !startServiceNow()) {
            return CommandResult.error("Android would not start the background listener - open this screen and try again")
        }
        refreshConfig()
        dispatch(SessionInput.StartAssistant)
        return CommandResult.ok("loading the command writer - say a command after the beep")
    }

    fun stopAssistant(): CommandResult {
        if (machine.state == SessionState.OFF || machine.state == SessionState.LISTENING) return CommandResult.ok("the assistant is not running")
        dispatch(SessionInput.StopAssistant)
        return CommandResult.ok("assistant stopped - writer unloaded")
    }

    fun recordKey(name: String?, roleArg: String?): CommandResult {
        if (name == null) return CommandResult.error("usage: record key <name> [wake|confirm|dismiss]")
        if (!keyStore.validName(name)) return CommandResult.error("a key name may use letters, digits, _ and - (up to 32)")
        val role = when (roleArg?.lowercase()) {
            null, "wake" -> KeyRole.WAKE
            "confirm" -> KeyRole.CONFIRM
            "dismiss" -> KeyRole.DISMISS
            else -> return CommandResult.error("role must be wake, confirm or dismiss")
        }
        if (!perms.hasMicrophone()) return CommandResult.error("microphone permission is missing - type: grant microphone")
        if (enroller.running) return CommandResult.error("already recording")
        if (machine.state != SessionState.OFF && machine.state != SessionState.LISTENING) return CommandResult.error("stop the assistant first:  stop assistant")
        if (testing) return CommandResult.error("a key test is running - wait for it to finish")
        keyListener.stop()
        enroller.start(name) { key ->
            if (key != null) {
                keyStore.save(key)
                settings.setRole(role, name)
                refreshConfig()
                log(Level.OK, "'$name' is now your ${role.name.lowercase()} key")
            }
            if (machine.state == SessionState.LISTENING) applyKeys(setOf(KeyRole.WAKE))
        }
        return CommandResult.ok(null)
    }

    fun useKey(args: List<String>): CommandResult {
        val clean = args.filter { !it.equals("as", true) }
        if (clean.size != 2) return CommandResult.error("usage: use key <name> as <wake|confirm|dismiss>")
        val name = clean[0]
        if (!keyStore.exists(name)) return CommandResult.error("no key named '$name' (see: keys)")
        val role = KeyRole.entries.firstOrNull { it.name.equals(clean[1], true) } ?: return CommandResult.error("role must be wake, confirm or dismiss")
        settings.setRole(role, name)
        refreshConfig()
        if (role == KeyRole.WAKE && machine.state == SessionState.LISTENING) applyKeys(setOf(KeyRole.WAKE))
        return CommandResult.ok("'$name' is now the ${role.name.lowercase()} key")
    }

    fun keyListText(): String {
        val names = keyStore.names()
        if (names.isEmpty()) return "no keys yet - record one:  record key wake_word"
        return names.joinToString("\n") { n ->
            val roles = KeyRole.entries.filter { settings.role(it) == n }.joinToString(", ") { it.name.lowercase() }
            "  $n" + if (roles.isNotEmpty()) "   [$roles]" else "   [unused]"
        }
    }

    fun deleteKey(name: String?): CommandResult {
        if (name == null) return CommandResult.error("usage: delete key <name>")
        if (!keyStore.delete(name)) return CommandResult.error("no key named '$name'")
        KeyRole.entries.filter { settings.role(it) == name }.forEach { settings.setRole(it, null) }
        refreshConfig()
        if (machine.state == SessionState.LISTENING && settings.role(KeyRole.WAKE) == null) {
            settings.listeningWanted = false
            dispatch(SessionInput.StopListening)
        }
        return CommandResult.ok("key '$name' deleted")
    }

    fun testKey(seconds: Int): CommandResult {
        if (!perms.hasMicrophone()) return CommandResult.error("microphone permission is missing - type: grant microphone")
        if (machine.state != SessionState.OFF && machine.state != SessionState.LISTENING) return CommandResult.error("stop the assistant first")
        if (enroller.running || testing) return CommandResult.error("busy")
        val keys = keyStore.names().mapNotNull { keyStore.load(it) }
        if (keys.isEmpty()) return CommandResult.error("no keys yet - record one:  record key wake_word")
        val secs = seconds.coerceIn(5, 120)
        testing = true
        keyListener.start(keys, settings.sensitivity)
        handler.postDelayed({
            testing = false
            keyListener.stop()
            if (machine.state == SessionState.LISTENING) applyKeys(setOf(KeyRole.WAKE))
            log(Level.INFO, "key test finished")
        }, secs * 1000L)
        return CommandResult.ok("testing for $secs s - make your sound; every sound shows how close it was to a key (lower is closer)")
    }

    fun sensitivity(arg: String?): CommandResult {
        if (arg == null) return CommandResult.ok("sensitivity %.2f  (higher = triggers more easily)".format(settings.sensitivity))
        val v = arg.toDoubleOrNull()
        if (v == null || v < 0.3 || v > 3.0) return CommandResult.error("usage: sensitivity <0.3 - 3.0>")
        settings.sensitivity = v
        restartKeysIfRunning()
        return CommandResult.ok("sensitivity %.2f".format(v))
    }

    fun language(arg: String?): CommandResult {
        if (arg == null) return CommandResult.ok("language: ${settings.language}")
        val v = arg.lowercase()
        if (v != "en" && v != "am") return CommandResult.error("usage: language <en|am>")
        settings.language = v
        return CommandResult.ok("language: $v" + if (v == "am") "  (needs an Amharic speech pack on the phone)" else "")
    }

    fun timeout(arg: String?): CommandResult {
        if (arg == null) return CommandResult.ok("timeout: ${settings.idleSeconds} s")
        val n = arg.toIntOrNull()
        if (n == null || n !in 5..600) return CommandResult.error("usage: timeout <5-600 seconds>")
        settings.idleSeconds = n
        refreshConfig()
        return CommandResult.ok("the writer unloads after $n s without a command")
    }

    private val offeredPages = HashSet<String>()

    fun grant(arg: String?): CommandResult {
        val bridge = ui ?: return CommandResult.error("open the Dimts screen to grant permissions")
        val which = arg?.lowercase()
        if (which == null || which == "all") {
            val popups = perms.missingPopupPerms()
            if (popups.isNotEmpty()) {
                bridge.requestPopupPermissions(popups)
                return CommandResult.ok("Android will now ask for: ${popups.joinToString { it.substringAfterLast('.').lowercase() }}")
            }
            val missing = perms.items.filter { it.kind == Permissions.Kind.SETTINGS_PAGE && !it.granted() }
            if (missing.isEmpty()) return CommandResult.ok("everything is granted")
            val next = missing.filter { it.id !in offeredPages }.sortedByDescending { it.required }.firstOrNull()
            if (next == null) {
                offeredPages.clear()
                return CommandResult.ok(
                    "every page was opened once and these are still off: ${missing.joinToString { it.id }}\n" +
                        "switch them on yourself, or open one again with:  grant <name>   (see: permissions)",
                )
            }
            offeredPages.add(next.id)
            bridge.openSettingsPage(next.settingsIntent!!())
            return CommandResult.ok("opening '${next.title}' - switch it on, come back, then type:  grant all")
        }
        val item = perms.find(which) ?: return CommandResult.error("unknown permission '$which' (see: permissions)")
        if (item.granted()) return CommandResult.ok("'${item.title}' is already granted")
        if (item.kind == Permissions.Kind.POPUP) bridge.requestPopupPermissions(item.perms)
        else bridge.openSettingsPage(item.settingsIntent!!())
        return CommandResult.ok("asking for '${item.title}'")
    }

    fun statusText(): String {
        val roles = KeyRole.entries.joinToString("  ") { r -> "${r.name.lowercase()}=${settings.role(r) ?: "-"}" }
        val missing = perms.missingRequired().joinToString { it.id }
        return "assistant: ${machine.state.name.lowercase()}   listening: ${if (machine.isListening) "yes" else "no"}\n" +
            "language: ${settings.language}   sensitivity: %.2f   writer timeout: ${settings.idleSeconds} s\n".format(settings.sensitivity) +
            "keys: $roles\n" +
            "writer in RAM: ${if (machine.state == SessionState.OFF || machine.state == SessionState.LISTENING) "no" else "yes"}\n" +
            "missing permissions: ${missing.ifEmpty { "none" }}"
    }

    private companion object {
        const val POLL_MS = 15_000L
    }
}
