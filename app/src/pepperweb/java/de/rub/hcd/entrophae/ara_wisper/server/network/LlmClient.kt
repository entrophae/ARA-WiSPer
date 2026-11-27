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
import de.rub.hcd.entrophae.ara_wisper.server.manager.DashboardManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * OpenAI-compatible Chat Completions HTTP client for LLM and VLM inference.
 *
 * Fetches available models, probes vision/tool capabilities, formats multi-turn tool
 * histories and image attachments, and parses assistant messages and tool calls.
 */
object LlmClient {
    private val client = HttpClientManager.getInstance().getApiClient()

    data class ParsedToolCall( val id: String, val name: String, val arguments: String )

    suspend fun fetchLlmResponse(textToProcess: String, currentTurn: ChatTurn): JSONObject? {

        val isVisionTask = !currentTurn.userImageUrl.isNullOrBlank()

        val apiEndpoint = if (isVisionTask) ProxySettingsRepository.customVlmEndpoint else ProxySettingsRepository.customLlmEndpoint
        val apiModel = if (isVisionTask) ProxySettingsRepository.customVlmModel else ProxySettingsRepository.customLlmModel
        val apiKey = if (isVisionTask) ProxySettingsRepository.customVlmKey else ProxySettingsRepository.customLlmKey

        val userKnowledge = DataBaseClient.getUserKnowledge(currentTurn.userId)
        val baseSystemPrompt = ProxySettingsRepository.activeSystemPrompt
        val systemPrompt = if (userKnowledge.chatSummary.isNotBlank()) {
            """
            $baseSystemPrompt
    
            ## Langzeitgedächtnis / Nutzerprofil (aus bisherigen Sitzungen):
            ${userKnowledge.chatSummary.trim()}
            """.trimIndent()
        } else {
            baseSystemPrompt
        }
        val currentTools = ProxySettingsRepository.currentTools
        val restTools = ToolInterceptor.translateToolsToRestFormat(currentTools)
        val useNativeTools = ProxySettingsRepository.customLlmHasTools && restTools.length() > 0
        val restUrl = "$apiEndpoint/chat/completions"

        val messagesArray = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", systemPrompt)
            })

            val recentHistory = userKnowledge.history
                .filter { it.turnId != currentTurn.turnId }
                .takeLast(ProxySettingsRepository.auto_summary_interval * 2)

            for (turn in recentHistory) {
                appendFullTurnToMessages(
                    messagesArray = this,
                    turn = turn,
                    useNativeTools = useNativeTools
                )
            }

            // Current turn prompt (+ optional vision attachment)
            if (isVisionTask) {
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

    private fun appendFullTurnToMessages(
        messagesArray: JSONArray,
        turn: ChatTurn,
        useNativeTools: Boolean
    ) {
        // User message (text + image if present)
        if (turn.userQuery.isNotBlank() || !turn.userImageUrl.isNullOrBlank()) {
            messagesArray.put(JSONObject().apply {
                put("role", "user")
                if (!turn.userImageUrl.isNullOrBlank()) {
                    put("content", JSONArray().apply {
                        if (turn.userQuery.isNotBlank()) {
                            put(JSONObject().apply {
                                put("type", "text")
                                put("text", turn.userQuery)
                            })
                        }
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

        // Tools executed during this turn (calls + exact answers)
        if (turn.toolsUsed.isNotEmpty()) {
            if (useNativeTools) {
                val toolCallsJson = JSONArray()
                turn.toolsUsed.forEachIndexed { idx, record ->
                    val resolvedCallId = record.callId.ifBlank { "${turn.turnId}_call_$idx" }
                    val argsJson = JSONObject().put("query", record.query).toString()
                    toolCallsJson.put(JSONObject().apply {
                        put("id", resolvedCallId)
                        put("type", "function")
                        put("function", JSONObject().apply {
                            put("name", record.name)
                            put("arguments", argsJson)
                        })
                    })
                }

                // Assistant message that triggered the tool calls (plus any spoken filler/buffer)
                messagesArray.put(JSONObject().apply {
                    put("role", "assistant")
                    put("content", turn.toolBufferResponse?.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
                    put("tool_calls", toolCallsJson)
                })

                // Individual tool result messages matching each tool_call_id
                turn.toolsUsed.forEachIndexed { idx, record ->
                    val resolvedCallId = record.callId.ifBlank { "${turn.turnId}_call_$idx" }
                    messagesArray.put(JSONObject().apply {
                        put("role", "tool")
                        put("tool_call_id", resolvedCallId)
                        put("name", record.name)
                        put("content", record.answer.ifBlank { "(kein Ergebnis)" })
                    })
                }
            } else {
                // Fallback formatting for models without native OpenAI tool_calls role support
                val toolSummaryBlock = buildString {
                    if (!turn.toolBufferResponse.isNullOrBlank()) {
                        appendLine(turn.toolBufferResponse!!.trim())
                    }
                    turn.toolsUsed.forEach { record ->
                        appendLine("[Tool ausgeführt: ${record.name} | Anfrage: ${record.query}]")
                        appendLine("[Tool-Ergebnis: ${record.answer.ifBlank { "(kein Ergebnis)" }}]")
                    }
                }.trim()

                if (toolSummaryBlock.isNotBlank()) {
                    messagesArray.put(JSONObject().apply {
                        put("role", "assistant")
                        put("content", toolSummaryBlock)
                    })
                }
            }
        } else if (!turn.toolBufferResponse.isNullOrBlank()) {
            // Filler spoken before an external/app tool even if toolsUsed wasn't populated
            messagesArray.put(JSONObject().apply {
                put("role", "assistant")
                put("content", turn.toolBufferResponse)
            })
        }

        // Final assistant response
        if (turn.llmResponse.isNotBlank()) {
            messagesArray.put(JSONObject().apply {
                put("role", "assistant")
                put("content", turn.llmResponse)
            })
        }
    }

    suspend fun summarizeUserKnowledge(
        userId: String,
        turnsOverride: List<ChatTurn>? = null,
        autoTrigger: Boolean = false
    ): String? = withContext(Dispatchers.IO) {
        if (userId.isBlank()) return@withContext null

        val knowledge = DataBaseClient.getUserKnowledge(userId)
        val maxTurns = ProxySettingsRepository.auto_summary_interval

        val effectiveTurnsOverride = if (autoTrigger) {
            val totalTurns = knowledge.history.size
            val shouldCompress = totalTurns >= maxTurns &&
                    (knowledge.chatSummary.isBlank() || totalTurns % maxTurns == 0)
            if (!shouldCompress) return@withContext knowledge.chatSummary
            knowledge.history.takeLast(maxTurns)
        } else {
            turnsOverride
        }

        val turns = effectiveTurnsOverride ?: knowledge.history
        if (turns.isEmpty() && knowledge.chatSummary.isBlank()) return@withContext knowledge.chatSummary

        val transcript = buildString {
            if (knowledge.chatSummary.isNotBlank()) {
                appendLine("### Bisheriges Nutzerprofil:")
                appendLine(knowledge.chatSummary.trim())
                appendLine()
            }
            appendLine("### Gesprächszüge:")
            turns.forEachIndexed { idx, turn ->
                val turnNum = idx + 1
                if (!turn.userImageUrl.isNullOrBlank()) {
                    appendLine("[$turnNum] Nutzer-Kamera/Bild: [Bild wurde gezeigt]")
                }
                if (turn.userQuery.isNotBlank()) {
                    appendLine("[$turnNum] Nutzer: ${turn.userQuery.trim()}")
                }
                if (!turn.toolBufferResponse.isNullOrBlank()) {
                    appendLine("[$turnNum] Pepper (Zwischenansage): ${turn.toolBufferResponse!!.trim()}")
                }
                if (turn.toolsUsed.isNotEmpty()) {
                    turn.toolsUsed.forEach { record ->
                        appendLine("[$turnNum] Tool '${record.name}' (Query: ${record.query}) -> Ergebnis: ${record.answer}")
                    }
                }
                if (turn.llmResponse.isNotBlank()) {
                    appendLine("[$turnNum] Pepper: ${turn.llmResponse.trim()}")
                }
            }
        }.trim()

        val summaryPrompt = """
            Du verwaltest das sitzungsübergreifende Langzeitgedächtnis und Interaktionsprotokoll des humanoiden Roboters Pepper.
            Erstelle aus dem bisherigen Gedächtnisstand und den neuen Gesprächszügen eine kompakte, strukturierte Zusammenfassung, damit Pepper (inklusive nachgelagerter Informationssuche über RAG-Abfragen oder Internet-Abfragen) in späteren Zügen oder Sitzungen nahtlos anknüpfen kann, ohne bereits vermittelte Fakten zu wiederholen oder bereits getestete Schritte erneut vorzuschlagen.
        
            Strukturiere die Ausgabe exakt in diese drei Abschnitte:
    
            ### Personenprofil
            - Identität & Kontext (Name, Alter, Studium/Beruf, Hintergrund, relevante Bezugspersonen) falls erwähnt
            - Interessen, Vorlieben, Abneigungen, Gewohnheiten & bevorzugter Gesprächsstil (z. B. technisch vs. laienverständlich, knapp vs. detailliert)
            
            ### Wissensstand der Person
            - Vorwissen: Welche Probleme, Ursachen oder Zusammenhänge kannte die Person bereits von sich aus?
            - Bereits vermitteltes Wissen: Welche Fakten, Erklärungen, Regeln oder RAG-Inhalte wurden der Person bereits mitgeteilt oder gemeinsam erarbeitet (müssen nicht erneut erklärt werden)?
            - Offene Wissenslücken: Was wusste die Person ausdrücklich nicht oder hat sie missverstanden?
            
            ### Interaktions- & Aufgabenprotokoll
            - Ausgangslage / Ziel: Worum ging es in der Sitzung bzw. im Experiment (z. B. konkretes Problem, Gerät, Aufgabe)?
            - Durchgeführte Schritte & Ergebnisse: Was wurde gemeinsam ausprobiert, geprüft oder besprochen – und was hat funktioniert bzw. nicht funktioniert?
            - Gemeinsame Erkenntnisse & aktueller Stand: Was wurde zusammen herausgefunden oder gelöst, und welche offenen Punkte oder nächsten Schritte stehen als Nächstes an?
            
            Regeln:
            - Zustand fortschreiben: Integriere neue Erkenntnisse in den bestehenden Stand. Wenn sich ein Status ändert (z. B. ein zuvor unbekannter Fakt wurde erklärt oder ein Schritt wurde inzwischen getestet), aktualisiere den Eintrag entsprechend.
            - Epistemische Genauigkeit: Trenne strikt zwischen dem, was die Person selbst schon wusste, und dem, was erst durch Pepper oder gemeinsame Prüfung eingebracht wurde.
            - Relevanz: Streiche inhaltsleeren Smalltalk und reine Höflichkeitsfloskeln, behalte aber alle personen-, wissens- und aufgabenrelevanten Aussagen sowie Bestätigungen und Fehlschläge bei.
            - Keine Halluzinationen: Erfinde NIEMALS Fakten, Diagnosen oder Vermutungen hinzu. Wenn keine neuen relevanten Informationen vorliegen, gib den bisherigen Stand unverändert zurück.
            - Format: Prägnante Stichpunkte in der dritten Person (auf Deutsch, außer es wurde eine andere Sprache verwendet), ohne Einleitung oder Schlusswort.
        """.trimIndent()

        val apiEndpoint = ProxySettingsRepository.customLlmEndpoint
        val apiModel = ProxySettingsRepository.customLlmModel
        val apiKey = ProxySettingsRepository.customLlmKey
        if (apiEndpoint.isBlank() || apiModel.isBlank()) return@withContext null

        val reqBody = JSONObject().apply {
            put("model", apiModel)
            put("temperature", 0.2)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", summaryPrompt)
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", transcript)
                })
            })
        }.toString().toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("$apiEndpoint/chat/completions")
            .authBearer(apiKey)
            .post(reqBody)
            .build()

        return@withContext try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(ProxySettingsRepository.TAG, "Summary LLM HTTP ${response.code}")
                    return@use null
                }
                val rawResponse = response.body?.string() ?: "{}"
                val updatedSummary = JSONObject(rawResponse)
                    .optJSONArray("choices")?.optJSONObject(0)
                    ?.optJSONObject("message")?.optString("content")
                    ?.trim()

                if (!updatedSummary.isNullOrBlank()) {
                    DataBaseClient.updateSummary(userId, updatedSummary)
                    DashboardManager.syncHistoryToBrowser(userId)
                }
                updatedSummary
            }
        } catch (e: Exception) {
            Log.e(ProxySettingsRepository.TAG, "Summary LLM Network Error", e)
            null
        }
    }

    fun toolDetected(llmMessageObj: JSONObject): Boolean {
        val toolCalls = llmMessageObj.optJSONArray("tool_calls")
        return (toolCalls != null && toolCalls.length() > 0)
                || llmMessageObj.optString("content", "").contains("[TOOL_CALLS]")
    }

    fun parseModelFallback(llmResponseText: String): Pair<String, ParsedToolCall>? {
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
            return cleanText to ParsedToolCall(
                id = "call_${System.currentTimeMillis()}",
                name = name,
                arguments = args
            )
        }
        return null
    }

    fun extractToolCalls(llmMessageObj: JSONObject, llmText: String): Pair<List<ParsedToolCall>, String> {
        val toolCallsArray = llmMessageObj.optJSONArray("tool_calls")
        if (toolCallsArray != null && toolCallsArray.length() > 0) {
            val parsed = (0 until toolCallsArray.length()).mapNotNull { i ->
                val callObj = toolCallsArray.optJSONObject(i) ?: return@mapNotNull null
                val fnObj = callObj.optJSONObject("function")
                val name = fnObj?.optString("name")?.takeIf { it.isNotBlank() }
                    ?: callObj.optString("name", "")
                if (name.isBlank()) return@mapNotNull null

                val args = fnObj?.optString("arguments") ?: callObj.optString("arguments", "{}")
                val callId = callObj.optString("id").takeIf { it.isNotBlank() }
                    ?: "call_${System.currentTimeMillis()}_$i"

                ParsedToolCall(id = callId, name = name, arguments = args)
            }
            return parsed to llmText
        }

        if (!ProxySettingsRepository.customLlmHasTools) {
            parseModelFallback(llmText)?.let { (cleanText, fallbackCall) ->
                return listOf(fallbackCall) to cleanText
            }
        }

        return emptyList<ParsedToolCall>() to llmText
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