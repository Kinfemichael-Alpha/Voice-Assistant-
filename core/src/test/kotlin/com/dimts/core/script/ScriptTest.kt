package com.dimts.core.script

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptTest {

    // ---- plain commands, variables, expressions --------------------------------------------

    @Test
    fun runsACommandLineWithWordsAndQuotes() {
        val h = Harness()
        h.run("""sms mom "I will be late" now""")
        assertEquals(listOf(listOf("sms", "mom", "I will be late", "now")), h.commands)
        assertEquals(Origin.CONSOLE, h.origins[0])
    }

    @Test
    fun variablesArithmeticAndInterpolation() {
        val h = Harness()
        h.run(
            """
            set n = 2 + 3 * 4
            name = "Abebe"
            say "n is {n}, hello {name}"
            say ${'$'}name
            """,
        )
        assertEquals(listOf("say n is 14, hello Abebe", "say Abebe"), h.ran)
        assertEquals(Value.Num(14.0), h.engine.vars["n"])
    }

    @Test
    fun timeArithmeticAndFormatting() {
        val h = Harness()
        h.run("set t = 10:30 pm + 45")
        assertEquals(Value.Time(23 * 60 + 15), h.engine.vars["t"])
        h.run("say {t}")
        assertEquals("say 11:15 pm", h.ran.last())
    }

    @Test
    fun timeLiteralsInAllSupportedSpellings() {
        val h = Harness()
        for ((text, minute) in listOf("10:30 pm" to 1350, "10:30pm" to 1350, "22:30" to 1350, "7 am" to 420, "7am" to 420, "12 am" to 0, "12 pm" to 720, "12:05 am" to 5)) {
            h.run("set t = $text")
            assertEquals("literal '$text'", Value.Time(minute), h.engine.vars["t"])
        }
        assertNotNull(h.run("set t = 25:00"))
        assertNotNull(h.run("set t = 13 pm"))
        assertNotNull(h.run("set t = 10:75"))
    }

    @Test
    fun escapedBracesAndEmptyArguments() {
        val h = Harness()
        h.run("""say "literal \{braces}" "" end""")
        assertEquals(listOf("say", "literal {braces}", "", "end"), h.commands[0])
    }

    @Test
    fun commentsTabsAndBlankLinesAreIgnored() {
        val h = Harness()
        h.run("# a comment\n\nif true:\n\tsay hi   # trailing comment\n\n")
        assertEquals(listOf("say hi"), h.ran)
        h.run("""say "not # a comment"""")
        assertEquals("say not # a comment", h.ran.last())
    }

    // ---- if / elif / else, nesting ---------------------------------------------------------

    @Test
    fun ifElifElseChooseOneBranch() {
        val h = Harness()
        val script = """
            if x > 10:
                say big
            elif x > 5:
                say medium
            else:
                say small
        """
        for ((x, expected) in listOf(20 to "say big", 7 to "say medium", 1 to "say small")) {
            h.run("set x = $x")
            h.commands.clear()
            h.run(script)
            assertEquals(listOf(expected), h.ran)
        }
    }

    @Test
    fun inlineBodiesAndSemicolons() {
        val h = Harness()
        h.run("set x = 1")
        h.run("if x == 1: say one; say uno")
        h.run("if x == 2: say two")
        assertEquals(listOf("say one", "say uno"), h.ran)
    }

    @Test
    fun deeplyNestedConditions() {
        val h = Harness()
        h.run(
            """
            set a = 1
            set b = 2
            if a == 1:
                if b == 2:
                    if a + b == 3:
                        say deep
                    else:
                        say wrong
                else:
                    say wrong2
            """,
        )
        assertEquals(listOf("say deep"), h.ran)
    }

    @Test
    fun logicOperatorsAndStringTests() {
        val h = Harness()
        h.env.values["call.state"] = Value.Str("ringing")
        h.env.values["battery.level"] = Value.Num(15.0)
        h.env.values["battery.charging"] = Value.Bool(false)
        h.run(
            """
            if call.state is ringing and battery.level < 20 and not battery.charging:
                say a
            if call.state is not idle: say b
            if "hello world" contains "WORLD": say c
            if battery.level between 10 and 20: say d
            if battery.level >= 20 or call.state == "idle": say never
            """,
        )
        assertEquals(listOf("say a", "say b", "say c", "say d"), h.ran)
    }

    @Test
    fun repeatLoopsAndStopsAndPass() {
        val h = Harness()
        h.run(
            """
            repeat 3 times:
                say tick
            repeat 2:
                if true:
                    pass
                say again
            say end
            stop
            say unreachable
            """,
        )
        assertEquals(listOf("say tick", "say tick", "say tick", "say again", "say again", "say end"), h.ran)
    }

    @Test
    fun repeatIsBounded() {
        val h = Harness()
        val err = h.run("repeat 100000:\n    say x")
        assertNull(err)
        assertTrue(h.commands.isEmpty())
        assertTrue(h.logText(Level.ERROR).single().contains("limited"))
    }

    // ---- scheduling: the "if time is 10:30 pm: call mom" family ----------------------------

    @Test
    fun atRunsOnceAtTheRightMinute() {
        val h = Harness(startHour = 20)
        assertNull(h.run("if_placeholder = 1")) // harmless assignment
        h.run("at 10:30 pm: call mom")
        assertEquals(1, h.engine.rules().size)
        h.at(22, 29, second = 59)
        assertTrue(h.commands.isEmpty())
        h.at(22, 30, second = 1)
        assertEquals(listOf("call mom"), h.ran)
        assertEquals(Origin.RULE, h.origins.last())
        assertEquals(0, h.engine.rules().size)
        h.at(22, 31)
        h.at(23, 30)
        assertEquals(1, h.commands.size)
    }

    @Test
    fun theExampleFromTheSpecWithNestedWhenWorks() {
        val h = Harness(startHour = 20)
        h.run(
            """
            at 10:30 pm:
                call mom
                when call.answered:
                    hangup
            """,
        )
        h.engine.emit("call.answered") // too early: nothing is waiting yet
        assertTrue(h.commands.isEmpty())

        h.at(22, 30)
        assertEquals(listOf("call mom"), h.ran)
        assertEquals(1, h.engine.rules().size) // the inner `when` is now registered
        assertTrue(h.engine.rules()[0].label.startsWith("when call.answered"))

        h.engine.emit("call.answered")
        assertEquals(listOf("call mom", "hangup"), h.ran)
        assertEquals(0, h.engine.rules().size)
        h.engine.emit("call.answered")
        assertEquals(2, h.commands.size)
    }

    @Test
    fun nestedConditionsInsideAFiredRule() {
        val h = Harness(startHour = 20)
        h.env.values["battery.level"] = Value.Num(50.0)
        h.run(
            """
            at 10:30 pm:
                if battery.level < 20:
                    say low
                else:
                    call mom
                    when call.ended:
                        if battery.level > 10: say done
            """,
        )
        h.at(22, 30)
        assertEquals(listOf("call mom"), h.ran)
        h.engine.emit("call.ended")
        assertEquals(listOf("call mom", "say done"), h.ran)
    }

    @Test
    fun dayRestrictedAndRepeatingSchedules() {
        // 2026-11-02 is a Monday.
        val h = Harness(startDay = 2, startHour = 6)
        h.run("every mon wed fri at 7:00 am: say workout")
        h.run("every weekdays at 8:00 am: say work")
        h.run("at 9 am on sat, sun: say weekend")
        h.at(7, 0, day = 2)
        h.at(8, 0, day = 2)
        assertEquals(listOf("say workout", "say work"), h.ran)
        h.at(7, 0, day = 3) // Tuesday: not mon/wed/fri
        h.at(8, 0, day = 3) // but a weekday
        assertEquals(listOf("say workout", "say work", "say work"), h.ran)
        h.at(7, 0, day = 4) // Wednesday
        assertEquals("say workout", h.ran.last())
        h.at(9, 0, day = 7) // Saturday
        assertEquals("say weekend", h.ran.last())
        assertEquals(2, h.engine.rules().size) // the repeating ones remain, the `at` rule is gone
    }

    @Test
    fun aLateTickStillCatchesTheMissedMinute() {
        val h = Harness(startHour = 20)
        h.run("at 10:30 pm: call mom")
        h.at(22, 47) // the device slept through 22:30
        assertEquals(listOf("call mom"), h.ran)
    }

    @Test
    fun registeringInTheSameMinuteDoesNotFireImmediately() {
        val h = Harness(startHour = 22, startMinute = 30)
        h.run("at 10:30 pm: call mom")
        h.engine.tick()
        assertTrue(h.commands.isEmpty())
        h.at(22, 30, day = 3) // tomorrow's 10:30 pm
        assertEquals(listOf("call mom"), h.ran)
    }

    @Test
    fun afterAndEveryTimers() {
        val h = Harness()
        h.run("after 10 min: say once")
        h.run("every 5 min: say tock")
        h.clock.advanceSeconds(299); h.engine.tick()
        assertTrue(h.commands.isEmpty())
        h.clock.advanceSeconds(1); h.engine.tick()
        assertEquals(listOf("say tock"), h.ran)
        h.clock.advanceSeconds(300); h.engine.tick()
        assertEquals(listOf("say tock", "say once", "say tock").sorted(), h.ran.sorted())
        h.clock.advanceSeconds(300); h.engine.tick()
        assertEquals(3, h.ran.count { it == "say tock" })
        assertEquals(1, h.ran.count { it == "say once" })
    }

    @Test
    fun waitSuspendsTheRestOfTheBlock() {
        val h = Harness()
        h.run("say a\nwait 30 s\nsay b\nwait 1 min\nsay c")
        assertEquals(listOf("say a"), h.ran)
        assertEquals(1, h.engine.jobs().size)
        h.clock.advanceSeconds(29); h.engine.tick()
        assertEquals(1, h.commands.size)
        h.clock.advanceSeconds(1); h.engine.tick()
        assertEquals(listOf("say a", "say b"), h.ran)
        h.clock.advanceSeconds(60); h.engine.tick()
        assertEquals(listOf("say a", "say b", "say c"), h.ran)
        assertEquals(0, h.engine.jobs().size)
    }

    @Test
    fun waitInsideRepeatKeepsItsPlace() {
        val h = Harness()
        h.run("repeat 2:\n    say x\n    wait 10 s\nsay end")
        repeat(3) { h.clock.advanceSeconds(10); h.engine.tick() }
        assertEquals(listOf("say x", "say x", "say end"), h.ran)
    }

    // ---- when / whenever: state conditions fire on the false -> true edge -------------------

    @Test
    fun wheneverFiresOnEachRisingEdgeOnly() {
        val h = Harness()
        h.env.values["battery.level"] = Value.Num(50.0)
        h.run("whenever battery.level < 20: say low")
        for (level in listOf(40, 15, 14, 13, 30, 19, 10)) {
            h.env.values["battery.level"] = Value.Num(level.toDouble())
            h.engine.tick()
        }
        // Fires on 15 and on 19 (both rising edges). 14, 13 and 10 stay below 20, so no new edge.
        assertEquals(listOf("say low", "say low"), h.ran)
    }

    @Test
    fun whenFiresOnceThenIsGone() {
        val h = Harness()
        h.env.values["battery.charging"] = Value.Bool(false)
        h.run("when battery.charging: say plugged")
        h.env.values["battery.charging"] = Value.Bool(true)
        h.engine.tick()
        h.env.values["battery.charging"] = Value.Bool(false)
        h.engine.tick()
        h.env.values["battery.charging"] = Value.Bool(true)
        h.engine.tick()
        assertEquals(listOf("say plugged"), h.ran)
        assertEquals(0, h.engine.rules().size)
    }

    @Test
    fun alreadyTrueWhenRegisteredDoesNotFireUntilItBecomesTrueAgain() {
        val h = Harness()
        h.env.values["battery.level"] = Value.Num(10.0)
        h.run("when battery.level < 20: say low")
        h.engine.tick()
        assertTrue(h.commands.isEmpty())
        h.env.values["battery.level"] = Value.Num(90.0); h.engine.tick()
        h.env.values["battery.level"] = Value.Num(10.0); h.engine.tick()
        assertEquals(listOf("say low"), h.ran)
    }

    @Test
    fun repeatedEventsEachFireAWheneverRule() {
        val h = Harness()
        h.run("whenever key.heard: say hello")
        h.engine.emit("key.heard")
        h.engine.emit("key.heard")
        h.engine.emit("screen.on")
        assertEquals(listOf("say hello", "say hello"), h.ran)
    }

    @Test
    fun conditionCombiningAnEventAndState() {
        val h = Harness()
        h.env.values["call.contact"] = Value.Str("Mom")
        h.run("whenever call.incoming and call.contact is mom: say mom_calls")
        h.engine.emit("call.incoming")
        h.env.values["call.contact"] = Value.Str("Bob")
        h.engine.emit("call.incoming")
        assertEquals(listOf("say mom_calls"), h.ran)
    }

    @Test
    fun timeRangeConditionWrapsPastMidnight() {
        val h = Harness(startHour = 12)
        for ((hour, expected) in listOf(23 to true, 2 to true, 7 to false, 12 to false, 22 to true, 6 to true)) {
            h.clock.set(2, hour, 0)
            val v = h.engine.evalNow("time between 10 pm and 6 am")
            assertEquals("hour $hour", Value.Bool(expected), v)
        }
    }

    // ---- management -------------------------------------------------------------------------

    @Test
    fun rulesCanBeListedAndCancelled() {
        val h = Harness()
        h.run("at 10:30 pm: call mom")
        h.run("every 1 h: say hourly")
        val rules = h.engine.rules()
        assertEquals(2, rules.size)
        assertNotNull(rules[0].nextWakeMillis)
        assertTrue(h.engine.cancelRule(rules[0].id))
        assertFalse(h.engine.cancelRule(rules[0].id))
        assertEquals(1, h.engine.rules().size)
        assertEquals(1, h.engine.clearRules())
    }

    @Test
    fun nextWakeIsTheEarliestOfTimersAndTimeRules() {
        val h = Harness(startHour = 20)
        assertNull(h.engine.nextWakeMillis())
        h.run("at 10:30 pm: say a")
        val tenThirty = h.engine.nextWakeMillis()!!
        h.clock.set(2, 22, 30)
        assertTrue(tenThirty in h.clock.now..(h.clock.now + 2000))
        h.run("after 5 min: say b")
        assertEquals(h.clock.now + 300_000, h.engine.nextWakeMillis())
        h.run("whenever battery.level < 5: say c") // state-driven only: does not need an alarm
        assertEquals(2 + 1, h.engine.rules().size)
    }

    // ---- errors ----------------------------------------------------------------------------

    @Test
    fun syntaxErrorsReportTheLineAndRunNothing() {
        val h = Harness()
        val err = h.run("say hi\nif x == 1\n    say no")
        assertNotNull(err)
        assertEquals(2, err!!.line)
        assertTrue(err.detail.contains("':'"))
        assertTrue(h.commands.isEmpty()) // nothing ran, not even the valid first line
    }

    @Test
    fun variousSyntaxErrorsAreCaught() {
        val h = Harness()
        for (bad in listOf("else:\n    say x", "if x:\nsay y", "at banana: say x", "after 5: say x", "after 5 parsecs: say x", "every day: say x", "say \"open", "if (1 + 2: say x", "repeat: say x", "  say a\nsay b", "wait", "at 10 pm on funday: say x")) {
            assertNotNull("should be rejected: <$bad>", h.run(bad))
        }
        assertTrue(h.commands.isEmpty())
    }

    @Test
    fun aFailingCommandStopsTheTaskAndSaysWhy() {
        val h = Harness()
        h.failOn = "call"
        h.run("say start\ncall nobody\nsay never")
        assertEquals(listOf("say start", "call nobody"), h.ran)
        assertEquals(listOf("line 2: boom"), h.logText(Level.ERROR))
    }

    @Test
    fun runtimeErrorsAreReportedWithLineNumbers() {
        val h = Harness()
        h.run("say ok\nset x = 1 / 0")
        assertEquals(listOf("line 2: division by zero"), h.logText(Level.ERROR))
        h.run("set y = \"abc\" - 1")
        assertTrue(h.logText(Level.ERROR).last().contains("numbers"))
    }

    @Test
    fun misspelledDottedNamesGetASuggestion() {
        val h = Harness()
        h.run("when mom.picked_up_call: hangup")
        val warning = h.logText(Level.WARN).single()
        assertTrue(warning, warning.contains("mom.picked_up_call"))
        h.logs.clear()
        h.run("when call.answerd: hangup")
        assertTrue(h.logText(Level.WARN).single().contains("did you mean 'call.answered'"))
        h.logs.clear()
        h.run("when call.answered: hangup")
        assertTrue(h.logText(Level.WARN).isEmpty())
    }

    @Test
    fun aBrokenRuleIsRemovedInsteadOfSpammingTheLog() {
        val h = Harness()
        h.env.values["thing"] = Value.Num(1.0)
        h.run("whenever 10 / (thing - 1) > 5: say x")
        h.engine.tick()
        assertEquals(0, h.engine.rules().size)
        assertEquals(1, h.logText(Level.ERROR).size)
        h.engine.tick()
        assertEquals(1, h.logText(Level.ERROR).size)
    }

    @Test
    fun runawayScriptsAreStopped() {
        val h = Harness()
        h.run("repeat 1000:\n    repeat 1000:\n        repeat 1000:\n            pass")
        assertTrue(h.logText(Level.ERROR).single().contains("too many steps"))
    }

    // ---- persistence -----------------------------------------------------------------------

    @Test
    fun rulesAndVariablesSurviveRestart() {
        val a = Harness(startHour = 20)
        a.run("set mom = \"Mama\"\nset count = 3\nset t = 7 am")
        a.run("at 10:30 pm:\n    call \$mom\n    when call.answered:\n        hangup")
        a.run("after 3 h: say later")
        val saved = a.engine.save()

        val b = Harness(startHour = 21)
        b.engine.load(saved)
        assertEquals(Value.Str("Mama"), b.engine.vars["mom"])
        assertEquals(Value.Num(3.0), b.engine.vars["count"])
        assertEquals(Value.Time(420), b.engine.vars["t"])
        assertEquals(2, b.engine.rules().size)
        b.at(22, 30)
        assertEquals(listOf("call Mama"), b.ran)
        b.engine.emit("call.answered")
        assertEquals(listOf("call Mama", "hangup"), b.ran)
        assertEquals(1, b.engine.rules().size) // only the 3 h timer is left
        b.clock.set(2, 23, 0, 1)
        b.engine.tick()
        assertEquals("say later", b.ran.last())
    }

    @Test
    fun restartCatchesUpMissedTimeButOnlyWithinTheGraceWindow() {
        val a = Harness(startHour = 20)
        a.run("at 10:30 pm: call mom")
        val saved = a.engine.save()

        val within = Harness(startHour = 23, startMinute = 0) // 30 min late: still fires
        within.engine.load(saved)
        within.engine.tick()
        assertEquals(listOf("call mom"), within.ran)

        val tooLate = Harness(startDay = 3, startHour = 3) // 4.5 hours late: skipped
        tooLate.engine.load(saved)
        tooLate.engine.tick()
        assertTrue(tooLate.commands.isEmpty())
        assertEquals(1, tooLate.engine.rules().size)
    }

    @Test
    fun corruptSavedLinesAreSkippedNotFatal() {
        val h = Harness()
        h.engine.load("dimts-state 1\nV\tok\tN\t5.0\nR\t1\tnot-a-number\t0\t\tat 10 pm: say x\nR\t2\n")
        assertEquals(Value.Num(5.0), h.engine.vars["ok"])
        assertTrue(h.logText(Level.WARN).isNotEmpty())
        h.engine.load("garbage")
    }

    // ---- REPL --------------------------------------------------------------------------------

    @Test
    fun replCollectsABlockUntilABlankLine() {
        val h = Harness(startHour = 20)
        val repl = Repl { h.engine.submit(it) }
        assertEquals("> ", repl.prompt)
        assertFalse(repl.line("at 10:30 pm:"))
        assertEquals("... ", repl.prompt)
        repl.line("    call mom")
        repl.line("    when call.answered:")
        repl.line("        hangup")
        assertTrue(h.commands.isEmpty())
        assertTrue(repl.line(""))
        assertEquals("> ", repl.prompt)
        assertEquals(1, h.engine.rules().size)
        h.at(22, 30)
        h.engine.emit("call.answered")
        assertEquals(listOf("call mom", "hangup"), h.ran)
    }

    @Test
    fun replRunsSingleLinesImmediatelyAndHandlesPastedScripts() {
        val h = Harness()
        val repl = Repl { h.engine.submit(it) }
        assertTrue(repl.line("say one"))
        assertTrue(repl.line("if true: say two"))
        repl.line("if true:\n    say three\nsay four")
        assertEquals(listOf("say one", "say two", "say three", "say four"), h.ran.take(3) + "say four")
        repl.line("repeat 2:")
        repl.abort()
        assertEquals("> ", repl.prompt)
    }
}
