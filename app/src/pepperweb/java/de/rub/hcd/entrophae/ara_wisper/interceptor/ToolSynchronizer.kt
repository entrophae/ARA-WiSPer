package de.rub.hcd.entrophae.ara_wisper.interceptor

import ch.fhnw.pepper_realtime.manager.TurnManager
import de.rub.hcd.entrophae.ara_wisper.manager.AraWisperManager
import kotlinx.coroutines.delay
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Coordinates turn-scoped speech playback and audio-producing tools (e.g. play_melody)
 * so local TTS finishes draining before an audio tool starts and tracks already-spoken
 * text within a turn to prevent stateless LLM rounds from repeating themselves.
 */
class ToolSynchronizer(
    private val audioOutputTools: Set<String> = DEFAULT_AUDIO_OUTPUT_TOOLS
) {
    private val spokenTextsInTurn = CopyOnWriteArrayList<String>()

    @Volatile
    private var lastSpeechQueuedAtMs: Long = 0L

    fun recordSpokenText(text: String) {
        if (text.isBlank()) return
        spokenTextsInTurn.add(text)
        lastSpeechQueuedAtMs = System.currentTimeMillis()
    }

    fun getSpokenTexts(): List<String> = spokenTextsInTurn.toList()

    fun hasSpokenInTurn(): Boolean = spokenTextsInTurn.isNotEmpty()

    suspend fun awaitBeforeToolExecution(
        toolName: String,
        isCancelled: () -> Boolean
    ) {
        if (toolName !in audioOutputTools || spokenTextsInTurn.isEmpty()) return
        awaitSpeechPlaybackFinished(
            queuedAtMs = lastSpeechQueuedAtMs,
            isCancelled = isCancelled
        )
    }

    suspend fun awaitSpeechPlaybackFinished(
        queuedAtMs: Long,
        startTimeoutMs: Long = DEFAULT_START_TIMEOUT_MS,
        maxWaitMs: Long = DEFAULT_MAX_WAIT_MS,
        isCancelled: () -> Boolean
    ) {
        // Wait for TTS + AudioPlayer startup
        if (queuedAtMs > 0L) {
            val startDeadline = queuedAtMs + startTimeoutMs
            while (!isCancelled() && System.currentTimeMillis() < startDeadline) {
                if (isPlaybackActive()) break
                delay(50L)
            }
        }

        // Wait until AudioPlayer and TurnManager finish draining
        val endDeadline = System.currentTimeMillis() + maxWaitMs
        while (!isCancelled() && System.currentTimeMillis() < endDeadline) {
            if (!isPlaybackActive()) break
            delay(100L)
        }
    }

    fun reset() {
        spokenTextsInTurn.clear()
        lastSpeechQueuedAtMs = 0L
    }

    private fun isPlaybackActive(): Boolean {
        val adapter = AraWisperManager.chatActivityAdapter
        return AraWisperManager.speechManager.isSpeakingOrCoolingDown() ||
                adapter?.viewModel?.isAudioPlaying?.value == true ||
                adapter?.turnManager?.state == TurnManager.State.SPEAKING
    }

    companion object {
        private const val DEFAULT_START_TIMEOUT_MS = 2_500L
        private const val DEFAULT_MAX_WAIT_MS = 15_000L
        val DEFAULT_AUDIO_OUTPUT_TOOLS = setOf("play_melody")
    }
}