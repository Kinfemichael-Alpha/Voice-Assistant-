package com.dimts.core.voice

import com.dimts.core.command.Commands
import com.dimts.core.script.Parser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoicePlannerTest {
    private val planner = VoicePlanner(Commands.registry)

    private fun line(text: String): String {
        val p = planner.plan(text)
        assertTrue("'$text' -> $p", p is Plan.Run)
        return (p as Plan.Run).line
    }

    @Test
    fun englishPhoneCommands() {
        assertEquals("call \"mom\"", line("call mom"))
        assertEquals("call \"mom\"", line("Please call my mom."))
        assertEquals("call \"abebe kebede\"", line("hey could you dial Abebe Kebede please"))
        assertEquals("sms \"mom\" \"i will be late\"", line("text mom saying I will be late"))
        assertEquals("sms \"abebe\" \"hello there\"", line("send a message to Abebe saying hello there"))
        assertEquals("hangup", line("hang up"))
        assertEquals("answer", line("answer the call"))
        assertEquals("open \"camera\"", line("open camera"))
        assertEquals("search \"weather in addis ababa\"", line("search for weather in Addis Ababa"))
    }

    @Test
    fun englishScreenAndSoundCommands() {
        assertEquals("scroll down", line("scroll down"))
        assertEquals("swipe left", line("swipe left"))
        assertEquals("back", line("go back"))
        assertEquals("home", line("go home"))
        assertEquals("lock", line("lock the phone"))
        assertEquals("notifications", line("show notifications"))
        assertEquals("tap \"send\"", line("tap on send"))
        assertEquals("volume up", line("turn the volume up"))
        assertEquals("volume up", line("louder"))
        assertEquals("volume 45", line("volume forty five"))
        assertEquals("volume 70", line("set the volume to 70 percent"))
        assertEquals("volume mute", line("mute"))
        assertEquals("pause", line("pause the music"))
        assertEquals("next", line("next song"))
        assertEquals("stop music", line("stop the music"))
        assertEquals("flashlight on", line("turn on the flashlight"))
        assertEquals("flashlight off", line("turn the torch off"))
        assertEquals("settings wifi", line("open wifi settings"))
        assertEquals("status", line("run status"))
    }

    @Test
    fun informationQuestionsUseInterpolation() {
        assertEquals("say \"It is {time}\"", line("what time is it"))
        assertEquals("say \"Battery is {battery.level} percent\"", line("what's the battery"))
    }

    @Test
    fun yesNoAndDismiss() {
        for (w in listOf("yes", "Yeah.", "okay", "confirm", "do it")) assertEquals(w, Plan.Yes, planner.plan(w))
        for (w in listOf("no", "Nope!", "don't")) assertEquals(w, Plan.No, planner.plan(w))
        for (w in listOf("stop", "cancel", "never mind", "that's all", "goodbye")) assertEquals(w, Plan.Dismiss, planner.plan(w))
    }

    @Test
    fun unknownSentencesAreReportedNotGuessed() {
        val p = planner.plan("the quick brown fox")
        assertTrue(p is Plan.Unknown)
        assertEquals(Plan.Unknown(""), planner.plan("   "))
    }

    @Test
    fun everyGeneratedLineParsesAndNamesARealCommand() {
        val phrases = listOf(
            "call mom", "text mom saying hi", "open camera", "scroll up", "volume 30", "what time is it", "tap ok",
            "type hello world", "search cats", "go to example.com", "read screen", "take a screenshot", "recent apps",
        )
        for (p in phrases) {
            val l = line(p)
            val stmt = Parser.parse(l).stmts.single()
            assertTrue("'$l' did not parse as a command", stmt is com.dimts.core.script.CommandStmt)
            val words = (stmt as com.dimts.core.script.CommandStmt).words.map { w ->
                w.parts.joinToString("") { (it as? com.dimts.core.script.Part.Text)?.s ?: "?" }
            }
            assertTrue("'$l' is not a known command", Commands.registry.resolve(words) != null)
        }
    }

    @Test
    fun quotingKeepsBracesAndQuotesSafe() {
        val nasty = "say \"hi\" {now} \\ end"
        val stmt = Parser.parse("type " + VoicePlanner.q(nasty)).stmts.single() as com.dimts.core.script.CommandStmt
        val text = stmt.words[1].parts.joinToString("") { (it as com.dimts.core.script.Part.Text).s }
        assertEquals(nasty, text)
        assertEquals(2, stmt.words.size)
    }

    // ---- Amharic ----------------------------------------------------------------------------

    @Test
    fun amharicHomophonesFoldToOneSpelling() {
        assertEquals(Normalizer.normalize("ድምጽ"), Normalizer.normalize("ድምፅ"))
        assertEquals(Normalizer.normalize("ሰዓት"), Normalizer.normalize("ሰአት"))
        assertEquals(Normalizer.normalize("ሐሙስ"), Normalizer.normalize("ሀሙስ"))
        assertEquals(Normalizer.normalize("ሠላም"), Normalizer.normalize("ሰላም"))
        assertEquals(Normalizer.normalize("ፀሐይ"), Normalizer.normalize("ጸሀይ"))
    }

    @Test
    fun amharicCommands() {
        assertEquals("call \"እማማ\"", line("ለእማማ ደውል"))
        assertEquals("open \"camera\"", line("ካሜራ ክፈት"))
        assertEquals("open \"camera\"", line("ካሜራውን ክፈት"))
        assertEquals("volume up", line("ድምጽ ጨምር"))
        assertEquals("volume up", line("ድምፅ ጨምር።"))
        assertEquals("volume down", line("ድምጹን ቀንስ"))
        assertEquals("back", line("ተመለስ"))
        assertEquals("lock", line("ስልኩን ቆልፍ"))
        assertEquals("hangup", line("ስልኩን ዝጋ"))
        assertEquals("scroll down", line("ወደ ታች ሸብልል"))
        assertEquals("say \"It is {time}\"", line("ሰዓት ስንት ነው"))
        assertEquals("search \"የአየር ሁኔታ\"", line("የአየር ሁኔታ ፈልግ"))
        assertEquals(Plan.Yes, planner.plan("አዎ"))
        assertEquals(Plan.No, planner.plan("አይ"))
        assertEquals(Plan.Dismiss, planner.plan("በቃ"))
    }

    @Test
    fun numberWords() {
        assertEquals(0, NumberWords.parse("zero"))
        assertEquals(45, NumberWords.parse("forty five"))
        assertEquals(45, NumberWords.parse("forty-five"))
        assertEquals(100, NumberWords.parse("one hundred"))
        assertEquals(100, NumberWords.parse("a hundred"))
        assertEquals(17, NumberWords.parse("seventeen"))
        assertEquals(55, NumberWords.parse("55%"))
        assertEquals(null, NumberWords.parse("banana"))
    }
}
