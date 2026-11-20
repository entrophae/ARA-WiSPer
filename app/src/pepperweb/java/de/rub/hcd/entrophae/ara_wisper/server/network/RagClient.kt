package de.rub.hcd.entrophae.ara_wisper.server.network

import android.util.Log
import ch.fhnw.pepper_realtime.network.HttpClientManager
import de.rub.hcd.entrophae.ara_wisper.manager.ProxySettingsRepository
import de.rub.hcd.entrophae.ara_wisper.network.authToken
import io.ktor.utils.io.errors.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder

object RagClient {
    private val client = HttpClientManager.getInstance().getQuickApiClient()

    private fun buildConfigJson(endpoint: String, key: String, model: String): String {
        return JSONObject().apply {
            put("endpoint", endpoint)
            put("key", key)
            put("model", model)
        }.toString()
    }

    suspend fun fetchStatus() = withContext(Dispatchers.IO) {
        val endpoint = ProxySettingsRepository.databaseFactApiEndpoint
        val apiKey = ProxySettingsRepository.databaseFactKey
        val targetUrl = "$endpoint/status"

        try {
            val request = Request.Builder()
                .url(targetUrl)
                .authToken(apiKey)
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                Log.i("RAG HEALTH", response.toString())

                val contentType = response.header("Content-Type") ?: ""

                if (contentType.contains("text/html")) {
                    throw IOException("Intercepted by Shibboleth SSO instead of target endpoint")
                }

                if (response.isSuccessful && response.body != null) {
                    return@withContext response.body!!.string()
                }
            }
        } catch (e: Exception) {
            Log.e(ProxySettingsRepository.TAG, "RAG Status Error: ${e.message}")
        }
        return@withContext """{"status":"error"}"""
    }

    suspend fun ingestFile(experimentId: String, fileName: String, fileBytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val endpoint = ProxySettingsRepository.databaseFactApiEndpoint
        val apiKey = ProxySettingsRepository.databaseFactKey
        val targetUrl = "$endpoint${ProxySettingsRepository.Routes.DB_API_INGEST}"

        try {
            val requestBody = fileBytes.toRequestBody("application/octet-stream".toMediaType())

            val multipartBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", fileName, requestBody)
                .build()

            val llmConfig = buildConfigJson(ProxySettingsRepository.customLlmEndpoint, ProxySettingsRepository.customLlmKey, ProxySettingsRepository.customLlmModel)
            val vlmConfig = buildConfigJson(ProxySettingsRepository.customVlmEndpoint, ProxySettingsRepository.customVlmKey, ProxySettingsRepository.customVlmModel)
            val embeddingConfig = buildConfigJson(ProxySettingsRepository.customEmbeddingEndpoint, ProxySettingsRepository.customEmbeddingKey, ProxySettingsRepository.customEmbeddingModel)
            val sttConfig = buildConfigJson(ProxySettingsRepository.customSttEndpoint, ProxySettingsRepository.customSttKey, "custom-whisper")

            val request = Request.Builder()
                .url(targetUrl)
                .authToken(apiKey)
                .post(multipartBody)
                .addHeader("llm-config", llmConfig)
                .addHeader("vlm-config", vlmConfig)
                .addHeader("embedding-config", embeddingConfig)
                .addHeader("stt-config", sttConfig)
                .addHeader("experiment-id", experimentId)
                .addHeader("user-id", ProxySettingsRepository.activeUserId)
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Log.d(
                        ProxySettingsRepository.TAG,
                        "DB: Ingestesd file in Vault"
                    )
                } else {
                    Log.e(
                        ProxySettingsRepository.TAG,
                        "DB: REST rejected file injection - Code: ${response.code}"
                    )
                    Log.e(ProxySettingsRepository.TAG, "DB: response: $response")
                }
                return@withContext response.isSuccessful
            }
        } catch (e: Exception) {
            Log.e(ProxySettingsRepository.TAG, "RAG Ingest Error: ${e.message}")
            return@withContext false
        }
    }

    suspend fun search(keywords: String, userId: String, experimentId: String): List<String> = withContext(Dispatchers.IO) {
        val endpoint = ProxySettingsRepository.databaseFactApiEndpoint
        val apiKey = ProxySettingsRepository.databaseFactKey
        val encodedKeywords = URLEncoder.encode(keywords, "UTF-8").replace("+", "%20")
        val targetUrl = "$endpoint${ProxySettingsRepository.Routes.DB_API_SEARCH}"

        val llmConfig = buildConfigJson(ProxySettingsRepository.customLlmEndpoint, ProxySettingsRepository.customLlmKey, ProxySettingsRepository.customLlmModel)
        val vlmConfig = buildConfigJson(ProxySettingsRepository.customVlmEndpoint, ProxySettingsRepository.customVlmKey, ProxySettingsRepository.customVlmModel)
        val embeddingConfig = buildConfigJson(ProxySettingsRepository.customEmbeddingEndpoint, ProxySettingsRepository.customEmbeddingKey, ProxySettingsRepository.customEmbeddingModel)

        try {

            val request = Request.Builder()
                .url(targetUrl)
                .authToken(apiKey)
                .get()
                .addHeader("llm-config", llmConfig)
                .addHeader("vlm-config", vlmConfig)
                .addHeader("embedding-config", embeddingConfig)
                .addHeader("dimension", "4096")
                .addHeader("limit", "10")
                .addHeader("keywords", encodedKeywords)
                .addHeader("experiment-id", experimentId)
                .addHeader("user-id", userId)
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful && response.body != null) {
                    val jsonResponse = JSONObject(response.body!!.string())
                    val resultsArray = jsonResponse.optJSONArray("results")
                    val resultsList = mutableListOf<String>()
                    if (resultsArray != null) {
                        for (i in 0 until resultsArray.length()) {
                            resultsList.add(resultsArray.getString(i))
                        }
                    }
                    return@withContext resultsList
                }
            }
        } catch (e: Exception) {
            Log.e("AraWisper", "RAG Search Error: ${e.message}")
        }
        return@withContext emptyList()
    }

    suspend fun getProgress(experimentId: String, fileName: String) = withContext(Dispatchers.IO){
        val endpoint = ProxySettingsRepository.databaseFactApiEndpoint
        val apiKey = ProxySettingsRepository.databaseFactKey
        val targetUrl = "$endpoint/progress"

        try {
            val request = Request.Builder()
                .url(targetUrl)
                .authToken(apiKey)
                .get()
                .addHeader("experiment-id", experimentId)
                .addHeader("file-name", fileName)
                .build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful && response.body != null) {
                    return@withContext  response.body!!.string()
                }
            }
        } catch (e: Exception) {
            Log.e(ProxySettingsRepository.TAG, "RAG Progress Error: ${e.message}")
        }
        return@withContext """{"status":"error"}"""
    }

    suspend fun getFile(experimentId: String, fileName: String): Pair<ByteArray, String>? = withContext(Dispatchers.IO) {
        val endpoint = ProxySettingsRepository.databasePersonApiEndpoint
        val apiKey = ProxySettingsRepository.databasePersonKey
        val encodedName = URLEncoder.encode(fileName, "UTF-8").replace("+", "%20")
        val targetUrl = "$endpoint/storage/$experimentId/$encodedName"

        try {
            val request = Request.Builder()
                .url(targetUrl)
                .authToken(apiKey)
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful && response.body != null) {
                    val bytes = response.body!!.bytes()
                    val contentType = response.header("Content-Type") ?: "application/octet-stream"
                    return@withContext Pair(bytes, contentType)
                } else {
                    Log.e(ProxySettingsRepository.TAG, "Failed to fetch file: HTTP ${response.code}")
                }
            }
        } catch (e: Exception) {
            Log.e(ProxySettingsRepository.TAG, "Error fetching file from RAG server: ${e.message}", e)
        }
        return@withContext null
    }
}