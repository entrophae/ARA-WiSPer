package de.rub.hcd.entrophae.ara_wisper.server.network

import android.util.Log
import ch.fhnw.pepper_realtime.network.HttpClientManager
import de.rub.hcd.entrophae.ara_wisper.manager.ProxySettingsRepository
import de.rub.hcd.entrophae.ara_wisper.server.data.ChatTurn
import de.rub.hcd.entrophae.ara_wisper.server.data.logTurnState
import de.rub.hcd.entrophae.ara_wisper.server.service.AudioProcessor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object SttClient {
    private val client = HttpClientManager.getInstance().getApiClient()


    suspend fun fetchStatus() = withContext(Dispatchers.IO) {
        val apiEndpoint = ProxySettingsRepository.customSttEndpoint.replace("/api","")
        val apiKey = ProxySettingsRepository.customSttKey
        val targetUrl = "$apiEndpoint/status"

        try {
            val request = Request.Builder()
                .url(targetUrl)
                .authToken(apiKey)
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                Log.i("STT HEALTH", response.toString())
                if (response.isSuccessful && response.body != null) {
                    return@withContext  response.body!!.string()
                }
            }
        } catch (e: Exception) {
            Log.e(ProxySettingsRepository.TAG, "RAG Status Error: ${e.message}")
        }
        return@withContext """{"status":"error"}"""

    }

    fun sendAudioChunk(audioDataToSend: String): String {
        val apiEndpoint = ProxySettingsRepository.customSttEndpoint
        val apiKey = ProxySettingsRepository.customSttKey

        Log.i(ProxySettingsRepository.TAG, "Sending ${audioDataToSend.length} bytes of audio to Python Faster-Whisper...")

        val sttBody = JSONObject().apply {
            put("audio_b64", audioDataToSend)
        }.toString().toRequestBody("application/json".toMediaType())

        val request = Request.Builder().url(apiEndpoint).authToken(apiKey).post(sttBody).build()

        return try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val respJson = JSONObject(response.body?.string() ?: "{}")
                    val transcribedText = respJson.optString(ProxySettingsRepository.Proxy.Keys.TEXT, "")
                    Log.i(ProxySettingsRepository.TAG, "STT Result: $transcribedText")
                    transcribedText
                } else {
                    Log.e(ProxySettingsRepository.TAG, "STT Request failed: HTTP ${response.code}")
                    ""
                }
            }
        } catch (e: Exception) {
            Log.e(ProxySettingsRepository.TAG, "STT Request error", e)
            ""
        }
    }

    fun fixTranscribedTruncatedText(original: String, truncated: String): String {
        if (original.isBlank() || truncated.isBlank()) return original

        // Extract just the spoken words, ignoring all punctuation and markdown
        val wordRegex = Regex("[\\p{L}\\p{N}]+")
        val truncatedWordCount = wordRegex.findAll(truncated).count()
        val originalWords = wordRegex.findAll(original).toList()

        if (truncatedWordCount == 0 || originalWords.isEmpty()) return original

        // Take exactly the number of words STT heard, capped at the original length
        val wordsToKeep = minOf(truncatedWordCount, originalWords.size)

        // Find the index in the original string right after that final word
        val cutoffIndex = originalWords[wordsToKeep - 1].range.last + 1
        return original.substring(0, cutoffIndex)
    }

    suspend fun handleAudioTruncation( currentTurn: ChatTurn?, audioEndMs: Int, audioProcessor: AudioProcessor ) {
        val turnObj = currentTurn ?: return
        val audioBytes = turnObj.audioBuffer.toByteArray()
        val base64Audio = audioProcessor.sliceBufferAsBase64(audioBytes, audioEndMs) ?: return

        val rawTruncatedText = sendAudioChunk(base64Audio)
        if (rawTruncatedText.isNotBlank()) {
            val originalText = turnObj.llmResponse
            val fixedText = fixTranscribedTruncatedText(originalText, rawTruncatedText)
            turnObj.llmResponse = "$fixedText-"

            logTurnState("Truncated via STT transcription", turnObj)
            DataBaseClient.updateHistory(turnObj)
        }
    }
}