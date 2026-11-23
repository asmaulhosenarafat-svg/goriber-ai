package com.example

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import java.util.Locale

class VoiceService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}

class VoiceAssistantHelper(private val context: Context) : TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var speechRecognizer: SpeechRecognizer? = null
    private var currentLangTag: String = "bn-BD"

    init {
        tts = TextToSpeech(context.applicationContext, this)
    }

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) {
            applyLocale(currentLangTag)
        }
    }

    fun setLanguage(langTag: String) {
        currentLangTag = langTag
        applyLocale(langTag)
    }

    private fun applyLocale(langTag: String) {
        val locale = Locale.forLanguageTag(langTag)
        tts?.language = locale
    }

    fun speak(text: String) {
        if (!ttsReady || text.isBlank()) return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ai_resp_${System.currentTimeMillis()}")
    }

    fun stopSpeaking() {
        tts?.stop()
    }

    fun startListening(
        langTag: String,
        onResult: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        setLanguage(langTag)
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onError("Speech recognition service unavailable on this device.")
            return
        }
        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) {
                    onError("Voice recognition error code: $error")
                }
                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val best = matches?.firstOrNull().orEmpty()
                    if (best.isNotBlank()) onResult(best) else onError("No speech recognized.")
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, langTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        speechRecognizer?.startListening(intent)
    }

    fun buildVoiceIntent(langTag: String): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, langTag)
            putExtra(RecognizerIntent.EXTRA_PROMPT, if (langTag.startsWith("bn")) "আপনার নির্দেশ বলুন..." else "Speak your command...")
        }
    }

    fun release() {
        speechRecognizer?.destroy()
        speechRecognizer = null
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}
