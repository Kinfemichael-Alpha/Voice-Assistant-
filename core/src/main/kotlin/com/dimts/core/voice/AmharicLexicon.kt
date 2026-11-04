package com.dimts.core.voice

/**
 * Starter Amharic vocabulary. Amharic is verb-final ("ለእማማ ደውል" = "to-mom call"), so commands
 * are written as `<target> <verb>`, plus a few fixed phrases. This is a starting point: speech
 * engines vary, so add the phrases you really say (every entry is folded through [Normalizer],
 * so spelling variants like ፅ/ጽ do not matter).
 */
object AmharicLexicon {
    private fun n(s: String) = Normalizer.normalize(s)
    private fun set(vararg s: String) = s.map { n(it) }.toSet()

    val yes = set("አዎ", "አዎን", "እሺ", "ይሁን", "እሺ በል")
    val no = set("አይ", "አይደለም", "አይሆንም", "አታድርግ")
    val dismiss = set("ተወው", "ተው", "በቃ", "ይበቃል", "በቃህ")

    private class Phrase(val line: String, val say: String, vararg val forms: String)

    private val phrases = listOf(
        Phrase("volume up", "Volume up", "ድምጽ ጨምር", "ድምጹን ጨምር", "ድምጽ ከፍ አድርግ", "ድምጹን ከፍ አድርግ"),
        Phrase("volume down", "Volume down", "ድምጽ ቀንስ", "ድምጹን ቀንስ", "ድምጽ ዝቅ አድርግ", "ድምጹን ዝቅ አድርግ"),
        Phrase("volume mute", "Muted", "ዝም በል", "ድምጽ ዝጋ", "ድምጹን ዝጋ"),
        Phrase("back", "Going back", "ተመለስ", "ወደ ኋላ ተመለስ"),
        Phrase("home", "Home", "ወደ ቤት ሂድ", "ወደ መነሻ ሂድ", "መነሻ ገጽ", "ወደ መነሻ ገጽ ሂድ"),
        Phrase("lock", "Locking", "ስልኩን ቆልፍ", "ስልክ ቆልፍ", "ቆልፍ"),
        Phrase("hangup", "Ending the call", "ስልኩን ዝጋ", "ጥሪውን ዝጋ", "ጥሪ ዝጋ"),
        Phrase("answer", "Answering", "ስልኩን አንሳ", "ስልኩን አንሳው", "አንሳ"),
        Phrase("scroll down", "Scrolling down", "ወደ ታች ሸብልል", "ታች ሸብልል"),
        Phrase("scroll up", "Scrolling up", "ወደ ላይ ሸብልል", "ላይ ሸብልል"),
        Phrase("swipe left", "Swiping left", "ወደ ግራ ጎትት"),
        Phrase("swipe right", "Swiping right", "ወደ ቀኝ ጎትት"),
        Phrase("say \"It is {time}\"", "Time", "ሰዓት ስንት ነው", "ሰዓቱ ስንት ነው", "ስንት ሰዓት ነው"),
        Phrase("flashlight on", "Flashlight on", "ባትሪ አብራ", "ባትሪውን አብራ"),
        Phrase("flashlight off", "Flashlight off", "ባትሪ አጥፋ", "ባትሪውን አጥፋ"),
        Phrase("help", "Help", "እርዳታ"),
    )

    private val phraseMap: Map<String, Phrase> = phrases.flatMap { p -> p.forms.map { n(it) to p } }.toMap()

    private val callVerbs = set("ደውል", "ደውልልኝ", "ደውሉ", "ደውልለት", "ደውይ")
    private val openVerbs = set("ክፈት", "ክፈትልኝ", "ክፈቺ")
    private val searchVerbs = set("ፈልግ", "ፈልግልኝ", "ፈልጊ")
    private val typeVerbs = set("ጻፍ", "ፃፍ", "ጻፍልኝ")

    /** Amharic app names -> the name Android shows for the app. */
    private val apps: Map<String, String> = mapOf(
        "ካሜራ" to "camera", "ዩቲዩብ" to "youtube", "ቴሌግራም" to "telegram", "ፌስቡክ" to "facebook",
        "ዋትስአፕ" to "whatsapp", "ጎግል" to "google", "ካልኩሌተር" to "calculator", "ሰዓት" to "clock",
        "ቅንብሮች" to "settings", "ጋለሪ" to "gallery", "ካርታ" to "maps", "ኢሜል" to "gmail",
        "መልእክት" to "messages", "ሙዚቃ" to "music", "ፎቶ" to "photos", "አድራሻዎች" to "contacts",
    ).mapKeys { n(it.key) }

    fun plan(text: String): Plan? {
        phraseMap[text]?.let { return Plan.Run(it.line, it.say) }

        val tokens = text.split(' ').filter { it.isNotEmpty() }
        if (tokens.size < 2) return null
        val verb = tokens.last()
        val target = tokens.dropLast(1).joinToString(" ")
        return when (verb) {
            in callVerbs -> {
                val who = stripPrefixLe(target)
                Plan.Run("call ${VoicePlanner.q(who)}", "Calling $who")
            }
            in openVerbs -> {
                val app = appName(target)
                Plan.Run("open ${VoicePlanner.q(app)}", "Opening $app")
            }
            in searchVerbs -> Plan.Run("search ${VoicePlanner.q(target)}", "Searching")
            in typeVerbs -> Plan.Run("type ${VoicePlanner.q(target)}", "Typing")
            else -> null
        }
    }

    /** "ለእማማ" -> "እማማ": the prefix ለ means "to/for". */
    private fun stripPrefixLe(word: String): String {
        val le = n("ለ")
        return if (word.startsWith(le) && word.length > 2) word.substring(1) else word
    }

    private fun appName(target: String): String {
        apps[target]?.let { return it }
        // Definite endings: ካሜራው, ካሜራውን, ካሜራዋ ...
        var t = target
        for (suffix in listOf("ን", "ው", "ዋ")) {
            if (t.endsWith(suffix) && t.length > 2) {
                t = t.removeSuffix(suffix)
                apps[t]?.let { return it }
            }
        }
        return target
    }
}
