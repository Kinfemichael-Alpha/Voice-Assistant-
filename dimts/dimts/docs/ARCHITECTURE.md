# Architecture

```
mic ─▶ KeyListener (AudioRecord thread) ─▶ KeyDetector (core/audio: energy gate → MFCC → DTW)
                                                │ key heard
                                                ▼
                    SessionMachine (core/session) — pure state machine
        OFF ─▶ LISTENING ─▶ WRITER_LOADING ─▶ WRITER_ACTIVE ⇄ AWAITING_CONFIRM ─▶ LISTENING
                                                │ effects: StartKeys, LoadWriter, ListenForSpeech, Speak, Run…
                                                ▼
 Writer (Android SpeechRecognizer) ─▶ VoicePlanner (EN/AM text → command line) ─▶ Shell.submit
                                                                                     │
 console / rules / voice ───────────────────────────────────────────────────────────▶ Engine (core/script)
                                                                                     │ DeviceHost.perform
                                                                                     ▼
                                                                    PhoneHost (calls, apps, volume, accessibility…)
```

- **core** has no Android dependency and no threads. The host calls `Engine.tick()` / `emit(event)` and asks `nextWakeMillis()` to arm `AlarmManager`.
- **AppRuntime** wires everything; `DimtsApp` owns one instance, so receivers and the console share it.
- **ListenService** exists only to keep the microphone allowed while listening/assistant is on.
- Rules and variables are saved to `files/state.txt` and caught up after restarts (up to 120 min late). `wait` jobs are not saved.
- The key detector stores recorded keys in `files/keys/*.dtk`.
