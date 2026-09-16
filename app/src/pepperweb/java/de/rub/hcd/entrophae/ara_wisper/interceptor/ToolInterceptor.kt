package de.rub.hcd.entrophae.ara_wisper.interceptor

import android.util.Log
import ch.fhnw.pepper_realtime.tools.ToolContext
import de.rub.hcd.entrophae.ara_wisper.manager.AraWisperToolRegistry
import de.rub.hcd.entrophae.ara_wisper.manager.ProxySettingsRepository
import org.json.JSONArray
import org.json.JSONObject

class ToolInterceptor {
    private val localRegistry =
        AraWisperToolRegistry()

    fun injectTools(existingTools: JSONArray) {
        val definitions = localRegistry.getAllDefinitions()

        definitions.forEach { definition ->
            existingTools.put(definition)
            val name = definition.optString("name", "unknown")
            Log.i(ProxySettingsRepository.TAG, "Interceptor smuggled in: $name")
        }
    }

    fun attemptExecution(toolName: String, arguments: JSONObject, context: ToolContext?): String? {
        return localRegistry.executeTool(toolName, arguments, context)
    }
}