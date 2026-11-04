# Ignis

Ignis is an Android app, written in Kotlin, that you control with a **key sound** and a **black console** (like CMD). In the console you type commands or write small Python-like programs that react to the phone's **states** (battery 20%, Wi-Fi connected, screen locked ...) and perform **actions** (call, volume, flashlight, lock ...).

```
> if battery.level < 20 and not battery.charging: say "please charge me"
> while battery.level < 80: wait 5 min
> at 10:30 pm:
...     call mom
...     when call.answered:
...         hangup
...
> all current conditions
> all possible actions
```

Contents: [1 Install](#1-install) | [2 First start](#2-first-start) | [3 The console](#3-the-console) | [4 States, actions, events](#4-states-actions-and-events) | [5 Programming](#5-programming-guide) | [6 Voice](#6-voice-control) | [7 Opening apps](#7-opening-apps-and-choosing) | [8 Command reference](#8-command-reference) | [9 Examples](#9-examples) | [10 Troubleshooting](#10-troubleshooting) | [11 Limits](#11-what-android-does-not-allow) | [12 Build](#12-build-and-signing)

---

## 1. Install

1. Open the repository on GitHub, go to **Actions**, open the latest green **Build** run.
2. Under **Artifacts** download `dimts-debug-apk` (a zip), unzip it, and copy the `.apk` to the phone.
3. Open the `.apk` on the phone and allow "install unknown apps" for your file manager or browser when Android asks.

### "App not installed as package conflicts with an existing package"

Android only installs an update over an app when both were signed with the **same key**. Builds made before the fixed key was added (commit "Sign every build with a fixed key") were signed with a random key each time.

**One time only:** uninstall the old Ignis (Settings > Apps > Ignis > Uninstall), then install the new `.apk`. This deletes your recorded key sounds, rules and variables. From this build on, every new build is signed with the same key and installs over the previous one with all data kept.

---

## 2. First start

1. Open **Ignis**. A black console appears.
2. Type `permissions` to see what is allowed, then `grant all`. Android asks for the pop-up permissions; when it opens a settings page, switch the item on, come back and type `grant all` again until it says *everything is granted*.

| Permission | Needed for |
|---|---|
| microphone | hearing your key sound and commands |
| notifications | the "listening" notice, `notify` |
| phone, contacts | `call`, `answer`, `hangup`, names like "mom", `call.*` events |
| sms (optional) | `sms` |
| accessibility | `tap`, `scroll`, `swipe`, `back`, `home`, `lock`, `screenshot`, `read screen` (on Samsung: Settings > Accessibility > Installed apps > **Ignis screen control**; if it says *Restricted setting*, see [Troubleshooting](#10-troubleshooting)) |
| alarms | rules fire on the exact minute even while the phone sleeps |
| battery (optional) | keeps the key listener alive in the background (choose "unrestricted") |
| overlay (optional) | lets rules open apps while the screen is off or another app is on top |
| dnd (optional) | `dnd`, `ringer silent` |
| write_settings (optional) | `brightness`, `rotate` |
| bluetooth (optional) | the `bluetooth.on` state |

Grant a single one with `grant accessibility`, `grant dnd`, `grant write_settings` and so on.

3. Type `help` to see everything, `version` to see the build.

---

## 3. The console

| Control | What it does |
|---|---|
| input line + keyboard Enter | runs the line |
| line ending in `:` | starts a block; keep typing indented lines; an **empty line** runs the block |
| **TAB** | completes the command you are typing |
| **UP / DOWN** | previous / next command from history |
| **ESC** | throws away a half-typed block |
| **MIC** | same as `start voice_assistant on android` |
| `# text` | comment (ignored) |
| pasting several lines | runs them as one script |

Typing a wrong command suggests the right one: `statuss` gives *did you mean 'status'?*

Useful basics: `help`, `help <command>`, `status`, `clear`, `exit` (closes the console; rules and listening keep running).

---

## 4. States, actions and events

Everything the app understands is one of three things:

- **State**: something that **is** true now (`battery.level`, `wifi.connected`). Use it in conditions.
- **Action**: something you **do** (`volume`, `call`, `flashlight`). Use it as a command.
- **Event**: something that **happens** at one instant (`call.missed`, `screen.unlocked`). Wait for it with `when` / `whenever`.

### List them (live)

```
all current conditions      every state with its value right now
states battery              only one group (battery, network, screen, sound, phone, system, time, assistant)
help battery.level          explains one state with an example
all possible actions        every action the app can do
actions sound               only one group
events                      every event
defs                        the actions and states you defined yourself
```

### States

| Group | States |
|---|---|
| time | `time` `hour` `minute` `day` `date` `month` `year` `random` |
| battery | `battery.level` `battery.charging` `battery.plugged` (none/ac/usb/wireless) `battery.temperature` `battery.health` `battery.saver` |
| network | `wifi.connected` `mobile.connected` `network.connected` `network.type` `airplane.mode` `bluetooth.on` `headphones.connected` |
| screen | `screen.awake` `screen.locked` `screen.brightness` `app.current` |
| sound | `volume` `volume.ring` `volume.alarm` `volume.notification` `ringer.mode` `dnd.mode` `audio.playing` `flashlight.on` |
| phone | `call.state` (idle/ringing/offhook) `call.contact` `call.number` |
| system | `memory.free` `storage.free` `uptime.minutes` `device.model` `android.version` |
| assistant | `assistant.state` `listening` |

Type `all current conditions` for the exact, live list on your phone. A state that Android cannot report on your phone shows `-`.

### Events

`call.incoming` `call.answered` `call.started` `call.ended` `call.missed` `key.heard` `assistant.started` `assistant.stopped` `screen.on` `screen.off` `screen.unlocked` `power.connected` `power.disconnected` `battery.low` `battery.okay` `boot`

### Actions

Type `all possible actions`. Groups: listening, rules, states/events/actions, phone, screen, sound, system. Section 8 lists them all.

---

## 5. Programming guide

Blocks are made with **indentation** and every block header ends with `:` (just like Python). Blocks can be nested as deep as you like.

### Check now: `if`

```
if battery.level < 20: say "low"
if battery.level < 20 and not battery.charging:
    say "please charge me"
elif battery.level < 50:
    say "half"
else:
    say "fine"
```
Conditions: `==  !=  <  <=  >  >=  is  is not  contains  between A and B  and  or  not  ( )`
Examples: `time between 10 pm and 6 am`, `app.current contains chrome`, `day is monday`, `network.type is wifi`.

### Loops: `while`, `repeat`, `break`, `continue`

```
set n = 0
while n < 3:
    n += 1
    if n == 2: continue
    say "round {n}"

while battery.level < 80 and battery.charging:
    wait 1 min
say "charged"

repeat 3:
    vibrate 200
    wait 1 s
```
`break` leaves the loop, `continue` jumps to the next round. A loop is stopped after 100000 steps so a mistake cannot freeze the phone.

### Wait for something: `when`, `whenever`

```
when call.answered: hangup                       once
whenever battery.level < 15: notify "low"        every time it becomes true
whenever call.missed: notify "missed a call from {call.contact}"
```
They fire when the condition turns from false to true. A rule already true when you type it waits until it becomes false and true again.
State rules are re-checked every 15 seconds while the app process is alive.

### Time: `at`, `every`, `after`, `wait`

```
at 10:30 pm: call mom
at 7 am on mon, fri: say "gym day"
every day at 7 am: say "good morning"
every weekdays at 8:30 am: open calendar
every 30 min: notify "stand up"
after 10 min: say "tea is ready"
wait 5 s                                         pause the rest of this block
```
Time rules are saved and fire even while the phone sleeps (needs the *alarms* permission). A time missed while the phone was off still runs if it is less than 2 hours late. Manage them with `rules`, `cancel rule N`, `clear rules`, `jobs`, `cancel job N`.

### Variables

```
set who = "mom"        or just   who = "mom"
x += 1   x -= 1   x *= 2   x /= 2   x %= 3
call $who
say "{battery.level}% at {time}"
vars        unset who
eval battery.level + 5
```
Numbers, `"text"`, `true/false`, times (`10:30 pm`), operators `+ - * / %`.

### Your own states: `state`

```
state night = time between 10 pm and 6 am
state low_power = battery.level < 20 and not battery.charging
state quiet = night and not call.incoming

if low_power: say "please charge me"
whenever low_power: notify "battery low"
```
A defined state is always up to date when read. It appears in `all current conditions` under YOUR STATES.

### Your own actions: `def`

```
def sleep_mode:
    volume 10
    brightness 5
    dnd on
    say "good night"

def greet:
    say "hello {arg1}"

sleep_mode
greet Kinfemichael
at 11 pm: sleep_mode
```
Arguments arrive as `arg1`, `arg2`, ... and `{args}` is how many were given. Actions may call other actions. You cannot name an action like an existing command. Both `state` and `def` are saved; `defs` lists them, `undef NAME` deletes one.

### Other statements

`pass` (do nothing), `stop` (end this script), `# comment`, inline blocks with `;`: `if x == 1: say one; say uno`.

---

## 6. Voice control

1. **Record your key sound** (a word, whistle, clap pattern): `record key wake_word`, then make the sound each time the app beeps (4 times).
2. **Start listening**: `start listening wake_word`. Only a tiny sound-shape detector runs; no AI model is loaded, so battery and RAM use stay low.
3. **Make the key sound**, then speak one command: "call mom", "open camera", "scroll down", "volume 40", "all current conditions".
4. After 60 s without a command the speech recogniser is unloaded and only the key listener remains (`timeout 120` changes it).

Extras: `record key yes_sound confirm` and `record key bye_sound dismiss` give you sounds for "yes" and "close". `keys` lists keys, `test key` shows how close your sound is, `sensitivity 1.3` makes matching easier, `delete key NAME`.

Risky commands (`call`, `sms`) ask **yes or no** first when spoken. Say "yes" / "no" or use the confirm/dismiss sounds.

Voice also understands: "run <any console command>", "set a timer for 10 minutes", "alarm 7:30 am", "turn on do not disturb", "pick two" / "the second one", "all possible actions".

**Amharic:** `language am` makes the common commands work in Amharic (needs the Amharic speech pack on the phone). `language en` switches back.

---

## 7. Opening apps and choosing

`open <name>` opens the app with exactly that name. If there is no exact match, Ignis finds similar names, also by **sound**, and lets you choose:

```
> open cloud
no app is called exactly 'cloud'. Did you mean:
  1. Claude
  2. Clock
Choose with:  pick <number>
> pick 1
opening Claude
```
By voice it reads the options aloud; say "pick one" or "the first one". `pick` alone chooses option 1.

---

## 8. Command reference

Type `help <command>` for details and examples. Several names work for most commands (`help call` lists them).

**Listening & assistant**
`start listening [key]`, `stop listening`, `record key <name> [wake|confirm|dismiss]`, `use key <name> as <role>`, `keys`, `delete key <name>`, `test key [seconds]`, `sensitivity [0.3-3.0]`, `start voice_assistant on android` (or `wake`), `stop assistant`, `language [en|am]`, `timeout [seconds]`

**Rules, variables, scripts**
`rules`, `cancel rule <id>`, `clear rules`, `jobs`, `cancel job <id|all>`, `vars`, `unset <name>`, `names`, `eval <expression>`

**States, events, actions**
`all current conditions` (or `states [filter]`), `events`, `all possible actions` (or `actions [filter]`), `defs`, `undef <name>`

**Phone**
`call <contact|number>`, `hangup`, `answer`, `sms <contact> <text>`, `open <app>`, `pick [number]`, `search <text>`, `browse <url>`, `say <text>`, `notify <text>`, `contact <name>`, `flashlight <on|off|toggle>`, `settings [page]`, `alarm <time> [label]`, `timer <duration> [label]`, `copy <text>`, `share <text>`, `map <place>`

Settings pages: wifi, bluetooth, display, sound, battery, apps, location, accessibility, airplane, nfc, date, language, storage, privacy, security, vpn, roaming, cast, night, wireless, sync, info, input, usage.

**Screen** (needs accessibility, brightness/rotate need write_settings)
`back`, `home`, `recents`, `notifications`, `lock`, `scroll <up|down>`, `swipe <dir>`, `tap <x> <y>` or `tap <text on screen>`, `type <text>`, `screenshot`, `read screen`, `brightness <0-100|auto>`, `rotate <on|off>`

**Sound & media**
`volume [music|ring|alarm|notification|call|system] <up|down|0-100|mute|unmute>`, `play`, `pause`, `next`, `previous`, `stop music`, `ringer <normal|vibrate|silent>`, `dnd <on|off|alarms|priority>`, `vibrate [ms]`

**System**
`permissions`, `grant [name|all]`, `status`, `help [topic]`, `clear`, `echo <text>`, `version`, `exit`

Help topics: `help script`, `if`, `while`, `when`, `at`, `every`, `after`, `wait`, `repeat`, `set`, `def`, `state`, `concepts`, `nesting`, `time`, `names`, `examples`, `voice`, `keys`, `permissions`, `limits`.

---

## 9. Examples

```
# Call mom at 10:30 pm and hang up if she answers
at 10:30 pm:
    call mom
    when call.answered:
        hangup

# Night mode every day
def night_mode:
    volume 10
    brightness 5
    dnd on
every day at 11 pm: night_mode

# Battery saver routine
whenever battery.level < 20 and not battery.charging:
    brightness 10
    notify "battery low, brightness reduced"

# Say who is calling (needs Phone + Contacts permissions)
whenever call.incoming and call.contact is mom: say "mom is calling"

# Welcome message when you unlock the phone at home
whenever screen.unlocked:
    if network.type is wifi and hour < 12: say "good morning"

# Reminder every 45 minutes, only 8 am - 6 pm
every 45 min:
    if time between 8 am and 6 pm: notify "stretch"

# Warn when plugged in too long
when power.connected:
    wait 2 h
    if battery.level >= 100: notify "unplug the charger"
```

---

## 10. Troubleshooting

| Problem | Fix |
|---|---|
| *App not installed as package conflicts with an existing package* | Uninstall the old Ignis once (see [Install](#1-install)); later updates install over it. |
| *Restricted setting - this setting is unavailable* when switching on accessibility | Android blocks this for apps installed outside the Play Store. Settings > Apps > Ignis > **⋮** (top right) > **Allow restricted settings**, confirm, then switch the service on again. |
| `tap`, `scroll`, `back` say the accessibility service is off | `grant accessibility`, then in Settings > Accessibility > Installed apps (or Installed services) switch **Ignis screen control** on and tap Allow. |
| `brightness` / `rotate` refuse | `grant write_settings`. |
| `dnd` / `ringer silent` refuse | `grant dnd`. |
| Rules stop reacting after a while | `grant battery` and choose *unrestricted*; open the app once after a reboot (Android 14+). |
| `open` does nothing while the screen is off | `grant overlay`. |
| Key sound is not recognised | Re-record in the place you use it, use `test key`, raise `sensitivity`. |
| A state shows `-` | Android cannot report it on this phone, or its permission is missing (`permissions`). |
| Unknown name warning in a rule | A typo: the warning suggests the right name; `names` lists everything. |

---

## 11. What Android does not allow

These are Android rules, not bugs:

- Unlocking the phone by voice (locking works).
- Knowing when the **other** person answers your outgoing call (`call.answered` is for incoming calls).
- Switching Wi-Fi or Bluetooth on and off directly: `settings wifi` opens the page instead.
- Force-closing other apps (`close` presses Back).
- Screen control inside banking and other secure apps.
- Starting the microphone service at boot on Android 14+: open the app once after a reboot.
- State and event rules only run while the app process is alive; time rules always run.
- A recorded key sound can be replayed by anyone: it is a convenience, not security.

---

## 12. Build and signing

Needs JDK 17 and the Android SDK (Android Studio has both):

```
./gradlew :core:test          # unit tests (plain JVM)
./gradlew :app:assembleDebug  # APK in app/build/outputs/apk/debug/
```

GitHub Actions (`.github/workflows/build.yml`) does the same on every push and uploads the APK; you can also start it by hand with **Run workflow**. Every build gets a higher `versionCode` (the run number), so each one installs as an update.

**App ID and signing:** the package id is `com.dimts.assistant` and every build is signed with the key in `keystore/ignis-debug.p12`. Keeping the same id and key is what lets updates install over each other. Do not change either, or Android will report a package conflict again. The key is public in this repository, which is fine for a personal app installed by hand; if you ever publish the app, create a private key and keep it out of Git.

### Project layout

```
core/      pure Kotlin/JVM: key-sound detector, script language, state/event catalog,
           command registry, voice planner (English + Amharic), session state machine. Unit tested.
app/       Android: console screen, foreground service, alarms, phone and accessibility executor, permissions
keystore/  fixed signing key
docs/      ARCHITECTURE.md, TRAINING.md
training/  notes for training your own speech recogniser (optional)
```

## License

MIT
