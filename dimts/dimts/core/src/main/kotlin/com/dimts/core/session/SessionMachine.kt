package com.dimts.core.session

import com.dimts.core.script.Level
import com.dimts.core.voice.Plan
import com.dimts.core.voice.VoicePlanner

enum class KeyRole { WAKE, CONFIRM, DISMISS }

enum class SessionState {
    /** Nothing runs at all. */
    OFF,

    /** Only the key-sound listener runs. No model is in RAM. */
    LISTENING,

    /** The command writer is being loaded into RAM. */
    WRITER_LOADING,

    /** The writer is in RAM and transcribing one command after another. */
    WRITER_ACTIVE,

    /** A risky command is waiting for yes/no. */
    AWAITING_CONFIRM,
}

data class SessionConfig(
    /** Unload the writer after this long without a command (the "1 minute" in the plan). */
    val idleTimeoutMs: Long = 60_000,
    val confirmTimeoutMs: Long = 15_000,
    val loadTimeoutMs: Long = 15_000,
    /**
     * When true, confirmations are answered with the recorded confirm/dismiss key sounds. When
     * false they are answered by speech ("yes" / "no"). The app sets this from the recorded keys.
     */
    val confirmByKeys: Boolean = false,
)

sealed interface SessionInput {
    data object StartListening : SessionInput
    data object StopListening : SessionInput
    data object StartAssistant : SessionInput
    data object StopAssistant : SessionInput
    data object WriterReady : SessionInput
    data class KeyHeard(val role: KeyRole) : SessionInput
    data class Transcript(val text: String) : SessionInput

    /** The recogniser ended without hearing anything. */
    data object NothingHeard : SessionInput

    /** Time passed; lets the machine check its deadline. */
    data object Tick : SessionInput
}

enum class BeepKind { WAKE, OK, ERROR, ASK }

/** Things the app must do. The machine itself has no side effects. */
sealed interface Effect {
    data class StartKeys(val roles: Set<KeyRole>) : Effect
    data object StopKeys : Effect
    data object LoadWriter : Effect
    data object UnloadWriter : Effect
    data object ListenForSpeech : Effect
    data object StopSpeech : Effect
    data class Speak(val text: String) : Effect
    data class Beep(val kind: BeepKind) : Effect

    /** Run a console command line with Origin.VOICE. */
    data class Run(val line: String) : Effect
    data class Emit(val event: String) : Effect
    data class Log(val level: Level, val text: String) : Effect
}

/**
 * The four steps of the plan as a pure state machine:
 *
 *  1. Wake on sound   - only the key listener runs (LISTENING)
 *  2. Command writer  - key heard -> load the writer, transcribe commands (WRITER_ACTIVE)
 *  3. Executor        - each command is run; risky ones wait for confirmation (AWAITING_CONFIRM)
 *  4. Reboot          - dismiss, "stop", or 1 minute of silence -> unload the writer, back to LISTENING
 *
 * Time is passed in, so every timeout is testable without waiting.
 */
class SessionMachine(
    private val planner: VoicePlanner,
    private val isRisky: (String) -> Boolean,
    var config: SessionConfig = SessionConfig(),
) {
    var state: SessionState = SessionState.OFF
        private set

    private var listening = false
    private var deadline: Long? = null
    private var pending: Plan.Run? = null

    /** When the host should send [SessionInput.Tick] next (null = no timer needed). */
    fun nextDeadline(): Long? = deadline

    val isListening: Boolean get() = listening

    fun handle(input: SessionInput, now: Long): List<Effect> {
        val out = ArrayList<Effect>()
        when (input) {
            SessionInput.StartListening -> {
                listening = true
                if (state == SessionState.OFF) {
                    state = SessionState.LISTENING
                    out.add(Effect.StartKeys(setOf(KeyRole.WAKE)))
                }
            }
            SessionInput.StopListening -> {
                listening = false
                if (state == SessionState.LISTENING) {
                    state = SessionState.OFF
                    out.add(Effect.StopKeys)
                }
            }
            SessionInput.StartAssistant -> if (state == SessionState.OFF || state == SessionState.LISTENING) beginLoad(now, false, out)
            SessionInput.StopAssistant -> if (isAssistantState()) exit(out)
            is SessionInput.KeyHeard -> onKey(input.role, now, out)
            SessionInput.WriterReady -> if (state == SessionState.WRITER_LOADING) {
                state = SessionState.WRITER_ACTIVE
                deadline = now + config.idleTimeoutMs
                out.add(Effect.Emit("assistant.started"))
                out.add(Effect.ListenForSpeech)
            }
            is SessionInput.Transcript -> onTranscript(input.text, now, out)
            SessionInput.NothingHeard -> when (state) {
                SessionState.WRITER_ACTIVE -> out.add(Effect.ListenForSpeech)
                SessionState.AWAITING_CONFIRM -> if (!config.confirmByKeys) out.add(Effect.ListenForSpeech)
                else -> {}
            }
            SessionInput.Tick -> onTick(now, out)
        }
        return out
    }

    private fun isAssistantState() = state == SessionState.WRITER_LOADING ||
        state == SessionState.WRITER_ACTIVE || state == SessionState.AWAITING_CONFIRM

    private fun beginLoad(now: Long, byKey: Boolean, out: MutableList<Effect>) {
        if (state == SessionState.LISTENING) out.add(Effect.StopKeys)
        if (byKey) out.add(Effect.Emit("key.heard"))
        out.add(Effect.Beep(BeepKind.WAKE))
        out.add(Effect.LoadWriter)
        state = SessionState.WRITER_LOADING
        deadline = now + config.loadTimeoutMs
    }

    private fun onKey(role: KeyRole, now: Long, out: MutableList<Effect>) {
        when {
            role == KeyRole.WAKE && state == SessionState.LISTENING -> beginLoad(now, true, out)
            state == SessionState.AWAITING_CONFIRM && role == KeyRole.CONFIRM -> confirm(now, out)
            state == SessionState.AWAITING_CONFIRM && role == KeyRole.DISMISS -> {
                pending = null
                out.add(Effect.Log(Level.INFO, "dismissed"))
                exit(out)
            }
        }
    }

    private fun confirm(now: Long, out: MutableList<Effect>) {
        val run = pending ?: return
        pending = null
        if (config.confirmByKeys) out.add(Effect.StopKeys)
        out.add(Effect.Beep(BeepKind.OK))
        out.add(Effect.Run(run.line))
        backToActive(now, out)
    }

    private fun backToActive(now: Long, out: MutableList<Effect>) {
        state = SessionState.WRITER_ACTIVE
        deadline = now + config.idleTimeoutMs
        out.add(Effect.ListenForSpeech)
    }

    private fun cancelPending(now: Long, out: MutableList<Effect>) {
        pending = null
        if (config.confirmByKeys) out.add(Effect.StopKeys)
        out.add(Effect.Speak("Cancelled"))
        backToActive(now, out)
    }

    private fun onTranscript(text: String, now: Long, out: MutableList<Effect>) {
        if (state != SessionState.WRITER_ACTIVE && state != SessionState.AWAITING_CONFIRM) return
        val plan = planner.plan(text)
        if (state == SessionState.AWAITING_CONFIRM) {
            when (plan) {
                Plan.Yes -> confirm(now, out)
                Plan.No, Plan.Dismiss -> cancelPending(now, out)
                else -> {
                    out.add(Effect.Speak("Please say yes or no"))
                    out.add(Effect.ListenForSpeech)
                }
            }
            return
        }

        deadline = now + config.idleTimeoutMs // any recognised speech counts as activity
        when (plan) {
            Plan.Dismiss -> exit(out)
            is Plan.Run -> {
                if (isRisky(plan.line)) {
                    pending = plan
                    state = SessionState.AWAITING_CONFIRM
                    deadline = now + config.confirmTimeoutMs
                    out.add(Effect.Beep(BeepKind.ASK))
                    if (config.confirmByKeys) {
                        out.add(Effect.StopSpeech)
                        out.add(Effect.Speak("${plan.say}? Confirm sound to go ahead"))
                        out.add(Effect.StartKeys(setOf(KeyRole.CONFIRM, KeyRole.DISMISS)))
                    } else {
                        out.add(Effect.Speak("${plan.say}? Say yes or no"))
                        out.add(Effect.ListenForSpeech)
                    }
                } else {
                    out.add(Effect.Beep(BeepKind.OK))
                    out.add(Effect.Run(plan.line))
                    out.add(Effect.ListenForSpeech)
                }
            }
            Plan.Yes, Plan.No -> {
                out.add(Effect.Log(Level.INFO, "nothing to confirm"))
                out.add(Effect.ListenForSpeech)
            }
            is Plan.Unknown -> {
                out.add(Effect.Beep(BeepKind.ERROR))
                out.add(Effect.Log(Level.WARN, "did not understand: \"${plan.heard}\""))
                out.add(Effect.Speak("Sorry, I did not understand"))
                out.add(Effect.ListenForSpeech)
            }
        }
    }

    private fun onTick(now: Long, out: MutableList<Effect>) {
        val d = deadline ?: return
        if (now < d) return
        when (state) {
            SessionState.WRITER_LOADING -> {
                out.add(Effect.Log(Level.ERROR, "the command writer did not load in time"))
                exit(out)
            }
            SessionState.WRITER_ACTIVE -> {
                out.add(Effect.Log(Level.INFO, "no command for ${config.idleTimeoutMs / 1000} s - unloading the writer"))
                exit(out)
            }
            SessionState.AWAITING_CONFIRM -> cancelPending(now, out)
            else -> deadline = null
        }
    }

    /** Step 4: unload everything and go back to the single always-on listener (or fully off). */
    private fun exit(out: MutableList<Effect>) {
        pending = null
        deadline = null
        out.add(Effect.StopSpeech)
        out.add(Effect.StopKeys)
        out.add(Effect.UnloadWriter)
        out.add(Effect.Emit("assistant.stopped"))
        if (listening) {
            state = SessionState.LISTENING
            out.add(Effect.StartKeys(setOf(KeyRole.WAKE)))
        } else {
            state = SessionState.OFF
        }
    }
}
