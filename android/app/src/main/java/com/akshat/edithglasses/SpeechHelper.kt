package com.akshat.edithglasses

import android.content.Context
import android.content.Intent
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

enum class ListeningState { IDLE, LISTENING, ERROR }

/**
 * Wraps Android's built-in on-device/cloud SpeechRecognizer (no paid API
 * required — uses whatever speech service is configured on the phone,
 * typically Google's).
 */
class SpeechHelper(private val context: Context) {

    private var recognizer: SpeechRecognizer? = null

    private val _listeningState = MutableStateFlow(ListeningState.IDLE)
    val listeningState: StateFlow<ListeningState> = _listeningState.asStateFlow()

    private val _transcript = MutableStateFlow("")
    val transcript: StateFlow<String> = _transcript.asStateFlow()

    /** True only while SpeechRecognizer is actively turning spoken words into text. */
    private val _isTranscribing = MutableStateFlow(false)
    val isTranscribing: StateFlow<Boolean> = _isTranscribing.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    /** Raw SpeechRecognizer error code from the most recent onError, so callers can decide what's safe to auto-retry. */
    private val _lastErrorCode = MutableStateFlow<Int?>(null)
    val lastErrorCode: StateFlow<Int?> = _lastErrorCode.asStateFlow()

    /** Emits a finalized (non-partial) transcript each time recognition completes with a non-empty result. */
    private val _finalResults = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val finalResults: SharedFlow<String> = _finalResults

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    fun startListening() {
        if (!isAvailable()) {
            _errorMessage.value = "Speech recognition is not available on this device"
            _listeningState.value = ListeningState.ERROR
            return
        }

        _errorMessage.value = null
        _lastErrorCode.value = null
        _transcript.value = ""
        _isTranscribing.value = false

        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: android.os.Bundle?) {
                    _listeningState.value = ListeningState.LISTENING
                }

                override fun onBeginningOfSpeech() {
                    _isTranscribing.value = true
                }
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    _listeningState.value = ListeningState.IDLE
                }

                override fun onError(error: Int) {
                    _isTranscribing.value = false
                    _listeningState.value = ListeningState.ERROR
                    _lastErrorCode.value = error
                    _errorMessage.value = describeError(error)
                }

                override fun onResults(results: android.os.Bundle?) {
                    _isTranscribing.value = false
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val best = matches?.firstOrNull().orEmpty()
                    _transcript.value = best
                    _listeningState.value = ListeningState.IDLE
                    if (best.isNotBlank()) {
                        _finalResults.tryEmit(best)
                    }
                }

                override fun onPartialResults(partialResults: android.os.Bundle?) {
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val best = matches?.firstOrNull()
                    if (!best.isNullOrEmpty()) {
                        _isTranscribing.value = true
                        _transcript.value = best
                    }
                }

                override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
            })
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }

        recognizer?.startListening(intent)
    }

    fun stopListening() {
        _isTranscribing.value = false
        recognizer?.stopListening()
    }

    fun destroy() {
        recognizer?.destroy()
        recognizer = null
    }

    private fun describeError(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
        SpeechRecognizer.ERROR_CLIENT -> "Client-side error"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission not granted"
        SpeechRecognizer.ERROR_NETWORK -> "Network error"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
        SpeechRecognizer.ERROR_NO_MATCH -> "Didn't catch that — listening again"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
        SpeechRecognizer.ERROR_SERVER -> "Speech server error"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected — listening again"
        else -> "Unknown speech recognition error ($error)"
    }
}
