package com.nexus.companion.stt

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class SpeechRecognizerManager(private val context: Context) {

    private var recognizer: SpeechRecognizer? = null

    private val _state = MutableStateFlow<SttState>(SttState.Idle)
    val state: StateFlow<SttState> = _state

    private val _result = MutableStateFlow("")
    val result: StateFlow<String> = _result

    fun initialize() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            _state.value = SttState.Error("Spracherkennung nicht verfügbar")
            return
        }
        recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                _state.value = SttState.Listening
            }

            override fun onBeginningOfSpeech() {}

            override fun onRmsChanged(rmsdB: Float) {}

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                _state.value = SttState.Processing
            }

            override fun onError(error: Int) {
                _state.value = SttState.Error(getErrorMessage(error))
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull() ?: ""
                _result.value = text
                _state.value = SttState.Done(text)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull() ?: ""
                if (text.isNotBlank()) {
                    _result.value = text
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    fun startListening() {
        _state.value = SttState.Starting
        _result.value = ""

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "de-DE")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        recognizer?.startListening(intent)
    }

    fun stopListening() {
        recognizer?.stopListening()
        _state.value = SttState.Idle
    }

    fun destroy() {
        recognizer?.destroy()
        recognizer = null
    }

    private fun getErrorMessage(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "Audio-Fehler"
        SpeechRecognizer.ERROR_CLIENT -> "Client-Fehler"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Keine Berechtigung"
        SpeechRecognizer.ERROR_NETWORK -> "Netzwerk-Fehler"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Netzwerk-Timeout"
        SpeechRecognizer.ERROR_NO_MATCH -> "Nicht erkannt"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Erkennung beschäftigt"
        SpeechRecognizer.ERROR_SERVER -> "Server-Fehler"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Zeitüberschreitung"
        else -> "Unbekannter Fehler ($error)"
    }

    sealed class SttState {
        data object Idle : SttState()
        data object Starting : SttState()
        data object Listening : SttState()
        data object Processing : SttState()
        data class Done(val text: String) : SttState()
        data class Error(val message: String) : SttState()
    }
}
