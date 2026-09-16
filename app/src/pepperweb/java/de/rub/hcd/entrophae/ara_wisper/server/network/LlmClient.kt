package de.rub.hcd.entrophae.ara_wisper.server.network

import android.util.Log
import ch.fhnw.pepper_realtime.network.HttpClientManager
import de.rub.hcd.entrophae.ara_wisper.manager.ProxySettingsRepository
import de.rub.hcd.entrophae.ara_wisper.server.data.PipelineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

object LlmClient {
    private val client = HttpClientManager.getInstance().getApiClient()

    data class ModelFallback(val cleanText: String, val toolName: String, val arguments: String)

    suspend fun fetchLlmResponse( textToProcess: String, pipelineContext: PipelineContext ): JSONObject? {
        val apiEndpoint = ProxySettingsRepository.customLlmEndpoint
        val apiModel = ProxySettingsRepository.customLlmModel
        val apiKey = ProxySettingsRepository.customLlmKey
        val restUrl = "$apiEndpoint/chat/completions"

        val messagesArray = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", pipelineContext.systemPrompt)
            })
            
            put(JSONObject().apply {
                put("role", "user")
                put("content", textToProcess)
            })
        }
        /*Log.i(
            ProxySettingsRepository.TAG,
            "Full LLM Context Payload:\n${messagesArray.toString(2)}"
        )*/

        val restTools = translateToolsToRestFormat(pipelineContext.toolsArray)

        if (!pipelineContext.apiSupportsTools && restTools.length() > 0) {
            val systemMessage = messagesArray.getJSONObject(0)
            val originalPrompt = systemMessage.optString("content", "")
            val fallbackInstructions = ProxySettingsRepository.fallbackToolInstruction
            val dynamicToolsList = collectTools(restTools)
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

    fun extractToolInformation( llmMessageObj: JSONObject, llmText: String, pipelineContext: PipelineContext): Triple<String?, String?, String> {
        var toolName: String? = null
        var toolArgs: String? = null
        var cleanedText = llmText

        val toolCalls = llmMessageObj.optJSONArray("tool_calls")
        if (toolCalls != null && toolCalls.length() > 0) {
            toolName = toolCalls.optJSONObject(0)?.optJSONObject("function")?.optString("name")
            toolArgs = toolCalls.optJSONObject(0)?.optJSONObject("function")?.optString("arguments")
        } else if (!pipelineContext.apiSupportsTools) {
            parseModelFallback(llmText)?.let {
                cleanedText = it.cleanText
                toolName = it.toolName
                toolArgs = it.arguments
            }
        }

        return Triple(toolName, toolArgs, cleanedText)
    }

    fun translateToolsToRestFormat(toolsArray: JSONArray?): JSONArray {
        val restTools = JSONArray()
        if (toolsArray == null) return restTools

        for (i in 0 until toolsArray.length()) {
            val rtTool = toolsArray.getJSONObject(i)
            if (rtTool.has("name") && !rtTool.has("function")) {
                restTools.put(JSONObject().apply {
                    put("type", "function")
                    put("function", JSONObject().apply {
                        put("name", rtTool.optString("name"))
                        put("description", rtTool.optString("description", ""))
                        if (rtTool.has("parameters")) put(
                            "parameters",
                            rtTool.optJSONObject("parameters")
                        )
                    })
                })
            } else {
                restTools.put(rtTool)
            }
        }
        return restTools
    }

    fun collectTools(toolsArray: JSONArray): String {
        val sb = StringBuilder()
        sb.append("\n\n# VERFÜGBARE TOOLS\n")
        sb.append("Du hast Zugriff auf die folgenden Tools. Verwende exakt die angegebenen Namen und Parameter-Schemas:\n\n")

        for (i in 0 until toolsArray.length()) {
            val tool = toolsArray.getJSONObject(i)
            val functionObj = tool.optJSONObject("function") ?: continue

            val name = functionObj.optString("name")
            val description = functionObj.optString("description", "Keine Beschreibung.")
            val parameters = functionObj.optJSONObject("parameters")?.toString() ?: "{}"

            sb.append("- Tool Name: $name\n")
            sb.append("  Beschreibung: $description\n")
            sb.append("  Parameter (JSON Schema): $parameters\n\n")
        }
        return sb.toString()
    }

    suspend fun checkApiToolSupport(modelName: String): Boolean = withContext(Dispatchers.IO){
        val apiEndpoint = ProxySettingsRepository.customLlmEndpoint
        val apiKey = ProxySettingsRepository.customLlmKey
        val restUrl = "$apiEndpoint/chat/completions"

        Log.i(ProxySettingsRepository.TAG, "Probing API to see if it supports native Tool Calling...")

        // If support is there, wrong parameter input should give an error
        val testBody = JSONObject().apply {
            put("model", modelName)
            put("messages", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("content", "ping")
            }))
            put("tools", "not_an_array_test")
        }.toString().toRequestBody("application/json".toMediaType())


        val request = Request.Builder().url(restUrl).authBearer(apiKey).post(testBody).build()

        try {
            client.newCall(request).execute().use { response ->
                val supported = response.code == 400

                if (supported) {
                    Log.i(ProxySettingsRepository.TAG, "Probe Result: API SUPPORTS native tools! (Got 400)")
                } else {
                    Log.i(ProxySettingsRepository.TAG, "Probe Result: API STRIPS tools. (Got ${response.code}). Using Prompt Fallback.")
                }
                return@withContext supported
            }
        } catch (e: Exception) {
            Log.e(ProxySettingsRepository.TAG, "Probe failed, defaulting to Prompt Fallback", e)
            return@withContext false
        }
    }
}