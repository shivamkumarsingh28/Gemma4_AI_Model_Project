# Dr. AI: Technical Architecture & In-Depth Documentation

This document provides a comprehensive deep-dive into the folder structure, backend logic, and line-by-line code explanation for the **Dr. AI** Android application.

---

## 📂 1. Project Folder Structure

```text
Dr AI (Root)
├── app
│   ├── src
│   │   ├── main
│   │   │   ├── cpp                 <-- Backend (C++/LLM) Logic
│   │   │   │   ├── llamacpp        <-- The core llama.cpp library
│   │   │   │   ├── llama_bridge.cpp <-- JNI Bridge (C++ to Kotlin)
│   │   │   │   └── CMakeLists.txt  <-- Native Build Optimization
│   │   │   ├── java/com/example/gemma4app
│   │   │   │   ├── data            <-- Database & Entities (SQLite)
│   │   │   │   ├── MainActivity.kt <-- UI & Navigation (Jetpack Compose)
│   │   │   │   ├── ChatViewModel.kt<-- State & Logic Controller
│   │   │   │   ├── VoiceAssistant.kt<-- Speech & TTS Engine
│   │   │   │   └── PrescriptionScanner.kt <-- OCR Vision Logic
│   │   │   └── res                 <-- Clinical Theme, Icons, Strings
│   └── build.gradle.kts            <-- Version & Dependency Config
├── gradle/libs.versions.toml       <-- Centralized Version Catalog
└── settings.gradle.kts             <-- Root Project Settings
```

---

## 🧠 2. Backend Engine: `llama_bridge.cpp`
This file is the "Engine Room," written in C++ for direct hardware access and maximum speed.

### Global State Logic
- `static llama_model* g_model`: Stores the neural network weights in a memory-mapped format.
- `static llama_context* g_ctx`: The active "thinking" session that holds the current KV cache.
- `static std::atomic<bool> g_should_stop`: A thread-safe flag that enables the "Stop AI" feature to kill loops instantly.
- `static std::mutex g_mutex`: A critical safety lock that prevents the app from accessing the AI model from multiple threads at once (prevents Fatal Signal 6).

### Key Native Functions
- **`loadModel`**: Uses `mmap=true` to map the 1.6GB GGUF file without filling physical RAM. It detects CPU cores via JNI to set the optimal number of threads.
- **`generateStreaming`**: 
    - `llama_kv_cache_clear(g_ctx)`: Wipes short-term memory before every prompt for precision.
    - `llama_tokenize`: Converts human text into numbers the AI can process.
    - **The Token Loop**: Predicts one word at a time. After every word, it uses `env->CallVoidMethod` to jump back into Kotlin and update the screen in real-time.

---

## 🎮 3. Logic Controller: `ChatViewModel.kt`
The "Brain" of the UI that manages how data flows between the screen and the engine.

### Communication Flow
- **`sendMessage(userText)`**: 
    - Wraps text in **Gemma ChatML** (`<start_of_turn>user...`).
    - Uses `viewModelScope.launch(Dispatchers.IO)` to run the heavy AI math on a background thread so the UI never freezes.
- **`analyzePrescription(rawText)`**:
    - Takes raw text from the OCR scanner.
    - Limits input to **2000 characters** to prevent context window overflow.
    - Injects a specialized "Doctor Prompt" to force the AI to simplify complex medical terms.

### Memory Management
- **`trimHistory`**: Implements a "Sliding Window" algorithm. It calculates token counts and deletes the oldest messages as the chat grows, keeping the AI response speed constant.

---

## 🔍 4. Vision System: `PrescriptionScanner.kt`
Uses On-Device AI Vision to read handwriting and printed documents.

- **Logical Extraction**: Instead of just grabbing all text, it iterates through `visionText.textBlocks`.
- **Structural Integrity**: By scanning line-by-line within blocks, it keeps medicine names next to their dosages, which is crucial for the AI to give accurate advice.
- **Noise Filtering**: Automatically ignores single-character noise and artifacts to keep the AI's prompt clean.

---

## 🗣️ 5. Accessibility Layer: `VoiceAssistant.kt`
Designed for blind users to interact via natural speech.

- **`SpeechRecognizer`**: Configured with `EXTRA_PREFER_OFFLINE` to allow voice input without an internet connection.
- **`TextToSpeech (TTS)`**: Uses an `UtteranceProgressListener`. This allows the UI to show/hide the "Stop Voice" button exactly when the AI starts and finishes speaking.
- **Locale Switching**: Dynamically maps human names (like "Hindi") to system locales (`hi-IN`) so the phone knows which accent to use.

---

## 🎨 6. UI Architecture: `MainActivity.kt`
Built with Jetpack Compose using Material 3 Clinical standards.

- **`ModalNavigationDrawer`**: Connects directly to the SQLite database to show a history of consultations in the sidebar.
- **`Scaffold`**: Manages the TopBar (Branding) and the Floating Action Buttons.
- **State-Aware Input**: The text field and send button change color and icon based on whether the model is loading, thinking, or speaking.

---

## 🛠️ 7. Optimization Summary
- **Thermal Fix**: Thread count is limited to `AvailableCores - 1` to prevent the phone from overheating.
- **Offline First**: All OCR, LLM, and Storage logic is 100% local. 
- **Safety**: Includes a global Medical Disclaimer and "Stop" controls for every automated action.

---
*Technical Document Version 1.2 · Created for Dr. AI Development*
