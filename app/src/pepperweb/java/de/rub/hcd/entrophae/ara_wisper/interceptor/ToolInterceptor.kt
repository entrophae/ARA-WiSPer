package de.rub.hcd.entrophae.ara_wisper.interceptor

import android.util.Log
import ch.fhnw.pepper_realtime.tools.ToolContext
import de.rub.hcd.entrophae.ara_wisper.manager.AraWisperManager
import de.rub.hcd.entrophae.ara_wisper.manager.AraWisperToolRegistry
import de.rub.hcd.entrophae.ara_wisper.manager.ProxySettingsRepository
import de.rub.hcd.entrophae.ara_wisper.server.data.ChatTurn
import de.rub.hcd.entrophae.ara_wisper.server.data.PipelineContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Hybrid tool registry injector, execution router, and multistep prompt builder.
 *
 * Merges local `ara_wisper` tools (e.g., RAG knowledge search) with standard host tools,
 * executes local tools directly on the proxy while delegating hardware/UI tools through
 * [TurnBridge], and formats deduplicated tool/speech history for subsequent LLM rounds.
 */
class ToolInterceptor {
    private val localRegistry = AraWisperToolRegistry()

    fun injectTools(existingTools: JSONArray) {
        val enabledTools = ProxySettingsRepository.enabledTools

        localRegistry.filterTools(enabledTools)
        val filteredTools = JSONArray()

        for (i in 0 until existingTools.length()) {
            val toolObj = existingTools.optJSONObject(i) ?: continue
            filteredTools.put(toolObj)
        }

        val definitions = localRegistry.getAllDefinitions(enabledTools)
        definitions.forEach { definition ->
            filteredTools.put(definition)
            val name = definition.optString("name").takeIf { it.isNotBlank() }
                ?: definition.optJSONObject("function")?.optString("name") ?: "unknown"
            Log.i(ProxySettingsRepository.TAG, "Interceptor smuggled in: $name")
        }

        while (existingTools.length() > 0) {
            existingTools.remove(0)
        }
        for (i in 0 until filteredTools.length()) {
            existingTools.put(filteredTools.getJSONObject(i))
        }
    }

    private fun attemptExecution(toolName: String, arguments: JSONObject, toolContext: ToolContext?, currentContext: String): String? {
        if (ProxySettingsRepository.isStandardTool(toolName)) {
            Log.i(ProxySettingsRepository.TAG, "Delegating standard tool to core system: $toolName with content:\n$currentContext\nand arguments:\n${arguments.toString(2)}")
            return null
        }
        if (toolContext == null) {
            Log.w(ProxySettingsRepository.TAG, "Local tool $toolName skipped: toolContext is null")
            return "Error: Tool context is currently unavailable."
        }
        Log.i(ProxySettingsRepository.TAG, "Local tool $toolName executing with content: $currentContext")
        return localRegistry.executeTool(toolName, arguments, toolContext)
    }

    suspend fun processToolCall( toolArgs: String?, toolCallId: String,  pipelineContext: PipelineContext ): Boolean {
        val turn = pipelineContext.turn
        val calledTool = turn.toolsUsed.find { it.callId == toolCallId }
        val toolName = calledTool?.name ?: "unknown"

        val arguments = JSONObject(toolArgs ?: "{}")

        val localResult = attemptExecution(toolName, arguments, AraWisperManager.chatActivityAdapter?.toolContext, pipelineContext.currentPrompt)

        return if (localResult != null) {
            turn.addToolAnswer(toolCallId, localResult)
            turn.completedToolResults.add(toolName to localResult)

            pipelineContext.currentPrompt = formatLocalResultPrompt(
                turn = turn,
                fallbackQuery = pipelineContext.currentPrompt,
                toolName = toolName,
                result = localResult
            )
            true
        } else {
            val outgoingArgs = if (toolName == "analyze_vision" && !turn.userImageUrl.isNullOrBlank()) {
                val userQuestion = turn.userQuery
                val enhancedPrompt = """
                    The user asks: "$userQuestion". 
                    Look very closely at the image and answer this specific question in extreme detail.
                """.trimIndent()
                arguments.put("prompt", enhancedPrompt)
                Log.i(ProxySettingsRepository.TAG, "Injected vision prompt: $enhancedPrompt")
                arguments.toString()
            } else {
                toolArgs
            }

            pipelineContext.bridge.onDelegateToolCall(toolCallId, toolName, outgoingArgs)
            false
        }
    }

    fun extractQueryOrInput(toolName: String, args: JSONObject): String {
        if (args.has("queries")) {
            val queries = args.optJSONArray("queries")
            if (queries != null && queries.length() > 0) {
                return queries.optString(0, "")
            }
            return args.optString("queries", "")
        }
        if (args.has("query")) {
            return args.optString("query", "")
        }

        return when (toolName) {
            "navigate_to_location" -> args.optString("location_name", "")
            "save_current_location" -> args.optString("location_name", "")
            "analyze_vision" -> args.optString("prompt", "Analyze surroundings")
            "play_animation" -> args.optString("name", "")
            "move_pepper" -> "Forward: ${args.optDouble("distance_forward", 0.0)}m, Sideways: ${args.optDouble("distance_sideways", 0.0)}m"
            "turn_pepper" -> "${args.optString("direction", "")} ${args.optDouble("degrees", 0.0)}°"
            "look_at_position" -> "x:${args.optDouble("x", 0.0)}, y:${args.optDouble("y", 0.0)}, z:${args.optDouble("z", 0.0)}"
            "approach_human" -> "Human ID: ${args.optInt("human_id", -1)}"
            "follow_human" -> "Distance: ${args.optDouble("distance", 1.0)}m"
            else -> args.toString()
        }
    }

    private fun buildToolHistorySection( allToolResults: List<Pair<String, String>>,  fallbackToolName: String,  fallbackResult: String ): String {
        if (allToolResults.isEmpty()) {
            return "## System-Info (Bereits ausgeführtes Tool '$fallbackToolName'):\n$fallbackResult"
        }
        return buildString {
            appendLine("## Bisherige Tool-Ergebnisse in diesem Zug (ALLE BEREITS AUSGEFÜHRT):")
            allToolResults.forEachIndexed { index, (name, output) ->
                appendLine("${index + 1}. [$name] (BEREITS ERLEDIGT): $output")
            }
        }.trimEnd()
    }

    private fun buildSpokenHistorySection(spokenTextsInTurn: List<String>): String {
        if (spokenTextsInTurn.isEmpty()) return ""
        return "\n\n## Bereits laut gesagter Text in diesem Zug (NICHT wiederholen):\n" +
                spokenTextsInTurn.joinToString("\n")
    }

    private fun formatLocalResultPrompt( turn: ChatTurn,  fallbackQuery: String,  toolName: String,  result: String ): String {
        val originalQuery = turn.userQuery.takeIf { it.isNotBlank() } ?: fallbackQuery
        val toolSection = buildToolHistorySection(turn.completedToolResults.toList(), toolName, result)
        val spokenSection = buildSpokenHistorySection(turn.toolSynchronizer.getSpokenTexts())

        return """
            ## Ursprüngliche Anfrage:
            $originalQuery
            
            $toolSection$spokenSection
            
            ## WICHTIGE ANWEISUNG:
            - Wiederhole KEINE bereits beantworteten Recherchen, Tool-Aufrufe oder bereits gesagten Sätze.
            - Wenn noch eine andere Teilaufgabe ein bisher ungenutztes Tool erfordert, rufe es jetzt direkt auf.
            - Wenn alle benötigten Informationen vorliegen (oder für einen Teil kein eigenes Tool existiert), beantworte nur noch die offenen Teile der ursprünglichen Anfrage vollständig, direkt und natürlich auf der Sprache der Anfrage.
        """.trimIndent()
    }

    fun formatToolOutputPrompt( turn: ChatTurn?,  toolAnswer: String,  completedToolName: String = "tool" ): String {
        val lastKnownUserQuery = turn?.userQuery.orEmpty()
        val queryWaitingForTool = turn?.queryWaitingForTool.orEmpty()
        val userImageUrl = turn?.userImageUrl
        val previousToolResults = turn?.completedToolResults?.toList().orEmpty()
        val spokenTextsInTurn = turn?.toolSynchronizer?.getSpokenTexts().orEmpty()

        val combinedQuery = if (lastKnownUserQuery != queryWaitingForTool && queryWaitingForTool.isNotBlank()) {
            // The user asked something new while the tool was running! Combine them.
            "$queryWaitingForTool\n\n[Zusätzliche Nutzer-Frage während des Ladens]: $lastKnownUserQuery"
        } else {
            lastKnownUserQuery
        }

        val isVisionResult = completedToolName == "analyze_vision" && !userImageUrl.isNullOrBlank()
        val toolSection = buildToolHistorySection(previousToolResults, completedToolName, toolAnswer)
        val spokenSection = buildSpokenHistorySection(spokenTextsInTurn)

        val instructions = if (isVisionResult) {
            """
                Ein Foto wurde aufgenommen und dieser Nachricht als Bild angehängt. Schau dir das angehängte Bild genau an.
                - Wenn die ursprüngliche Anfrage nur das Bild betrifft, beantworte sie jetzt direkt auf Basis des Bildes (ohne einleitende Floskeln wie „Lass mich das Bild analysieren“).
                - Wenn die ursprüngliche Anfrage zusätzlich eine weitere Aktion erfordert (z. B. eine Internetsuche), beschreibe zuerst kurz was du auf dem Bild siehst und rufe **im selben Schritt** direkt das nächste benötigte Tool über einen Tool-Call auf. Kündige weitere Tool-Aufrufe niemals nur im Text an.
            """.trimIndent()
        } else {
            """
                - Nutze die obigen Tool-Ergebnisse (und falls vorhanden das angehängte Bild). Wiederhole KEINE bereits erledigte Suche.
                - Wenn noch eine weitere Teilaufgabe aus der ursprünglichen Anfrage ein anderes Tool erfordert (z. B. play_melody), beantworte zuerst die bereits vorliegenden Such-/Bild-Ergebnisse im Text (ohne bereits laut gesagten Text zu wiederholen) und rufe das nächste Tool **im selben Schritt** über einen Tool-Call auf.
                - WICHTIG zu „Bereits laut gesagter Text in diesem Zug“: Wiederhole diese Fakten (z. B. die Anzahl der Finger) NICHT noch einmal! Gib nur noch die fehlenden Informationen aus.
                - WICHTIG zu Aktions-Tools (wie play_melody oder play_animation): Wenn ein solches Tool oben als BEREITS ERLEDIGT steht, wurde die Melodie/Animation GERADE EBEN SCHON abgespielt. Sage auf keinen Fall „Ich spiele dir jetzt eine Melodie“ oder Ähnliches im Präsens/Futur — nutze höchstens die Vergangenheitsform (z. B. „Ich hoffe, die Melodie hat dir gefallen!“).
            """.trimIndent()
        }


        return """
            ## Ursprüngliche Anfrage:
            $combinedQuery
            
            $toolSection$spokenSection
            
            ## WICHTIGE ANWEISUNG:
            $instructions
            Du MUSST deine Antwort auf der Sprache der ursprünglichen Anfrage formulieren (übersetze die System-Info falls nötig).
        """.trimIndent()
    }

    companion object {
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
    }
}