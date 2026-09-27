package com.akshat.edithglasses

import android.app.Application
import android.speech.SpeechRecognizer
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Ties BLE + speech together into a hands-free loop: as soon as the app is
 * opened and permissions are granted, it starts listening continuously,
 * and every time speech recognition finalizes a phrase it's sent to the
 * glasses automatically — no manual mic tap or send tap required. The mic
 * button in the UI now only pauses/resumes this loop; it's not a manual
 * one-shot trigger anymore.
 */
class EdithViewModel(application: Application) : AndroidViewModel(application) {

    private val bleManager = BleManager(application)
    private val speechHelper = SpeechHelper(application)

    val connectionState: StateFlow<ConnectionState> = bleManager.connectionState
    val bleStatusMessage: StateFlow<String> = bleManager.statusMessage
    val discoveredDeviceName: StateFlow<String?> = bleManager.discoveredDeviceName

    val listeningState: StateFlow<ListeningState> = speechHelper.listeningState
    val transcript: StateFlow<String> = speechHelper.transcript
    val isTranscribing: StateFlow<Boolean> = speechHelper.isTranscribing
    val speechError: StateFlow<String?> = speechHelper.errorMessage

    private val _lastSentText = MutableStateFlow<String?>(null)
    val lastSentText: StateFlow<String?> = _lastSentText.asStateFlow()

    /** True while the app should be auto-listening + auto-sending. False only when the user explicitly pauses via the mic button. */
    private val _autoModeEnabled = MutableStateFlow(true)
    val autoModeEnabled: StateFlow<Boolean> = _autoModeEnabled.asStateFlow()

    /** Error codes worth silently retrying on — anything else (permissions, client errors) stops the loop instead of spinning forever. */
    private val recoverableErrorCodes = setOf(
        SpeechRecognizer.ERROR_NO_MATCH,
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY
    )

    private var autoStartedOnce = false

    init {
        // Every time a phrase is finalized, send it to the glasses, then
        // go straight back to listening for the next one.
        viewModelScope.launch {
            speechHelper.finalResults.collect { text ->
                bleManager.sendText(text)
                _lastSentText.value = text
                if (_autoModeEnabled.value) {
                    delay(300) // let the recognizer fully tear down before relaunching it
                    speechHelper.startListening()
                }
            }
        }

        // Recoverable errors (no speech detected, brief network hiccup) just
        // restart listening automatically so the loop never goes silent.
        viewModelScope.launch {
            speechHelper.lastErrorCode.collect { code ->
                if (code != null && _autoModeEnabled.value && code in recoverableErrorCodes) {
                    delay(500)
                    speechHelper.startListening()
                }
            }
        }
    }

    /**
     * Called once from the UI as soon as required permissions are granted.
     * Starts BLE auto-connect (if not already connected) and starts the
     * continuous listen/transcribe/send loop. Safe to call more than once —
     * it only actually kicks things off the first time.
     */
    fun autoStart() {
        if (autoStartedOnce) return
        autoStartedOnce = true

        if (connectionState.value == ConnectionState.DISCONNECTED) {
            bleManager.startScan()
        }
        _autoModeEnabled.value = true
        speechHelper.startListening()
    }

    /** Pauses the continuous loop — mic stops, and finalized/errored results no longer auto-restart listening. */
    fun pauseAuto() {
        _autoModeEnabled.value = false
        speechHelper.stopListening()
    }

    /** Resumes the continuous loop after a manual pause. */
    fun resumeAuto() {
        _autoModeEnabled.value = true
        speechHelper.startListening()
    }

    fun connect() {
        bleManager.startScan()
    }

    fun disconnect() {
        bleManager.disconnect()
    }

    /** Manual resend of whatever is currently in the transcript box (e.g. after a pause). */
    fun sendCurrentTranscript() {
        val text = transcript.value
        if (text.isBlank()) return
        viewModelScope.launch {
            bleManager.sendText(text)
            _lastSentText.value = text
        }
    }

    /** Sends arbitrary text (e.g. from a manual text field) to the glasses. */
    fun sendText(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            bleManager.sendText(text)
            _lastSentText.value = text
        }
    }

    override fun onCleared() {
        super.onCleared()
        speechHelper.destroy()
        bleManager.disconnect()
    }
}
