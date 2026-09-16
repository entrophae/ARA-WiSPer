package de.rub.hcd.entrophae.ara_wisper.manager

import android.util.Log
import ch.fhnw.pepper_realtime.tools.Tool
import ch.fhnw.pepper_realtime.tools.ToolContext
import de.rub.hcd.entrophae.ara_wisper.tools.KnowledgeDatabaseTool
import org.json.JSONObject

class AraWisperToolRegistry {

    fun interface ToolFactory {
        fun createTool(): Tool
    }

    private val toolFactories = mutableMapOf<String, ToolFactory>()
    private val toolCache = mutableMapOf<String, Tool>()

    init {
        registerAllCustomTools()
    }

    private fun registerAllCustomTools() {
        // Add all your flavor-specific tools here!
        registerTool("search_knowledge_db") { KnowledgeDatabaseTool() }

        Log.d(ProxySettingsRepository.TAG, "AraWisperToolRegistry initialized with ${toolFactories.size} tools.")
    }

    private fun registerTool(name: String, factory: ToolFactory) {
        toolFactories[name] = factory
    }

    private fun getOrCreateTool(name: String): Tool? {
        toolCache[name]?.let { return it }

        val factory = toolFactories[name] ?: return null

        val tool = factory.createTool()
        toolCache[name] = tool
        return tool
    }

    fun getAllDefinitions(): List<JSONObject> {
        return toolFactories.keys.mapNotNull { toolName ->
            getOrCreateTool(toolName)?.getDefinition()
        }
    }

    fun executeTool(toolName: String, args: JSONObject, context: ToolContext?): String? {
        val tool = getOrCreateTool(toolName) ?: return null

        return try {
            if (context == null) {
                Log.w(_root_ide_package_.de.rub.hcd.entrophae.ara_wisper.manager.ProxySettingsRepository.TAG, "Executing $toolName without a ToolContext!")
            }
            tool.execute(args, context!!)
        } catch (e: Exception) {
            Log.e(_root_ide_package_.de.rub.hcd.entrophae.ara_wisper.manager.ProxySettingsRepository.TAG, "Error executing local tool: $toolName", e)
            JSONObject().put("error", e.message).toString()
        }
    }

}