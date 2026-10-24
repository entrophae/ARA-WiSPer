package de.rub.hcd.entrophae.ara_wisper.server.data

import de.rub.hcd.entrophae.ara_wisper.server.network.WebSocketPayloads

data class PipelineContext(
    val responseId: String,
    val sendPacket: suspend (String) -> Unit
) {
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

fun createPipelineContext(responseId: String, sendPacket: suspend (String) -> Unit): PipelineContext {
    val context = PipelineContext(
        responseId = responseId,
        sendPacket = sendPacket
    )
    return context
}