package com.example.gemma4app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import java.util.*

class VoiceAssistant(private val context: Context, private val onReady: () -> Unit) {

    private var tts: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null
    var isSpeakingVoice = mutableStateOf(false)

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.getDefault()
                setupProgressListener()
                onReady()
            }
        }
    }

    private fun setupProgressListener() {
        tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                isSpeakingVoice.value = true
            }
            override fun onDone(utteranceId: String?) {
                isSpeakingVoice.value = false
            }
            override fun onError(utteranceId: String?) {
                isSpeakingVoice.value = false
            }
        })
    }

    fun speak(text: String) {
        Log.d("DrAI", "Speaking: $text")
        isSpeakingVoice.value = true
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "voice_id")
    }

    fun stopSpeaking() {
        tts?.stop()
        isSpeakingVoice.value = false
    }

    fun isSpeaking(): Boolean {
        return tts?.isSpeaking ?: false
    }

    fun setLanguage(locale: Locale) {
        tts?.language = locale
    }

    fun startListening(locale: Locale, onResult: (String) -> Unit, onError: (String) -> Unit) {
        Log.d("DrAI", "Start listening for: ${locale.toLanguageTag()}")
        
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onError("Speech recognition not available")
            return
        }

        if (speechRecognizer != null) {
            try {
                speechRecognizer?.destroy()
            } catch (e: Exception) {}
        }
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, locale.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                val message = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                    SpeechRecognizer.ERROR_CLIENT -> "Internal error"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Mic permission missing"
                    SpeechRecognizer.ERROR_NETWORK -> "Internet connection needed"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Internet timeout"
                    SpeechRecognizer.ERROR_NO_MATCH -> "Didn't catch that"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Mic busy, try again"
                    SpeechRecognizer.ERROR_SERVER -> "Server error"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected"
                    12 -> "Language '${locale.displayLanguage}' not supported on this phone"
                    13 -> "Language package missing"
                    else -> "Voice error ($error)"
                }
                Log.e("DrAI", "Speech Error: $message")
                onError(message)
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    onResult(matches[0])
                } else {
                    onError("Nothing heard")
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        speechRecognizer?.startListening(intent)
    }

    fun shutdown() {
        try {
            tts?.stop()
            tts?.shutdown()
            speechRecognizer?.destroy()
        } catch (e: Exception) {}
    }
}
