package de.rub.hcd.entrophae.ara_wisper.server.network

import android.util.Log
import ch.fhnw.pepper_realtime.network.HttpClientManager
import de.rub.hcd.entrophae.ara_wisper.manager.ProxySettingsRepository
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

object SttClient {
    private val client = HttpClientManager.getInstance().getApiClient()

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
}