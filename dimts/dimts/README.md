# Dimts — an offline voice assistant with a terminal and a small programming language

Dimts (ድምጽ, "sound/voice") is an Android app, written in Kotlin, that you control by a **key sound** and by a **black console** where you can type commands or write small programs.

```
> at 10:30 pm:
...     call mom
...     when call.answered:
...         hangup
...
> start listening my_word
> help
```

## The four steps (why it is cheap on battery and RAM)

1. **Listen** – only a tiny signal-processing detector (MFCC + DTW, no AI model) listens for *your* sound. Nothing else is in RAM.
2. **Write** – the key sound loads the speech recogniser ("command writer") and it transcribes what you say.
3. **Execute** – the text becomes a whitelisted command (`call`, `open`, `scroll`, `volume`, …). Risky ones (`call`, `sms`) ask yes/no first, by voice or by recorded *confirm* / *dismiss* sounds.
4. **Reboot** – after 60 s without a command, or on the dismiss sound, the writer is unloaded and only step 1 remains.

## The console language (Python-like)

| You type | Meaning |
|---|---|
| `call mom` | run now |
| `if battery.level < 20: say "low"` | check now (`elif`/`else`, nesting by indentation) |
| `at 10:30 pm: call mom` | run once at that time (like an alarm; survives restarts) |
| `every day at 7 am: ...`, `every 30 min: ...` | repeat |
| `after 10 min: ...`, `wait 5 s`, `repeat 3: ...` | delays and loops |
| `when call.answered: hangup` | wait for an event/state, fire once (`whenever` = every time) |
| `set who = "mom"` … `call $who` | variables, `{expr}` interpolation |

Console commands: `help [topic]`, `permissions`, `grant all`, `start listening <key>`, `stop listening`, `start voice_assistant on android`, `record key <name> [confirm|dismiss]`, `test key`, `sensitivity`, `language am|en`, `rules`, `cancel rule N`, `jobs`, `vars`, `names`, `status`, `clear`, `exit`. Type `help` in the app for the full, always-correct list.

Voice works in English and in Amharic (set `language am`) for the common commands.

## Project layout

```
core/   pure Kotlin/JVM: DSP key detector, script language, command registry,
        voice planner (English + Amharic), session state machine. Unit tested.
app/    Android: console screen, foreground service, alarms, phone/accessibility
        executor, permissions.
docs/   ARCHITECTURE.md, TRAINING.md
training/  notes for training your own recogniser (optional)
```

## Build

Needs JDK 17 and the Android SDK (Android Studio does both).

```
./gradlew :core:test          # unit tests
./gradlew :app:assembleDebug  # APK in app/build/outputs/apk/debug/
```

GitHub Actions (`.github/workflows/build.yml`) does the same and uploads the APK.

> **Status:** `:core` has 109 passing tests. The `:app` module was written without an Android SDK available and has **not been compiled yet** — the first build may need small fixes.

## Things Android does not allow (not bugs)

- Unlocking the phone by voice; locking works.
- Knowing when the *other* person answers your outgoing call (`call.answered` is for incoming calls).
- Toggling Wi‑Fi/Bluetooth directly (`settings wifi` opens the page); force-closing apps (`close` = Back).
- Screen control inside banking/secure apps.
- Starting the microphone service from boot on Android 14+: open the app once after a reboot.
- State/event rules (`when battery…`, `call.*`) only run while the app process is alive; time rules always run.
- A key sound can be replayed by anyone — it is a convenience, not security.

## License

MIT
