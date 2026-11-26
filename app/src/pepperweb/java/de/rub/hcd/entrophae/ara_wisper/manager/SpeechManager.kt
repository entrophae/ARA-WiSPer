package de.rub.hcd.entrophae.ara_wisper.manager

import android.os.SystemClock
import android.util.Log
import de.rub.hcd.entrophae.ara_wisper.controller.SpeechController
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class SpeechManager {
    private val speechController = SpeechController()
    private val bufferLock = Any()
    private val sentenceBuffer = StringBuilder()

    @Volatile
    private var isDriverSpeaking: Boolean = false

    @Volatile
    private var lastDriverSpeechEndMs: Long = 0L

    @Volatile
    private var lastPlaybackEndMs: Long = 0L

    // True when PCM is handed to main's AudioPlayer (standalone);
    // False when SpeechController speaks directly via hardware TTS (pepper / speakImmediate).
    @Volatile
    private var delegatesPlaybackToClient: Boolean = false

    @Volatile
    var isTurnActive: Boolean = false

    /**
     * Notifies ProxyStateBridge when direct hardware TTS starts or stops speaking.
     */
    var onDirectSpeakingChanged: ((Boolean) -> Unit)? = null

    init {
        speechController.onSpeakingStateChanged = { speaking ->
            handleDriverSpeakingChanged(speaking)
        }
    }

    suspend fun speakImmediate(text: String, onPcmReady: ((ByteArray) -> Unit)? = null) {
        if (text.isBlank()) return
        val punctuated = "$text." // Make sure it ends with punctuation
        processTextChunk(punctuated, onPcmReady)
        flush(onPcmReady)
    }

    fun notifyClientPlaybackEnded() {
        lastPlaybackEndMs = SystemClock.elapsedRealtime()
    }

    fun isSpeakingOrCoolingDown(isClientAudioPlaying: Boolean = false,  cooldownMs: Long = DEFAULT_COOLDOWN_MS ): Boolean {
        val now = SystemClock.elapsedRealtime()

        if (isDriverSpeaking) return true
        val driverEndedAt = lastDriverSpeechEndMs
        if (driverEndedAt > 0L && (now - driverEndedAt) < cooldownMs) return true

        if (isClientAudioPlaying) return true
        val playbackEndedAt = lastPlaybackEndMs
        return playbackEndedAt > 0L && (now - playbackEndedAt) < cooldownMs
    }

    fun processTextChunk(text: String, onPcmReady: ((ByteArray) -> Unit)? = null) {
        if (text.isBlank()) return
        delegatesPlaybackToClient = (onPcmReady != null)

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
                onDirectSpeakingChanged?.invoke(false)
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
            onDirectSpeakingChanged?.invoke(false)
        }
    }

    fun destroy() {
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
            onDirectSpeakingChanged?.invoke(speaking)
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