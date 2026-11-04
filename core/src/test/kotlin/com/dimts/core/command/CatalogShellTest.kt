package com.dimts.core.command

import com.dimts.core.script.CommandResult
import com.dimts.core.script.FakeClock
import com.dimts.core.script.FakeEnv
import com.dimts.core.script.Level
import com.dimts.core.script.Origin
import com.dimts.core.script.Value
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogShellTest {
    private val clock = FakeClock(0).also { it.set(2, 20, 0) }
    private val env = FakeEnv()
    private val logs = ArrayList<Pair<Level, String>>()
    private val host = object : DeviceHost {
        override fun perform(spec: CommandSpec, args: List<String>, origin: Origin) = CommandResult.ok()
        override fun status() = ""
    }
    private val shell = Shell(clock, env, host, { l, t -> logs.add(l to t) })

    private fun out(): String = logs.filter { it.first == Level.OUT }.joinToString("\n") { it.second }

    @Test
    fun allCurrentConditionsListsStatesWithLiveValues() {
        env.values["battery.level"] = Value.Num(15.0)
        shell.submit("all current conditions")
        val text = out()
        assertTrue(text, text.contains("BATTERY & POWER"))
        assertTrue(text, text.contains("NETWORK"))
        assertTrue(text, text.contains("wifi.connected"))
        assertTrue(text, Regex("battery\\.level\\s+= 15").containsMatchIn(text))
    }

    @Test
    fun statesCanBeFilteredByGroupAndName() {
        shell.submit("states battery")
        assertTrue(out().contains("battery.level"))
        assertTrue(!out().contains("wifi.connected"))
        logs.clear()
        shell.submit("states wifi")
        assertTrue(out().contains("wifi.connected"))
    }

    @Test
    fun allPossibleActionsListsEveryCommand() {
        shell.submit("all possible actions")
        val text = out()
        assertTrue(text.contains("PHONE ACTIONS"))
        for (s in Commands.registry.specs) assertTrue("missing ${s.id}", text.contains(s.usage.take(20)))
    }

    @Test
    fun helpExplainsAStateAndAnEvent() {
        env.values["battery.level"] = Value.Num(42.0)
        shell.submit("help battery.level")
        assertTrue(out().contains("example: if battery.level < 20"))
        assertTrue(out().contains("now: 42"))
        logs.clear()
        shell.submit("help call.missed")
        assertTrue(out().contains("event"))
    }

    @Test
    fun yourOwnStatesAndActionsAreListed() {
        shell.submit("state night = hour > 21")
        shell.submit("def bed:\n    say sleep")
        shell.submit("defs")
        assertTrue(out().contains("state night"))
        assertTrue(out().contains("def bed"))
        logs.clear()
        shell.submit("all current conditions")
        assertTrue(out().contains("YOUR STATES"))
        logs.clear()
        shell.submit("all possible actions")
        assertTrue(out().contains("YOUR ACTIONS"))
        logs.clear()
        shell.submit("undef bed")
        shell.submit("undef night")
        shell.submit("defs")
        assertTrue(out().contains("nothing defined"))
    }

    @Test
    fun anActionCannotShadowACommand() {
        shell.submit("def call:\n    say hi")
        assertTrue(logs.any { it.first == Level.ERROR && it.second.contains("already a command") })
        assertEquals(0, shell.engine.actionDefs().size)
    }

    @Test
    fun theSpecExampleWorksEndToEnd() {
        env.values["battery.level"] = Value.Num(15.0)
        shell.submit("if battery.level < 20: echo low")
        assertEquals("low", out())
        env.values["battery.level"] = Value.Num(80.0)
        logs.clear()
        shell.submit("if battery.level < 20: echo low")
        assertEquals("", out())
        assertTrue(logs.none { it.first == Level.ERROR })
    }
}
