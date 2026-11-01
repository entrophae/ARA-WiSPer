package de.rub.hcd.entrophae.ara_wisper.server.service

import android.util.Base64
import android.util.Log
import de.rub.hcd.entrophae.ara_wisper.manager.AraWisperManager
import de.rub.hcd.entrophae.ara_wisper.manager.ProxySettingsRepository
import de.rub.hcd.entrophae.ara_wisper.server.manager.DashboardManager
import java.io.ByteArrayOutputStream


class AudioProcessor(
    private val preRollChunks: Int = 3,
    private val silenceChunksThreshold: Int = 15
) {
    private var currentModel = ProxySettingsRepository.currentVadMode;
    private var vad: VadProvider = VadProvider.create(currentModel);

    private var isSpeaking = false
    private var silentChunksCount = 0
    private val audioBuffer = ByteArrayOutputStream()
    private val preSpeechBuffer = mutableListOf<ByteArray>()

    private var vadBuffer = ByteArray(0)

    fun processChunk(audioBytes: ByteArray): String? {
        vadBuffer += audioBytes

        var hasSpeech = false
        var framesEvaluated = 0

        // slice out frame sizes to fit into vad model
        while (vadBuffer.size >= vad.frameSizeBytes) {
            val frame = vadBuffer.sliceArray(0 until vad.frameSizeBytes)
            vadBuffer = vadBuffer.sliceArray(vad.frameSizeBytes until vadBuffer.size)

            if (vad.isSpeech(frame)) {
                hasSpeech = true
            }
            framesEvaluated++
        }

        val effectivelySpeaking = if (framesEvaluated > 0) hasSpeech else isSpeaking

        if (effectivelySpeaking) {

            DashboardManager.sendDashboardEvent(ProxySettingsRepository.Dashboard.Events.MIC_ACTIVITY)
            if (!isSpeaking) {
                Log.d(ProxySettingsRepository.TAG, "AudioProcessor: Speech started!")
                isSpeaking = true

                preSpeechBuffer.forEach { audioBuffer.write(it) }
                preSpeechBuffer.clear()
            }

            silentChunksCount = 0
            audioBuffer.write(audioBytes)

        } else {
            if (isSpeaking) {

                if (framesEvaluated > 0) silentChunksCount++

                audioBuffer.write(audioBytes)

                if (silentChunksCount >= silenceChunksThreshold) {
                    Log.i( ProxySettingsRepository.TAG, "AudioProcessor: Speech ended! Ready for STT.")
                    isSpeaking = false
                    silentChunksCount = 0

                    val finalBytes = audioBuffer.toByteArray()
                    audioBuffer.reset()

                    if (finalBytes.isNotEmpty()) {
                        return Base64.encodeToString(finalBytes, Base64.NO_WRAP)
                    }
                }
            } else {
                preSpeechBuffer.add(audioBytes)
                if (preSpeechBuffer.size > preRollChunks) {
                    preSpeechBuffer.removeAt(0)
                }
            }
        }

        return null
    }

    fun sliceBufferAsBase64(buffer: ByteArray, audioEndMs: Int): String? {
        val byteCount = audioEndMs * 32 // 16kHz PCM16 mono = 32 bytes/ms
        if (buffer.isEmpty() || byteCount <= 0) return null

        val sliceLength = minOf(byteCount, buffer.size)
        val slicedAudio = buffer.copyOfRange(0, sliceLength)
        return Base64.encodeToString(slicedAudio, Base64.NO_WRAP)
    }

    fun reset() {
        isSpeaking = false
        silentChunksCount = 0
        audioBuffer.reset()
        preSpeechBuffer.clear()
        vadBuffer = ByteArray(0)
    }

    fun close() {
        vad.close()
    }

    fun reconfigure() {
        val newVadModel = ProxySettingsRepository.currentVadMode;

        if (newVadModel != currentModel) {
            Log.i(ProxySettingsRepository.TAG, "AudioProcessor: Hot-swapping VAD from $currentModel to $newVadModel")

            vad.close()
            try {
                vad = VadProvider.create(newVadModel)
                currentModel = newVadModel
                vadBuffer = ByteArray(0)
            } catch (e: Exception) {
                Log.e(ProxySettingsRepository.TAG, "AudioProcessor: Error $e")
            }
            Log.i(ProxySettingsRepository.TAG, "AudioProcessor: Hot-swap complete")
        } else {
            Log.d(ProxySettingsRepository.TAG, "AudioProcessor: VAD model unchanged ($currentModel).")
        }
    }

}