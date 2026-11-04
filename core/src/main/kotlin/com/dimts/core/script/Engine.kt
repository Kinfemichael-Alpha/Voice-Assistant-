package com.dimts.core.script

import com.dimts.core.Fuzzy

/** Where a command came from. Voice commands can require confirmation; rules and typed commands do not. */
enum class Origin { CONSOLE, VOICE, RULE }

enum class Level { INFO, OK, WARN, ERROR, OUT, INPUT }

fun interface LogSink {
    fun log(level: Level, text: String)
}

data class CommandResult(val ok: Boolean, val message: String? = null) {
    companion object {
        fun ok(message: String? = null) = CommandResult(true, message)
        fun error(message: String) = CommandResult(false, message)
    }
}

/** Runs one command line (already split into words). The first words name the command. */
fun interface CommandRunner {
    fun run(words: List<String>, origin: Origin): CommandResult
}

data class RuleInfo(
    val id: Int,
    val label: String,
    val source: String,
    /** Next time this rule is known to need attention, or null when it only reacts to events/state. */
    val nextWakeMillis: Long?,
)

data class JobInfo(val id: Int, val label: String, val wakeAtMillis: Long)

/**
 * Runs scripts and keeps waiting rules.
 *
 * There is no thread inside. The host calls [tick] when the clock may have reached something
 * ([nextWakeMillis] says when), and [emit] when something happens (a call ended, the key sound
 * was heard). That keeps the engine deterministic, testable on a plain JVM, and lets Android
 * sleep between alarms instead of polling.
 */
class Engine(
    private val clock: Clock,
    private val env: Environment,
    private val runner: CommandRunner,
    private val log: LogSink,
    private val knownEvents: Set<String> = Builtins.EVENTS,
    private val reserved: (String) -> Boolean = { false },
) {
    val vars = LinkedHashMap<String, Value>()
    private val derived = LinkedHashMap<String, StateDefStmt>()
    private val actions = LinkedHashMap<String, DefStmt>()

    private class Rule(val id: Int, val key: String?, val stmt: RuleStmt) {
        var prev = false
        var windowStart = 0L
        var due = 0L
    }

    private class Frame(
        val stmts: List<Stmt>,
        var remaining: Int = 1,
        val loopCond: Expr? = null,
        val isLoop: Boolean = false,
    ) {
        var idx = 0
    }

    private class Task(val id: Int, val origin: Origin, val ruleId: Int?, val label: String) {
        val frames = ArrayList<Frame>()
        var wakeAt = 0L
    }

    private val rules = LinkedHashMap<Int, Rule>()
    private val sleepers = ArrayList<Task>()
    private var nextRuleId = 1
    private var nextTaskId = 1
    private var pulses: Set<String> = emptySet()
    private var changeListener: (() -> Unit)? = null

    /** Called whenever rules or sleeping jobs change, so the host can save state and re-arm its alarm. */
    fun setOnChange(listener: (() -> Unit)?) {
        changeListener = listener
    }

    private fun changed() = changeListener?.invoke()

    // ---- time helpers ----------------------------------------------------------------------

    private fun localMinutes(): Long = Math.floorDiv(clock.nowMillis() + clock.offsetMillis(), 60_000L)

    private fun context(windowStart: Long? = null): EvalContext {
        val cur = localMinutes()
        return EvalContext(vars, env, cur, windowStart ?: (cur - 1), pulses, knownEvents, derived)
    }

    fun evalNow(expr: String): Value = Evaluator.eval(Parser.parseExpression(expr), context())

    // ---- public API ------------------------------------------------------------------------

    /**
     * Parses and runs [source] (one or many lines). Returns null on success, or the syntax error
     * (already written to the log).
     */
    fun submit(source: String, origin: Origin = Origin.CONSOLE): ScriptError? {
        val program = try {
            Parser.parse(source)
        } catch (e: ScriptError) {
            log.log(Level.ERROR, e.message ?: "syntax error")
            return e
        }
        validate(program).forEach { log.log(Level.WARN, it) }
        val task = Task(nextTaskId++, origin, null, "console")
        task.frames.add(Frame(program.stmts))
        run(task)
        return null
    }

    /** Evaluates timers, waiting conditions and sleeping jobs against the current time and state. */
    fun tick() {
        evaluateRules()
        val now = clock.nowMillis()
        val due = sleepers.filter { it.wakeAt <= now }
        if (due.isNotEmpty()) {
            sleepers.removeAll(due.toSet())
            due.forEach { run(it) }
            changed()
        }
    }

    /** Delivers a one-shot event such as `call.ended`. Rules see it as true during this call only. */
    fun emit(event: String) {
        val saved = pulses
        pulses = saved + event
        try {
            evaluateRules()
        } finally {
            pulses = saved
        }
        evaluateRules() // let "prev" fall back to false so the next event can fire again
    }

    fun derivedStates(): List<StateDefStmt> = derived.values.toList()

    fun actionDefs(): List<DefStmt> = actions.values.toList()

    fun hasAction(name: String): Boolean = actions.containsKey(name.lowercase())

    fun removeDefinition(name: String): Boolean {
        val removedAction = actions.remove(name.lowercase()) != null
        val removedState = derived.remove(name) != null
        if (removedAction || removedState) changed()
        return removedAction || removedState
    }

    fun needsPolling(): Boolean =
        rules.values.any { r -> r.stmt.kind == RuleKind.WHEN && r.stmt.cond?.let { usesState(it) } == true }

    private fun usesState(e: Expr): Boolean = when (e) {
        is Name -> {
            val d = derived[e.name]
            when {
                d != null -> usesState(d.expr)
                e.name in knownEvents || e.name in vars -> false
                else -> '.' in e.name || e.name in env.names() || e.name == "random"
            }
        }
        is Not -> usesState(e.e)
        is Neg -> usesState(e.e)
        is Bin -> usesState(e.l) || usesState(e.r)
        is Between -> usesState(e.e) || usesState(e.lo) || usesState(e.hi)
        is Lit -> false
    }

    fun rules(): List<RuleInfo> = rules.values.map { RuleInfo(it.id, it.stmt.label, it.stmt.source, wakeOf(it)) }

    fun jobs(): List<JobInfo> = sleepers.map { JobInfo(it.id, it.label, it.wakeAt) }

    fun cancelRule(id: Int): Boolean {
        val removed = rules.remove(id) != null
        if (removed) changed()
        return removed
    }

    fun clearRules(): Int {
        val n = rules.size
        rules.clear()
        if (n > 0) changed()
        return n
    }

    fun cancelJob(id: Int): Boolean {
        val removed = sleepers.removeAll { it.id == id }
        if (removed) changed()
        return removed
    }

    fun cancelAllJobs(): Int {
        val n = sleepers.size
        sleepers.clear()
        if (n > 0) changed()
        return n
    }

    /** Earliest moment the host must call [tick] again, or null if nothing is time-driven. */
    fun nextWakeMillis(): Long? {
        var best: Long? = null
        fun consider(t: Long?) {
            if (t != null && (best == null || t < best!!)) best = t
        }
        sleepers.forEach { consider(it.wakeAt) }
        rules.values.forEach { consider(wakeOf(it)) }
        return best
    }

    private fun wakeOf(r: Rule): Long? {
        if (r.stmt.kind == RuleKind.TIMER) return r.due
        val cond = r.stmt.cond ?: return null
        if (!usesTime(cond)) return null
        val localNow = localMinutes()
        var best: Long? = null
        for (m in timeLiterals(cond)) {
            val k = Times.nextOccurrence(localNow, m)
            val millis = k * 60_000L - clock.offsetMillis() + 500
            if (best == null || millis < best!!) best = millis
        }
        return best
    }

    private fun usesTime(e: Expr): Boolean = when (e) {
        is Name -> e.name == "time" || (derived[e.name]?.let { usesTime(it.expr) } ?: false)
        is Not -> usesTime(e.e)
        is Neg -> usesTime(e.e)
        is Bin -> usesTime(e.l) || usesTime(e.r)
        is Between -> usesTime(e.e) || usesTime(e.lo) || usesTime(e.hi)
        is Lit -> false
    }

    private fun timeLiterals(e: Expr, out: MutableList<Int> = ArrayList()): List<Int> {
        when (e) {
            is Lit -> (e.value as? Value.Time)?.let { out.add(it.minuteOfDay) }
            is Not -> timeLiterals(e.e, out)
            is Neg -> timeLiterals(e.e, out)
            is Bin -> { timeLiterals(e.l, out); timeLiterals(e.r, out) }
            is Between -> { timeLiterals(e.e, out); timeLiterals(e.lo, out); timeLiterals(e.hi, out) }
            is Name -> { derived[e.name]?.let { timeLiterals(it.expr, out) } }
        }
        return out
    }

    private fun dependsOn(expr: Expr, target: String, seen: MutableSet<String> = HashSet()): Boolean {
        for (n in Parser.namesUsed(listOf(SetStmt(0, "_", expr)))) {
            if (n == target) return true
            val d = derived[n] ?: continue
            if (seen.add(n) && dependsOn(d.expr, target, seen)) return true
        }
        return false
    }

    // ---- validation ------------------------------------------------------------------------

    /** Warnings for names that can never have a value (typos like `mom.picked_up_call`). */
    fun validate(program: Program): List<String> {
        val known = HashSet<String>()
        known.addAll(vars.keys)
        known.addAll(Builtins.NAMES)
        known.addAll(env.names())
        known.addAll(knownEvents)
        known.addAll(derived.keys)
        known.addAll(Parser.assignedNames(program.stmts))
        val warnings = ArrayList<String>()
        for (n in Parser.namesUsed(program.stmts)) {
            if ('.' !in n || n in known) continue
            val hint = Fuzzy.closest(n, known.filter { '.' in it })
            warnings.add(
                "unknown name '$n' - it will always be empty" +
                    (hint?.let { " (did you mean '$it'?)" } ?: " (type 'names' to list what exists)"),
            )
        }
        return warnings
    }

    fun knownNames(): List<String> = (Builtins.NAMES + env.names() + knownEvents + derived.keys + vars.keys).sorted()

    // ---- rules -----------------------------------------------------------------------------

    private fun registerRule(stmt: RuleStmt, ownerRuleId: Int?, id: Int? = null, due: Long? = null): Rule {
        val key = ownerRuleId?.let { "$it:${stmt.line}:${stmt.source.hashCode()}" }
        if (key != null) rules.values.removeAll { it.key == key }
        val rule = Rule(id ?: nextRuleId, key, stmt)
        nextRuleId = maxOf(nextRuleId, rule.id + 1)
        val now = clock.nowMillis()
        if (stmt.kind == RuleKind.TIMER) {
            rule.due = due ?: (now + stmt.delayMillis)
        } else {
            rule.windowStart = localMinutes()
            rule.prev = try {
                Evaluator.eval(stmt.cond!!, context()).truthy()
            } catch (e: ScriptError) {
                false
            }
        }
        rules[rule.id] = rule
        changed()
        return rule
    }

    private fun evaluateRules() {
        val now = clock.nowMillis()
        val cur = localMinutes()
        for (rule in rules.values.toList()) {
            if (rules[rule.id] !== rule) continue // removed by an earlier body
            try {
                val fire: Boolean
                if (rule.stmt.kind == RuleKind.TIMER) {
                    fire = now >= rule.due
                } else {
                    val v = Evaluator.eval(rule.stmt.cond!!, context(rule.windowStart)).truthy()
                    fire = v && !rule.prev
                    rule.prev = v
                    rule.windowStart = cur
                }
                if (fire) fireRule(rule, now)
            } catch (e: ScriptError) {
                rules.remove(rule.id)
                changed()
                log.log(Level.ERROR, "rule #${rule.id} removed: ${e.detail}")
            }
        }
    }

    private fun fireRule(rule: Rule, now: Long) {
        val s = rule.stmt
        if (s.kind == RuleKind.TIMER && s.repeat) {
            rule.due += s.delayMillis
            if (rule.due <= now) rule.due = now + s.delayMillis
        } else if (!s.repeat) {
            rules.remove(rule.id)
        }
        changed()
        log.log(Level.INFO, "rule #${rule.id} fired: ${s.label}")
        val task = Task(nextTaskId++, Origin.RULE, rule.id, s.label)
        task.frames.add(Frame(s.body))
        run(task)
    }

    // ---- interpreter -----------------------------------------------------------------------

    private fun say(task: Task, level: Level, text: String) {
        log.log(level, if (task.ruleId != null) "[#${task.ruleId}] $text" else text)
    }

    private fun run(task: Task) {
        var steps = 0
        try {
            while (task.frames.isNotEmpty()) {
                if (++steps > MAX_STEPS) throw ScriptError("stopped: too many steps (is something looping?)")
                if (task.frames.size > MAX_DEPTH) throw ScriptError("stopped: nested too deeply (does an action call itself forever?)")
                val frame = task.frames.last()
                if (frame.idx >= frame.stmts.size) {
                    if (frame.loopCond != null) {
                        if (Evaluator.eval(frame.loopCond, context()).truthy()) {
                            frame.idx = 0
                        } else {
                            task.frames.removeAt(task.frames.size - 1)
                        }
                    } else if (frame.remaining > 1) {
                        frame.remaining--
                        frame.idx = 0
                    } else {
                        task.frames.removeAt(task.frames.size - 1)
                    }
                    continue
                }
                val stmt = frame.stmts[frame.idx++]
                try {
                    if (!step(task, stmt)) return // suspended by wait
                } catch (e: ScriptError) {
                    throw e.withLine(stmt.line)
                }
            }
        } catch (e: ScriptError) {
            task.frames.clear()
            say(task, Level.ERROR, e.message ?: "error")
        }
    }

    /** Executes one statement. Returns false when the task went to sleep. */
    private fun step(task: Task, stmt: Stmt): Boolean {
        when (stmt) {
            is CommandStmt -> {
                val c = context()
                val words = stmt.words.map { Evaluator.text(it, c) }
                val action = words.firstOrNull()?.let { actions[it.lowercase()] }
                if (action != null) {
                    vars.keys.removeAll { it == "args" || ARG_VAR.matches(it) }
                    words.drop(1).forEachIndexed { i, w -> vars["arg${i + 1}"] = Value.Str(w) }
                    vars["args"] = Value.Num((words.size - 1).toDouble())
                    task.frames.add(Frame(action.body))
                    return true
                }
                val result = try {
                    runner.run(words, task.origin)
                } catch (e: ScriptError) {
                    throw e
                } catch (e: Exception) {
                    CommandResult.error(e.message ?: e.toString())
                }
                if (result.ok) {
                    result.message?.takeIf { it.isNotEmpty() }?.let { say(task, Level.OUT, it) }
                } else {
                    throw ScriptError(result.message ?: "command failed")
                }
            }
            is IfStmt -> {
                val c = context()
                val hit = stmt.branches.firstOrNull { Evaluator.eval(it.cond, c).truthy() }
                val body = hit?.body ?: stmt.elseBody
                if (body != null) task.frames.add(Frame(body))
            }
            is RuleStmt -> {
                val rule = registerRule(stmt, task.ruleId)
                say(task, Level.INFO, "rule #${rule.id} waiting: ${stmt.label}")
            }
            is WaitStmt -> {
                if (stmt.millis > 0) {
                    task.wakeAt = clock.nowMillis() + stmt.millis
                    sleepers.add(task)
                    changed()
                    return false
                }
            }
            is RepeatStmt -> {
                val n = Evaluator.eval(stmt.count, context())
                val count = (n as? Value.Num)?.v?.toInt()
                    ?: throw ScriptError("repeat needs a number, not ${n.typeName()}")
                if (count > MAX_REPEAT) throw ScriptError("repeat is limited to $MAX_REPEAT times")
                if (count > 0) task.frames.add(Frame(stmt.body, count, null, true))
            }
            is WhileStmt -> {
                if (Evaluator.eval(stmt.cond, context()).truthy()) task.frames.add(Frame(stmt.body, 1, stmt.cond, true))
            }
            is BreakStmt -> if (!unwindLoop(task, true)) throw ScriptError("'break' only works inside 'repeat' or 'while'")
            is ContinueStmt -> if (!unwindLoop(task, false)) throw ScriptError("'continue' only works inside 'repeat' or 'while'")
            is DefStmt -> defineAction(stmt, task)
            is StateDefStmt -> defineState(stmt, task)
            is SetStmt -> {
                if (derived.containsKey(stmt.name)) throw ScriptError("'${stmt.name}' is a defined state - use 'undef ${stmt.name}' first")
                vars[stmt.name] = Evaluator.eval(stmt.expr, context())
                changed()
            }
            is PassStmt -> {}
            is StopStmt -> task.frames.clear()
        }
        return true
    }

    private fun unwindLoop(task: Task, exit: Boolean): Boolean {
        val i = task.frames.indexOfLast { it.isLoop }
        if (i < 0) return false
        while (task.frames.size > i + 1) task.frames.removeAt(task.frames.size - 1)
        val loop = task.frames[i]
        if (exit) task.frames.removeAt(i) else loop.idx = loop.stmts.size
        return true
    }

    private fun defineAction(stmt: DefStmt, task: Task) {
        val key = stmt.name.lowercase()
        if (reserved(key)) throw ScriptError("'${stmt.name}' is already a command - choose another name")
        actions[key] = stmt
        changed()
        say(task, Level.INFO, "action '${stmt.name}' defined  (run it by typing its name)")
    }

    private fun defineState(stmt: StateDefStmt, task: Task) {
        val n = stmt.name
        if (n in Builtins.NAMES || n in env.names() || n in knownEvents) throw ScriptError("'$n' is a built-in name - choose another")
        if (vars.containsKey(n)) throw ScriptError("'$n' is a variable - use 'unset $n' first")
        if (dependsOn(stmt.expr, n)) throw ScriptError("'$n' cannot be defined in terms of itself")
        derived[n] = stmt
        changed()
        say(task, Level.INFO, "state '$n' defined  (use it in if / when / while)")
    }

    // ---- persistence -----------------------------------------------------------------------

    /** Serialises variables and waiting rules (not running `wait` jobs). */
    fun save(): String {
        val sb = StringBuilder("dimts-state 1\n")
        for ((k, v) in vars) {
            if (k == "args" || ARG_VAR.matches(k)) continue
            val (type, text) = when (v) {
                is Value.Num -> "N" to v.v.toString()
                is Value.Str -> "S" to v.v
                is Value.Bool -> "B" to v.v.toString()
                is Value.Time -> "T" to v.minuteOfDay.toString()
                Value.Null -> "Z" to ""
            }
            sb.append("V\t").append(esc(k)).append('\t').append(type).append('\t').append(esc(text)).append('\n')
        }
        for (s in derived.values) sb.append("S\t").append(esc(s.source)).append('\n')
        for (a in actions.values) sb.append("D\t").append(esc(a.source)).append('\n')
        for (r in rules.values) {
            sb.append("R\t").append(r.id).append('\t').append(r.due).append('\t').append(r.windowStart).append('\t')
                .append(esc(r.key ?: "")).append('\t').append(esc(r.stmt.source)).append('\n')
        }
        return sb.toString()
    }

    /**
     * Restores state written by [save]. Waiting time-of-day rules catch up on moments missed
     * while the app was not running, but only up to [CATCH_UP_MINUTES] back.
     */
    fun load(text: String) {
        val lines = text.split('\n')
        if (lines.firstOrNull()?.startsWith("dimts-state") != true) return
        val cur = localMinutes()
        for (line in lines.drop(1)) {
            if (line.isBlank()) continue
            val f = line.split('\t')
            try {
                when (f[0]) {
                    "V" -> vars[unesc(f[1])] = when (f[2]) {
                        "N" -> Value.Num(unesc(f[3]).toDouble())
                        "B" -> Value.Bool(unesc(f[3]) == "true")
                        "T" -> Value.Time(unesc(f[3]).toInt())
                        "Z" -> Value.Null
                        else -> Value.Str(unesc(f[3]))
                    }
                    "S" -> {
                        val def = Parser.parse(unesc(f[1])).stmts.singleOrNull() as? StateDefStmt
                        if (def != null) derived[def.name] = def
                    }
                    "D" -> {
                        val def = Parser.parse(unesc(f[1])).stmts.singleOrNull() as? DefStmt
                        if (def != null) actions[def.name.lowercase()] = def
                    }
                    "R" -> {
                        val program = Parser.parse(unesc(f[5]))
                        val stmt = program.stmts.singleOrNull() as? RuleStmt ?: continue
                        val id = f[1].toInt()
                        val rule = registerRule(stmt, null, id, f[2].toLong())
                        val keyText = unesc(f[4])
                        val restored = if (keyText.isNotEmpty()) Rule(id, keyText, stmt).also {
                            it.due = rule.due; it.prev = rule.prev; it.windowStart = rule.windowStart
                        } else rule
                        restored.windowStart = maxOf(f[3].toLong(), cur - CATCH_UP_MINUTES)
                        rules[id] = restored
                    }
                }
            } catch (e: Exception) {
                log.log(Level.WARN, "could not restore a saved line: ${e.message}")
            }
        }
    }

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\n", "\\n").replace("\t", "\\t")

    private fun unesc(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                i++
                sb.append(
                    when (s[i]) {
                        'n' -> '\n'
                        't' -> '\t'
                        else -> s[i]
                    },
                )
            } else {
                sb.append(c)
            }
            i++
        }
        return sb.toString()
    }

    companion object {
        const val MAX_STEPS = 100_000
        const val MAX_REPEAT = 1_000
        const val MAX_DEPTH = 200
        const val CATCH_UP_MINUTES = 120L
        private val ARG_VAR = Regex("arg\\d+")
    }
}
