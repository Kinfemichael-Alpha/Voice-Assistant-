package com.dimts.core.script

/** Raised for syntax and run-time errors in scripts. [line] is 1-based within the submitted text. */
class ScriptError(val detail: String, val line: Int? = null) :
    Exception(if (line != null) "line $line: $detail" else detail) {
    fun withLine(l: Int): ScriptError = if (line != null) this else ScriptError(detail, l)
}

/** Every value in the shell language. */
sealed interface Value {
    data class Num(val v: Double) : Value
    data class Str(val v: String) : Value
    data class Bool(val v: Boolean) : Value

    /** A time of day, in minutes since midnight (0..1439). */
    data class Time(val minuteOfDay: Int) : Value

    object Null : Value {
        override fun toString() = "null"
    }
}

fun Value.truthy(): Boolean = when (this) {
    is Value.Bool -> v
    is Value.Num -> v != 0.0
    is Value.Str -> v.isNotEmpty()
    is Value.Time -> true
    Value.Null -> false
}

fun Value.typeName(): String = when (this) {
    is Value.Bool -> "yes/no"
    is Value.Num -> "number"
    is Value.Str -> "text"
    is Value.Time -> "time"
    Value.Null -> "nothing"
}

fun Value.show(): String = when (this) {
    is Value.Num -> if (v == Math.rint(v) && Math.abs(v) < 1e15) v.toLong().toString() else v.toString()
    is Value.Str -> v
    is Value.Bool -> if (v) "true" else "false"
    is Value.Time -> Times.format(minuteOfDay)
    Value.Null -> "null"
}

object Times {
    fun format(minuteOfDay: Int): String {
        val h24 = minuteOfDay / 60
        val m = minuteOfDay % 60
        val h12 = if (h24 % 12 == 0) 12 else h24 % 12
        return "%d:%02d %s".format(h12, m, if (h24 < 12) "am" else "pm")
    }

    /** True if some minute k with (start, end] has k mod 1440 == [minuteOfDay]. Both are "local minutes". */
    fun crossed(startExclusive: Long, endInclusive: Long, minuteOfDay: Int): Boolean {
        if (endInclusive <= startExclusive) return false
        if (endInclusive - startExclusive >= 1440) return true
        val base = startExclusive - Math.floorMod(startExclusive, 1440L) + minuteOfDay
        val k = if (base > startExclusive) base else base + 1440
        return k <= endInclusive
    }

    /** First local minute strictly after [localNow] that falls on [minuteOfDay]. */
    fun nextOccurrence(localNow: Long, minuteOfDay: Int): Long {
        val base = localNow - Math.floorMod(localNow, 1440L) + minuteOfDay
        return if (base > localNow) base else base + 1440
    }
}

object Days {
    val NAMES = listOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")

    /** "mon", "Monday", "MONDAY" -> "monday"; anything else -> null. */
    fun normalize(s: String): String? {
        val t = s.trim().lowercase()
        if (t.length < 3) return null
        return NAMES.firstOrNull { it == t || (t.length == 3 && it.startsWith(t)) }
    }

    /** Day name for a local-minute timestamp. 1970-01-01 was a Thursday. */
    fun nameOf(localMinutes: Long): String {
        val days = Math.floorDiv(localMinutes, 1440L)
        return NAMES[Math.floorMod(days + 3, 7L).toInt()]
    }
}
