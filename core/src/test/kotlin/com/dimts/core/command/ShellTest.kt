package com.dimts.core.command

import com.dimts.core.script.CommandResult
import com.dimts.core.script.FakeClock
import com.dimts.core.script.FakeEnv
import com.dimts.core.script.Level
import com.dimts.core.script.Origin
import com.dimts.core.script.Value
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellTest {
    private class FakeHost : DeviceHost {
        val calls = ArrayList<String>()
        override fun perform(spec: CommandSpec, args: List<String>, origin: Origin): CommandResult {
            calls.add((listOf(spec.id) + args).joinToString(" ") + " [$origin]")
            return if (spec.id == "call" && args.firstOrNull() == "nobody") CommandResult.error("no contact 'nobody'") else CommandResult.ok()
        }
        override fun status() = "listening: no"
    }

    private val clock = FakeClock(0).also { it.set(2, 20, 0) }
    private val env = FakeEnv()
    private val host = FakeHost()
    private val logs = ArrayList<Pair<Level, String>>()
    private val shell = Shell(clock, env, host, { l, t -> logs.add(l to t) })

    private fun out(): String = logs.filter { it.first == Level.OUT }.joinToString("\n") { it.second }

    // ---- registry ---------------------------------------------------------------------------

    @Test
    fun theExampleCommandsFromTheSpecAllResolve() {
        val r = Commands.registry
        assertEquals("listen.start", r.resolve(listOf("start", "listening", "my_word"))!!.spec.id)
        assertEquals(listOf("my_word"), r.resolve(listOf("start", "listening", "my_word"))!!.args)
        // longest match wins: the enrolment form starts with the same two words
        val enrol = r.resolve(listOf("start", "listening", "input", "wake_word"))!!
        assertEquals("key.record", enrol.spec.id)
        assertEquals(listOf("wake_word"), enrol.args)
        assertEquals("assistant.start", r.resolve(listOf("start", "voice_assistant", "on", "android"))!!.spec.id)
        assertEquals("assistant.start", r.resolve(listOf("start", "voice_assistant"))!!.spec.id)
        assertEquals("help", r.resolve(listOf("start", "help", "now"))!!.spec.id)
        assertEquals("help", r.resolve(listOf("HELP"))!!.spec.id)
        assertEquals("help", r.resolve(listOf("?"))!!.spec.id)
        assertEquals("hangup", r.resolve(listOf("hang", "up"))!!.spec.id)
        assertNull(r.resolve(listOf("frobnicate")))
    }

    @Test
    fun registryHasUniqueNamesAndCompleteDocs() {
        val specs = Commands.registry.specs
        val all = specs.flatMap { s -> s.names.map { it.joinToString(" ").lowercase() } }
        assertEquals("duplicate command names: ${all.groupBy { it }.filter { it.value.size > 1 }.keys}", all.size, all.toSet().size)
        for (s in specs) {
            assertTrue("${s.id} has no summary", s.summary.isNotBlank())
            assertTrue("${s.id} has no usage", s.usage.isNotBlank())
        }
        assertEquals(setOf("call", "sms"), specs.filter { it.risky }.map { it.id }.toSet())
    }

    @Test
    fun unknownCommandsGetASuggestion() {
        val r = shell.execute(listOf("statuss"), Origin.CONSOLE)
        assertFalse(r.ok)
        assertTrue(r.message!!, r.message!!.contains("did you mean 'status'"))
        assertTrue(shell.execute(listOf("volum", "up"), Origin.CONSOLE).message!!.contains("volume"))
        assertTrue(shell.execute(listOf("qqqqqqqq"), Origin.CONSOLE).message!!.contains("help"))
    }

    // ---- help -------------------------------------------------------------------------------

    @Test
    fun helpListsEveryCommandAndTopic() {
        shell.submit("help")
        val text = out()
        for (s in Commands.registry.specs) assertTrue("help is missing ${s.id}", text.contains(s.usage.take(20)))
        assertTrue(text.contains("PROGRAMMING"))
        logs.clear()
        shell.submit("start help now")
        assertTrue(out().contains("DIMTS console"))
    }

    @Test
    fun helpForOneCommandAndTopics() {
        shell.submit("help call")
        assertTrue(out().contains("call <contact|number>"))
        assertTrue(out().contains("confirmation"))
        logs.clear()
        shell.submit("help start listening input")
        assertTrue(out().contains("record key"))
        for (topic in com.dimts.core.command.Help.TOPICS.keys) {
            logs.clear()
            shell.submit("help $topic")
            assertTrue("help $topic is empty", out().length > 40)
        }
        logs.clear()
        shell.submit("help phone")
        assertTrue(out().contains("PHONE ACTIONS"))
        logs.clear()
        shell.submit("help wehn")
        assertTrue(out().contains("Did you mean 'help when'"))
    }

    @Test
    fun everyExampleInTheHelpTopicsParses() {
        val examples = Help.TOPICS["examples"]!!.trimIndent()
        val program = com.dimts.core.script.Parser.parse(examples)
        assertTrue(program.stmts.size >= 5)
        // and every command used in them exists
        fun names(stmts: List<com.dimts.core.script.Stmt>): List<List<String>> = stmts.flatMap { s ->
            when (s) {
                is com.dimts.core.script.CommandStmt -> listOf(s.words.map { w -> w.parts.joinToString("") { (it as? com.dimts.core.script.Part.Text)?.s ?: "?" } })
                is com.dimts.core.script.IfStmt -> s.branches.flatMap { names(it.body) } + (s.elseBody?.let { names(it) } ?: emptyList())
                is com.dimts.core.script.RuleStmt -> names(s.body)
                is com.dimts.core.script.RepeatStmt -> names(s.body)
                else -> emptyList()
            }
        }
        for (words in names(program.stmts)) assertNotNull("example uses unknown command $words", Commands.registry.resolve(words))
    }

    // ---- the shell around the engine ---------------------------------------------------------

    @Test
    fun deviceCommandsGoToTheHostWithTheirOrigin() {
        shell.submit("open camera")
        shell.submit("volume up", Origin.VOICE)
        assertEquals(listOf("open camera [CONSOLE]", "volume up [VOICE]"), host.calls)
    }

    @Test
    fun theWholeExampleFromTheSpecRunsEndToEnd() {
        shell.submit("at 10:30 pm:\n    call mom\n    when call.answered:\n        hangup")
        assertTrue(host.calls.isEmpty())
        shell.submit("rules")
        assertTrue(out(), out().contains("at 10:30 pm") && out().contains("[next: Mon 2 Nov 22:30"))
        clock.set(2, 22, 30, 1)
        shell.engine.tick()
        shell.engine.emit("call.answered")
        assertEquals(listOf("call mom [RULE]", "hangup [RULE]"), host.calls)
    }

    @Test
    fun failedDeviceCommandsAreReportedWithTheReason() {
        shell.submit("call nobody")
        assertTrue(logs.any { it.first == Level.ERROR && it.second.contains("no contact 'nobody'") })
    }

    @Test
    fun rulesVarsJobsCommands() {
        shell.submit("set who = \"mom\"\nat 9 pm: call \$who\nafter 1 h: say hi")
        shell.submit("vars"); assertTrue(out().contains("who = \"mom\""))
        logs.clear(); shell.submit("rules")
        assertTrue(out().contains("#1") && out().contains("#2"))
        logs.clear(); shell.submit("cancel rule 1"); assertTrue(out().contains("removed"))
        logs.clear(); shell.submit("cancel rule 1"); assertTrue(logs.any { it.first == Level.ERROR })
        logs.clear(); shell.submit("clear rules"); assertTrue(out().contains("1 rule"))
        logs.clear(); shell.submit("unset who"); assertTrue(out().contains("removed"))
        logs.clear(); shell.submit("say a\nwait 5 s\nsay b"); shell.submit("jobs"); assertTrue(out().contains("#"))
        logs.clear(); shell.submit("cancel job all"); assertTrue(out().contains("1 job"))
        logs.clear(); shell.submit("status"); assertTrue(out().contains("rules waiting: 0") && out().contains("listening: no"))
    }

    @Test
    fun evalPrintsValuesAndErrors() {
        env.values["battery.level"] = Value.Num(15.0)
        for ((expr, expected) in listOf(
            "battery.level < 20" to "true", "2 + 3 * 4" to "14", "\"a\" + \"b\"" to "\"ab\"",
            "time" to "8:00 pm", "time between 7 pm and 9 pm" to "true", "day" to "\"monday\"",
        )) {
            logs.clear()
            shell.submit("eval $expr")
            assertEquals(expr, expected, out())
        }
        logs.clear()
        assertNotNull(shell.submit("eval 1 / 0"))
        assertTrue(logs.single().second.contains("division by zero"))
    }

    @Test
    fun namesListsLiveNamesAndEvents() {
        env.values["battery.level"] = Value.Num(1.0)
        shell.submit("names")
        val text = out()
        assertTrue(text.contains("battery.level") && text.contains("call.answered") && text.contains("time"))
    }

    @Test
    fun riskDetectionLooksAtTheCommandNotItsArguments() {
        assertTrue(shell.isRisky("call \"mom\""))
        assertTrue(shell.isRisky("sms \"mom\" \"hi\""))
        assertFalse(shell.isRisky("open \"call\""))
        assertFalse(shell.isRisky("scroll down"))
        assertFalse(shell.isRisky("not a command"))
        assertTrue(shell.isRisky("at 9 pm: call mom")) // anything that is not one plain command is treated as risky
    }

    @Test
    fun commandsWithoutAHostSaySo() {
        val bare = Shell(clock, env, null, { l, t -> logs.add(l to t) })
        logs.clear()
        bare.submit("call mom")
        assertTrue(logs.single().second.contains("needs the phone"))
    }

    @Test
    fun saveAndLoadThroughTheShell() {
        shell.submit("set x = 42\nevery day at 7 am: say hi")
        val saved = shell.save()
        val other = Shell(clock, env, host, { l, t -> logs.add(l to t) })
        other.load(saved)
        assertEquals(Value.Num(42.0), other.engine.vars["x"])
        assertEquals(1, other.engine.rules().size)
    }
}
