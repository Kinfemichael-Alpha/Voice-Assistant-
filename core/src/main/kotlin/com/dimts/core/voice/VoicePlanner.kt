package com.dimts.core.voice

import com.dimts.core.command.CommandRegistry

/** What a spoken sentence means to the assistant. */
sealed interface Plan {
    /** [line] is a console command line; [say] is a short human description ("Calling mom"). */
    data class Run(val line: String, val say: String) : Plan

    data object Dismiss : Plan
    data object Yes : Plan
    data object No : Plan
    data class Unknown(val heard: String) : Plan
}

/**
 * Turns the writer's transcript into a console command line. Pure rules, no AI: predictable,
 * instant, and every phrase that works is listed here. Extend [rules] or [AmharicLexicon].
 */
class VoicePlanner(private val registry: CommandRegistry) {

    fun plan(transcript: String): Plan {
        val text = Normalizer.normalize(transcript)
        if (text.isEmpty()) return Plan.Unknown("")

        phrase(text)?.let { return it }

        val hasEthiopic = text.any { Normalizer.isEthiopic(it) }
        val stripped = stripFillers(text)
        val result = if (hasEthiopic) {
            AmharicLexicon.plan(stripped) ?: english(stripped)
        } else {
            english(stripped) ?: AmharicLexicon.plan(stripped)
        }
        return result ?: Plan.Unknown(text)
    }

    private fun phrase(text: String): Plan? = when {
        text in YES || text in AmharicLexicon.yes -> Plan.Yes
        text in NO || text in AmharicLexicon.no -> Plan.No
        text in DISMISS || text in AmharicLexicon.dismiss -> Plan.Dismiss
        else -> null
    }

    private fun stripFillers(text: String): String {
        var t = text
        var changed = true
        while (changed) {
            changed = false
            for (f in LEADING_FILLERS) {
                if (t.startsWith("$f ")) {
                    t = t.substring(f.length + 1)
                    changed = true
                }
            }
        }
        for (f in TRAILING_FILLERS) if (t.endsWith(" $f")) t = t.removeSuffix(" $f")
        return t.trim()
    }

    private fun english(t: String): Plan? {
        for ((regex, build) in rules) {
            val m = regex.matchEntire(t) ?: continue
            val plan = build(m)
            if (plan != null) return plan
        }
        return null
    }

    private fun run(line: String, say: String): Plan = Plan.Run(line, say)

    private val rules: List<Pair<Regex, (MatchResult) -> Plan?>> = listOf(
        // power-user passthrough:  "run status"
        r("(?:run|execute) (.+)") { run(it.groupValues[1], "Running ${it.groupValues[1]}") },

        // phone
        r("(?:send (?:a |an )?(?:text message |text |sms |message )?(?:to )?|text |sms |message )(.+?) (?:saying|that says|and say|say|with message|with) (.+)") {
            val who = cleanName(it.groupValues[1])
            run("sms ${q(who)} ${q(it.groupValues[2])}", "Texting $who")
        },
        r("(?:call|dial|phone|ring)(?: up)?(?: to)? (.+)") {
            val who = cleanName(it.groupValues[1])
            run("call ${q(who)}", "Calling $who")
        },
        r("hang up|hangup|end (?:the )?call|decline(?: the call)?|reject(?: the call)?|cut (?:the )?call") { run("hangup", "Ending the call") },
        r("(?:answer|pick up)(?: the)?(?: call| phone)?") { run("answer", "Answering") },

        // screen
        r("(?:go )?back|close(?: this| the)?(?: app| window| screen)?") { run("back", "Going back") },
        r("(?:go )?home(?: screen)?|go to (?:the )?home(?: screen)?") { run("home", "Home") },
        r("(?:show |open )?(?:the )?recent(?:s| apps)?|(?:show |open )?recent apps") { run("recents", "Recent apps") },
        r("(?:show |open |pull down )?(?:the )?notifications?") { run("notifications", "Notifications") },
        r("lock(?: the)?(?: phone| screen| device)?") { run("lock", "Locking") },
        r("(?:take (?:a )?)?screen ?shot") { run("screenshot", "Screenshot") },
        r("read(?: the| this)?(?: screen)?|what(?:'s| is) on (?:the |my )?screen") { run("read screen", "Reading the screen") },
        r("scroll (up|down)") { run("scroll ${it.groupValues[1]}", "Scrolling ${it.groupValues[1]}") },
        r("(?:swipe|slide) (left|right|up|down)") { run("swipe ${it.groupValues[1]}", "Swiping ${it.groupValues[1]}") },
        r("(?:tap|click|press|touch)(?: on)?(?: the)? (.+)") { run("tap ${q(it.groupValues[1])}", "Tapping ${it.groupValues[1]}") },
        r("(?:type|write|enter) (.+)") { run("type ${q(it.groupValues[1])}", "Typing") },

        // sound & media
        r("(?:turn |make )?(?:the )?volume (up|down)") { run("volume ${it.groupValues[1]}", "Volume ${it.groupValues[1]}") },
        r("(?:turn it |make it )?(?:up|louder)|louder|turn it up") { run("volume up", "Louder") },
        r("(?:turn it |make it )?(?:down|quieter|softer)|quieter|softer|turn it down") { run("volume down", "Quieter") },
        r("(?:set |put )?(?:the )?volume(?: to| at)? (.+?)(?: percent| %)?") {
            NumberWords.parse(it.groupValues[1])?.takeIf { n -> n in 0..100 }?.let { n -> run("volume $n", "Volume $n") }
        },
        r("mute|silence|be quiet|shut up") { run("volume mute", "Muted") },
        r("unmute") { run("volume unmute", "Unmuted") },
        r("(?:play|resume)(?: the)?(?: music| song| media)?") { run("play", "Play") },
        r("pause(?: the)?(?: music| song| media)?") { run("pause", "Pause") },
        r("(?:next|skip)(?: song| track)?") { run("next", "Next") },
        r("(?:previous|last)(?: song| track)?|go back a song") { run("previous", "Previous") },
        r("stop(?: the)? (?:music|song|media)") { run("stop music", "Stopped") },
        r("(?:turn |switch )?(on|off)(?: the)? (?:flashlight|torch)|(?:turn |switch )(?:the )?(?:flashlight|torch) (on|off)") {
            val state = it.groupValues[1].ifEmpty { it.groupValues[2] }
            run("flashlight $state", "Flashlight $state")
        },
        r("(?:flashlight|torch)") { run("flashlight toggle", "Flashlight") },

        // information
        r("what(?:'s| is) the time|what time is it|tell me the time|time") { run("say \"It is {time}\"", "Time") },
        r("(?:what(?:'s| is) the )?battery(?: level| status)?|how much battery(?: is left| do i have)?") {
            run("say \"Battery is {battery.level} percent\"", "Battery")
        },
        r("help|what can you do|commands") { run("help", "Help") },

        // settings, search, apps
        r("(?:open )?(wi ?fi|bluetooth|display|sound|battery|apps|location|accessibility|airplane) settings") {
            run("settings ${it.groupValues[1].replace(" ", "")}", "Opening settings")
        },
        r("(?:open )?settings") { run("settings", "Opening settings") },
        r("(?:search|google|look up|find)(?: for)?(?: the)? (.+)") { run("search ${q(it.groupValues[1])}", "Searching ${it.groupValues[1]}") },
        r("(?:go to|browse|visit) (\\S+\\.\\S+)") { run("browse ${q(it.groupValues[1])}", "Opening ${it.groupValues[1]}") },
        r("(?:open|launch|go to|show me)(?: the)?(?: app)? (.+)") {
            run("open ${q(it.groupValues[1])}", "Opening ${it.groupValues[1]}")
        },
    )

    private fun r(pattern: String, build: (MatchResult) -> Plan?): Pair<Regex, (MatchResult) -> Plan?> =
        Regex(pattern) to build

    private fun cleanName(raw: String): String = raw.removePrefix("my ").removePrefix("the ").trim()

    companion object {
        private val YES = setOf("yes", "yeah", "yep", "yup", "sure", "ok", "okay", "confirm", "confirmed", "do it", "go ahead", "affirmative", "correct", "yes please")
        private val NO = setOf("no", "nope", "nah", "don't", "do not", "negative", "not now", "wrong", "no thanks")
        private val DISMISS = setOf("stop", "cancel", "never mind", "nevermind", "dismiss", "that's all", "thats all", "that is all", "thank you", "thanks", "goodbye", "bye", "go to sleep", "sleep", "exit", "quit", "done")
        private val LEADING_FILLERS = listOf(
            "please", "hey", "hi", "ok", "okay", "can you", "could you", "would you", "will you",
            "i want you to", "i want to", "i would like to", "i'd like to", "i need to", "go ahead and",
        )
        private val TRAILING_FILLERS = listOf("please", "now", "for me", "thanks")

        /** Quotes [s] as one safe console word. */
        fun q(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("{", "\\{") + "\""
    }

    private fun q(s: String): String = Companion.q(s)
}

object Normalizer {
    fun isEthiopic(c: Char): Boolean = c.code in 0x1200..0x137F

    /** Lower-cases, folds Amharic homophones, and turns punctuation into single spaces. */
    fun normalize(text: String): String {
        val lower = text.lowercase()
        val sb = StringBuilder(lower.length)
        for (i in lower.indices) {
            val c = fold(lower[i])
            // Keep a '.' that sits inside a word (example.com, 3.5); every other punctuation mark is a space.
            val innerDot = c == '.' && i > 0 && i + 1 < lower.length &&
                lower[i - 1].isLetterOrDigit() && lower[i + 1].isLetterOrDigit()
            sb.append(if (c.isLetterOrDigit() || c == '\'' || c == ' ' || innerDot) c else ' ')
        }
        return sb.toString().trim().replace(Regex("\\s+"), " ")
    }

    /**
     * Amharic has several letters that sound identical (ሀ ሐ ኀ, ሰ ሠ, አ ዐ, ጸ ፀ) and speech engines
     * pick any of them. Folding them to one spelling makes "ድምጽ" and "ድምፅ" compare equal.
     */
    fun fold(c: Char): Char {
        val code = c.code
        val folded = when (code) {
            in 0x1210..0x1217 -> code - 0x10 // ሐ -> ሀ
            in 0x1280..0x1287 -> code - 0x80 // ኀ -> ሀ
            in 0x1220..0x1227 -> code + 0x10 // ሠ -> ሰ
            in 0x1340..0x1347 -> code - 0x08 // ፀ -> ጸ
            in 0x12D0..0x12D7 -> code - 0x30 // ዐ -> አ
            else -> code
        }
        return (if (folded == 0x12A3) 0x12A0 else folded).toChar() // ኣ (long a) -> አ
    }
}

/** "forty five", "45", "a hundred" -> number. */
object NumberWords {
    private val ONES = listOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
        "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen",
    )
    private val TENS = mapOf("twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90)

    fun parse(text: String): Int? {
        val t = text.trim().removeSuffix("%").trim()
        t.toIntOrNull()?.let { return it }
        val words = t.replace("-", " ").split(' ').filter { it.isNotEmpty() && it != "and" }
        if (words.isEmpty()) return null
        var total = 0
        for (w in words) {
            val n = ONES.indexOf(w).takeIf { it >= 0 } ?: TENS[w] ?: when (w) {
                "hundred" -> { total = if (total == 0) 100 else total * 100; continue }
                "a" -> continue
                else -> return null
            }
            total += n
        }
        return total
    }
}
