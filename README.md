# Qwen3 TTS Android

Android app for local, on-device text-to-speech with Qwen3-TTS and
`qwen3-tts.cpp`.

The app is a small native Android companion to the Qwen TTS desktop workflow:
download the model, write text, generate speech locally, manage saved voices,
and replay or export generated WAV files.

## Features

- On-device Qwen3-TTS synthesis through the `qwen3-tts.cpp` native runtime
- Jetpack Compose UI with Studio, Voices, History, and Settings screens
- Q4_K_M model download and loading from app-private storage
- Voice profiles with microphone recording and speaker embedding extraction
- Generation history stored locally with playback, stop, delete, and WAV export
- CPU-only runtime path for current Android builds
- Progress output with elapsed time, estimated audio length, and history-based ETA
- Android launcher icon shared with Qwen TTS Studio

## Model

The Android app currently supports one model package:

- `qwen-tokenizer-12hz-Q4_K_M.gguf`
- `qwen-talker-0.6b-base-Q4_K_M.gguf`

The app downloads both files from
[`Serveurperso/Qwen3-TTS-GGUF`](https://huggingface.co/Serveurperso/Qwen3-TTS-GGUF)
into the app-private files directory. Q8_0 and NVFP4 are intentionally not
exposed in the Android UI right now.

## Requirements

- Android Studio or a local Android SDK installation
- Android NDK through the SDK manager
- JDK 17
- An arm64 Android device, Android 12 / API 31 or newer

The native build targets `arm64-v8a`.

## Build

Clone with submodules, or initialize them after cloning:

```powershell
git submodule update --init --recursive
```

Build a debug APK:

```powershell
.\gradlew.bat :app:assembleDebug
```

Build a release APK:

```powershell
.\gradlew.bat :app:assembleRelease
```

APK outputs:

```text
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/release/app-release.apk
```

The current release build uses the debug signing config. Treat it as a local
test build, not a Play Store release artifact.

## Install On A Device

With a device connected through ADB:

```powershell
adb install -r app\build\outputs\apk\release\app-release.apk
```

On first launch, open Settings and download the Q4_K_M model package. The first
load and first synthesis can take a while on mobile CPU.

## Runtime Notes

- CPU is the only supported runtime path in the app UI.
- Vulkan is not exposed because it is not reliable for this app yet.
- Generated audio is saved automatically in app-private storage.
- Use History -> Export to save a WAV file outside the app.
- ETA during generation appears only after a few previous generations exist.

## Repository Layout

```text
app/                         Android app, Compose UI, JNI wrapper
app/src/main/cpp/            Android CMake integration
app/src/main/java/.../data   Recorder and Room database
external/qwen3-tts.cpp       Native Qwen3-TTS runtime submodule
```

## License

This project is licensed under the MIT License. See [LICENSE](LICENSE).

## Status

This is an early Android app, but the core workflow is usable:

1. Download Q4_K_M.
2. Generate speech from text.
3. Optionally create a voice profile from a microphone recording.
4. Replay or export generated WAV files from History.

Open items before a broader public release:

- Add a real release signing setup.
- Add screenshots to this README.
- Add automated UI or instrumentation smoke tests.
