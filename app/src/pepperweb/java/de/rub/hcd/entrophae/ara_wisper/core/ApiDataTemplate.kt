package de.rub.hcd.entrophae.ara_wisper.core

import android.content.Context
import androidx.annotation.Keep
import ch.fhnw.pepper_realtime.R
import ch.fhnw.pepper_realtime.manager.ApiKeyManager
import ch.fhnw.pepper_realtime.manager.SettingsRepository
import ch.fhnw.pepper_realtime.network.RealtimeApiProvider
import ch.fhnw.pepper_realtime.tools.ToolRegistry
import com.google.gson.Gson
import de.rub.hcd.entrophae.ara_wisper.manager.AraWisperManager.appString
import de.rub.hcd.entrophae.ara_wisper.manager.AraWisperToolRegistry
import de.rub.hcd.entrophae.ara_wisper.manager.ProxySettingsRepository
import org.json.JSONArray
import org.json.JSONObject

/**
 * Declarative configuration schema definitions for the web dashboard settings UI.
 *
 * Builds structured JSON schemas for LLM/VLM/Embedding models, database/speech/tool
 * endpoints, provider presets, Azure settings, and dynamic host app preferences.
 */
object ApiDataTemplate {

    @Keep
    data class SettingKey(
        val id: String,
        val label: String = "",
        val labelRes: Int = 0,
        val type: String = "",
        val placeholderRes: Int = 0,
        val default: String = "",
        val options: List<String>? = null,
        val min: Float = 0f,
        val max: Float = 100f,
        val step: Float = 1f,
        val unit: String = "",
        val description: String = ""
    )

    @Keep
    open class SettingGroup {
        fun allKeys(): List<SettingKey> {
            return this.javaClass.declaredFields.mapNotNull { field ->
                field.isAccessible = true
                field.get(this) as? SettingKey
            }
        }

        fun toUiSchema(): JSONArray {
            val array = JSONArray()
            allKeys().forEach { setting ->
                array.put(JSONObject().apply {
                    put("id", setting.id)
                    put("label", if (setting.label.isNotBlank()) setting.label else if (setting.labelRes != 0) appString(setting.labelRes) else setting.id)
                    put("type", setting.type)
                    if (setting.placeholderRes != 0) put("placeholder", appString(setting.placeholderRes))
                    if (setting.options != null) put("options", JSONArray(setting.options))
                    put("min", setting.min)
                    put("max", setting.max)
                    put("step", setting.step)
                    put("unit", setting.unit)
                    if (setting.description.isNotBlank()) put("description", setting.description)
                })
            }
            return array
        }
    }

    object Providers : SettingGroup() {
        val OPENAI_KEY = SettingKey(ProxySettingsRepository.Settings.OPENAI_KEY, labelRes = R.string.label_openai_key, type = "password", placeholderRes = R.string.placeholder_openai_key)
        val AZURE_ENDPOINT = SettingKey(ProxySettingsRepository.Settings.AZURE_ENDPOINT, labelRes = R.string.label_azure_endpoint, type = "url", placeholderRes = R.string.placeholder_azure_endpoint)
        val AZURE_KEY = SettingKey(ProxySettingsRepository.Settings.AZURE_KEY, labelRes = R.string.label_azure_key, type = "password")
        val XAI_KEY = SettingKey(ProxySettingsRepository.Settings.XAI_KEY, labelRes = R.string.label_grok_key, type = "password", placeholderRes = R.string.placeholder_grok_key)
        val GOOGLE_KEY = SettingKey(ProxySettingsRepository.Settings.GOOGLE_KEY, labelRes = R.string.label_gemini_key, type = "password", placeholderRes = R.string.placeholder_gemini_key)
    }

    object Azure : SettingGroup() {
        val AZURE_SPEECH_KEY = SettingKey(ProxySettingsRepository.Settings.AZURE_SPEECH_KEY, labelRes = R.string.label_azure_speech_key, type = "password")
        val AZURE_SPEECH_REGION = SettingKey(ProxySettingsRepository.Settings.AZURE_SPEECH_REGION, labelRes = R.string.label_azure_speech_region, type = "text", default = "switzerlandnorth")
    }

    object Models : SettingGroup() {
        val LLM = SettingKey(ProxySettingsRepository.Settings.LLM_ENDPOINT, labelRes = R.string.label_llm_endpoint, type = "url", placeholderRes = R.string.placeholder_custom_endpoint)
        val LLM_KEY = SettingKey(ProxySettingsRepository.Settings.LLM_KEY, labelRes = R.string.label_llm_key, type = "password")
        val LLM_CONFIG = SettingKey(ProxySettingsRepository.Settings.LLM_CONFIG, labelRes = R.string.label_llm_model, type = "model_select", placeholderRes = R.string.placeholder_custom_model)
        val VLM = SettingKey(ProxySettingsRepository.Settings.VLM_ENDPOINT, labelRes = R.string.label_vlm_endpoint, type = "url", placeholderRes = R.string.placeholder_custom_endpoint)
        val VLM_KEY = SettingKey(ProxySettingsRepository.Settings.VLM_KEY, labelRes = R.string.label_vlm_key, type = "password")
        val VLM_CONFIG = SettingKey(ProxySettingsRepository.Settings.VLM_CONFIG, labelRes = R.string.label_vlm_model, type = "model_select", placeholderRes = R.string.placeholder_custom_model)
        val EMBEDDING = SettingKey(ProxySettingsRepository.Settings.EMBEDDING_ENDPOINT, labelRes = R.string.label_embedding_endpoint, type = "url", placeholderRes = R.string.placeholder_custom_endpoint)
        val EMBEDDING_KEY = SettingKey(ProxySettingsRepository.Settings.EMBEDDING_KEY, labelRes = R.string.label_embedding_key, type = "password")
        val EMBEDDING_CONFIG = SettingKey(ProxySettingsRepository.Settings.EMBEDDING_CONFIG, labelRes = R.string.label_embedding_model, type = "model_select", placeholderRes = R.string.placeholder_custom_model)
    }

    object Speech : SettingGroup() {
        val STT = SettingKey(ProxySettingsRepository.Settings.STT_ENDPOINT, labelRes = R.string.label_stt_endpoint, type = "url", placeholderRes = R.string.placeholder_custom_endpoint)
        val STT_KEY = SettingKey(ProxySettingsRepository.Settings.STT_KEY, labelRes = R.string.label_stt_key, type = "password")
        val VAD = SettingKey(ProxySettingsRepository.Settings.CUSTOM_VAD_MODEL, labelRes = R.string.label_custom_vad, type = "options", options = ProxySettingsRepository.Proxy.VAD.OPTIONS, default = ProxySettingsRepository.Proxy.VAD.YAMNET)
    }

    object Database : SettingGroup() {
        val PERSON_ENDPOINT = SettingKey(ProxySettingsRepository.Settings.PERSON_DB_API_ENDPOINT, labelRes = R.string.label_person_db_api, type = "url", placeholderRes = R.string.placeholder_custom_endpoint)
        val PERSON_KEY = SettingKey(ProxySettingsRepository.Settings.PERSON_DB_KEY, labelRes = R.string.label_person_db_key, type = "password")
        val FACT_ENDPOINT = SettingKey(ProxySettingsRepository.Settings.FACT_DB_API_ENDPOINT, labelRes = R.string.label_fact_db_api, type = "url", placeholderRes = R.string.placeholder_custom_endpoint)
        val FACT_KEY = SettingKey(ProxySettingsRepository.Settings.FACT_DB_KEY, labelRes = R.string.label_fact_db_key, type = "password")
    }

    object Tools : SettingGroup() {
        val VISION_ANALYSIS = SettingKey(ProxySettingsRepository.Settings.GROQ_KEY, labelRes = R.string.label_groq_key, type = "password")
        val WEB_SEARCH_KEY = SettingKey(ProxySettingsRepository.Settings.TAVILY_KEY, labelRes = R.string.label_tavily_key, type = "password", placeholderRes = R.string.placeholder_tavily_key)
        val OPENWEATHER_KEY = SettingKey(ProxySettingsRepository.Settings.OPENWEATHER_KEY, labelRes = R.string.label_openweather_key, type = "password")
        val YOUTUBE_KEY = SettingKey(ProxySettingsRepository.Settings.YOUTUBE_KEY, labelRes = R.string.label_youtube_key, type = "password")
    }

    object System : SettingGroup() {
        val ROUTING_MODE = SettingKey(ProxySettingsRepository.Settings.SYSTEM_ROUTING_MODE, labelRes = R.string.label_routing_mode, type = "options", options = ProxySettingsRepository.Modes.ROUTING.OPTIONS, default = ProxySettingsRepository.Modes.ROUTING.CUSTOM)
        val AUDIO_MODE = SettingKey(ProxySettingsRepository.Settings.SYSTEM_AUDIO_MODE, labelRes = R.string.label_audio_mode, type = "options", options = ProxySettingsRepository.Modes.AUDIO.OPTIONS, default = ProxySettingsRepository.Modes.AUDIO.CUSTOM)
    }

    val allSettings: List<SettingKey> by lazy {
        Providers.allKeys() + Azure.allKeys() + Models.allKeys() + Speech.allKeys() + Database.allKeys() + Tools.allKeys() + System.allKeys()
    }

    /**
     * Builds dynamic schema for the app controls present in SettingsScreen.kt
     */
    fun buildAppDynamicSchema(context: Context): JSONObject {
        val gson = Gson()
        val apiKeyManager = ApiKeyManager(context)
        val customToolRegistry = AraWisperToolRegistry()
        val nativeToolRegistry = ToolRegistry()

        val configuredProviders = apiKeyManager.getConfiguredProviders().toList()
        val providersToUse = configuredProviders.ifEmpty { AppSettingsDefaults.providers }

        val activeProviders = providersToUse.map { provider ->
            JSONObject().apply {
                put("value", provider.name)
                put("label", provider.getDisplayName())
            }
        }

        val languages = SettingsRepository.getAvailableLanguages().map {
            JSONObject().apply {
                put("code", it.code)
                put("name", it.displayName)
            }
        }

        val toolsList = JSONArray()
        val allToolNames = nativeToolRegistry.getAllToolNames() + customToolRegistry.getAllToolNames()
        allToolNames.forEach { toolName ->
            val tool =
                if (ProxySettingsRepository.isStandardTool(toolName)) nativeToolRegistry.getOrCreateTool(toolName)
                else customToolRegistry.getOrCreateTool(toolName)
            val desc = tool?.getDefinition()?.optString("description", "No description") ?: "No description"
            val (reqKey, isAvail) = when (toolName) {
                "analyze_vision" -> "VLM/Groq/gpt-realtime" to ProxySettingsRepository.isVisionAnalysisAvailable(apiKeyManager)
                "search_internet" -> "Tavily" to apiKeyManager.isInternetSearchAvailable()
                "get_weather" -> "OpenWeatherMap" to apiKeyManager.isWeatherAvailable()
                "play_youtube_video" -> "YouTube" to apiKeyManager.isYouTubeAvailable()
                else -> null to true
            }
            toolsList.put(JSONObject().apply {
                put("name", toolName)
                put("description", desc)
                put("apiKeyRequired", reqKey ?: JSONObject.NULL)
                put("isApiKeyAvailable", isAvail)
            })
        }

        return JSONObject().apply {
            put("providers", JSONArray(activeProviders))
            put("modelsMap", JSONObject().apply {
                put(RealtimeApiProvider.OPENAI_DIRECT.name, JSONArray(AppSettingsDefaults.openAiModels))
                put(RealtimeApiProvider.AZURE_OPENAI.name, JSONArray(AppSettingsDefaults.openAiModels))
                put(RealtimeApiProvider.XAI.name, JSONArray(AppSettingsDefaults.xaiModels))
                put(RealtimeApiProvider.GOOGLE_GEMINI.name, JSONArray(AppSettingsDefaults.googleModels))
            })
            put("voicesMap", JSONObject().apply {
                put(RealtimeApiProvider.OPENAI_DIRECT.name, JSONObject(AppSettingsDefaults.openAiVoices as Map<*, *>))
                put(RealtimeApiProvider.AZURE_OPENAI.name, JSONObject(AppSettingsDefaults.openAiVoices as Map<*, *>))
                put(RealtimeApiProvider.XAI.name, JSONObject(AppSettingsDefaults.xaiVoices as Map<*, *>))
                put(RealtimeApiProvider.GOOGLE_GEMINI.name, JSONObject(AppSettingsDefaults.googleVoices as Map<*, *>))
            })
            put("languages", JSONArray(languages))

            put("audioInputModes", JSONArray(gson.toJson(AppSettingsDefaults.audioInputModes)))
            put("transcriptionModels", JSONArray(AppSettingsDefaults.transcriptionModels))
            put("eagernessLevels", JSONArray(gson.toJson(AppSettingsDefaults.eagernessLevels)))
            put("noiseReductionTypes", JSONArray(gson.toJson(AppSettingsDefaults.noiseReductionTypes)))
            put("turnDetectionTypes", JSONArray(gson.toJson(AppSettingsDefaults.turnDetectionTypes)))
            put("availableTools", toolsList)
        }
    }

}