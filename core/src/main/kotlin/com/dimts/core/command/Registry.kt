package com.dimts.core.command

import com.dimts.core.Fuzzy

enum class Category(val title: String, val key: String) {
    ASSISTANT("Listening & voice assistant", "listening"),
    RULES("Rules, variables & scripts", "rules"),
    CATALOG("States, events & actions", "catalog"),
    PHONE("Phone actions", "phone"),
    SCREEN("Screen control", "screen"),
    SOUND("Sound & media", "sound"),
    SYSTEM("System & console", "system"),
}

/**
 * One console command. [names] holds the primary name first, then aliases; each name is a list
 * of words (`start listening` is two words). The matcher picks the longest name that fits, so
 * `start listening input wake_word` finds `key.record` before it finds `listen.start`.
 */
data class CommandSpec(
    val id: String,
    val names: List<List<String>>,
    val usage: String,
    val summary: String,
    val category: Category,
    /** Asks for spoken/sound confirmation when it comes from voice. */
    val risky: Boolean = false,
    val example: String? = null,
    /** Extra explanation shown by `help <command>`. */
    val details: String? = null,
) {
    val primary: String get() = names[0].joinToString(" ")
}

data class Resolved(val spec: CommandSpec, val args: List<String>, val matched: List<String>)

class CommandRegistry(val specs: List<CommandSpec>) {
    private val byId = specs.associateBy { it.id }

    init {
        require(byId.size == specs.size) { "duplicate command ids" }
    }

    fun spec(id: String): CommandSpec? = byId[id]

    /** Finds the command named by the leading words of [words]. */
    fun resolve(words: List<String>): Resolved? {
        var best: Resolved? = null
        for (spec in specs) {
            for (name in spec.names) {
                if (name.size > words.size) continue
                if (best != null && name.size <= best.matched.size) continue
                var ok = true
                for (i in name.indices) if (!name[i].equals(words[i], ignoreCase = true)) { ok = false; break }
                if (ok) best = Resolved(spec, words.drop(name.size), name)
            }
        }
        return best
    }

    fun allNames(): List<String> = specs.flatMap { s -> s.names.map { it.joinToString(" ") } }

    /** "Did you mean ...?" for a line that matched nothing. */
    fun suggest(words: List<String>): String? {
        if (words.isEmpty()) return null
        val names = allNames()
        val two = words.take(2).joinToString(" ")
        return Fuzzy.closest(two, names) ?: Fuzzy.closest(words[0], names.map { it.substringBefore(' ') }.distinct())
    }

    fun unknownMessage(words: List<String>): String {
        val hint = suggest(words)
        return "unknown command '${words.firstOrNull() ?: ""}'" +
            (hint?.let { " - did you mean '$it'?" } ?: "") + "  (type 'help' to see all commands)"
    }

    /** Text lookups for `help <topic>`: command by name, or null. */
    fun find(topic: List<String>): CommandSpec? {
        val r = resolve(topic) ?: return null
        return if (r.args.isEmpty()) r.spec else null
    }
}

object Commands {
    private fun c(
        id: String,
        category: Category,
        names: String,
        usage: String,
        summary: String,
        risky: Boolean = false,
        example: String? = null,
        details: String? = null,
    ) = CommandSpec(
        id, names.split('|').map { n -> n.trim().split(Regex("\\s+")) }, usage, summary, category, risky, example, details,
    )

    val registry: CommandRegistry = CommandRegistry(
        listOf(
            // ---- listening & assistant ----
            c("listen.start", Category.ASSISTANT, "start listening", "start listening [key]",
                "Start the always-on key-sound listener. It is the ONLY thing kept running; no AI model is loaded.",
                example = "start listening wake_word",
                details = "Needs a recorded key (see 'record key'). The listener only measures sound shape, so it uses almost no battery. When it hears the key it loads the command writer."),
            c("listen.stop", Category.ASSISTANT, "stop listening", "stop listening", "Stop listening for the key sound completely."),
            c("key.record", Category.ASSISTANT, "record key|start listening input|enroll key", "record key <name> [wake|confirm|dismiss]",
                "Record 4 samples of your key sound (a word, whistle, clap pattern...).",
                example = "start listening input wake_word",
                details = "Say or make the sound each time the app beeps. Keep pauses inside the sound shorter than 0.3 s. Roles: wake (default) starts the assistant, confirm answers 'yes' to a confirmation, dismiss closes the assistant."),
            c("key.use", Category.ASSISTANT, "use key", "use key <name> as <wake|confirm|dismiss>", "Give an existing key a role."),
            c("key.list", Category.ASSISTANT, "keys", "keys", "List recorded keys and their roles."),
            c("key.delete", Category.ASSISTANT, "delete key", "delete key <name>", "Delete a recorded key."),
            c("key.test", Category.ASSISTANT, "test key", "test key [seconds]",
                "Show how close each sound you make is to your key (default 20 s). Use it to tune 'sensitivity'."),
            c("key.sensitivity", Category.ASSISTANT, "sensitivity", "sensitivity [0.5-2.0]",
                "Show or set how strict key matching is. Higher = triggers more easily, lower = stricter."),
            c("assistant.start", Category.ASSISTANT,
                "start voice_assistant|start voice_assistant on android|start voice assistant|start assistant|wake",
                "start voice_assistant",
                "Load the command writer now (as if the key sound was heard) and listen for one command at a time.",
                example = "start voice_assistant on android"),
            c("assistant.stop", Category.ASSISTANT, "stop assistant|stop voice_assistant|dismiss", "stop assistant",
                "Unload the command writer from RAM and go back to key-only listening."),
            c("assistant.language", Category.ASSISTANT, "language", "language [en|am]", "Show or set the speech language (English or Amharic)."),
            c("assistant.timeout", Category.ASSISTANT, "timeout", "timeout [seconds]",
                "Show or set how long the writer waits without a command before unloading (default 60)."),

            // ---- rules ----
            c("rules", Category.RULES, "rules", "rules", "List waiting rules (at / every / when ...)."),
            c("rules.cancel", Category.RULES, "cancel rule", "cancel rule <id>", "Remove one waiting rule."),
            c("rules.clear", Category.RULES, "clear rules", "clear rules", "Remove all waiting rules."),
            c("jobs", Category.RULES, "jobs", "jobs", "List running scripts that are paused in a 'wait'."),
            c("jobs.cancel", Category.RULES, "cancel job", "cancel job <id|all>", "Stop a paused script."),
            c("vars", Category.RULES, "vars", "vars", "Show variables."),
            c("unset", Category.RULES, "unset", "unset <name>", "Delete a variable."),
            c("names", Category.RULES, "names", "names", "List every name you can use in conditions (battery.level, call.answered ...)."),
            c("eval", Category.RULES, "eval", "eval <expression>", "Calculate an expression and print it.", example = "eval time between 10 pm and 6 am"),

            // ---- states, events & actions ----
            c("states", Category.CATALOG, "states|all current conditions|all current states|all conditions|conditions", "states [filter]",
                "List every phone state you can test in a condition, with its live value.",
                example = "all current conditions",
                details = "Filter by a group (battery, network, screen, sound, phone, system, time, assistant) or by part of a name. 'states battery.level' explains one state. Use any state like:  if battery.level < 20: say \"low\""),
            c("events", Category.CATALOG, "events", "events",
                "List the one-time events you can wait for with 'when' / 'whenever'.",
                details = "Events are true only at the instant they happen, e.g.  whenever call.missed: notify \"missed a call\""),
            c("actions", Category.CATALOG, "actions|all possible actions|all actions", "actions [filter]",
                "List every action you can run, plus the actions you defined with 'def'.",
                example = "all possible actions",
                details = "Filter by a group (listening, rules, catalog, phone, screen, sound, system) or by part of a name."),
            c("defs", Category.CATALOG, "defs", "defs", "Show the actions (def) and states (state) you defined."),
            c("undef", Category.CATALOG, "undef", "undef <name>", "Delete an action or state you defined."),

            // ---- phone ----
            c("call", Category.PHONE, "call|dial", "call <contact|number>", "Call a contact or number.", risky = true, example = "call mom"),
            c("hangup", Category.PHONE, "hangup|hang up|end call", "hangup", "End the current call.", details = "Works from Android 9. If the other side must have answered first, use: when call.answered: hangup (incoming calls only)."),
            c("answer", Category.PHONE, "answer|pick up", "answer", "Answer a ringing call."),
            c("sms", Category.PHONE, "sms|text", "sms <contact|number> <text>", "Send a text message.", risky = true, example = "sms mom \"I will be late\""),
            c("open", Category.PHONE, "open|launch", "open <app name>", "Open an app by its name.", example = "open camera",
                details = "If no app has exactly that name, the closest matches are listed (it also matches by sound: 'cloud' finds 'Claude'). Choose one with:  pick <number>"),
            c("pick", Category.PHONE, "pick|choose|select", "pick [number]", "Choose one of the options just offered (default 1).",
                example = "pick 2"),
            c("search", Category.PHONE, "search|google", "search <text>", "Search the web in the browser.", example = "search weather in Addis Ababa"),
            c("browse", Category.PHONE, "browse", "browse <url>", "Open a web address."),
            c("say", Category.PHONE, "say|speak", "say <text>", "Speak text aloud (and print it).", example = "say \"Battery is {battery.level} percent\""),
            c("notify", Category.PHONE, "notify", "notify <text>", "Show a notification."),
            c("contact", Category.PHONE, "contact", "contact <name>", "Look up a contact's number (useful to test 'call')."),
            c("flashlight", Category.PHONE, "flashlight|torch", "flashlight <on|off|toggle>", "Turn the flashlight on or off."),
            c("settings", Category.PHONE, "settings", "settings [page]",
                "Open a system settings page (Android does not let apps flip Wi-Fi or Bluetooth directly).",
                example = "settings wifi",
                details = "Pages: wifi, bluetooth, display, sound, battery, apps, location, accessibility, airplane, nfc, date, language, storage, privacy, security, vpn, roaming, cast, night, wireless, sync, info, input, usage."),
            c("alarm.set", Category.PHONE, "alarm|set alarm", "alarm <time> [label]",
                "Set an alarm in the clock app.", example = "alarm 7:30 am wake up",
                details = "Time can be 7:30 am, 7am, 07:30 or 19:30."),
            c("timer.set", Category.PHONE, "timer|set timer", "timer <duration> [label]",
                "Start a countdown timer in the clock app.", example = "timer 10 min tea",
                details = "Duration: 30 s, 5 min, 1 h 30 min, or plain minutes (timer 5)."),
            c("copy", Category.PHONE, "copy", "copy <text>", "Put text on the clipboard.", example = "copy \"my address\""),
            c("share", Category.PHONE, "share", "share <text>", "Open the Android share sheet with some text."),
            c("map", Category.PHONE, "map|navigate", "map <place>", "Show a place in the maps app.", example = "map Addis Ababa airport"),

            // ---- screen (needs the accessibility service) ----
            c("back", Category.SCREEN, "back|close|go back", "back", "Press Back.", details = "'close' is the same as Back: Android does not let apps force-close other apps."),
            c("home", Category.SCREEN, "home|go home", "home", "Go to the home screen."),
            c("recents", Category.SCREEN, "recents|recent apps", "recents", "Show recent apps."),
            c("notifications", Category.SCREEN, "notifications", "notifications", "Pull down the notification shade."),
            c("lock", Category.SCREEN, "lock", "lock", "Lock the screen (Android 9+). Unlocking by voice is not possible: Android forbids it."),
            c("scroll", Category.SCREEN, "scroll", "scroll <up|down>", "Scroll the current screen.", example = "scroll down"),
            c("swipe", Category.SCREEN, "swipe", "swipe <left|right|up|down>", "Swipe across the screen."),
            c("tap", Category.SCREEN, "tap|click", "tap <x> <y>  |  tap <text on screen>", "Tap a point (pixels) or a button by its text.", example = "tap Send"),
            c("type", Category.SCREEN, "type|write", "type <text>", "Type text into the focused field."),
            c("screenshot", Category.SCREEN, "screenshot", "screenshot", "Take a screenshot (Android 9+)."),
            c("read.screen", Category.SCREEN, "read screen|read", "read screen", "Read the visible text aloud."),
            c("brightness", Category.SCREEN, "brightness", "brightness <0-100|auto>",
                "Set the screen brightness.", example = "brightness 30",
                details = "Needs the 'modify system settings' access: type  grant write_settings"),
            c("rotate", Category.SCREEN, "rotate|auto rotate", "rotate <on|off>",
                "Turn automatic screen rotation on or off.",
                details = "Needs the 'modify system settings' access: type  grant write_settings"),

            // ---- sound & media ----
            c("volume", Category.SOUND, "volume", "volume [stream] <up|down|0-100|mute|unmute>", "Change the media volume, or another stream.",
                example = "volume ring 60",
                details = "Streams: music (default), ring, alarm, notification, call, system."),
            c("dnd", Category.SOUND, "dnd|do not disturb", "dnd <on|off|alarms|priority>",
                "Switch Do Not Disturb.", example = "dnd on",
                details = "on = total silence, alarms = alarms only, priority = priority interruptions, off = normal. Needs Do-Not-Disturb access: type  grant dnd"),
            c("vibrate", Category.SOUND, "vibrate|buzz", "vibrate [milliseconds]", "Vibrate the phone once (default 400 ms).", example = "vibrate 800"),
            c("media.play", Category.SOUND, "play", "play", "Media: play / resume."),
            c("media.pause", Category.SOUND, "pause", "pause", "Media: pause."),
            c("media.next", Category.SOUND, "next", "next", "Media: next track."),
            c("media.previous", Category.SOUND, "previous", "previous", "Media: previous track."),
            c("media.stop", Category.SOUND, "stop music|stop media", "stop music", "Media: stop."),
            c("ringer", Category.SOUND, "ringer", "ringer <normal|vibrate|silent>", "Set the ringer mode (silent needs Do-Not-Disturb access)."),

            // ---- system ----
            c("permissions", Category.SYSTEM, "permissions|perms", "permissions", "Show which permissions are granted and which are missing."),
            c("grant", Category.SYSTEM, "grant|allow", "grant [name|all]", "Ask Android for a permission (or the next missing ones).", example = "grant all"),
            c("status", Category.SYSTEM, "status", "status", "Show what is running right now."),
            c("help", Category.SYSTEM, "help|?|commands|start help now|help now", "help [topic]",
                "List commands, or explain one command or topic (help script, help examples ...).",
                example = "help call"),
            c("clear", Category.SYSTEM, "clear|cls", "clear", "Clear the console."),
            c("echo", Category.SYSTEM, "echo", "echo <text>", "Print text (no speech)."),
            c("version", Category.SYSTEM, "version", "version", "Show the app version."),
            c("exit", Category.SYSTEM, "exit|quit", "exit", "Close the console window (rules and listening keep running)."),
        ),
    )
}
