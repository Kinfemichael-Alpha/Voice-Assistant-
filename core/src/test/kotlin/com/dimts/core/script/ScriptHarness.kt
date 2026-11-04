package com.dimts.core.script

import java.time.LocalDateTime
import java.time.ZoneOffset

/** Test doubles and a tiny harness: a controllable clock, a fake phone and a recording runner. */
internal class FakeClock(var now: Long) : Clock {
    override fun nowMillis() = now
    override fun offsetMillis() = OFFSET_MS

    fun set(day: Int, hour: Int, minute: Int, second: Int = 0) {
        now = LocalDateTime.of(2026, 11, day, hour, minute, second).toInstant(ZoneOffset.ofHours(3)).toEpochMilli()
    }

    fun advanceSeconds(s: Long) {
        now += s * 1000
    }

    companion object {
        const val OFFSET_MS = 3 * 3_600_000L
    }
}

internal class FakeEnv : Environment {
    val values = HashMap<String, Value>()
    override fun lookup(name: String): Value? = values[name]
    override fun names(): Set<String> = values.keys
}

internal class Harness(startDay: Int = 2, startHour: Int = 20, startMinute: Int = 0) {
    val clock = FakeClock(0).also { it.set(startDay, startHour, startMinute) }
    val env = FakeEnv()
    val commands = ArrayList<List<String>>()
    val origins = ArrayList<Origin>()
    val logs = ArrayList<Pair<Level, String>>()
    var failOn: String? = null

    val engine = Engine(
        clock,
        env,
        { words, origin ->
            commands.add(words)
            origins.add(origin)
            if (failOn != null && words.firstOrNull() == failOn) CommandResult.error("boom") else CommandResult.ok()
        },
        { level, text -> logs.add(level to text) },
    )

    /** Commands as plain strings, e.g. "call mom". */
    val ran: List<String> get() = commands.map { it.joinToString(" ") }

    fun run(script: String): ScriptError? = engine.submit(script.trimIndent())

    fun at(hour: Int, minute: Int, day: Int = 2, second: Int = 0) {
        clock.set(day, hour, minute, second)
        engine.tick()
    }

    fun logText(level: Level): List<String> = logs.filter { it.first == level }.map { it.second }
}
