# Nexus Companion (Nocturne)

A fully offline, on-device AI companion app for Android. Chat and talk with your own LLM — no cloud, no subscriptions, no data leaves your phone.

## Features

### Chat Mode
- On-device LLM inference via [llama.cpp](https://github.com/ggerganov/llama.cpp)
- 5 downloadable models (1.6–7.9 GB), from lightweight to high quality
- Bilingual: German + English (responds in your language automatically)
- Persistent memory system (remembers your name, preferences, mood)
- Conversation summaries for long-term context
- Welcome screen with guided onboarding for first-time users

### Voice Mode (Phone Mode)
- Full STT → LLM → TTS conversation loop
- Works with screen off (ForegroundService + WakeLock)
- Proximity sensor: auto-switches between speaker and earpiece
- Manual speaker toggle button
- Bluetooth headset / car hands-free support (auto-connects)
- Call duration timer
- Visual feedback: pulsating circle changes color per state (listening → thinking → speaking)
- Notification with "Hang up" button

### Model Management
- Download models directly from HuggingFace in the background
- Background downloads with progress notification (survives screen-off)
- Download confirmation dialog for multi-GB files
- Long-press to delete downloaded models and free storage
- LLM on/off toggle in menu (free 2–8 GB RAM when not needed)
- 10-minute idle auto-unload (auto-reloads when you send a message)

### Debug & Developer
- In-app debug log (⋮ Menu → Debug Log)
- Timestamps, color-coded, monospace, auto-scroll
- Logs: model load time, generation time, token count, errors

## Available LLM Models

| Model | Size | Type | Description |
|-------|------|------|-------------|
| **Gemma 3 4B Heretic** | 2.5 GB | Abliterated | Uncensored, multilingual — recommended |
| **Qwen3 4B Abliterated** | 2.7 GB | Abliterated | Thinking mode, uncensored |
| **Noromaid 7B** | 5.1 GB | Roleplay | Warm and romantic |
| **MythoMax 13B** | 7.9 GB | Roleplay | Best quality, expressive |
| **Gemma 2B** | 1.6 GB | General | Battery saver, lightweight |

All models are GGUF Q4_K_M quantized and downloaded by the user at runtime from HuggingFace. The app does not bundle any AI models.

## Voice / TTS Status

### What works now
- **Android System TTS** — built-in, always available, German + English
- Speaker toggle works correctly (uses VOICE_COMMUNICATION audio stream)

### What's prepared (pending upstream llama.cpp support)

| Model | What it does | Status | Blocking on |
|-------|-------------|--------|-------------|
| **Liquid AI LFM2.5-Audio** | End-to-end Audio-LM: ASR+TTS+Chat in one model, DE+EN+6 languages, 1.5B params, ~855 MB | Config + UI ready, downloadable | [llama.cpp PR #18641](https://github.com/ggml-org/llama.cpp/pull/18641) |
| **Sesame CSM** | Highest quality conversational voice, EN only, 1B params | Config ready (experimental) | [llama.cpp Issue #12392](https://github.com/ggml-org/llama.cpp/issues/12392) |

### Why not other TTS models?

| Model | Problem |
|-------|---------|
| OuteTTS 0.3 | [Not supported](https://huggingface.co/OuteAI/OuteTTS-0.3-500M-GGUF/discussions/2) in llama.cpp (only v0.2 works) |
| OuteTTS 1.0 | [WIP PR](https://github.com/ggml-org/llama.cpp/pull/12794) not merged |
| Kokoro | Lives in separate [TTS.cpp](https://github.com/mmwillet/TTS.cpp) project with incompatible GGML fork |
| NeuTTS | English only, GGUF URLs unverified |
| Piper/sherpa-onnx | Works but quality is marginal upgrade over Android System TTS |

**Bottom line:** Neural TTS on Android via llama.cpp is not yet mature. We're prepared for LFM2.5-Audio (the best option) and Sesame CSM — once either PR is merged in llama.cpp, we update the tag and it works.

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
│  UI Layer (Jetpack Compose)                 │
│  ├── ChatScreen        (welcome, chat, dl)  │
│  ├── PhoneScreen       (voice mode, timer)  │
│  ├── DebugLogScreen    (in-app logs)        │
│  ├── ModelSwitcherSheet (download, delete)  │
│  └── VoiceSwitcherSheet (TTS model picker)  │
├─────────────────────────────────────────────┤
│  ViewModel                                  │
│  └── ChatViewModel (state, idle timer)      │
├─────────────────────────────────────────────┤
│  Services                                   │
│  ├── PhoneCallService  (voice, BT, wake)    │
│  └── DownloadService   (background DL)      │
├─────────────────────────────────────────────┤
│  Engines                                    │
│  ├── LlmEngine     (singleton, prompts)     │
│  ├── NeuTtsEngine   (TTS fallback chain)    │
│  ├── CsmEngine      (neural TTS, prepared)  │
│  ├── STT Manager    (SpeechRecognizer)       │
│  └── MemoryExtractor (regex + keyword)      │
├─────────────────────────────────────────────┤
│  Native (C++ / JNI)                         │
│  ├── llama_jni.cpp  (LLM inference)         │
│  └── tts_jni.cpp    (TTS, prepared)         │
├─────────────────────────────────────────────┤
│  llama.cpp (pinned to tag b8508)            │
│  ├── common (sampler, tokenizer, batch)     │
│  ├── KleidiAI (ARM NEON/SVE)               │
│  └── OpenMP (multi-threaded GEMM)           │
└─────────────────────────────────────────────┘
```

## Comparison with Sesame Maya

| Feature | Sesame Maya | Nexus |
|---------|------------|-------|
| Voice quality | World-class (CSM) | Android System TTS |
| Emotional intelligence | Detects mood from voice tone | Detects mood from text |
| Response time | 200–300ms | 5–15 seconds |
| **Long-term memory** | ~2 min context only | **Persistent DB** |
| **Offline** | No (cloud-based) | **Yes (100% on-device)** |
| **Privacy** | E2E encrypted, but cloud | **Nothing leaves device** |
| **Multilingual** | English only | **German + English** |
| **Model choice** | Fixed (Maya/Miles) | **5 models, user's choice** |
| **Cost** | Will be paid subscription | **Free, open source** |
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
mkdir -p external
git clone --depth 1 --branch b8508 \
  https://github.com/ggerganov/llama.cpp.git external/llama.cpp

echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

### CI/CD
GitHub Actions builds on every push:
- Unit tests → Debug APK → Release APK
- Artifacts uploaded with 90-day retention
- llama.cpp pinned to `b8508` for reproducible builds
- Release created automatically on `v*` tags

## Requirements
- Android 9+ (API 28)
- arm64-v8a device (Pixel, Samsung Galaxy, etc.)
- Min. 4 GB free storage (for smallest model)
- Min. 6 GB RAM recommended

## Known Limitations

1. **Neural TTS not yet functional** — Android System TTS is the only working voice output. Waiting for llama.cpp upstream (LFM2.5-Audio PR #18641 or Sesame CSM Issue #12392).
2. **No streaming text** — full response generated before display (no token-by-token UI updates).
3. **Generation can be slow** — 5–15 seconds on a Pixel 9 with 4B models. 30-second timeout prevents infinite hangs.
4. **First launch requires download** — 1.6–7.9 GB model download needed before first use.
5. **Noromaid 7B URL** — NeverSleep repo has non-standard filenames, may need verification.

## Privacy

- **100% offline** — no network calls except model downloads from HuggingFace
- **No analytics, no tracking, no telemetry**
- **No accounts** — no sign-up, no login required
- **All data stored locally** in app-private storage
- **Models downloaded by user** from public HuggingFace repos

## License

Copyright 2025–2026 McMarius11

Licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE) for details.

Third-party attributions: [NOTICE](NOTICE)

**Note on AI models:** Models are not bundled with the app. They are downloaded by the user at runtime. Each model has its own license (Noromaid: CC-BY-NC, Gemma: Google terms, Qwen: Apache 2.0, MythoMax: Llama 2 license). Users are responsible for complying with respective model licenses.
