package com.dimts.core.command

/** Text for the `help` command. Everything here describes behaviour that the engine really has. */
object Help {
    fun render(registry: CommandRegistry, topicWords: List<String>): String {
        if (topicWords.isEmpty()) return overview(registry)
        val topic = topicWords.joinToString(" ").lowercase()

        registry.find(topicWords)?.let { return command(it) }
        Category.entries.firstOrNull { it.key == topic || it.title.lowercase().startsWith(topic) }?.let { return category(registry, it) }
        TOPICS[topic]?.let { return it.trimIndent() }

        val choices = registry.allNames() + TOPICS.keys + Category.entries.map { it.key }
        val hint = com.dimts.core.Fuzzy.closest(topic, choices)
        return "No help for '$topic'." + (hint?.let { " Did you mean 'help $it'?" } ?: "") + "\nType 'help' for the list."
    }

    fun overview(registry: CommandRegistry): String {
        val sb = StringBuilder()
        sb.append("DIMTS console - type a command, or write small programs.\n")
        sb.append("Type 'help <command>' for details, e.g.  help call   |   help start listening\n\n")
        for (cat in Category.entries) {
            val items = registry.specs.filter { it.category == cat }
            if (items.isEmpty()) continue
            sb.append(cat.title.uppercase()).append('\n')
            for (s in items) sb.append("  ").append(s.usage.padEnd(34)).append(' ').append(s.summary.substringBefore(". ").trimEnd('.')).append('\n')
            sb.append('\n')
        }
        sb.append("PROGRAMMING (conditions, schedules, nesting)\n")
        sb.append("  at 10:30 pm: call mom              run once at a time\n")
        sb.append("  when call.answered: hangup         wait for an event or a state\n")
        sb.append("  if battery.level < 20: say \"low\"   check now\n")
        sb.append("  topics: help script | if | when | at | every | after | wait | repeat | set\n")
        sb.append("          help time | names | events | examples | voice | keys | permissions | limits\n")
        return sb.toString().trimEnd()
    }

    private fun category(registry: CommandRegistry, cat: Category): String =
        registry.specs.filter { it.category == cat }
            .joinToString("\n", prefix = cat.title.uppercase() + "\n") { "  " + it.usage.padEnd(34) + " " + it.summary }

    private fun command(s: CommandSpec): String {
        val sb = StringBuilder()
        sb.append(s.usage).append('\n').append("  ").append(s.summary).append('\n')
        if (s.names.size > 1) {
            sb.append("  also: ").append(s.names.drop(1).joinToString(", ") { it.joinToString(" ") }).append('\n')
        }
        s.details?.let { sb.append("  ").append(it).append('\n') }
        if (s.risky) sb.append("  Asks for confirmation when you say it by voice.\n")
        s.example?.let { sb.append("  example: ").append(it).append('\n') }
        return sb.toString().trimEnd()
    }

    val TOPICS: Map<String, String> = mapOf(
        "script" to """
            The console runs commands AND small programs. Blocks are made with indentation,
            and every block header ends with ':'  (just like Python).

              call mom                              run a command now
              if battery.level < 20: say "low"      check now
              at 10:30 pm: call mom                 wait for a time, then run once
              every day at 7 am: say "good morning" repeat
              when call.answered: hangup            wait for an event or a state
              repeat 3: say hi                      loop
              set x = 5     say "x is {x}"          variables

            Several lines: type a line that ends with ':' and keep typing indented lines.
            An empty line runs the block.  Comments start with #.
            Statements inside a block can be blocks too (if inside at inside when ...).
            More: help if | when | at | every | after | wait | repeat | set | time | names | events | examples
        """,
        "if" to """
            if CONDITION:  ...   [elif CONDITION: ...]   [else: ...]
            Checks the condition RIGHT NOW and runs one branch. Nest as deep as you like.

              if battery.level < 20 and not battery.charging:
                  say "please charge me"
              elif battery.level < 50: say "half"
              else:
                  say "fine"

            Conditions: == != < <= > >=   is   is not   contains   between A and B   and   or   not   ( )
            Inline form:  if x == 1: say one; say uno
            To WAIT for something instead of checking now, use 'when'.
        """,
        "when" to """
            when CONDITION:  ...          fires ONCE, the moment the condition turns from false to true
            whenever CONDITION:  ...      fires every time it turns from false to true

              when call.answered: hangup
              whenever battery.level < 15: notify "battery low"
              whenever call.incoming and call.contact is mom: say "mom is calling"

            A condition that is already true when you type it does not fire until it becomes
            false and true again. Events like call.answered are true only at the instant they happen.
            A 'when' inside another block starts waiting at the moment that block runs:

              at 10:30 pm:
                  call mom
                  when call.answered:
                      hangup
        """,
        "at" to """
            at TIME:  ...                       run once, at the next time it is that time
            at TIME on DAY, DAY:  ...           only on those days

              at 10:30 pm: call mom
              at 7 am on mon, fri: say "gym day"

            TIME can be 10:30 pm, 10:30pm, 22:30, 7am. Days: mon..sun, weekdays, weekend.
            If the phone was off, a missed time still runs when it starts again - but only
            up to 2 hours late.
        """,
        "every" to """
            every day at TIME:  ...            every day
            every mon wed fri at TIME:  ...    on chosen days (also: weekdays, weekend)
            every N unit:  ...                 every N seconds/minutes/hours

              every day at 7 am: say "good morning"
              every weekdays at 8:30 am: open calendar
              every 30 min: notify "stand up"

            Units: s, min, h, d (seconds, minutes, hours, days). Use 'rules' to list, 'cancel rule N' to stop.
        """,
        "after" to """
            after DURATION:  ...     run once after a delay

              after 10 min: say "tea is ready"
              after 1 h 30 min: call mom

            Waiting rules are saved, so they survive closing the app.
        """,
        "wait" to """
            wait DURATION            pause the rest of this block

              say "starting"
              wait 5 s
              say "five seconds later"

            Waiting scripts are listed by 'jobs' and stopped with 'cancel job'. They are NOT saved if
            the app is closed (use 'after' for that).
        """,
        "repeat" to """
            repeat N:  ...           run the block N times (N up to 1000)
              repeat 3 times:
                  volume up
                  wait 1 s
        """,
        "set" to """
            set NAME = EXPRESSION       (the word 'set' is optional)
            Numbers, "text", true/false, times (10:30 pm). + - * / %  and text joining with +.
            Use a variable inside a command with ${'$'}NAME or {EXPRESSION}:

              set who = "mom"
              at 9 pm: call ${'$'}who
              say "{battery.level}% battery at {time}"

            Variables are saved. 'vars' lists them, 'unset NAME' removes one.
        """,
        "time" to """
            time                the current time of day
            time is 10:30 pm    true at that minute (also true if the phone was asleep and wakes up late)
            time > 10 pm        true after that time every day
            time between 10 pm and 6 am     wraps past midnight
            day is monday       (mon, tue ... or weekdays)
            hour, minute        numbers
            Example:  whenever call.incoming and time between 10 pm and 6 am: say "late call"
        """,
        "names" to """
            Names you can use in conditions. Type 'names' to see the live list.
            time, hour, minute, day
            battery.level  battery.charging  call.state (idle|ringing|offhook)
            call.contact / call.number = the other side of the current call (the contact's name when it is in your contacts;
            for incoming calls the Phone permission is needed - see 'permissions')
            app.current  wifi.connected  volume  assistant.state  listening
            Events (true only at the moment they happen): call.incoming call.answered call.started call.ended
            key.heard assistant.started assistant.stopped screen.on screen.off power.connected power.disconnected boot
            Unknown dotted names (like mom.picked_up) are always empty - you get a warning with a suggestion.
        """,
        "events" to """
            Events are one-time moments. Use them with 'when' or 'whenever'.
              call.incoming    a call starts ringing
              call.answered    you answered an incoming call (Android cannot tell when the OTHER person answers your outgoing call)
              call.started     you dialled out
              call.ended       a call finished
              key.heard        your key sound was heard
              assistant.started / assistant.stopped
              screen.on / screen.off     power.connected / power.disconnected      boot
        """,
        "examples" to """
            # Call mom at 10:30 pm, and hang up if she answers (nested rule)
            at 10:30 pm:
                call mom
                when call.answered:
                    hangup

            # Night mode
            every day at 11 pm:
                volume 10
                say "good night"

            # Reminder every 45 minutes, only 8 am - 6 pm
            every 45 min:
                if time between 8 am and 6 pm: notify "stretch"

            # Low battery warning, each time it drops below 15
            whenever battery.level < 15: say "battery is low"

            # Say who is calling when mom rings (needs the Phone + Contacts permissions)
            whenever call.incoming and call.contact is mom: say "mom is calling"

            # After you call mom, hang up if nobody picks up within 30 seconds
            at 9 pm:
                call mom
                after 30 s: hangup
        """,
        "voice" to """
            1) Record your key sound:      record key wake_word      (say it when the app beeps, 4 times)
            2) Start listening:            start listening wake_word
            3) Make the sound, then speak a command: "call mom", "open camera", "scroll down", "volume 40" ...
            Amharic works for the common commands: set 'language am'.
            Risky commands (call, sms) ask 'yes or no' first. Say yes/no, or use confirm/dismiss key sounds
            (record key yes_sound confirm   |   record key bye_sound dismiss).
            After 60 s without a command the writer unloads and only the key listener remains.
        """,
        "keys" to """
            A key is a sound shape (a word, whistle, clap pattern). It is matched by signal processing, not AI,
            so it costs almost no battery. Tips:
              - record in the place you will use it, 4 samples, same way each time
              - keep pauses inside the sound under 0.3 s, total length 0.2 - 4 s
              - a recorded sound can be replayed by anyone, so keys are for convenience, not security
              - 'test key' shows distances so you can tune 'sensitivity'
        """,
        "permissions" to """
            'permissions' shows what is granted. 'grant all' asks for the missing ones one by one.
              microphone, notifications, phone (call/answer/end calls), contacts, sms  - normal pop-ups
              accessibility   - needed for tap/scroll/swipe/back/home/lock; opens Android settings, you switch it on
              exact alarms    - lets time rules fire on the minute even in sleep; opens settings
              battery         - 'unrestricted' keeps the key listener alive; opens settings
              overlay         - lets rules open apps while the screen is off or another app is on top
        """,
        "limits" to """
            What Android does NOT allow (not a bug):
              - Unlocking the phone by voice/sound (PIN, pattern, fingerprint are protected). Locking works.
              - Detecting when the OTHER person answers your outgoing call (apps that are not the default dialer).
              - Turning Wi-Fi/Bluetooth on or off directly: 'settings wifi' opens the page instead.
              - Force-closing other apps. 'close' presses Back.
              - Banking and some secure apps block screen control.
              - Starting the microphone service at boot (Android 14+): open the app once after a reboot to resume listening.
              - Rules about state or events (when battery..., call.*, screen.*) only work while the app process is alive
                (listening is on, or the app is open). Time rules (at / every / after) always work, even in sleep.
              - Apps cannot launch screens from the background unless overlay permission is granted.
        """,
    )
}
