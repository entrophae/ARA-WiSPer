package de.rub.hcd.entrophae.ara_wisper.manager

import android.util.Base64
import de.rub.hcd.entrophae.ara_wisper.server.network.WebSocketPayloads
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Turn-scoped outbound adapter created for a single assistant response (`responseId`).
 *
 * Encapsulates all Realtime WebSocket protocol packets (`thinking`, `transcript.delta`,
 * `audio.delta`, `tool_call`, `audio.done`, `done`) and coordinates sentence-buffered
 * TTS synthesis with ordered, sequential audio chunk transmission.
 */
class TurnBridge(
    val responseId: String,
    val turnId: String,
    private val sendPacket: suspend (String) -> Unit,
    private val launchAudioSend: (suspend () -> Unit) -> Job?
) {
    private val audioSendJobs = CopyOnWriteArrayList<Job>()

    suspend fun onUserSpeechCommitted() {
        sendPacket(WebSocketPayloads.userItemPlaceholder(turnId))
    }

    suspend fun onUserTranscriptReady(text: String) {
        sendPacket(WebSocketPayloads.sttTranscriptCompleted(text))
    }

    suspend fun onThinkingStarted() {
        ProxyStateBridge.markThinking()
        sendPacket(WebSocketPayloads.thinking(responseId))
        sendPacket(WebSocketPayloads.outputItemAdded("${turnId}_assistant"))
    }

    suspend fun onAssistantOutputChunk(
        text: String,
        isThinkingText: Boolean = false,
        onPcmGenerated: ((ByteArray) -> Unit)? = null,
        onAcceptedText: ((String) -> Unit)? = null
    ) {
        if (text.isBlank()) return

        sendPacket(WebSocketPayloads.transcriptDelta(responseId, text))
        AraWisperManager.speechManager.processTextChunk(text) { pcmBytes ->
            onPcmGenerated?.invoke(pcmBytes)
            enqueueAudioDelta(pcmBytes)
        }
        if (!isThinkingText) {
            onAcceptedText?.invoke(text)
        }
    }

    suspend fun flushSpeech(onPcmGenerated: ((ByteArray) -> Unit)? = null) {
        AraWisperManager.speechManager.flush { pcmBytes ->
            onPcmGenerated?.invoke(pcmBytes)
            enqueueAudioDelta(pcmBytes)
        }
        awaitAudioFlushed()
    }

    suspend fun onDelegateToolCall(callId: String, name: String, args: String?) {
        ProxyStateBridge.keepTurnActiveForExternalTool()
        sendPacket(WebSocketPayloads.toolCall(callId, name, args))
    }

    suspend fun onTurnCompleted(
        isWaitingForTool: Boolean,
        isCancelled: Boolean,
        onPcmGenerated: ((ByteArray) -> Unit)? = null
    ) {
        flushSpeech(onPcmGenerated)
        sendPacket(WebSocketPayloads.audioDone())

        if (!isWaitingForTool && !isCancelled) {
            sendPacket(WebSocketPayloads.done())
        }
    }

    fun cancelPendingAudio() {
        val pending = audioSendJobs.toList()
        audioSendJobs.clear()
        pending.forEach { it.cancel() }
    }

    private fun enqueueAudioDelta(pcmBytes: ByteArray) {
        val base64Audio = Base64.encodeToString(pcmBytes, Base64.NO_WRAP)
        val job = launchAudioSend {
            sendPacket(WebSocketPayloads.audioDelta(responseId, base64Audio))
        } ?: return
        audioSendJobs.add(job)
    }

    private suspend fun awaitAudioFlushed() {
        val pending = audioSendJobs.toList()
        audioSendJobs.clear()
        pending.joinAll()
    }
}