package com.akshat.edithglasses

import android.content.Context
import android.content.Intent
import android.os.Build
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
 * Continuous EDITH speech input.
 *
 * Error 11 is ERROR_SERVER_DISCONNECTED on modern Android. It is normally
 * recoverable, so the ViewModel restarts the recognizer instead of stopping
 * the hands-free loop.
 */
class SpeechHelper(private val context: Context) {

    private var recognizer: SpeechRecognizer? = null

    private val _listeningState = MutableStateFlow(ListeningState.IDLE)
    val listeningState: StateFlow<ListeningState> = _listeningState.asStateFlow()

    private val _transcript = MutableStateFlow("")
    val transcript: StateFlow<String> = _transcript.asStateFlow()

    private val _isTranscribing = MutableStateFlow(false)
    val isTranscribing: StateFlow<Boolean> = _isTranscribing.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _lastErrorCode = MutableStateFlow<Int?>(null)
    val lastErrorCode: StateFlow<Int?> = _lastErrorCode.asStateFlow()

    private val _partialResults = MutableSharedFlow<String>(extraBufferCapacity = 32)
    val partialResults: SharedFlow<String> = _partialResults

    private val _finalResults = MutableSharedFlow<String>(extraBufferCapacity = 8)
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

        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null

        val newRecognizer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        ) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }

        recognizer = newRecognizer.apply {
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
                    if (best.isNotBlank()) _finalResults.tryEmit(best)
                }

                override fun onPartialResults(partialResults: android.os.Bundle?) {
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val best = matches?.firstOrNull()
                    if (!best.isNullOrEmpty()) {
                        _isTranscribing.value = true
                        _transcript.value = best
                        _partialResults.tryEmit(best)
                    }
                }

                override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
            })
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)

            // Give the recognizer a little more room before it decides the
            // speaker stopped. This reduces constant stop/start churn.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 700L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 200L)
        }

        try {
            recognizer?.startListening(intent)
        } catch (e: Exception) {
            _lastErrorCode.value = SpeechRecognizer.ERROR_CLIENT
            _errorMessage.value = "Speech recognizer could not start"
            _listeningState.value = ListeningState.ERROR
            _isTranscribing.value = false
        }
    }

    fun stopListening() {
        _isTranscribing.value = false
        try {
            recognizer?.cancel()
        } catch (_: Exception) {}
    }

    fun destroy() {
        try {
            recognizer?.cancel()
        } catch (_: Exception) {}
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
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy — restarting"
        SpeechRecognizer.ERROR_SERVER -> "Speech server error"
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "Speech service disconnected — restarting"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected — listening again"
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "Speech service is busy — retrying"
        else -> "Speech recognition error ($error)"
    }
}
