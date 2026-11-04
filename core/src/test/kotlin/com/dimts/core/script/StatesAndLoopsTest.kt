package com.dimts.core.script

import com.dimts.core.Fuzzy
import com.dimts.core.command.Catalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StatesAndLoopsTest {
    @Test
    fun whileRunsAsLongAsTheConditionHolds() {
        val h = Harness()
        h.run(
            """
            set n = 0
            while n < 3:
                n += 1
                say tick
            """,
        )
        assertEquals(listOf("say tick", "say tick", "say tick"), h.ran)
    }

    @Test
    fun whileCanTestAPhoneState() {
        val h = Harness()
        h.env.values["battery.level"] = Value.Num(10.0)
        h.run(
            """
            while battery.level < 13:
                say charging
                set x = 1
                stop
            """,
        )
        assertEquals(listOf("say charging"), h.ran)
    }

    @Test
    fun breakLeavesTheLoop() {
        val h = Harness()
        h.run(
            """
            set n = 0
            while true:
                n += 1
                if n == 3: break
                say a
            say done
            """,
        )
        assertEquals(listOf("say a", "say a", "say done"), h.ran)
    }

    @Test
    fun continueSkipsToTheNextRound() {
        val h = Harness()
        h.run(
            """
            set n = 0
            while n < 4:
                n += 1
                if n == 2: continue
                say {n}
            """,
        )
        assertEquals(listOf("say 1", "say 3", "say 4"), h.ran)
    }

    @Test
    fun breakOutsideALoopIsAnError() {
        val h = Harness()
        h.run("break")
        assertTrue(h.logText(Level.ERROR).any { it.contains("break") })
    }

    @Test
    fun compoundAssignmentWorks() {
        val h = Harness()
        h.run(
            """
            set x = 10
            x -= 4
            x *= 3
            say {x}
            """,
        )
        assertEquals(listOf("say 18"), h.ran)
    }

    @Test
    fun definedActionsRunByNameWithArguments() {
        val h = Harness()
        h.run(
            """
            def greet:
                say hello {arg1}
            greet bob
            greet ann
            """,
        )
        assertEquals(listOf("say hello bob", "say hello ann"), h.ran)
    }

    @Test
    fun actionsCanCallActionsAndRunFromRules() {
        val h = Harness()
        h.run(
            """
            def inner:
                vibrate
            def outer:
                inner
                say done
            at 9 pm: outer
            """,
        )
        h.at(21, 0)
        assertEquals(listOf("vibrate", "say done"), h.ran)
    }

    @Test
    fun anActionThatCallsItselfIsStopped() {
        val h = Harness()
        h.run(
            """
            def boom:
                boom
            boom
            """,
        )
        assertTrue(h.logText(Level.ERROR).any { it.contains("deeply") })
    }

    @Test
    fun definedStatesCanBeUsedInConditions() {
        val h = Harness()
        h.env.values["battery.level"] = Value.Num(10.0)
        h.run("state low = battery.level < 20")
        h.run("if low: say low")
        h.env.values["battery.level"] = Value.Num(50.0)
        h.run("if low: say low")
        h.run("if low: say again\nelse: say fine")
        assertEquals(listOf("say low", "say fine"), h.ran)
    }

    @Test
    fun rulesCanWaitOnADefinedState() {
        val h = Harness()
        h.env.values["battery.level"] = Value.Num(50.0)
        h.run("state low = battery.level < 20")
        h.run("whenever low: say low")
        assertTrue(h.engine.needsPolling())
        h.env.values["battery.level"] = Value.Num(10.0)
        h.engine.tick()
        assertEquals(listOf("say low"), h.ran)
        h.engine.tick()
        assertEquals(listOf("say low"), h.ran)
    }

    @Test
    fun aStateCannotDependOnItself() {
        val h = Harness()
        h.run("state a = a and true")
        assertTrue(h.logText(Level.ERROR).any { it.contains("itself") })
    }

    @Test
    fun definitionsSurviveSaveAndLoad() {
        val h = Harness()
        h.run("state night = hour > 21\ndef bed:\n    say sleep")
        val saved = h.engine.save()
        val again = Harness()
        again.engine.load(saved)
        assertEquals(1, again.engine.derivedStates().size)
        assertEquals(1, again.engine.actionDefs().size)
        again.run("bed")
        assertEquals(listOf("say sleep"), again.ran)
    }

    @Test
    fun undefRemovesDefinitions() {
        val h = Harness()
        h.run("state s = true\ndef d:\n    pass")
        assertTrue(h.engine.removeDefinition("s"))
        assertTrue(h.engine.removeDefinition("d"))
        assertEquals(0, h.engine.derivedStates().size + h.engine.actionDefs().size)
    }

    @Test
    fun dateNamesAreAvailable() {
        val h = Harness()
        assertEquals(Value.Num(2.0), h.engine.evalNow("date"))
        assertEquals(Value.Num(11.0), h.engine.evalNow("month"))
        assertEquals(Value.Num(2026.0), h.engine.evalNow("year"))
    }

    @Test
    fun catalogEventsMatchTheEngine() {
        assertEquals(Builtins.EVENTS, Catalog.events.map { it.name }.toSet())
    }

    @Test
    fun catalogHasNoDuplicateStates() {
        val names = Catalog.states.map { it.name }
        assertEquals(names.size, names.toSet().size)
        assertTrue(Catalog.states.all { Catalog.groupTitle(it.group) != null })
    }

    @Test
    fun fuzzyFindsSoundAlikeNames() {
        val apps = listOf("Camera", "Chrome", "Claude", "Calculator", "Clock")
        assertEquals("Claude", Fuzzy.rank("cloud", apps).first())
        assertEquals("Camera", Fuzzy.rank("camra", apps).first())
        assertEquals("Chrome", Fuzzy.rank("krome", apps).first())
        assertTrue(Fuzzy.rank("zzzzzzzz", apps).isEmpty())
    }
}
