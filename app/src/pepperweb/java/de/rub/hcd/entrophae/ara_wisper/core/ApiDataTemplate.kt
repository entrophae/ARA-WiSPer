package de.rub.hcd.entrophae.ara_wisper.core

import ch.fhnw.pepper_realtime.R
import org.json.JSONArray
import org.json.JSONObject
import de.rub.hcd.entrophae.ara_wisper.manager.AraWisperManager.appString
import de.rub.hcd.entrophae.ara_wisper.manager.ProxySettingsRepository
import androidx.annotation.Keep

object ApiDataTemplate {

    @Keep
    data class SettingKey(
        val id: String,
        val labelRes: Int = 0,
        val type: String = "",
        val placeholderRes: Int = 0,
        val default: String = "",
        val options: List<String>? = null
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
                    if (setting.labelRes != 0) put("label", appString(setting.labelRes))
                    put("type", setting.type)
                    if (setting.placeholderRes != 0) put("placeholder", appString(setting.placeholderRes))
                    if (setting.options != null) {
                        put("options", JSONArray(setting.options))
                    }
                })
            }
            return array
        }
    }

    object Providers : SettingGroup() {
        val OPENAI_KEY = SettingKey(ProxySettingsRepository.Settings.OPENAI_KEY, R.string.label_openai_key, "password", R.string.placeholder_openai_key)
        val AZURE_ENDPOINT = SettingKey(ProxySettingsRepository.Settings.AZURE_ENDPOINT, R.string.label_azure_endpoint, "url", R.string.placeholder_azure_endpoint)
        val AZURE_KEY = SettingKey(ProxySettingsRepository.Settings.AZURE_KEY, R.string.label_azure_key, "password")
        val XAI_KEY = SettingKey(ProxySettingsRepository.Settings.XAI_KEY, R.string.label_grok_key, "password", R.string.placeholder_grok_key)
        val GOOGLE_KEY = SettingKey(ProxySettingsRepository.Settings.GOOGLE_KEY, R.string.label_gemini_key, "password", R.string.placeholder_gemini_key)
    }

    object Azure : SettingGroup() {
        val AZURE_SPEECH_KEY = SettingKey(ProxySettingsRepository.Settings.AZURE_SPEECH_KEY, R.string.label_azure_speech_key, "password")
        val AZURE_SPEECH_REGION = SettingKey(ProxySettingsRepository.Settings.AZURE_SPEECH_REGION, R.string.label_azure_speech_region, "text", default = "switzerlandnorth")
    }

    object Proxy : SettingGroup() {
        val LLM = SettingKey(ProxySettingsRepository.Settings.LLM_ENDPOINT, R.string.label_llm_endpoint, "url", R.string.placeholder_custom_endpoint)
        val LLM_KEY = SettingKey(ProxySettingsRepository.Settings.LLM_KEY, R.string.label_llm_key, "password")
        val LLM_MODEL = SettingKey(ProxySettingsRepository.Settings.LLM_MODEL, R.string.label_llm_model, "model_select", R.string.placeholder_custom_model)
        val VLM = SettingKey(ProxySettingsRepository.Settings.VLM_ENDPOINT, R.string.label_vlm_endpoint, "url", R.string.placeholder_custom_endpoint)
        val VLM_KEY = SettingKey(ProxySettingsRepository.Settings.VLM_KEY, R.string.label_vlm_key, "password")
        val VLM_MODEL = SettingKey(ProxySettingsRepository.Settings.VLM_MODEL, R.string.label_vlm_model, "model_select", R.string.placeholder_custom_model)
        val EMBEDDING = SettingKey(ProxySettingsRepository.Settings.EMBEDDING_ENDPOINT, R.string.label_embedding_endpoint, "url", R.string.placeholder_custom_endpoint)
        val EMBEDDING_KEY = SettingKey(ProxySettingsRepository.Settings.EMBEDDING_KEY, R.string.label_embedding_key, "password")
        val EMBEDDING_MODEL = SettingKey(ProxySettingsRepository.Settings.EMBEDDING_MODEL, R.string.label_embedding_model, "model_select", R.string.placeholder_custom_model)
        val STT = SettingKey(ProxySettingsRepository.Settings.STT_ENDPOINT, R.string.label_stt_endpoint, "url", R.string.placeholder_custom_endpoint)
        val STT_KEY = SettingKey(ProxySettingsRepository.Settings.STT_KEY, R.string.label_stt_key, "password")
        val VAD = SettingKey(ProxySettingsRepository.Settings.CUSTOM_VAD_MODEL, R.string.label_custom_vad, "radio", options = ProxySettingsRepository.Proxy.VAD.OPTIONS, default = ProxySettingsRepository.Proxy.VAD.YAMNET)
    }

    object Database : SettingGroup() {
        val PERSON_ENDPOINT = SettingKey(ProxySettingsRepository.Settings.PERSON_DB_API_ENDPOINT, R.string.label_person_db_api, "url", R.string.placeholder_custom_endpoint)
        val PERSON_KEY = SettingKey(ProxySettingsRepository.Settings.PERSON_DB_KEY, R.string.label_person_db_key, "password")
    }

    object Tools : SettingGroup() {
        val VISION_ANALYSIS = SettingKey(ProxySettingsRepository.Settings.GROQ_KEY, R.string.label_groq_key, "password")
        val WEB_SEARCH_KEY = SettingKey(ProxySettingsRepository.Settings.TAVILY_KEY, R.string.label_tavily_key, "password", R.string.placeholder_tavily_key)
        val OPENWEATHER_KEY = SettingKey(ProxySettingsRepository.Settings.OPENWEATHER_KEY, R.string.label_openweather_key, "password")
        val YOUTUBE_KEY = SettingKey(ProxySettingsRepository.Settings.YOUTUBE_KEY, R.string.label_youtube_key, "password")
    }

    object System : SettingGroup() {
        val PROMPT_OVERRIDE = SettingKey(
            ProxySettingsRepository.Settings.SYSTEM_PROMPT,
            R.string.label_system_prompt,
            "modal_textarea",
            default = ProxySettingsRepository.defaultSystemPrompt
        )
        val ROUTING_MODE = SettingKey(ProxySettingsRepository.Settings.SYSTEM_ROUTING_MODE, R.string.label_routing_mode, "radio", options = ProxySettingsRepository.Modes.ROUTING.OPTIONS, default = ProxySettingsRepository.Modes.ROUTING.CUSTOM)
        val AUDIO_MODE = SettingKey(ProxySettingsRepository.Settings.SYSTEM_AUDIO_MODE, R.string.label_audio_mode, "radio", options = ProxySettingsRepository.Modes.AUDIO.OPTIONS, default = ProxySettingsRepository.Modes.AUDIO.CUSTOM)
    }

    val allSettings: List<SettingKey> by lazy {
        Providers.allKeys() + Proxy.allKeys() + Database.allKeys() + Tools.allKeys() + System.allKeys()
    }

}