package com.dimts.core.session

import com.dimts.core.command.Commands
import com.dimts.core.script.Level
import com.dimts.core.voice.VoicePlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionMachineTest {
    private val planner = VoicePlanner(Commands.registry)
    private fun machine(cfg: SessionConfig = SessionConfig()) =
        SessionMachine(planner, { it.startsWith("call") || it.startsWith("sms") }, cfg)

    private fun SessionMachine.go(input: SessionInput, now: Long = 0): List<Effect> = handle(input, now)

    private fun List<Effect>.runs(): List<String> = filterIsInstance<Effect.Run>().map { it.line }

    @Test
    fun startsOffAndOnlyTheWakeKeyRunsWhenListening() {
        val m = machine()
        assertEquals(SessionState.OFF, m.state)
        val fx = m.go(SessionInput.StartListening)
        assertEquals(SessionState.LISTENING, m.state)
        assertEquals(listOf<Effect>(Effect.StartKeys(setOf(KeyRole.WAKE))), fx)
        assertNull(m.nextDeadline()) // no timers: truly idle
        assertTrue(m.go(SessionInput.StartListening).isEmpty())
    }

    @Test
    fun theFourStepCycle() {
        val m = machine()
        m.go(SessionInput.StartListening)

        // step 1 -> 2: key heard, listener hands over, writer loads
        var fx = m.go(SessionInput.KeyHeard(KeyRole.WAKE), 1_000)
        assertEquals(SessionState.WRITER_LOADING, m.state)
        assertTrue(Effect.StopKeys in fx)
        assertTrue(Effect.LoadWriter in fx)
        assertTrue(Effect.Emit("key.heard") in fx)

        fx = m.go(SessionInput.WriterReady, 1_500)
        assertEquals(SessionState.WRITER_ACTIVE, m.state)
        assertTrue(Effect.ListenForSpeech in fx)
        assertEquals(1_500L + 60_000L, m.nextDeadline())

        // step 3: the command is handed to the executor
        fx = m.go(SessionInput.Transcript("open camera"), 5_000)
        assertEquals(listOf("open \"camera\""), fx.runs())
        assertTrue(Effect.ListenForSpeech in fx)
        assertEquals(5_000L + 60_000L, m.nextDeadline()) // activity resets the 1 minute timer

        // step 4: a minute of silence unloads everything and only the key listener is left
        assertTrue(m.go(SessionInput.Tick, 64_999).isEmpty())
        fx = m.go(SessionInput.Tick, 65_000)
        assertEquals(SessionState.LISTENING, m.state)
        assertTrue(Effect.UnloadWriter in fx)
        assertTrue(Effect.StartKeys(setOf(KeyRole.WAKE)) in fx)
        assertTrue(Effect.Emit("assistant.stopped") in fx)
        assertNull(m.nextDeadline())

        // ...and the cycle repeats
        assertTrue(Effect.LoadWriter in m.go(SessionInput.KeyHeard(KeyRole.WAKE), 70_000))
    }

    @Test
    fun spokenDismissUnloadsImmediately() {
        val m = machine()
        m.go(SessionInput.StartListening)
        m.go(SessionInput.KeyHeard(KeyRole.WAKE))
        m.go(SessionInput.WriterReady)
        val fx = m.go(SessionInput.Transcript("never mind"), 3_000)
        assertEquals(SessionState.LISTENING, m.state)
        assertTrue(Effect.UnloadWriter in fx)
    }

    @Test
    fun manualStartWorksWithoutListeningAndEndsOff() {
        val m = machine()
        val fx = m.go(SessionInput.StartAssistant)
        assertEquals(SessionState.WRITER_LOADING, m.state)
        assertFalse(Effect.StopKeys in fx) // no key listener was running
        m.go(SessionInput.WriterReady)
        m.go(SessionInput.StopAssistant)
        assertEquals(SessionState.OFF, m.state)
    }

    @Test
    fun aFailedLoadGivesUpAndReturnsToListening() {
        val m = machine()
        m.go(SessionInput.StartListening)
        m.go(SessionInput.KeyHeard(KeyRole.WAKE), 0)
        val fx = m.go(SessionInput.Tick, 15_000)
        assertEquals(SessionState.LISTENING, m.state)
        assertTrue(fx.any { it is Effect.Log && it.level == Level.ERROR })
    }

    @Test
    fun otherKeysAreIgnoredWhileJustListening() {
        val m = machine()
        m.go(SessionInput.StartListening)
        assertTrue(m.go(SessionInput.KeyHeard(KeyRole.CONFIRM)).isEmpty())
        assertTrue(m.go(SessionInput.KeyHeard(KeyRole.DISMISS)).isEmpty())
        assertEquals(SessionState.LISTENING, m.state)
    }

    @Test
    fun stopListeningWhileAssistantIsActiveTurnsOffAfterwards() {
        val m = machine()
        m.go(SessionInput.StartListening)
        m.go(SessionInput.KeyHeard(KeyRole.WAKE))
        m.go(SessionInput.WriterReady)
        assertTrue(m.go(SessionInput.StopListening).isEmpty())
        m.go(SessionInput.Transcript("stop"))
        assertEquals(SessionState.OFF, m.state)
    }

    // ---- confirmation ---------------------------------------------------------------------

    private fun active(cfg: SessionConfig = SessionConfig()): SessionMachine {
        val m = machine(cfg)
        m.go(SessionInput.StartListening)
        m.go(SessionInput.KeyHeard(KeyRole.WAKE))
        m.go(SessionInput.WriterReady)
        return m
    }

    @Test
    fun riskyCommandWaitsForSpokenYes() {
        val m = active()
        var fx = m.go(SessionInput.Transcript("call mom"), 1_000)
        assertEquals(SessionState.AWAITING_CONFIRM, m.state)
        assertTrue(fx.runs().isEmpty()) // nothing happens before the answer
        assertTrue(fx.any { it is Effect.Speak && it.text.contains("Calling mom") })
        assertEquals(1_000L + 15_000L, m.nextDeadline())

        fx = m.go(SessionInput.Transcript("yes"), 2_000)
        assertEquals(listOf("call \"mom\""), fx.runs())
        assertEquals(SessionState.WRITER_ACTIVE, m.state)
    }

    @Test
    fun riskyCommandIsCancelledByNoOrByTimeout() {
        val m = active()
        m.go(SessionInput.Transcript("call mom"), 0)
        var fx = m.go(SessionInput.Transcript("no"), 100)
        assertTrue(fx.runs().isEmpty())
        assertEquals(SessionState.WRITER_ACTIVE, m.state)

        m.go(SessionInput.Transcript("text mom saying hi"), 200)
        fx = m.go(SessionInput.Tick, 200 + 15_000)
        assertTrue(fx.runs().isEmpty())
        assertEquals(SessionState.WRITER_ACTIVE, m.state)
        assertTrue(fx.any { it is Effect.Speak && it.text == "Cancelled" })
    }

    @Test
    fun unclearAnswerAsksAgainWithoutRunning() {
        val m = active()
        m.go(SessionInput.Transcript("call mom"))
        val fx = m.go(SessionInput.Transcript("banana"))
        assertTrue(fx.runs().isEmpty())
        assertEquals(SessionState.AWAITING_CONFIRM, m.state)
        assertTrue(fx.any { it is Effect.Speak && it.text.contains("yes or no") })
    }

    @Test
    fun confirmAndDismissKeySounds() {
        val cfg = SessionConfig(confirmByKeys = true)
        var m = active(cfg)
        var fx = m.go(SessionInput.Transcript("call mom"))
        assertTrue(Effect.StartKeys(setOf(KeyRole.CONFIRM, KeyRole.DISMISS)) in fx)
        assertFalse(Effect.ListenForSpeech in fx) // the mic is given to the key detector
        fx = m.go(SessionInput.KeyHeard(KeyRole.CONFIRM))
        assertEquals(listOf("call \"mom\""), fx.runs())
        assertTrue(Effect.StopKeys in fx)
        assertTrue(Effect.ListenForSpeech in fx)

        m = active(cfg)
        m.go(SessionInput.Transcript("call mom"))
        fx = m.go(SessionInput.KeyHeard(KeyRole.DISMISS))
        assertTrue(fx.runs().isEmpty())
        assertTrue(Effect.UnloadWriter in fx) // the "return sound" disables the task and exits
        assertEquals(SessionState.LISTENING, m.state)
    }

    @Test
    fun safeCommandsRunWithoutAsking() {
        val m = active()
        val fx = m.go(SessionInput.Transcript("scroll down"))
        assertEquals(listOf("scroll down"), fx.runs())
        assertEquals(SessionState.WRITER_ACTIVE, m.state)
    }

    @Test
    fun unknownSpeechIsReportedAndListeningContinues() {
        val m = active()
        val fx = m.go(SessionInput.Transcript("blah blah"))
        assertTrue(fx.runs().isEmpty())
        assertTrue(Effect.ListenForSpeech in fx)
        assertTrue(fx.any { it is Effect.Log && it.level == Level.WARN })
    }

    @Test
    fun silenceFromTheRecogniserDoesNotExtendTheTimer() {
        val m = active()
        val before = m.nextDeadline()
        val fx = m.go(SessionInput.NothingHeard, 30_000)
        assertEquals(listOf<Effect>(Effect.ListenForSpeech), fx)
        assertEquals(before, m.nextDeadline())
    }

    @Test
    fun staleInputsInWrongStatesAreHarmless() {
        val m = machine()
        assertTrue(m.go(SessionInput.WriterReady).isEmpty())
        assertTrue(m.go(SessionInput.Transcript("call mom")).isEmpty())
        assertTrue(m.go(SessionInput.Tick, 1_000_000).isEmpty())
        assertTrue(m.go(SessionInput.StopAssistant).isEmpty())
        assertEquals(SessionState.OFF, m.state)
    }
}
