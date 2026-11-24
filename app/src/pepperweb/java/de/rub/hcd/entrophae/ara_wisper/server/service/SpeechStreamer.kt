package de.rub.hcd.entrophae.ara_wisper.server.service

import android.util.Base64
import de.rub.hcd.entrophae.ara_wisper.manager.AraWisperManager
import de.rub.hcd.entrophae.ara_wisper.server.data.PipelineContext

/**
 * Outbound counterpart to AudioProcessor: converts LLM text chunks into TTS PCM audio.
 */
class SpeechStreamer {

    suspend fun speak(
        llmText: String,
        pipelineContext: PipelineContext,
        isThinkingText: Boolean = false
    ) {
        if (llmText.isBlank()) return

        pipelineContext.sendTranscriptDelta(llmText)
        AraWisperManager.speechManager.processTextChunk(llmText) { pcmBytes ->
            enqueuePcmBytes(pcmBytes, pipelineContext)
        }
        if (!isThinkingText) {
            pipelineContext.turn.appendLlmText(llmText)
        }
    }

    suspend fun flush(pipelineContext: PipelineContext) {
        AraWisperManager.speechManager.flush { pcmBytes ->
            enqueuePcmBytes(pcmBytes, pipelineContext)
        }
        pipelineContext.awaitAudioFlushed()
    }

    private fun enqueuePcmBytes(pcmBytes: ByteArray, pipelineContext: PipelineContext) {
        pipelineContext.turn.audioBuffer.write(pcmBytes)
        val base64Audio = Base64.encodeToString(pcmBytes, Base64.NO_WRAP)
        pipelineContext.enqueueAudioDelta(base64Audio)
    }
}