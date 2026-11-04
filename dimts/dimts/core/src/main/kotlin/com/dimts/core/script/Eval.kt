package com.dimts.core.script

/** Wall-clock source. Tests use a fake; Android uses the system clock and time zone. */
interface Clock {
    fun nowMillis(): Long

    /** Offset of the local time zone from UTC at the current moment. */
    fun offsetMillis(): Long
}

/** Read-only view of the phone's state (battery, call state, ...) provided by the app. */
interface Environment {
    fun lookup(name: String): Value?
    fun names(): Set<String>
}

object EmptyEnvironment : Environment {
    override fun lookup(name: String): Value? = null
    override fun names(): Set<String> = emptySet()
}

object Builtins {
    /** Names the engine itself provides. */
    val NAMES = setOf("time", "hour", "minute", "day")

    /** One-shot events the app emits; each is true only while it is being delivered. */
    val EVENTS = setOf(
        "call.incoming", "call.answered", "call.started", "call.ended",
        "key.heard", "assistant.started", "assistant.stopped",
        "screen.on", "screen.off", "power.connected", "power.disconnected", "boot",
    )
}

class EvalContext(
    val vars: Map<String, Value>,
    val env: Environment,
    /** "Now" as local minutes since the epoch. */
    val localMinutes: Long,
    /** Exclusive start of the window in which a `time is X` comparison may have been crossed. */
    val windowStart: Long,
    val pulses: Set<String>,
    val knownEvents: Set<String>,
)

object Evaluator {
    fun eval(e: Expr, c: EvalContext): Value = when (e) {
        is Lit -> e.value
        is Name -> lookup(e.name, c)
        is Not -> Value.Bool(!eval(e.e, c).truthy())
        is Neg -> when (val v = eval(e.e, c)) {
            is Value.Num -> Value.Num(-v.v)
            else -> throw ScriptError("cannot negate ${v.typeName()}")
        }
        is Between -> between(eval(e.e, c), eval(e.lo, c), eval(e.hi, c))
        is Bin -> binary(e, c)
    }

    fun lookup(name: String, c: EvalContext): Value {
        c.vars[name]?.let { return it }
        when (name) {
            "time" -> return Value.Time(Math.floorMod(c.localMinutes, 1440L).toInt())
            "hour" -> return Value.Num((Math.floorMod(c.localMinutes, 1440L) / 60).toDouble())
            "minute" -> return Value.Num((Math.floorMod(c.localMinutes, 60L)).toDouble())
            "day" -> return Value.Str(Days.nameOf(c.localMinutes))
        }
        if (name in c.pulses) return Value.Bool(true)
        c.env.lookup(name)?.let { return it }
        if (name in c.knownEvents) return Value.Bool(false)
        // Dotted names that nobody provides are "nothing"; bare words are just text (day is monday).
        return if ('.' in name) Value.Null else Value.Str(name)
    }

    fun text(word: Word, c: EvalContext): String {
        val sb = StringBuilder()
        for (p in word.parts) when (p) {
            is Part.Text -> sb.append(p.s)
            is Part.Ex -> sb.append(eval(p.e, c).show())
        }
        return sb.toString()
    }

    private fun binary(e: Bin, c: EvalContext): Value {
        if (e.op == "and") return Value.Bool(eval(e.l, c).truthy() && eval(e.r, c).truthy())
        if (e.op == "or") return Value.Bool(eval(e.l, c).truthy() || eval(e.r, c).truthy())

        val l = eval(e.l, c)
        val r = eval(e.r, c)
        return when (e.op) {
            "==", "!=" -> {
                val eq = if (e.l is Name && e.l.name == "time" && r is Value.Time && e.l.name !in c.vars) {
                    Times.crossed(c.windowStart, c.localMinutes, r.minuteOfDay)
                } else {
                    equal(l, r)
                }
                Value.Bool(if (e.op == "==") eq else !eq)
            }
            "<", "<=", ">", ">=" -> Value.Bool(order(e.op, l, r))
            "contains" -> Value.Bool(l.show().contains(r.show(), ignoreCase = true))
            "+" -> plus(l, r)
            "-" -> minus(l, r)
            "*", "/", "%" -> {
                val a = num(l, e.op)
                val b = num(r, e.op)
                if ((e.op == "/" || e.op == "%") && b == 0.0) throw ScriptError("division by zero")
                Value.Num(
                    when (e.op) {
                        "*" -> a * b
                        "/" -> a / b
                        else -> a % b
                    },
                )
            }
            else -> throw ScriptError("unknown operator '${e.op}'")
        }
    }

    private fun num(v: Value, op: String): Double = when (v) {
        is Value.Num -> v.v
        is Value.Str -> v.v.trim().toDoubleOrNull() ?: throw ScriptError("'$op' needs numbers, but '${v.v}' is text")
        else -> throw ScriptError("'$op' needs numbers, not ${v.typeName()}")
    }

    private fun plus(l: Value, r: Value): Value = when {
        l is Value.Time && r is Value.Num -> Value.Time(Math.floorMod(l.minuteOfDay + r.v.toInt(), 1440))
        l is Value.Num && r is Value.Num -> Value.Num(l.v + r.v)
        l is Value.Str || r is Value.Str -> Value.Str(l.show() + r.show())
        else -> throw ScriptError("cannot add ${l.typeName()} and ${r.typeName()}")
    }

    private fun minus(l: Value, r: Value): Value = when {
        l is Value.Time && r is Value.Time -> Value.Num((l.minuteOfDay - r.minuteOfDay).toDouble())
        l is Value.Time && r is Value.Num -> Value.Time(Math.floorMod(l.minuteOfDay - r.v.toInt(), 1440))
        else -> Value.Num(num(l, "-") - num(r, "-"))
    }

    private fun equal(l: Value, r: Value): Boolean = when {
        l is Value.Num && r is Value.Num -> l.v == r.v
        l is Value.Time && r is Value.Time -> l.minuteOfDay == r.minuteOfDay
        l is Value.Bool && r is Value.Bool -> l.v == r.v
        l is Value.Str && r is Value.Str -> sameText(l.v, r.v)
        l is Value.Num && r is Value.Str -> r.v.trim().toDoubleOrNull() == l.v
        l is Value.Str && r is Value.Num -> l.v.trim().toDoubleOrNull() == r.v
        l is Value.Bool && r is Value.Str -> l.v == (r.v.equals("true", true) || r.v.equals("yes", true))
        l is Value.Str && r is Value.Bool -> r.v == (l.v.equals("true", true) || l.v.equals("yes", true))
        l === Value.Null && r === Value.Null -> true
        else -> false
    }

    private fun sameText(a: String, b: String): Boolean {
        if (a.equals(b, ignoreCase = true)) return true
        val da = Days.normalize(a)
        return da != null && da == Days.normalize(b)
    }

    private fun order(op: String, l: Value, r: Value): Boolean {
        val cmp: Int = when {
            l is Value.Num && r is Value.Num -> l.v.compareTo(r.v)
            l is Value.Time && r is Value.Time -> l.minuteOfDay.compareTo(r.minuteOfDay)
            l is Value.Str && r is Value.Str -> {
                val a = l.v.toDoubleOrNull()
                val b = r.v.toDoubleOrNull()
                if (a != null && b != null) a.compareTo(b) else l.v.compareTo(r.v, ignoreCase = true)
            }
            l is Value.Num && r is Value.Str -> l.v.compareTo(r.v.toDoubleOrNull() ?: return false)
            l is Value.Str && r is Value.Num -> (l.v.toDoubleOrNull() ?: return false).compareTo(r.v)
            else -> return false
        }
        return when (op) {
            "<" -> cmp < 0
            "<=" -> cmp <= 0
            ">" -> cmp > 0
            else -> cmp >= 0
        }
    }

    private fun between(v: Value, lo: Value, hi: Value): Value {
        if (v is Value.Time && lo is Value.Time && hi is Value.Time) {
            val x = v.minuteOfDay
            val ok = if (lo.minuteOfDay <= hi.minuteOfDay) x in lo.minuteOfDay..hi.minuteOfDay
            else x >= lo.minuteOfDay || x <= hi.minuteOfDay // wraps past midnight: 22:00 .. 06:00
            return Value.Bool(ok)
        }
        return Value.Bool(order(">=", v, lo) && order("<=", v, hi))
    }
}
