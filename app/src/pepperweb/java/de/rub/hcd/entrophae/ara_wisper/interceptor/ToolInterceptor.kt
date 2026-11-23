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
        Log.i(ProxySettingsRepository.TAG, "Local tool $toolName executing with content: $currentContext")
        return localRegistry.executeTool(toolName, arguments, toolContext!!)
    }

    suspend fun processToolCall(toolArgs: String?, toolCallId: String, currentContext: String, currentTurn: ChatTurn?, pipelineContext: PipelineContext ): Pair<String, Boolean> {

        val calledTool = currentTurn?.toolsUsed?.find { tool -> tool.callId == toolCallId }
        val toolName = calledTool?.name ?: "unknown"

        val arguments = JSONObject(toolArgs ?: "{}")

        val localResult = attemptExecution(toolName, arguments, AraWisperManager.chatActivityAdapter?.toolContext, currentContext)

        return if (localResult != null) {
            // Tool executed locally, update context and continue loop
            currentTurn?.addToolAnswer(toolCallId, localResult)

            val updatedContext  = formatLocalResultPrompt(currentContext, toolName, localResult)
            Pair(updatedContext, true)
        } else {
            // Tool not executed locally, send tool call packet and deactivate loop
            val outgoingArgs = if (toolName == "analyze_vision" && !currentTurn?.userImageUrl.isNullOrBlank()) {
                val userQuestion = currentTurn.userQuery
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

            pipelineContext.sendToolCallPacket(toolCallId, toolName, outgoingArgs)
            Pair(currentContext, false)
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

    private fun formatLocalResultPrompt(currentContext: String, toolName: String, result: String): String {
        return """
            ## Ursprüngliche Anfrage:
            $currentContext
            
            ## System-Info (Tool-Ergebnis für '$toolName'):
            $result 
            
            ## WICHTIGE ANWEISUNG:
            Bitte antworte basierend auf diesem Ergebnis.
        """.trimIndent()
    }

    fun formatToolOutputPrompt(lastKnownUserQuery: String, queryWaitingForTool: String, userImageUrl: String?, toolAnswer: String): String {
        val combinedQuery = if (lastKnownUserQuery != queryWaitingForTool && queryWaitingForTool.isNotBlank()) {
            // The user asked something new while the tool was running! Combine them.
            "$queryWaitingForTool\n\n[Zusätzliche Nutzer-Frage während des Ladens]: $lastKnownUserQuery"
        } else {
            lastKnownUserQuery
        }

        val isVisionResult = !userImageUrl.isNullOrBlank()
        val instructions = if (isVisionResult) {
            "Ein Foto wurde aufgenommen und dieser Nachricht als Bild angehängt. Schau dir das angehängte Bild genau an und beantworte die ursprüngliche Anfrage basierend auf dem, was du auf dem Bild siehst."
        } else {
            "Beantworte die ursprüngliche Anfrage basierend auf der System-Info."
        }


        return """
            ## Ursprüngliche Anfrage:
            $combinedQuery
            
            ## System-Info (Tool-Ergebnis):
            $toolAnswer 
            
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