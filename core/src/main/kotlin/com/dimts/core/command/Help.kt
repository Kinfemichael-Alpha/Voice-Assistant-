package com.dimts.core.command

import com.dimts.core.script.DefStmt
import com.dimts.core.script.StateDefStmt

/** Text for the `help` command. Everything here describes behaviour that the engine really has. */
object Help {
    fun render(registry: CommandRegistry, topicWords: List<String>, live: (String) -> String? = { null }): String {
        if (topicWords.isEmpty()) return overview(registry)
        val topic = topicWords.joinToString(" ").lowercase()

        registry.find(topicWords)?.let { return command(it) }
        Category.entries.firstOrNull { it.key == topic || it.title.lowercase().startsWith(topic) }?.let { return category(registry, it) }
        TOPICS[topic]?.let { return it.trimIndent() }
        Catalog.state(topic)?.let { return stateDetail(it, live(it.name)) }
        Catalog.event(topic)?.let { return "${it.name}  (event)\n  ${it.summary}\n  example: whenever ${it.name}: say \"it happened\"" }
        Catalog.groups.firstOrNull { it.first == topic }?.let { return states(topic, live, emptyList()) }

        val choices = registry.allNames() + TOPICS.keys + Category.entries.map { it.key } + Catalog.states.map { it.name } + Catalog.events.map { it.name }
        val hint = com.dimts.core.Fuzzy.closest(topic, choices)
        return "No help for '$topic'." + (hint?.let { " Did you mean 'help $it'?" } ?: "") + "\nType 'help' for the list."
    }

    fun stateDetail(spec: StateSpec, live: String?): String {
        val example = when {
            spec.name == "time" -> "if time between 10 pm and 6 am: say \"night\""
            spec.kind == "yes/no" -> "when ${spec.name}: say \"it is true\""
            spec.kind == "number" -> "if ${spec.name} < 20: say \"low\""
            else -> "if ${spec.name} is something: say \"match\""
        }
        val sb = StringBuilder()
        sb.append(spec.name).append("  (").append(spec.kind).append(")\n")
        sb.append("  ").append(spec.summary).append('\n')
        if (spec.values.isNotEmpty()) sb.append("  values: ").append(spec.values).append('\n')
        sb.append("  now: ").append(live ?: "unavailable").append('\n')
        sb.append("  example: ").append(example)
        return sb.toString()
    }

    fun states(filter: String, live: (String) -> String?, mine: List<StateDefStmt>): String {
        val f = filter.trim().lowercase()
        if (f.isNotEmpty()) Catalog.state(f)?.let { return stateDetail(it, live(it.name)) }
        val matches = Catalog.states.filter { f.isEmpty() || it.group == f || it.name.contains(f) }
        val sb = StringBuilder()
        for ((key, title) in Catalog.groups) {
            val items = matches.filter { it.group == key }
            if (items.isEmpty()) continue
            sb.append(title).append('\n')
            for (st in items) {
                sb.append("  ").append(st.name.padEnd(20)).append(" = ").append((live(st.name) ?: "-").padEnd(10)).append(' ').append(st.summary).append('\n')
            }
            sb.append('\n')
        }
        val own = mine.filter { f.isEmpty() || f == "mine" || it.name.lowercase().contains(f) }
        if (own.isNotEmpty()) {
            sb.append("YOUR STATES (state)\n")
            for (d in own) {
                sb.append("  ").append(d.name.padEnd(20)).append(" = ").append((live(d.name) ?: "-").padEnd(10)).append(' ')
                    .append(d.source.substringAfter('=').trim()).append('\n')
            }
            sb.append('\n')
        }
        if (sb.isEmpty()) return "No state matches '$filter'.  Type 'states' to see them all."
        if (f.isEmpty()) sb.append("Use any state:  if battery.level < 20: say \"low\"   |   whenever wifi.connected: say \"online\"\n")
        return sb.toString().trimEnd()
    }

    fun events(): String {
        val sb = StringBuilder("EVENTS (true only at the instant they happen)\n")
        for (e in Catalog.events) sb.append("  ").append(e.name.padEnd(20)).append(' ').append(e.summary).append('\n')
        sb.append("\nUse:  whenever call.missed: notify \"missed a call\"\n      when screen.unlocked: say \"welcome back\"")
        return sb.toString()
    }

    fun actions(registry: CommandRegistry, filter: String, mine: List<DefStmt>): String {
        val f = filter.trim().lowercase()
        val sb = StringBuilder()
        for (cat in Category.entries) {
            val items = registry.specs.filter { s ->
                s.category == cat && (
                    f.isEmpty() || cat.key == f ||
                        s.names.any { n -> n.joinToString(" ").contains(f) } || s.summary.lowercase().contains(f)
                    )
            }
            if (items.isEmpty()) continue
            sb.append(cat.title.uppercase()).append('\n')
            for (s in items) sb.append("  ").append(s.usage.padEnd(34)).append(' ').append(s.summary.substringBefore(". ").trimEnd('.')).append('\n')
            sb.append('\n')
        }
        val own = mine.filter { f.isEmpty() || f == "mine" || it.name.lowercase().contains(f) }
        if (own.isNotEmpty()) {
            sb.append("YOUR ACTIONS (def)\n")
            for (d in own) {
                val first = d.source.lines().drop(1).firstOrNull()?.trim() ?: d.source.substringAfter(':').trim()
                sb.append("  ").append(d.name.padEnd(34)).append(' ').append(first).append('\n')
            }
            sb.append('\n')
        }
        if (sb.isEmpty()) return "No action matches '$filter'.  Type 'actions' to list them all."
        return sb.toString().trimEnd()
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
        sb.append("  while battery.level < 80: wait 5 min   loop while a state holds\n")
        sb.append("  def night: volume 10               your own action, run it by name\n")
        sb.append("  state night = time between 10 pm and 6 am   your own state\n")
        sb.append("  topics: help script | if | when | while | at | every | after | wait | repeat | set | def | state\n")
        sb.append("          help time | names | examples | voice | keys | permissions | limits\n")
        sb.append("  look up: all current conditions | all possible actions | events | defs\n")
        sb.append("           help <state or event name>   e.g. help battery.level      help concepts\n")
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
        "while" to """
            while CONDITION:  ...        repeat the block as long as the condition is true
            break                        leave the loop        continue   jump to the next round

              set n = 0
              while n < 3:
                  n += 1
                  say "round {n}"

              while battery.level < 80 and battery.charging:
                  wait 1 min
              say "charged"

            A loop is stopped after 100000 steps so a mistake cannot freeze the phone.
        """,
        "def" to """
            def NAME:  ...         define your own action. Run it later by typing its name.
            Arguments arrive as arg1, arg2 ... and {args} is how many were given.

              def night:
                  volume 10
                  brightness 5
                  dnd on
                  say "good night"

              def greet:
                  say "hello {arg1}"

              night
              greet Kinfemichael
              at 11 pm: night

            Actions are saved. 'defs' lists them, 'undef NAME' deletes one. Actions can call other actions.
        """,
        "state" to """
            state NAME = CONDITION     define your own state: a name for a condition you reuse

              state night = time between 10 pm and 6 am
              state low_power = battery.level < 20 and not battery.charging
              state at_home = wifi.connected

              if low_power: say "please charge me"
              whenever low_power: notify "battery low"
              while night: wait 10 min

            A state is always up to date when you read it. Names can be combined:
              state quiet_hours = night and not call.incoming
            'states' lists all states with their live values, 'defs' shows yours, 'undef NAME' deletes one.
        """,
        "concepts" to """
            A STATE is something that IS true right now: battery.level, wifi.connected, screen.locked ...
            An ACTION is something you DO: volume, call, flashlight, lock ...
            An EVENT is something that HAPPENS at one instant: call.missed, screen.unlocked ...

              all current conditions   every state with its live value   (also: states, states battery)
              help battery.level       explains one state, with an example
              all possible actions     every action                      (also: actions, actions sound)
              events                   every event

            Putting them together, exactly like Python:
              if battery.level < 20: flashlight on
              if wifi.connected and not battery.charging: say "unplugged and online"
              whenever call.missed: notify "missed a call"
              while screen.awake: wait 1 min
        """,
        "nesting" to """
            Blocks go inside blocks. Indent with spaces; each header ends with ':'.

              at 11 pm:
                  if battery.level < 30:
                      notify "charge me"
                      when power.connected:
                          say "thanks"
                  else:
                      repeat 3:
                          vibrate
                          wait 1 s

            Allowed inside each other: if / elif / else, while, repeat, when, whenever, at, every, after, def, state.
        """,
        "set" to """
            set NAME = EXPRESSION       (the word 'set' is optional)
            Shortcuts: x += 1   x -= 1   x *= 2   x /= 2   x %= 3
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
            Type 'names' for the live list of every name, 'states' for states with their current values,
            'events' for events, and 'help <name>' for one of them (help battery.level).
            Groups: time, battery, network, screen, sound, phone, system, assistant.
            Unknown dotted names (like mom.picked_up) are always empty - you get a warning with a suggestion.
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

            # Your own states and actions
            state night = time between 10 pm and 6 am
            state low_power = battery.level < 20 and not battery.charging
            def sleep_mode:
                volume 10
                dnd on
                say "good night"
            whenever low_power: notify "battery low"
            at 11 pm: sleep_mode

            # Nested logic with a loop
            set n = 0
            while n < 3:
                n += 1
                if n == 2: continue
                vibrate 200
                wait 1 s

            # React to a missed call
            whenever call.missed: notify "you missed a call from {call.contact}"
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
