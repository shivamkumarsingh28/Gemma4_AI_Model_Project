# Dr. AI: Advanced Local Gemma-Powered Medical Assistant

Dr. AI is a professional, privacy-first Android application that runs a large language model (Gemma) locally on-device. It is designed to act as an accessible medical assistant, providing expert guidance, prescription analysis, and multi-language voice interaction without ever requiring an internet connection.

---

## 🚀 Key Features

-   **100% Local LLM Inference**: Powered by `llama.cpp` high-performance C++ backend. Your medical data never leaves your phone.
-   **Intelligent Prescription Scanner**: Advanced on-device OCR (Optical Character Recognition) to extract text from doctor's handwriting or printed reports.
-   **Medical Decoding**: Automatically simplifies complex prescriptions into "Health Issue," "Symptoms," "Medications," and "Dos & Don'ts."
-   **Blind-Friendly Voice Assistant**: Fully accessible voice interface with "Repeat-and-Reply" logic. It listens to your question, repeats it to confirm, and speaks the AI's medical response.
-   **Native Language Support**: Chat and listen in **English, Hindi, Bengali, Spanish, Arabic, Urdu, and French**.
-   **Real-time Streaming**: Instant feedback with word-by-word text generation.
-   **Conversation History**: Persistent SQLite storage to keep track of all previous medical consultations.
-   **Custom Model Settings**: Easily switch between different `.gguf` model files from your phone's storage.

---

## 🛠 Tech Stack

-   **UI**: Jetpack Compose (Material 3) with a polished medical clinical theme.
-   **AI Engine**: `llama.cpp` (C++) via custom JNI Bridge with multi-threading.
-   **OCR**: Google ML Kit Vision (On-Device Text Recognition).
-   **Voice**: Android SpeechRecognizer & Text-to-Speech (TTS).
-   **Storage**: Custom SQLite implementation for maximum stability with AGP 9.2.1.
-   **Concurrency**: Kotlin Coroutines & StateFlow.

---

## 🏗 Setup & Build Guide

### Prerequisites
1.  **Android Studio Ladybug** (or newer).
2.  **Android NDK & CMake** installed via SDK Manager.
3.  A **GGUF Model File**: Recommended `Gemma-2b-it-Q4_K_M.gguf` (approx 1.6GB).

### Build Instructions
1.  **Clone the Repository**:
    ```bash
    git clone https://github.com/your-username/dr-ai-android
    ```
2.  **Native Setup**: Ensure native files are present in `app/src/main/cpp/llamacpp`.
3.  **Gradle Sync**: Perform a Gradle Sync. The build scripts are optimized for Android 35 and AGP 9.2.1 stability.
4.  **Deployment**: Deploy to a physical **ARM64 device** (LLMs are not supported on standard emulators).

---

## 🧠 Recent Technical Updates & Bug Fixes

### 1. Robust Native Bridge (Crash Prevention)
**Challenge**: Random "Fatal Signal 6" crashes during high-load processing (like prescription scanning).
**Solution**: Implemented a global `std::mutex` in the JNI bridge and a strict memory management cycle. The app now safely clears the KV cache and frees model memory before reloading, preventing concurrent thread collisions.

### 2. Structured Prescription Analysis
**Challenge**: OCR text was often jumbled, making it hard for the AI to understand.
**Solution**: Redesigned the scanner to extract text in logical **Blocks and Lines**. We also added a 2000-character "Safety Buffer" to prevent oversized prompts from crashing the model's context window.

### 3. Accessible Voice Interaction
**Challenge**: Blind users needed a way to confirm the app heard them correctly.
**Solution**: Implemented a **"Repeat-and-Reply"** flow. The `VoiceAssistant` now repeats the user's question in their mother tongue before providing the AI answer, ensuring 100% clarity.

### 4. Multi-Language Logic
**Challenge**: "Error 12" (Language Not Supported) on various devices.
**Solution**: Refined Locale identification using modern Language Tags (e.g., `en-US` instead of generic `en`). Added an automatic online fallback for voice recognition if the offline language pack is missing, ensuring the Mic never fails.

### 5. Smart UI Controls
**Challenge**: App felt "frozen" while the AI was generating or speaking.
**Solution**: Added dedicated **"Stop AI"** and **"Stop Voice"** buttons. These provide instant control, allowing users to interrupt the AI at any time.

---

## ⚕ Medical Disclaimer
Dr. AI provides AI-generated guidance for informational purposes only. It is **not** a substitute for professional medical advice, diagnosis, or treatment. Always consult a certified healthcare provider for any medical concerns.

---

## 📜 License
*Project developed as a research implementation of high-performance local LLMs for healthcare accessibility.*
