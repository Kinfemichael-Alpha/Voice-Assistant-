package com.dimts.core.command

import com.dimts.core.script.Clock
import com.dimts.core.script.CommandResult
import com.dimts.core.script.CommandRunner
import com.dimts.core.script.CommandStmt
import com.dimts.core.script.Engine
import com.dimts.core.script.Environment
import com.dimts.core.script.Level
import com.dimts.core.script.LogSink
import com.dimts.core.script.Origin
import com.dimts.core.script.Parser
import com.dimts.core.script.ScriptError
import com.dimts.core.script.Value
import com.dimts.core.script.show
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * What only the phone can do. The Android app implements this; tests use a fake.
 * Commands that [Shell] handles itself (help, rules, vars ...) never reach it.
 */
interface DeviceHost {
    fun perform(spec: CommandSpec, args: List<String>, origin: Origin): CommandResult

    /** Extra lines for the `status` command (listening, assistant state, permissions summary). */
    fun status(): String
}

/**
 * The console brain: script [engine] + command [registry] + the phone ([host]).
 * Console, voice and rules all go through [submit], so they behave the same way.
 */
class Shell(
    private val clock: Clock,
    env: Environment,
    private val host: DeviceHost?,
    private val log: LogSink,
    val registry: CommandRegistry = Commands.registry,
    private val versionName: String = "0.1.0",
) {
    val engine: Engine = Engine(
        clock, env, CommandRunner { words, origin -> execute(words, origin) }, log,
        reserved = { name -> registry.resolve(listOf(name)) != null },
    )

    private fun live(name: String): String? = try {
        val v = engine.evalNow(name)
        if (v === Value.Null) null else display(v)
    } catch (e: ScriptError) {
        null
    }

    /** Runs typed or spoken text (one line or a whole script). Returns the syntax error, if any. */
    fun submit(text: String, origin: Origin = Origin.CONSOLE): ScriptError? {
        val t = text.trim()
        if (t.length > 5 && t.substring(0, 5).equals("eval ", ignoreCase = true) && '\n' !in t) {
            return evalLine(t.substring(5))
        }
        return engine.submit(text, origin)
    }

    private fun evalLine(expr: String): ScriptError? = try {
        log.log(Level.OUT, display(engine.evalNow(expr)))
        null
    } catch (e: ScriptError) {
        log.log(Level.ERROR, e.detail)
        e
    }

    private fun display(v: Value): String = if (v is Value.Str) "\"${v.v}\"" else v.show()

    /** True if this line is a single command that needs confirmation when spoken. */
    fun isRisky(line: String): Boolean {
        val program = try {
            Parser.parse(line)
        } catch (e: ScriptError) {
            return false
        }
        val stmt = program.stmts.singleOrNull() as? CommandStmt ?: return program.stmts.isNotEmpty()
        // Words with {..} or $.. are evaluated later; the command name itself is always literal text.
        val words = stmt.words.map { w -> w.parts.joinToString("") { (it as? com.dimts.core.script.Part.Text)?.s ?: "?" } }
        return registry.resolve(words)?.spec?.risky ?: false
    }

    fun execute(words: List<String>, origin: Origin): CommandResult {
        if (words.isEmpty()) return CommandResult.ok()
        val resolved = registry.resolve(words) ?: return CommandResult.error(registry.unknownMessage(words))
        return try {
            builtin(resolved)
                ?: host?.perform(resolved.spec, resolved.args, origin)
                ?: CommandResult.error("'${resolved.spec.primary}' needs the phone and is not available here")
        } catch (e: ScriptError) {
            CommandResult.error(e.detail)
        } catch (e: Exception) {
            CommandResult.error(e.message ?: e.toString())
        }
    }

    // ---- commands the shell implements itself ---------------------------------------------

    private fun builtin(r: Resolved): CommandResult? {
        val a = r.args
        return when (r.spec.id) {
            "help" -> CommandResult.ok(Help.render(registry, a) { live(it) })
            "states" -> CommandResult.ok(Help.states(a.joinToString(" "), { live(it) }, engine.derivedStates()))
            "events" -> CommandResult.ok(Help.events())
            "actions" -> CommandResult.ok(Help.actions(registry, a.joinToString(" "), engine.actionDefs()))
            "defs" -> CommandResult.ok(describeDefs())
            "undef" -> {
                val name = a.firstOrNull() ?: return CommandResult.error("usage: undef <name>  (see 'defs')")
                if (engine.removeDefinition(name)) CommandResult.ok("$name removed") else CommandResult.error("nothing defined as '$name'")
            }
            "echo" -> CommandResult.ok(a.joinToString(" "))
            "version" -> CommandResult.ok("Dimts $versionName")
            "rules" -> CommandResult.ok(describeRules())
            "rules.cancel" -> {
                val id = a.firstOrNull()?.toIntOrNull() ?: return CommandResult.error("usage: cancel rule <id>  (see 'rules')")
                if (engine.cancelRule(id)) CommandResult.ok("rule #$id removed") else CommandResult.error("no rule #$id")
            }
            "rules.clear" -> CommandResult.ok("${engine.clearRules()} rule(s) removed")
            "jobs" -> CommandResult.ok(
                engine.jobs().joinToString("\n") { "#${it.id}  waiting until ${formatTime(it.wakeAtMillis)}" }.ifEmpty { "no paused scripts" },
            )
            "jobs.cancel" -> {
                val arg = a.firstOrNull() ?: return CommandResult.error("usage: cancel job <id|all>")
                if (arg.equals("all", true)) {
                    CommandResult.ok("${engine.cancelAllJobs()} job(s) stopped")
                } else {
                    val id = arg.toIntOrNull() ?: return CommandResult.error("usage: cancel job <id|all>")
                    if (engine.cancelJob(id)) CommandResult.ok("job #$id stopped") else CommandResult.error("no job #$id")
                }
            }
            "vars" -> CommandResult.ok(
                engine.vars.entries.joinToString("\n") { (k, v) -> "$k = " + if (v is Value.Str) "\"${v.v}\"" else v.show() }
                    .ifEmpty { "no variables (create one:  set x = 5)" },
            )
            "unset" -> {
                val name = a.firstOrNull() ?: return CommandResult.error("usage: unset <name>")
                if (engine.vars.remove(name) != null) CommandResult.ok("$name removed") else CommandResult.error("no variable '$name'")
            }
            "names" -> CommandResult.ok(engine.knownNames().joinToString("  "))
            "eval" -> {
                if (a.isEmpty()) return CommandResult.error("usage: eval <expression>")
                CommandResult.ok(display(engine.evalNow(a.joinToString(" "))))
            }
            "status" -> CommandResult.ok(
                "rules waiting: ${engine.rules().size}   paused scripts: ${engine.jobs().size}" +
                    (host?.status()?.let { "\n$it" } ?: ""),
            )
            else -> null
        }
    }

    private fun describeDefs(): String {
        val states = engine.derivedStates()
        val actions = engine.actionDefs()
        if (states.isEmpty() && actions.isEmpty()) {
            return "nothing defined yet\n  state night = time between 10 pm and 6 am\n  def night_mode:\n      volume 10"
        }
        val sb = StringBuilder()
        for (s in states) sb.append(s.source).append('\n')
        for (a in actions) sb.append(a.source).append('\n')
        return sb.toString().trimEnd()
    }

    private fun describeRules(): String {
        val rules = engine.rules()
        if (rules.isEmpty()) return "no waiting rules (create one:  at 10:30 pm: call mom)"
        return rules.joinToString("\n") { r ->
            val first = r.source.lines().first()
            val rest = r.source.lines().drop(1).joinToString("") { "\n        $it" }
            val next = r.nextWakeMillis?.let { "   [next: ${formatTime(it)}]" } ?: "   [waits for an event/state]"
            "#${r.id}  $first$next$rest"
        }
    }

    fun formatTime(millis: Long): String {
        val zone = ZoneOffset.ofTotalSeconds((clock.offsetMillis() / 1000).toInt())
        return DateTimeFormatter.ofPattern("EEE d MMM HH:mm:ss", Locale.ENGLISH).withZone(zone).format(Instant.ofEpochMilli(millis))
    }

    /** Saves rules and variables. */
    fun save(): String = engine.save()

    fun load(text: String) = engine.load(text)
}
