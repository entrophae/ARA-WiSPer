package de.rub.hcd.entrophae.ara_wisper.tools

import ch.fhnw.pepper_realtime.tools.Tool
import ch.fhnw.pepper_realtime.tools.ToolContext
import org.json.JSONObject
import android.util.Log
import ch.fhnw.pepper_realtime.manager.SettingsRepository
import ch.fhnw.pepper_realtime.tools.information.SearchInternetTool
import de.rub.hcd.entrophae.ara_wisper.manager.ProxySettingsRepository
import de.rub.hcd.entrophae.ara_wisper.server.network.DataBaseClient
import de.rub.hcd.entrophae.ara_wisper.server.network.RagClient
import org.json.JSONArray

class KnowledgeDatabaseTool : Tool  {
    override fun getName(): String = "search_primary_knowledge_database"

    override fun getDefinition(): JSONObject {
        // toggle for internet search fallback
        val isInternetEnabled = ProxySettingsRepository.enabledFrameworkTools.contains(SearchInternetTool().getName())
        val baseDescription = "Searches the private factual knowledge database for all facts, information, biographical data, project details, and custom files. This is your primary source of truth if you don't know the answer already. CRITICAL FIRST STEP: You MUST ALWAYS use this tool first for ANY question, fact, name, or topic before using any other tool or external search. Do not attempt to answer from memory. Never extrapolate or improvise unmentioned details beyond what this search returns. Always respond to the user before you perform the function. Always first tell the user to wait a moment and then immediately perform the function. ALWAYS use this tool first when the user asks for information. CRITICAL: You must resolve all conversational context into a standalone query, and then provide 1 to 3 highly specific search keywords. Never pass ambiguous words like 'it' or 'how'. Example: If the user says 'how?' after discussing 'getting energized when tired', your search keywords must be: 'get active tired'."
        val fallbackInstruction = if (isInternetEnabled) {
            "If this database search returns nothing or 'No content found' or or lacks the answer, you are fully PERMITTED and EXPECTED to immediately call the ${SearchInternetTool().getName()} tool as a secondary step to find the answer online."
        } else {
            "Rely solely on this database for answers."
        }
        Log.i(ProxySettingsRepository.TAG,fallbackInstruction)

        return JSONObject().apply {
            put("type", "function")
            put("name", getName())
            put("description",baseDescription + fallbackInstruction)

            put("parameters", JSONObject().apply {
                put("type", "object")

                put("properties", JSONObject().apply {
                    put("query", JSONObject().apply {
                        put("type", "string")
                        put(
                            "description",
                            "1 to 3 highly specific keywords resolved from the conversational context. Do not use full sentences or pronouns."
                        )
                    })
                })

                put("required", JSONArray().put("query"))
            })
        }
    }

    override fun execute( args: JSONObject, context: ToolContext ): String {
        val topic = args.optString("query")
        if (topic.isBlank()) {
            Log.d(ProxySettingsRepository.TAG, "TOOL Topic is empty")
            return JSONObject().put("error", "No search topic provided").toString()
        }

        if (!context.apiKeyManager.isInternetSearchAvailable()) {
            Log.d(ProxySettingsRepository.TAG, "TOOL No intenet search availbale")
            return JSONObject().put("error", context.apiKeyManager.searchSetupMessage).toString()
        }

        val userId = ProxySettingsRepository.activeUserId

        return try {
            val results = kotlinx.coroutines.runBlocking {
                val experimentId = DataBaseClient.getExperimentIdForUser(userId)
                if (experimentId.isNotEmpty()) {
                    RagClient.search(topic, userId, experimentId)
                } else emptyList()
            }

            if (results.isNotEmpty()) {
                val groundedResults = "STRICT GROUNDING: Answer using ONLY the retrieved facts below. Do NOT assume, extrapolate, or invent specific details (e.g., specific tools, items, or events) not explicitly named:\n\n" + results.joinToString("\n\n---\n\n")
                JSONObject().put("results", groundedResults).toString()
            } else {
                JSONObject().put("results", "No content found").toString()
            }
        } catch (e: Exception) {
            Log.e(ProxySettingsRepository.TAG, "RAG DB Network Error", e)
            JSONObject().put("error", "Failed to connect to Knowledge database: ${e.message}").toString()
        }
    }
}