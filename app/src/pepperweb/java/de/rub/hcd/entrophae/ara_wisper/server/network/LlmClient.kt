package de.rub.hcd.entrophae.ara_wisper.server.network

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Base64
import android.util.Log
import androidx.core.graphics.createBitmap
import ch.fhnw.pepper_realtime.network.HttpClientManager
import de.rub.hcd.entrophae.ara_wisper.interceptor.ToolInterceptor
import de.rub.hcd.entrophae.ara_wisper.manager.ProxySettingsRepository
import de.rub.hcd.entrophae.ara_wisper.network.authBearer
import de.rub.hcd.entrophae.ara_wisper.server.data.ChatTurn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

object LlmClient {
    private val client = HttpClientManager.getInstance().getApiClient()

    data class ModelFallback(val cleanText: String, val toolName: String, val arguments: String)

    suspend fun fetchLlmResponse(textToProcess: String, currentTurn: ChatTurn): JSONObject? {

        val isVisionTask = !currentTurn.userImageUrl.isNullOrBlank()

        val apiEndpoint = if (isVisionTask) ProxySettingsRepository.customVlmEndpoint else ProxySettingsRepository.customLlmEndpoint
        val apiModel = if (isVisionTask) ProxySettingsRepository.customVlmModel else ProxySettingsRepository.customLlmModel
        val apiKey = if (isVisionTask) ProxySettingsRepository.customVlmKey else ProxySettingsRepository.customLlmKey

        val systemPrompt = ProxySettingsRepository.activeSystemPrompt
        val currentTools = ProxySettingsRepository.currentTools

        val restUrl = "$apiEndpoint/chat/completions"

        val messagesArray = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", systemPrompt)
            })
            val history = DataBaseClient.getUserHistory(currentTurn.userId)
            for (turn in history) {
                if (turn.userQuery.isNotBlank()) {
                    put(JSONObject().apply {
                        put("role", "user")
                        if (!turn.userImageUrl.isNullOrBlank()) {
                            put("content", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("type", "text")
                                    put("text", turn.userQuery)
                                })
                                put(JSONObject().apply {
                                    put("type", "image_url")
                                    put("image_url", JSONObject().apply {
                                        put("url", turn.userImageUrl)
                                    })
                                })
                            })
                        } else {
                            put("content", turn.userQuery)
                        }
                    })
                }
                if (turn.llmResponse.isNotBlank()) {
                    put(JSONObject().apply {
                        put("role", "assistant")
                        put("content", turn.llmResponse)
                    })
                }
            }
            if (isVisionTask) {
                Log.i(ProxySettingsRepository.TAG, "Sending image with image=${!currentTurn.userImageUrl.isNullOrBlank()}")
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "text")
                            put("text", textToProcess)
                        })
                        put(JSONObject().apply {
                            put("type", "image_url")
                            put("image_url", JSONObject().apply {
                                put("url", currentTurn.userImageUrl)
                            })
                        })
                    })
                })
            } else {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", textToProcess)
                })
            }
        }
        /*
        Log.i(
            ProxySettingsRepository.TAG,
            "Full LLM Context Payload:\n${messagesArray.toString(2)}"
        )
         */

        val restTools = ToolInterceptor.translateToolsToRestFormat(currentTools)

        if (!ProxySettingsRepository.customLlmHasTools && restTools.length() > 0) {
            val systemMessage = messagesArray.getJSONObject(0)
            val originalPrompt = systemMessage.optString("content", "")
            val fallbackInstructions = ProxySettingsRepository.fallbackToolInstruction
            val dynamicToolsList = ToolInterceptor.collectTools(restTools)
            systemMessage.put("content", originalPrompt + fallbackInstructions + dynamicToolsList)
        }

        val reqBody = JSONObject().apply {
            put("model", apiModel)
            put("messages", messagesArray)
            if (restTools.length() > 0) {
                put("tools", restTools)
                put("tool_choice", "auto")
            }
        }.toString().toRequestBody("application/json".toMediaType())

        val request = Request.Builder().url(restUrl).authBearer(apiKey).post(reqBody).build()

        return try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val rawResponse = response.body?.string() ?: "{}"
                    Log.i("TEST", rawResponse)
                    JSONObject(rawResponse).optJSONArray("choices")?.optJSONObject(0)
                        ?.optJSONObject("message")
                } else {
                    Log.e(ProxySettingsRepository.TAG, "HTTP ${response.code}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(ProxySettingsRepository.TAG, "REST LLM Network Error", e)
            null
        }
    }

    fun toolDetected(llmMessageObj: JSONObject): Boolean {
        val toolCalls = llmMessageObj.optJSONArray("tool_calls")
        return (toolCalls != null && toolCalls.length() > 0)
                || llmMessageObj.optString("content", "").contains("[TOOL_CALLS]")
    }

    fun parseModelFallback(llmResponseText: String): ModelFallback? {
        if (!llmResponseText.contains("[TOOL_CALLS]")) return null
        val splitText = llmResponseText.split("\\[TOOL_CALLS]".toRegex())
        val cleanText = splitText[0].trim()
        val toolString = splitText.getOrNull(1)?.trim() ?: return null

        val firstBrace = toolString.indexOf('{')
        if (firstBrace == -1) return null

        var braceCount = 0
        var lastBraceIndex = -1
        for (i in firstBrace until toolString.length) {
            if (toolString[i] == '{') braceCount++
            else if (toolString[i] == '}') {
                braceCount--
                if (braceCount == 0) {
                    lastBraceIndex = i
                    break
                }
            }
        }
        if (lastBraceIndex != -1) {
            val name = toolString.substring(0, firstBrace).trim()
            val args = toolString.substring(firstBrace, lastBraceIndex + 1).trim()
            return ModelFallback(cleanText, name, args)
        }
        return null
    }

    fun extractToolInformation( llmMessageObj: JSONObject, llmText: String): Triple<String?, String?, String> {
        var toolName: String? = null
        var toolArgs: String? = null
        var cleanedText = llmText

        val toolCalls = llmMessageObj.optJSONArray("tool_calls")
        if (toolCalls != null && toolCalls.length() > 0) {
            toolName = toolCalls.optJSONObject(0)?.optJSONObject("function")?.optString("name")
            toolArgs = toolCalls.optJSONObject(0)?.optJSONObject("function")?.optString("arguments")
        } else if (!ProxySettingsRepository.customLlmHasTools) {
            parseModelFallback(llmText)?.let {
                cleanedText = it.cleanText
                toolName = it.toolName
                toolArgs = it.arguments
            }
        }

        return Triple(toolName, toolArgs, cleanedText)
    }

    suspend fun getAvailableModels(endpoint: String, apiKey: String): String = withContext(Dispatchers.IO) {
        if (endpoint.isBlank() || apiKey.isBlank()) return@withContext """{"error": "Missing Endpoint/apiKey"}"""

        val targetUrl = "$endpoint/models"
        val request = Request.Builder().url(targetUrl).authBearer(apiKey).get().build()

        return@withContext try {
            client.newCall(request).execute().use { response ->
                response.body?.string() ?: "[]"
            }
        } catch (e: Exception) {
            """{"error": "${e.message}"}"""
        }
    }

    suspend fun probeModelCapability(modelId: String, probeType: String, endpoint: String, apiKey: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (endpoint.isBlank() || apiKey.isBlank()) return@withContext Pair(false, "endpoint empty")

        val targetUrl = if (probeType == "embed") "$endpoint/embeddings" else "$endpoint/chat/completions"

        val requestBodyJson = JSONObject().apply {
            put("model", modelId)
            when (probeType) {
                "vision" -> {
                    put("messages", JSONArray().put(JSONObject().apply {
                        put("role", "user")
                        put("content", JSONArray().put(JSONObject().apply {
                            put("type", "text")
                            put("text", "Read the nine characters in the image. Reply with only those nine characters")
                        }).put(JSONObject().apply {
                            put("type", "image_url")
                            put("image_url", JSONObject().put("url", createVisionProbeImage()))
                        }))
                    }))
                }
                "embed" -> put("input", "test")
                "tools" -> {
                    put("messages", JSONArray().put(JSONObject().apply {
                        put("role", "user")
                        put("content", "ping")
                    }))
                    put("tools", "tools-not-array")
                }
                "text" -> {
                    put("messages", JSONArray().put(JSONObject().apply {
                        put("role", "user")
                        put("content", "ping")
                    }))
                    put("max_tokens", 1)
                }
            }
        }

        val reqBody = requestBodyJson.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(targetUrl).authBearer(apiKey).post(reqBody).build()

        return@withContext try {
            client.newCall(request).execute().use { response ->
                val responseBodyStr = response.body?.string() ?: ""
                if (response.code == 404 || response.code == 405) {
                    return@use Pair(false, responseBodyStr)
                }

                val isSupported = when (probeType) {
                    "tools" -> response.code == 400 && (responseBodyStr.lowercase().contains("tools") || responseBodyStr.lowercase().contains("param"))
                    "vision" -> {
                        if (!response.isSuccessful) false else {
                            checkVisionProbeResult(responseBodyStr)
                        }
                    }
                    "embed" -> response.isSuccessful && !responseBodyStr.contains("\"error\"")
                    else -> response.isSuccessful
                }
                Pair(isSupported, responseBodyStr)
            }
        } catch (e: Exception) {
            Pair(false, e.message ?: "Unknown error")
        }
    }

    private fun createVisionProbeImage(): String {
        val probeText = ProxySettingsRepository.VISUAL_PROBE;
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 50F
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.LEFT
        }
        val bounds = android.graphics.Rect()
        paint.getTextBounds(probeText, 0, probeText.length, bounds)

        val paddingX = 60
        val paddingY = 40
        val width = bounds.width() + paddingX
        val height = bounds.height() + paddingY

        val bitmap = createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        canvas.drawColor(Color.WHITE)

        val x = (paddingX / 2f) - bounds.left
        val y = (height / 2f) + (bounds.height() / 2f) - bounds.bottom

        canvas.drawText(probeText, x, y, paint)

        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
        bitmap.recycle()

        val base64 = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
        return "data:image/png;base64,$base64"
    }

    private suspend fun checkVisionProbeResult(responseBodyStr: String): Boolean = withContext(
        Dispatchers.IO) {
        val probeText = ProxySettingsRepository.VISUAL_PROBE;

        return@withContext try {
            val answer = JSONObject(responseBodyStr)
                .getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").optString("content").trim()
            val sameLength = answer.length == probeText.length
            val characterDeviation = answer.zip(probeText)
                .count {(a, b) -> a.uppercaseChar() != b.uppercaseChar()}
            val acceptableDeviation = characterDeviation <= kotlin.math.ceil((probeText.length / 2).toDouble())

            sameLength && acceptableDeviation
        } catch (e: Exception) { false }
    }
}