package de.rub.hcd.entrophae.ara_wisper.manager

import android.util.Log
import ch.fhnw.pepper_realtime.tools.Tool
import ch.fhnw.pepper_realtime.tools.ToolContext
import org.json.JSONObject

class AraWisperToolRegistry {

    fun interface ToolFactory {
        fun createTool(): Tool
    }

    // Factory for creating tool instances
    private val toolFactories = mutableMapOf<String, ToolFactory>()

    // Cache for tool instances to avoid repeated object allocation
    private val toolCache = mutableMapOf<String, Tool>()

    init {
        registerAllCustomTools()
    }

    private fun registerAllCustomTools() {
        // registerTool("custom_tool") { CustomTool() }

        Log.d(ProxySettingsRepository.TAG, "AraWisperToolRegistry initialized with ${toolFactories.size} tools.")
    }

    private fun registerTool(name: String, factory: ToolFactory) {
        toolFactories[name] = factory
    }

    private fun getOrCreateTool(name: String): Tool? {
        toolCache[name]?.let { return it }

        val factory = toolFactories[name]
        if (factory == null) {
            Log.w(ProxySettingsRepository.TAG, "Unknown tool requested: $name")
            return null
        }

        val tool = factory.createTool()
        toolCache[name] = tool
        return tool
    }

    /**
     * Clear the tool cache.
     * Useful when tool configurations might have changed.
     */
    fun clearCache() {
        toolCache.clear()
        Log.d(ProxySettingsRepository.TAG, "Tool cache cleared")
    }

    fun getAllToolNames(): Set<String> = toolFactories.keys

    private val activeEnabledTools = mutableSetOf<String>()

    fun filterTools(enabledTools: Set<String>) {
        activeEnabledTools.clear()
        activeEnabledTools.addAll(enabledTools)
        Log.d(ProxySettingsRepository.TAG, "AraWisperToolRegistry filters updated. Active tools: $activeEnabledTools")
    }

    fun getAllDefinitions(enabledTools: Set<String>? = null): List<JSONObject> {
        val targetTools = enabledTools ?: activeEnabledTools
        return toolFactories.keys
            .filter { it in targetTools }
            .mapNotNull { toolName ->
                getOrCreateTool(toolName)?.getDefinition()
            }
    }

    fun executeTool(toolName: String, args: JSONObject, toolContext: ToolContext): String? {
        val tool = getOrCreateTool(toolName) ?: return try {
            JSONObject().put("error", "Unknown tool: $toolName").toString()
        } catch (e: Exception) {
            "{\"error\":\"Unknown tool and failed to create error response\"}"
        }

        return try {
            tool.execute(args, toolContext)
        } catch (e: Exception) {
            Log.e(ProxySettingsRepository.TAG, "Local Tool execution error: $toolName", e)
            try {
                JSONObject().put("error", e.message).toString()
            } catch (ignored: Exception) {
                "{\"error\":\"execution failed\"}"
            }
        }
    }

}