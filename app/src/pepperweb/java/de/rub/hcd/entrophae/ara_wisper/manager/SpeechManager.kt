package de.rub.hcd.entrophae.ara_wisper.manager

import android.os.SystemClock
import android.util.Log
import ch.fhnw.pepper_realtime.manager.TurnManager
import de.rub.hcd.entrophae.ara_wisper.controller.SpeechController
import de.rub.hcd.entrophae.ara_wisper.core.ChatActivityAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.lang.ref.WeakReference
import kotlin.coroutines.resume

class SpeechManager {
    private val speechController = SpeechController()
    private val bufferLock = Any()
    private val sentenceBuffer = StringBuilder()

    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var playbackObserverJob: Job? = null
    private var observedAdapterRef: WeakReference<ChatActivityAdapter>? = null

    @Volatile
    private var isDriverSpeaking: Boolean = false

    @Volatile
    private var lastDriverSpeechEndMs: Long = 0L

    @Volatile
    private var lastPlaybackEndMs: Long = 0L

    // True when PCM is handed to main's AudioPlayer (standalone);
    // False when SpeechController speaks directly via hardware TTS (pepper).
    @Volatile
    private var delegatesPlaybackToClient: Boolean = false

    @Volatile
    var isTurnActive: Boolean = false

    init {
        speechController.onSpeakingStateChanged = { speaking ->
            handleDriverSpeakingChanged(speaking)
        }
    }

    suspend fun speakImmediate(text: String, onPcmReady: ((ByteArray) -> Unit)? = null) {
        if (text.isBlank()) return
        val text = "$text." // Make sure it ends with punctuation
        processTextChunk(text, onPcmReady)
        flush(onPcmReady)
    }

    fun isSpeakingOrCoolingDown(cooldownMs: Long = DEFAULT_COOLDOWN_MS): Boolean {
        ensurePlaybackObserver()
        val now = SystemClock.elapsedRealtime()

        if (isDriverSpeaking) return true
        val driverEndedAt = lastDriverSpeechEndMs
        if (driverEndedAt > 0L && (now - driverEndedAt) < cooldownMs) return true

        val isAudioPlaying = AraWisperManager.chatActivityAdapter?.viewModel?.isAudioPlaying?.value == true
        if (isAudioPlaying) return true
        val playbackEndedAt = lastPlaybackEndMs

        return playbackEndedAt > 0L && (now - playbackEndedAt) < cooldownMs
    }

    fun processTextChunk(text: String, onPcmReady: ((ByteArray) -> Unit)? = null) {
        if (text.isBlank()) return
        delegatesPlaybackToClient = (onPcmReady != null)
        ensurePlaybackObserver()

        val completeSentences = mutableListOf<String>()
        synchronized(bufferLock) {
            sentenceBuffer.append(text)
            val buffered = sentenceBuffer.toString()

            // Split on sentence-ending punctuation followed by whitespace or end-of-buffer
            val lastBoundaryEnd = findLastSentenceBoundary(buffered)
            if (lastBoundaryEnd > 0) {
                val readyBlock = buffered.substring(0, lastBoundaryEnd).trim()
                sentenceBuffer.delete(0, lastBoundaryEnd)
                if (readyBlock.isNotEmpty()) {
                    completeSentences.add(readyBlock)
                }
            }
        }

        for (sentence in completeSentences) {
            Log.i(TAG, "Queueing sentence: $sentence")
            speechController.speak(sentence, onPcmReady)
        }
    }

    fun clearBuffer() {
        synchronized(bufferLock) {
            sentenceBuffer.clear()
        }
    }

    suspend fun flush(onPcmReady: ((ByteArray) -> Unit)? = null) {
        if (onPcmReady != null) {
            delegatesPlaybackToClient = true
        }
        ensurePlaybackObserver()

        val leftover = synchronized(bufferLock) {
            val text = sentenceBuffer.toString().trim()
            sentenceBuffer.clear()
            text
        }

        if (leftover.isNotEmpty()) {
            Log.i(TAG, "Flushing leftover text: $leftover")
            speechController.speak(leftover, onPcmReady)
        }

        val completed = kotlinx.coroutines.withTimeoutOrNull(DEFAULT_COOLDOWN_MS) {
            suspendCancellableCoroutine { continuation ->
                speechController.flush {
                    if (continuation.isActive) {
                        continuation.resume(Unit)
                    }
                }
            }
        }

        if (completed == null) {
            Log.w(TAG, "speechController.flush timed out after ${DEFAULT_COOLDOWN_MS}ms; resetting speaking state.")
            isDriverSpeaking = false
            if (!delegatesPlaybackToClient) {
                syncDirectSpeechUiState(speaking = false)
            }
        }
    }


    fun stop(clearCooldown: Boolean = false) {
        synchronized(bufferLock) {
            sentenceBuffer.clear()
        }
        speechController.stopAll()

        if (clearCooldown) {
            lastDriverSpeechEndMs = 0L
            lastPlaybackEndMs = 0L
        } else {
            val now = SystemClock.elapsedRealtime()
            lastDriverSpeechEndMs = now
            lastPlaybackEndMs = now
        }
        isDriverSpeaking = false

        if (!delegatesPlaybackToClient) {
            syncDirectSpeechUiState(speaking = false)
        }
    }

    fun destroy() {
        playbackObserverJob?.cancel()
        stop(clearCooldown = true)
        speechController.destroy()
    }

    private fun handleDriverSpeakingChanged(speaking: Boolean) {
        if (speaking) {
            isDriverSpeaking = true
        } else {
            if (isDriverSpeaking) {
                lastDriverSpeechEndMs = SystemClock.elapsedRealtime()
            }
            isDriverSpeaking = false
        }
        // In standalone mode, AudioPlayer owns SPEAKING -> LISTENING
        if (!delegatesPlaybackToClient) {
            syncDirectSpeechUiState(speaking)
        }
    }

    private fun syncDirectSpeechUiState(speaking: Boolean) {
        val adapter = AraWisperManager.chatActivityAdapter ?: return
        adapter.viewModel.setAudioPlaying(speaking)

        val turnManager = adapter.turnManager ?: return
        if (speaking) {
            turnManager.setState(TurnManager.State.SPEAKING)
        } else if (turnManager.state == TurnManager.State.SPEAKING) {
            // Only return to LISTENING if the pipeline turn itself is no longer active
            val nextState = if (isTurnActive) TurnManager.State.THINKING else TurnManager.State.LISTENING
            turnManager.setState(nextState)
        }
    }

    private fun ensurePlaybackObserver() {
        val adapter = AraWisperManager.chatActivityAdapter ?: return
        if (observedAdapterRef?.get() === adapter && playbackObserverJob?.isActive == true) return

        observedAdapterRef = WeakReference(adapter)
        playbackObserverJob?.cancel()
        playbackObserverJob = managerScope.launch {
            var wasPlaying = false
            adapter.viewModel.isAudioPlaying.collect { playing ->
                if (playing) {
                    wasPlaying = true
                } else if (wasPlaying) {
                    wasPlaying = false
                    lastPlaybackEndMs = SystemClock.elapsedRealtime()
                }
            }
        }
    }

    private fun findLastSentenceBoundary(text: String): Int {
        var lastBoundary = -1
        for (i in text.indices) {
            val c = text[i]
            if (c == '.' || c == '!' || c == '?') {
                val isAtEnd = (i == text.lastIndex)
                val followedByWhitespace = !isAtEnd && text[i + 1].isWhitespace()
                if (isAtEnd || followedByWhitespace) {
                    lastBoundary = i + 1
                }
            }
        }
        return lastBoundary
    }

    companion object {
        private const val TAG = "SpeechManager"
        const val DEFAULT_COOLDOWN_MS = 600L
    }

}