# Qwen3 TTS Android

Minimal Android app for on-device Qwen3-TTS synthesis using `qwen3-tts.cpp` as a Git submodule.

## What is included

- Jetpack Compose single-screen UI
- `external/qwen3-tts.cpp` submodule with recursive `ggml` submodule
- Android CMake wrapper that builds only `qwen3_tts_jni` for `arm64-v8a`
- In-app downloader for:
  - `qwen-tokenizer-12hz-Q8_0.gguf`
  - `qwen-talker-0.6b-base-Q8_0.gguf`
- On-device model loading, text synthesis, and AudioTrack playback

## Build

```powershell
git submodule update --init --recursive
.\gradlew.bat :app:assembleDebug
```

The debug APK is generated at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Notes

The app downloads models from `Serveurperso/Qwen3-TTS-GGUF` on Hugging Face into the app-private files directory. The first synthesis after loading can take a while on mobile CPU, and the current native build is CPU-only.

