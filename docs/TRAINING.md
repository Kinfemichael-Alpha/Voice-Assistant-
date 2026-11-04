# Training / replacing the command writer

The default writer is Android's `SpeechRecognizer` (offline if the language pack is downloaded). To use your own small model:

1. Pick a model: Whisper-tiny/base (fine-tuned on your phrases, e.g. in Google Colab), or Vosk.
2. Fine-tune on recordings of your command set (English + Amharic). Distillation from a larger model's transcripts also works.
3. Export (ONNX / TFLite / whisper.cpp) and implement the same `load / listen / cancel / destroy` interface as `speech/Speech.kt: Writer`.
4. Keep it loaded **only** while the session is in WRITER_* states; `SessionMachine` already asks for `LoadWriter` / `UnloadWriter`.

Keep the command grammar small: the `VoicePlanner` maps text to commands, so the model only needs to transcribe well, not understand.
