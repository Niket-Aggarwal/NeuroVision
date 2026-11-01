# NeuroVision 👁️🤖

[![Platform](https://img.shields.io/badge/Platform-Android%208.0%2B%20(API%2026%2B)-brightgreen.svg)](https://developer.android.com)
[![Language](https://img.shields.io/badge/Language-Java%20%7C%20Android%20SDK-orange.svg)](https://developer.android.com/guide)
[![AI Engine](https://img.shields.io/badge/Inference-ONNX%20Runtime%20%7C%20YOLO-blue.svg)](https://onnxruntime.ai/)
[![LLM Assistant](https://img.shields.io/badge/AI%20Assistant-Google%20Gemini-4285F4.svg)](https://aistudio.google.com/)
[![License](https://img.shields.io/badge/License-Apache%202.0%20%2F%20MIT-lightgrey.svg)](#license)

**NeuroVision** is an intelligent, real-time assistive vision and spatial navigation application built for Android. Engineered to empower visually impaired and blind individuals, NeuroVision transforms a smartphone into an autonomous AI co-pilot that perceives physical environments, estimates obstacle hazards, reads text and medication labels, provides turn-by-turn walking guidance, and offers conversational multimodal scene understanding.

---

## 📑 Table of Contents

- [Key Features](#-key-features)
- [Architecture & Core Modules](#-architecture--core-modules)
- [System Requirements](#-system-requirements)
- [Security & API Key Configuration](#-security--api-key-configuration)
- [Getting Started](#-getting-started)
  - [Prerequisites](#prerequisites)
  - [Clone & Environment Setup](#1-clone--environment-setup)
  - [Configure API Keys](#2-configure-api-keys)
  - [Build and Run with Android Studio](#3-build-and-run-with-android-studio)
  - [Build via Command Line (Gradle)](#4-build-via-command-line-gradle)
- [Voice Commands Reference](#-voice-commands-reference)
- [Project Structure](#-project-structure)
- [Settings & Customization](#-settings--customization)
- [Contributing](#-contributing)
- [License](#-license)

---

## 🌟 Key Features

### 1. 🔍 Real-Time Object Detection & Spatial Awareness
- **Edge AI Inference**: Runs `yolo26n.onnx` locally on device via **Microsoft ONNX Runtime**, achieving low-latency inference without requiring an active internet connection.
- **3×3 Spatial FOV Grid**: Segments the camera field of view into nine sectors (top/middle/bottom × left/center/right) to provide precise spatial orientation.
- **Dynamic Multi-Object Tracking**: Tracks objects across consecutive frames to calculate movement vectors, distinguishing approaching hazards from benign background items while suppressing camera shake from walking.
- **Wall & Edge Proximity Detection**: Detects close-range boundaries, obstacles, and flat surfaces to warn the user before impact.

### 2. 🗺️ Turn-by-Turn Pedestrian Navigation
- **Powered by Geoapify**: Real-time routing, geocoding, and map tile rendering tailored for walking.
- **Audio Maneuver Cues**: Continuous voice instructions detailing upcoming turns, distances, waypoint arrivals, and street names.
- **Visual & Haptic Aids**: High-contrast directional maneuver icons, dynamic map previews, and haptic vibration feedback.

### 3. 🎙️ Conversational AI Assistant (Google Gemini)
- **Natural Scene Inquiries**: Ask natural questions like *"Describe what is in front of me"*, *"Are there any obstacles nearby?"*, or *"Is there a chair to sit down?"*.
- **Multimodal Context**: Leverages Google Gemini models (`gemini-3.5-flash-lite`, `gemini-3.5-flash`, `gemini-3.8-flash`) to analyze real-time detections and generate empathetic, concise responses.
- **Hands-Free Wake Word**: Trigger voice commands naturally using built-in wake-word listening ("*Hey Neuro*", "*OK Neuro*") or on-screen touch actions.

### 4. 📄 OCR Signboard & Document Reader
- **Google ML Kit Text Recognition**: Identifies text on packages, books, restaurant menus, office doors, and street signs.
- **Voice Confirmation**: Reads detected text aloud upon user confirmation to prevent unwanted chatter.

### 5. 💊 NFC Smart Medicine & Prescription Reader
- **Touch-to-Speak Medication**: Tap NFC tags attached to prescription bottles, blister packs, or medical devices to instantly hear dosage instructions, names, and precaution warnings.

### 6. 🚨 Emergency SOS System
- **One-Touch / Voice SOS**: Trigger emergency protocol by saying *"Activate emergency"* or tapping the SOS button.
- **Automated Dispatch**: Sends immediate SMS messages with real-time GPS coordinates (`https://maps.google.com/?q=lat,lng`) to designated emergency contacts and automatically dials emergency phone numbers.

---

## 🏗️ Architecture & Core Modules

```
                         ┌────────────────────────────────────────┐
                         │              CameraX / NFC             │
                         │    (Live Video Stream & Tag Reader)    │
                         └───────────────────┬────────────────────┘
                                             │
                       ┌─────────────────────┴────────────────────┐
                       ▼                                          ▼
            ┌──────────────────────┐                   ┌──────────────────────┐
            │   YoloOnnxDetector   │                   │  TextDetectorManager │
            │  (ONNX Runtime Mobile)│                   │   (Google ML Kit)    │
            └──────────┬───────────┘                   └──────────┬───────────┘
                       │                                          │
                       ▼                                          ▼
            ┌──────────────────────┐                   ┌──────────────────────┐
            │ MultiObjectTracker & │                   │  NfcMedicineManager  │
            │   GuidanceEngine     │                   │  (Prescription Audio)│
            └──────────┬───────────┘                   └──────────┬───────────┘
                       │                                          │
                       └─────────────────────┬────────────────────┘
                                             │
                                             ▼
                       ┌──────────────────────────────────────────┐
                       │           Decision & Speech Hub          │
                       │    (SpeechGuide / Android TTS / Haptic)  │
                       └─────────────────────┬────────────────────┘
                                             ▲
                                             │
              ┌──────────────────────────────┴──────────────────────────────┐
              ▼                                                             ▼
   ┌──────────────────────┐                                      ┌──────────────────────┐
   │ GeminiVoiceAssistant │                                      │  NavigationManager   │
   │  (Google Gemini LLM) │                                      │ (Geoapify GPS Route) │
   └──────────────────────┘                                      └──────────────────────┘
```

---

## 💻 System Requirements

- **Operating System**: Android 8.0 (Oreo / API Level 26) or higher.
- **Target SDK**: Android 14 / 15 (API Levels 34 - 36).
- **Architecture**: `arm64-v8a` (recommended), `armeabi-v7a`.
- **Sensors & Permissions**: Camera, GPS / Fine Location, Audio Record, NFC (optional), Phone/SMS (for emergency dispatch).
- **Development Tooling**: Android Studio (Hedgehog 2023.1.1 or newer recommended) with JDK 17+.

---

## 🔐 Security & API Key Configuration

To ensure credentials remain confidential, **all sensitive API keys and signing keystores are strictly decoupled from source control** and gitignored.

Keys are injected into the build via Android Studio's local configuration (`local.properties`), which is never checked into Git.

### Externalized Keys
| Key Identifier | Purpose | Default Behavior if Unset |
|---|---|---|
| `GEMINI_API_KEY` | Google Gemini AI assistant & scene analysis | App prompts for key via voice/settings |
| `GEOAPIFY_API_KEY` | Turn-by-turn routing, geocoding & map tiles | Navigation displays descriptive configuration warning |
| `KEYSTORE_PASSWORD` | Release APK signing keystore password | Debug build uses standard fallback |

---

## 🚀 Getting Started

### Prerequisites

1. Install [Android Studio](https://developer.android.com/studio) (includes Android SDK and OpenJDK 17).
2. Prepare an Android device with **USB Debugging** enabled (Developer Options).

### 1. Clone & Environment Setup

```bash
git clone https://github.com/<your-username>/NeuroVision.git
cd NeuroVision
```

### 2. Configure API Keys

Copy the sample configuration file to `local.properties`:

```bash
# On Windows PowerShell
Copy-Item local.properties.example local.properties

# On macOS / Linux
cp local.properties.example local.properties
```

Open `local.properties` in your favorite editor and populate your keys:

```properties
## Local Configuration for NeuroVision
# Android SDK location (automatically set by Android Studio)
sdk.dir=C\:\\Users\\YourUsername\\AppData\\Local\\Android\\Sdk

# Google Gemini API Key (Get free key from https://aistudio.google.com/)
GEMINI_API_KEY=your_actual_gemini_api_key_here

# Geoapify API Key (Get free key from https://www.geoapify.com/)
GEOAPIFY_API_KEY=your_actual_geoapify_api_key_here
```

> 💡 **Tip**: If you prefer, you can also export them as environment variables in your terminal:
> ```bash
> export GEMINI_API_KEY="your_gemini_api_key"
> export GEOAPIFY_API_KEY="your_geoapify_api_key"
> ```

### 3. Build and Run with Android Studio

1. Launch **Android Studio**.
2. Select **Open an Existing Project** and navigate to your `NeuroVision` directory.
3. Allow Gradle to sync dependencies.
4. Connect your Android smartphone via USB.
5. Click the green **Run (Shift + F10)** button in the top toolbar.

### 4. Build via Command Line (Gradle)

To assemble an installable debug APK directly:

```powershell
# Windows
.\gradlew.bat assembleDebug

# macOS / Linux
./gradlew assembleDebug
```

The compiled APK will be generated at:
```
app/build/outputs/apk/debug/app-debug.apk
```

---

## 🗣️ Voice Commands Reference

NeuroVision includes a voice listener that accepts natural phrasing. Tap the **Voice** button or use the wake word:

| Intent | Sample Phrases |
|---|---|
| **Scene Inquiries** | *"What's in front of me?"*, *"Describe the scene"*, *"Do you see anyone?"* |
| **Navigation** | *"Navigate to Central Park"*, *"Go to Starbucks"*, *"Where am I going?"* |
| **Route Controls** | *"Next step"*, *"Repeat instruction"*, *"Cancel navigation"* |
| **Camera Control** | *"Start camera"*, *"Stop camera"*, *"Pause detection"* |
| **Emergency SOS** | *"Activate emergency"*, *"Emergency mode"*, *"SOS"*, *"Call help"* |
| **Contacts** | *"Add emergency contact"*, *"Set contact"*, *"Emergency contact"* |
| **OCR / Reading** | *"Read text"*, *"Confirm read"*, *"Dismiss text"* |
| **Settings** | *"Open settings"*, *"Increase speed"*, *"Gemini key `<your-key>`"* |

---

## 📁 Project Structure

```text
NeuroVision/
├── app/
│   ├── build.gradle                 # App-level build logic, secrets injection & dependencies
│   └── src/main/
│       ├── AndroidManifest.xml      # App permissions, intents, and hardware features
│       ├── assets/
│       │   └── yolo26n.onnx         # Quantized YOLO vision model for on-device inference
│       ├── java/com/visionguide/phone/
│       │   ├── MainActivity.java         # Camera lifecycle, UI orchestration & sensor bindings
│       │   ├── YoloOnnxDetector.java     # Tensor preprocessing and ONNX Runtime inference
│       │   ├── GuidanceEngine.java       # Obstacle priority scoring and avoidance logic
│       │   ├── DepthEstimator.java       # Monocular spatial depth & bounding box scaling
│       │   ├── WallDetector.java         # Close-range barrier & wall detection heuristics
│       │   ├── MultiObjectTracker.java   # Object tracking across frames to isolate moving threats
│       │   ├── NavigationManager.java    # Turn-by-turn routing with Geoapify Maps API
│       │   ├── GeminiVoiceAssistant.java # Google Gemini conversational voice interface
│       │   ├── TextDetectorManager.java  # Google ML Kit OCR text recognition pipeline
│       │   ├── NfcMedicineManager.java   # NFC tag parser for medicine bottles & blister packs
│       │   ├── WakeWordListener.java     # Hands-free audio wake-word detection
│       │   ├── SpeechGuide.java          # Android TTS speech queue & vibration manager
│       │   ├── AppSettings.java          # Persistent configuration management
│       │   ├── SettingsDialog.java       # User preferences dialog UI
│       │   ├── OverlayView.java          # High-contrast visual overlay for low-vision users
│       │   └── Detection.java            # Detection data models
│       └── res/                          # Vector drawables, layouts, and mipmap resources
├── build.gradle                     # Top-level Gradle build configuration
├── gradle.properties                # JVM & AndroidX build parameters
├── local.properties.example         # Template for environment configuration & API keys
├── .gitignore                       # Production-grade gitignore rule definitions
└── README.md                        # Documentation
```

---

## ⚙️ Settings & Customization

Through the in-app **Settings** panel, users or caregivers can fine-tune system behavior:

- **Speech Delay**: Adjust pause between voice announcements (0.5s to 3.5s) to avoid cognitive fatigue.
- **Repeat Cooldown**: Minimum interval before the same non-hazardous object is announced again (5s to 35s).
- **Motion Sensitivity**: Set threshold for walking motion suppression vs. moving threat detection.
- **Notify Distance**: Configure sensitivity for notifying distant, medium, or close-range objects.
- **Ignore Side Edges**: Ignore objects at the periphery that do not pose direct collision risks.
- **Priority Override**: Ensure vehicles, incoming pedestrians, and rapid hazards immediately preempt ongoing speech.

---

## 🤝 Contributing

Contributions are welcome! Please follow these steps:

1. Fork the project repository.
2. Create your feature branch (`git checkout -b feature/AmazingFeature`).
3. Ensure no private keys or keystore files are committed.
4. Commit your changes (`git commit -m 'Add AmazingFeature'`).
5. Push to the branch (`git push origin feature/AmazingFeature`).
6. Open a Pull Request.

---

## 📄 License

This project is open-source. Please see the [LICENSE](LICENSE) file for more information.
Ultralytics YOLO weights are licensed according to Ultralytics license terms.
