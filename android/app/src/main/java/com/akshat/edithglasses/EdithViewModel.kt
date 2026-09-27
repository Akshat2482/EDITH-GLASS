package com.akshat.edithglasses

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import android.speech.SpeechRecognizer
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Hands-free EDITH loop: speech is automatically streamed to the glasses.
 */
class EdithViewModel(application: Application) : AndroidViewModel(application) {

    private val bleManager = BleManager(application)
    private val speechHelper = SpeechHelper(application)
    private val grokClient = GrokClient()

    val connectionState: StateFlow<ConnectionState> = bleManager.connectionState
    val bleStatusMessage: StateFlow<String> = bleManager.statusMessage
    val discoveredDeviceName: StateFlow<String?> = bleManager.discoveredDeviceName

    val listeningState: StateFlow<ListeningState> = speechHelper.listeningState
    val transcript: StateFlow<String> = speechHelper.transcript
    val isTranscribing: StateFlow<Boolean> = speechHelper.isTranscribing
    val speechError: StateFlow<String?> = speechHelper.errorMessage

    private val _lastSentText = MutableStateFlow<String?>(null)
    val lastSentText: StateFlow<String?> = _lastSentText.asStateFlow()

    private val _autoModeEnabled = MutableStateFlow(true)
    val autoModeEnabled: StateFlow<Boolean> = _autoModeEnabled.asStateFlow()

    private val recoverableErrorCodes = setOf(
        SpeechRecognizer.ERROR_NO_MATCH,
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS
    )

    private var autoStartedOnce = false
    private var streamingJob: Job? = null
    private var lastStreamedTranscript = ""
    private var streamStarted = false

    init {
        viewModelScope.launch {
            speechHelper.finalResults.collect { text ->
                handleFinalSpeech(text)
            }
        }

        viewModelScope.launch {
            speechHelper.lastErrorCode.collect { code ->
                if (code != null && _autoModeEnabled.value && code in recoverableErrorCodes) {
                    // Error 11 means the Android speech service disconnected.
                    // Recreate the recognizer instead of getting stuck.
                    delay(if (code == SpeechRecognizer.ERROR_SERVER_DISCONNECTED) 900 else 500)
                    if (_autoModeEnabled.value) speechHelper.startListening()
                }
            }
        }
    }

    fun autoStart() {
        if (autoStartedOnce) return
        autoStartedOnce = true

        if (connectionState.value == ConnectionState.DISCONNECTED) {
            bleManager.startScan()
        }
        _autoModeEnabled.value = true
        resetStreamingState()
        startBackgroundService()
        speechHelper.startListening()
        startStreamingPartialUpdates()
    }

    fun pauseAuto() {
        _autoModeEnabled.value = false
        resetStreamingState()
        speechHelper.stopListening()
        stopBackgroundService()
    }

    fun resumeAuto() {
        _autoModeEnabled.value = true
        resetStreamingState()
        startBackgroundService()
        speechHelper.startListening()
        startStreamingPartialUpdates()
    }

    fun connect() = bleManager.startScan()
    fun disconnect() = bleManager.disconnect()

    fun sendCurrentTranscript() {
        val text = transcript.value
        if (text.isBlank()) return
        viewModelScope.launch {
            bleManager.sendText(text)
            _lastSentText.value = text
        }
    }

    fun sendText(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            bleManager.sendText(text)
            _lastSentText.value = text
        }
    }

    private fun startBackgroundService() {
        val intent = Intent(getApplication<Application>(), EdithBackgroundService::class.java)
        ContextCompat.startForegroundService(getApplication(), intent)
    }

    private fun stopBackgroundService() {
        getApplication<Application>().stopService(
            Intent(getApplication<Application>(), EdithBackgroundService::class.java)
        )
    }

    private fun resetStreamingState() {
        streamingJob?.cancel()
        streamingJob = null
        lastStreamedTranscript = ""
        streamStarted = false
    }

    private fun startStreamingPartialUpdates() {
        if (streamingJob?.isActive == true) return
        streamingJob = viewModelScope.launch {
            speechHelper.partialResults.collect { current ->
                if (!_autoModeEnabled.value || current.isBlank() || current == lastStreamedTranscript) {
                    return@collect
                }

                // A JARVIS command must never be streamed to the OLED as the
                // user speaks it. Wait for the final phrase, send only the
                // question to Grok, then display Grok's answer.
                if (current.trimStart().startsWith("jarvis", ignoreCase = true)) {
                    return@collect
                }

                val suffix = if (streamStarted && current.startsWith(lastStreamedTranscript)) {
                    current.substring(lastStreamedTranscript.length)
                } else current

                if (suffix.isNotBlank()) {
                    bleManager.sendStreamingChunk(suffix, !streamStarted)
                    streamStarted = true
                    lastStreamedTranscript = current
                    _lastSentText.value = current
                }
            }
        }
    }

    private suspend fun handleFinalSpeech(text: String) {\n        if (text.isBlank()) return\n\n        val trimmed = text.trim()\n        if (trimmed.startsWith("jarvis", ignoreCase = true)) {\n            resetStreamingState()\n            speechHelper.stopListening()\n\n            val question = trimmed\n                .replace(Regex("(?i)^jarvis\\s*[,;:.!?-]?\\s*"), "")\n                .trim()\n\n            if (question.isBlank()) {\n                val reply = "Yes, sir?"\n                bleManager.sendText(reply)\n                _lastSentText.value = reply\n            } else {\n                val thinking = "JARVIS: THINKING..."\n                bleManager.sendText(thinking)\n                _lastSentText.value = thinking\n\n                val result = grokClient.ask(question)\n                val answer = result.getOrElse { error ->\n                    "JARVIS ERROR: " + (error.message ?: "Grok request failed")\n                }.take(512)\n\n                bleManager.sendText(answer)\n                _lastSentText.value = answer\n            }\n\n            if (_autoModeEnabled.value) {\n                delay(400)\n                speechHelper.startListening()\n            }\n            return\n        }\n\n        val remaining = if (streamStarted && trimmed.startsWith(lastStreamedTranscript)) {\n            trimmed.substring(lastStreamedTranscript.length)\n        } else trimmed\n\n        if (remaining.isNotBlank()) {\n            bleManager.sendStreamingChunk(remaining, !streamStarted)\n            streamStarted = true\n        }\n\n        if (streamStarted) {\n            bleManager.finishStreaming()\n        }\n\n        lastStreamedTranscript = trimmed\n        _lastSentText.value = trimmed\n        streamStarted = false\n\n        if (_autoModeEnabled.value) {\n            delay(650)\n            speechHelper.startListening()\n        }\n    }\n    override fun onCleared() {
        super.onCleared()
        speechHelper.destroy()
        bleManager.disconnect()
    }
}
