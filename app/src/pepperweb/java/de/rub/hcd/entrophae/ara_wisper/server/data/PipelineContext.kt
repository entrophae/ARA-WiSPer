package de.rub.hcd.entrophae.ara_wisper.server.data

import de.rub.hcd.entrophae.ara_wisper.server.network.WebSocketPayloads
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList

data class PipelineContext(
    val responseId: String,
    val sendPacket: suspend (String) -> Unit,
    private val audioSendDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val audioSendJobs = CopyOnWriteArrayList<Job>()

    fun enqueueAudioDelta(scope: CoroutineScope, base64Audio: String) {
        val job = scope.launch(audioSendDispatcher) {
            sendAudioDelta(base64Audio)
        }
        audioSendJobs.add(job)
    }

    suspend fun awaitAudioFlushed() {
        val pendingJobs = audioSendJobs.toList()
        audioSendJobs.clear()
        pendingJobs.joinAll()
    }

    suspend fun sendAudioDelta(base64Audio: String) {
        sendPacket(WebSocketPayloads.audioDelta(responseId, base64Audio))
    }

    suspend fun sendTranscriptDelta(text: String) {
        sendPacket(WebSocketPayloads.transcriptDelta(responseId, text))
    }

    suspend fun sendOutputItemAdded(itemId: String) {
        sendPacket(WebSocketPayloads.outputItemAdded(itemId))
    }

    suspend fun sendThinkingPacket() {
        sendPacket(WebSocketPayloads.thinking(responseId))
    }

    suspend fun sendSttTranscriptCompleted(text: String) {
        sendPacket(WebSocketPayloads.sttTranscriptCompleted(text))
    }

    suspend fun sendAudioDone() {
        sendPacket(WebSocketPayloads.audioDone())
    }

    suspend fun sendDone() {
        sendPacket(WebSocketPayloads.done())
    }

    suspend fun sendToolCallPacket(callId: String, name: String, args: String?) {
        sendPacket(WebSocketPayloads.toolCall(callId, name, args))
    }
}

fun createPipelineContext(
    responseId: String,
    sendPacket: suspend (String) -> Unit,
    audioSendDispatcher: CoroutineDispatcher = Dispatchers.IO
): PipelineContext {
    return PipelineContext(
        responseId = responseId,
        sendPacket = sendPacket,
        audioSendDispatcher = audioSendDispatcher
    )
}