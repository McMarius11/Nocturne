# Nexus Companion (Nocturne)

A fully offline, on-device AI companion app for Android. Chat and talk with your own LLM — no cloud, no subscriptions, no data leaves your phone.

## Features

### Chat Mode
- On-device LLM inference via [llama.cpp](https://github.com/ggml-org/llama.cpp) (b8648)
- 4 downloadable models (2.7–7.9 GB), including Gemma 4 with 128K context
- **Streaming text** — tokens appear in real-time as they're generated
- **Code block rendering** — ``` blocks shown in monospace with dark background
- **Long-press to copy** messages to clipboard
- Bilingual: German + English (responds in your language automatically)
- Persistent memory system (remembers your name, preferences, mood)
- Conversation summaries for long-term context
- **3-page onboarding** for first-time users

### Voice Mode (Phone Mode)
- Full STT → LLM → TTS conversation loop
- **Neural TTS**: Kokoro (EN, high quality) + Piper thorsten (DE) via [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)
- Android System TTS as always-available fallback
- Works with screen off (ForegroundService + WakeLock)
- Proximity sensor: auto-switches between speaker and earpiece
- Bluetooth headset / car hands-free support (auto-connects)
- Visual feedback: pulsating circle with distinct colors per state (cyan=listening, yellow=thinking, green=speaking)
- Call duration timer + notification with "Hang up" button

### Model Management
- Download models from HuggingFace in the background (ForegroundService + WakeLock)
- **RAM & storage warnings** before downloading large models
- Download confirmation dialog showing available space
- Visible delete button on downloaded models
- LLM on/off toggle with 10-minute idle auto-unload
- Auto-reload when sending a message after idle unload

### Settings & Debug
- **Settings screen** with LLM toggle, model info, RAM/storage overview
- In-app debug log with color-coded entries and system info header
- **Copy all logs** to clipboard for bug reports
- Model load time, generation speed (tokens/sec), first-token latency

## Available LLM Models

| Model | Size | Type | Description |
|-------|------|------|-------------|
| **Gemma 4 E4B** | 5.0 GB | Abliterated | **Recommended** — Gemma 4, uncensored, 128K context, multilingual |
| **Qwen3 4B Abliterated** | 2.7 GB | Abliterated | Thinking mode, uncensored, compact |
| **Noromaid 7B** | 5.1 GB | Roleplay | Warm and romantic |
| **MythoMax 13B** | 7.9 GB | Roleplay | Best quality, expressive |

All models are GGUF Q4_K_M quantized and downloaded by the user at runtime from HuggingFace. The app does not bundle any AI models.

## Voice / TTS

| Engine | Quality | Languages | Size | Runtime |
|--------|---------|-----------|------|---------|
| **Kokoro 82M** | High | EN (11 voices) | 346 MB | sherpa-onnx (ONNX) |
| **Piper thorsten** | Good | DE (emotional) | 75 MB | sherpa-onnx (ONNX) |
| **Android System TTS** | Basic | DE + EN | 0 MB | Built-in |

Neural TTS runs on-device via [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) (ONNX Runtime). Models are downloaded at runtime as tar.bz2 archives and extracted automatically.

### Future TTS (pending upstream llama.cpp)

| Model | Description | Blocking on |
|-------|-------------|-------------|
| **LFM2.5-Audio** | End-to-end Audio-LM: ASR+TTS+Chat, DE+EN+6 langs | [llama.cpp PR #18641](https://github.com/ggml-org/llama.cpp/pull/18641) |
| **Sesame CSM** | Highest quality conversational voice, EN only | [llama.cpp Issue #12392](https://github.com/ggml-org/llama.cpp/issues/12392) |

## Memory System

The app extracts and stores personal facts from conversations in both German and English:

- **Core identity**: name, age, job, location — always included in LLM context
- **Interests**: likes, dislikes, hobbies — accumulating lists (never overwrites)
- **Emotional state**: mood detection from phrases, recent emotions (<1h) in context
- **Conversation summaries**: auto-generated every 10 messages for long-term memory
- **Relevance-based retrieval**: keyword matching pulls relevant memories for current topic
- **Bidirectional**: extracts from both user messages AND assistant responses

Memory is stored in a local Room database and persists across app restarts.

## Architecture

```
┌─────────────────────────────────────────────┐
│  UI Layer (Jetpack Compose + Material 3)    │
│  ├── ChatScreen       (streaming, code)     │
│  ├── PhoneScreen      (voice mode)          │
│  ├── SettingsScreen   (config, system info)  │
│  ├── DebugLogScreen   (logs, copy)          │
│  ├── OnboardingOverlay (first launch)       │
│  ├── ModelSwitcherSheet (download, delete)  │
│  └── VoiceSwitcherSheet (TTS model picker)  │
├─────────────────────────────────────────────┤
│  ViewModel + Navigation                     │
│  ├── ChatViewModel (state, idle timer)      │
│  └── Screen sealed class (type-safe nav)    │
├────────────────────���────────────────────────┤
│  Services                                   │
│  ├── PhoneCallService  (voice, BT, wake)    │
│  └── DownloadService   (DL + tar.bz2)      │
���─────────────────────��───────────────────────┤
│  Engines                                    │
│  ├── LlmEngine      (singleton, prompts)    │
│  ├── NeuTtsEngine    (Kokoro/Piper/System)  │
│  ├── SherpaOnnxTts   (sherpa-onnx wrapper)  │
│  ├── STT Manager     (SpeechRecognizer)     │
│  └── MemoryExtractor (regex + keyword)      │
├────────────────────��────────────────────────┤
│  Native (C++ / JNI)                         │
│  └── llama_jni.cpp   (LLM inference)        │
├─��────────────────────────────────────���──────┤
│  sherpa-onnx (TTS runtime)                  │
│  ├── libsherpa-onnx-jni.so                  ���
│  ├── libonnxruntime.so                      │
│  └── Kokoro / Piper ONNX models            │
├─────────────────────────────────────────────┤
│  llama.cpp (pinned to tag b8648)            │
│  ├── common (sampler, tokenizer, batch)     ��
│  ├── KleidiAI (ARM NEON/SVE)               │
│  └── OpenMP (multi-threaded GEMM)           │
└─────��──────────────────────────────���────────┘
```

## Comparison with Sesame Maya

| Feature | Sesame Maya | Nexus |
|---------|------------|-------|
| Voice quality | World-class (CSM) | Kokoro (EN) + Piper (DE) + System TTS |
| Emotional intelligence | Detects mood from voice tone | Detects mood from text |
| Response time | 200–300ms | 5–15 seconds |
| **Long-term memory** | ~2 min context only | **Persistent DB** |
| **Offline** | No (cloud-based) | **Yes (100% on-device)** |
| **Privacy** | E2E encrypted, but cloud | **Nothing leaves device** |
| **Multilingual** | English only | **German + English** |
| **Model choice** | Fixed (Maya/Miles) | **4 LLM + 2 TTS models** |
| **Cost** | Paid subscription | **Free, open source** |
| **Uncensored** | No | **Yes (abliterated models)** |
| **Open source** | No | **Apache 2.0** |

## Building

### Prerequisites
- Android Studio Hedgehog or later
- JDK 17
- Android SDK 35, NDK 27.2.12479018, CMake 3.22.1

### Build from source
```bash
git clone https://github.com/McMarius11/Nocturne.git
cd Nocturne

# LLM engine
mkdir -p external
git clone --depth 1 --branch b8648 \
  https://github.com/ggml-org/llama.cpp.git external/llama.cpp

# TTS engine (sherpa-onnx pre-built libs)
SHERPA_VERSION="1.12.34"
wget -q "https://github.com/k2-fsa/sherpa-onnx/releases/download/v${SHERPA_VERSION}/sherpa-onnx-v${SHERPA_VERSION}-android.tar.bz2"
tar xjf "sherpa-onnx-v${SHERPA_VERSION}-android.tar.bz2"
mkdir -p app/src/main/jniLibs/arm64-v8a
cp jniLibs/arm64-v8a/libsherpa-onnx-jni.so app/src/main/jniLibs/arm64-v8a/
cp jniLibs/arm64-v8a/libonnxruntime.so app/src/main/jniLibs/arm64-v8a/
rm -rf jniLibs *.tar.bz2

echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew assembleDebug
```

Or use the automated setup script:
```bash
./setup.sh
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

### CI/CD
GitHub Actions builds on every push:
- Unit tests → Debug APK → Release APK
- llama.cpp `b8648` + sherpa-onnx `1.12.34` downloaded automatically
- Artifacts uploaded with 90-day retention
- Release created automatically on `v*` tags

## Requirements
- Android 9+ (API 28)
- arm64-v8a device (Pixel, Samsung Galaxy, etc.)
- Min. 4 GB free storage (for smallest model)
- Min. 6 GB RAM recommended (8 GB for Gemma 4 E4B)

## Known Limitations

1. **Generation speed** — 5–15 seconds on Pixel 9 with 4B models. 60-second timeout.
2. **Gemma 4 tokenizer** — llama.cpp PR #21343 may not be in b8648. If `<unused24>` tokens appear, update llama.cpp.
3. **Neural TTS quality** — Kokoro is English-only (high quality), Piper covers German (good quality). Full multilingual neural TTS pending LFM2.5-Audio.
4. **First launch** — 2.7–7.9 GB model download needed before first use.

## Privacy

- **100% offline** — no network calls except model downloads
- **No analytics, no tracking, no telemetry**
- **No accounts** — no sign-up, no login
- **All data stored locally** in app-private storage
- **Models downloaded by user** from public repos (HuggingFace, GitHub)

## License

Copyright 2025–2026 McMarius11

Licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE) for details.

Third-party attributions: [NOTICE](NOTICE)

**Note on AI models:** Models are not bundled with the app. They are downloaded by the user at runtime. Each model has its own license:
- Gemma 4: [Google Gemma Terms](https://ai.google.dev/gemma/terms)
- Qwen3: Apache 2.0
- Noromaid: CC-BY-NC-4.0
- MythoMax: Llama 2 Community License
- Kokoro: Apache 2.0
- Piper voices: MIT

Users are responsible for complying with respective model licenses.
