# Nexus Companion

Android-App mit lokaler KI als Companion. Alle Modelle laufen komplett auf dem Gerät via [llama.cpp](https://github.com/ggerganov/llama.cpp) — keine Cloud, keine Daten die das Handy verlassen.

## Features

### KI-Chat
- On-Device LLM-Inferenz via llama.cpp (JNI)
- Deutschsprachiger Companion mit eigenem Charakter
- Chat-Verlauf wird lokal in Room-Datenbank gespeichert
- Alpaca-Prompt-Format, kompatibel mit GGUF-Modellen

### Modell-Auswahl
| Modell | Groesse | Akku/Stunde | Beschreibung |
|--------|---------|-------------|--------------|
| Noromaid 7B | 4.1 GB | ~13% | Standard — warm und romantisch |
| MythoMax 13B | 7.9 GB | ~25% | Beste Qualitaet — ausdrucksstark |
| Gemma 2B | 1.5 GB | ~6% | Akkusparer — leicht und schnell |

Modelle werden direkt von HuggingFace heruntergeladen (GGUF Q4_K_M).

### Sprache
- **Speech-to-Text** — Android SpeechRecognizer (Deutsch)
- **Text-to-Speech** — Android TTS als Fallback, NeuTTS-Integration vorbereitet
- **Phone-Modus** — Freisprechen wie ein Telefonat: automatisches Zuhoeren nach jeder KI-Antwort

### Memory-System
- Erkennt automatisch persoenliche Infos (Name, Beruf, Interessen, Haustiere, Vorlieben)
- Speichert Erinnerungen mit Confidence-Score in lokaler Datenbank
- KI bezieht sich auf gespeicherte Erinnerungen in Antworten

### UI
- OLED-optimiertes Dark Theme (pure black)
- Jetpack Compose mit Material3
- Chat-Bildschirm mit Message-Bubbles und Zeitstempeln
- Phone-Bildschirm mit pulsierender Animation beim Zuhoeren
- Model-Switcher als Bottom Sheet

## Tech Stack

- **Kotlin** + Jetpack Compose
- **llama.cpp** via JNI (C++ Native Build)
- **Room** Datenbank
- **OkHttp3** fuer Modell-Downloads
- **Coroutines** fuer async Verarbeitung
- **Android SpeechRecognizer** + **TextToSpeech**

## Voraussetzungen

- Android 9+ (API 28)
- arm64 Geraet (z.B. Pixel 9)
- Min. 4 GB freier Speicher (fuer kleinstes Modell)
- JDK 17, Android SDK 35, NDK 27.2

## Bauen

### Automatisch (Fedora Linux)

```bash
./setup.sh
```

### Manuell

```bash
# llama.cpp klonen
mkdir -p external
git clone --depth 1 --recurse-submodules --shallow-submodules \
    https://github.com/ggerganov/llama.cpp.git external/llama.cpp

# local.properties anlegen
echo "sdk.dir=$ANDROID_HOME" > local.properties

# APK bauen
./gradlew assembleDebug
```

Die APK liegt dann unter `app/build/outputs/apk/debug/app-debug.apk`.

### CI/CD

Der GitHub Actions Workflow baut bei jedem Push automatisch eine APK und laedt sie als Artifact hoch. Bei einem Git-Tag (`v*`) wird automatisch ein GitHub Release erstellt.

## Projektstruktur

```
app/src/main/java/com/nexus/companion/
├── MainActivity.kt              # Entry Point, Navigation
├── NexusApp.kt                  # Application-Klasse
├── data/                        # Room DB, DAO, Repository
├── llm/                         # LlamaJni, LlmEngine, ModelManager
├── memory/                      # Memory-Extraktion und Speicherung
├── stt/                         # Speech-to-Text Manager
├── tts/                         # Text-to-Speech Engine
├── ui/
│   ├── components/              # MessageBubble, ModelSwitcher
│   ├── screens/                 # ChatScreen, PhoneScreen
│   └── theme/                   # Color, Theme, Typography
└── viewmodel/                   # ChatViewModel

llama-jni/
├── CMakeLists.txt               # Native Build (llama.cpp)
└── llama_jni.cpp                # JNI Bridge
```
