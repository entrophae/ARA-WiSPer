package de.rub.hcd.entrophae.ara_wisper.manager

import android.util.Log
import ch.fhnw.pepper_realtime.manager.SettingsRepository
import ch.fhnw.pepper_realtime.tools.ToolRegistry
import de.rub.hcd.entrophae.ara_wisper.manager.AraWisperManager.appPrefs
import androidx.core.content.edit
import ch.fhnw.pepper_realtime.manager.ApiKeyManager

object ProxySettingsRepository {
    const val PREFS_NAME = "PepperDialogPrefs"
    const val TAG = "AraWisper Proxy"
    const val VISUAL_PROBE = "ARAW1SP3R"

    const val KEY_ENABLED_TOOLS = "KEY_ENABLED_TOOLS"
    const val KEY_KNOWN_TOOLS = "KEY_KNOWN_TOOLS"

    fun getPref(key: String, default: String = ""): String {
        return appPrefs.getString(key, default) ?: default
    }

    fun setPref(key: String, value: String) {
        appPrefs.edit { putString(key, value) }
    }

    val currentAudioMode: String
        get() = getPref(Settings.SYSTEM_AUDIO_MODE, Modes.AUDIO.CUSTOM)

    val currentRoutingMode: String
        get() = getPref(Settings.SYSTEM_ROUTING_MODE, Modes.ROUTING.CUSTOM)

    val currentVadMode: String
        get() = getPref(Settings.CUSTOM_VAD_MODEL, Proxy.VAD.WEBRTC)

    val isCustomProxyAudio: Boolean
        get() = currentAudioMode == Modes.AUDIO.CUSTOM

    val isGoogleAudio: Boolean
        get() = currentAudioMode == Modes.AUDIO.GOOGLE

    val isCustomProxyRouting: Boolean
        get() = currentRoutingMode == Modes.ROUTING.CUSTOM

    val customLlmEndpoint: String
        get() = getPref(Settings.LLM_ENDPOINT, "")

    val customLlmKey: String
        get() = getPref(Settings.LLM_KEY, "")

    val customLlmModel: String get() = getModelConfig(Settings.LLM_CONFIG).optString("id", "")
    val customLlmHasTools: Boolean get() = getModelConfig(Settings.LLM_CONFIG).optBoolean("hasTools", false)
    val customLlmHasVisuals: Boolean get() = getModelConfig(Settings.LLM_CONFIG).optBoolean("hasVision", false)
    val customLlmHasEmbedding: Boolean get() = getModelConfig(Settings.LLM_CONFIG).optBoolean("hasEmbedding", false)

    val customVlmEndpoint: String
        get() = getPref(Settings.VLM_ENDPOINT, "")

    val customVlmKey: String
        get() = getPref(Settings.VLM_KEY, "")

    val customVlmModel: String get() = getModelConfig(Settings.VLM_CONFIG).optString("id", "")
    val customVlmHasTools: Boolean get() = getModelConfig(Settings.VLM_CONFIG).optBoolean("hasTools", false)
    val customVlmHasVisuals: Boolean get() = getModelConfig(Settings.VLM_CONFIG).optBoolean("hasVision", false)
    val customVlmHasEmbedding: Boolean get() = getModelConfig(Settings.VLM_CONFIG).optBoolean("hasEmbedding", false)

    fun isVisionAnalysisAvailable(apiKeyManager: ApiKeyManager): Boolean = customVlmHasVisuals || getPref(Settings.GROQ_KEY).isNotEmpty() || apiKeyManager.isVisionAnalysisAvailable()

    val customEmbeddingEndpoint: String
        get() = getPref(Settings.EMBEDDING_ENDPOINT, "")

    val customEmbeddingKey: String
        get() = getPref(Settings.EMBEDDING_KEY, "")

    val customEmbeddingModel: String get() = getModelConfig(Settings.EMBEDDING_CONFIG).optString("id", "")
    val customEmbeddingHasTools: Boolean get() = getModelConfig(Settings.EMBEDDING_CONFIG).optBoolean("hasTools", false)
    val customEmbeddingHasVisuals: Boolean get() = getModelConfig(Settings.EMBEDDING_CONFIG).optBoolean("hasVision", false)
    val customEmbeddingHasEmbedding: Boolean get() = getModelConfig(Settings.EMBEDDING_CONFIG).optBoolean("hasEmbedding", false)

    val customSttEndpoint: String
        get() = getPref(Settings.STT_ENDPOINT, "")

    val customSttKey: String
        get() = getPref(Settings.STT_KEY, "")

    val databasePersonApiEndpoint: String
        get() = getPref(Settings.PERSON_DB_API_ENDPOINT, "").removeSuffix("/")

    val databasePersonKey: String
        get() = getPref(Settings.PERSON_DB_KEY, "")

    val databaseFactApiEndpoint: String
        get() = getPref(Settings.FACT_DB_API_ENDPOINT, "").removeSuffix("/")

    val databaseFactKey: String
        get() = getPref(Settings.FACT_DB_KEY, "")

    var customSystemPrompt: String
        get() = getPref(Settings.SYSTEM_PROMPT, "")
        set(value) = setPref(Settings.SYSTEM_PROMPT, value)

    val activeSystemPrompt: String
        get() {
            val current = customSystemPrompt
            return if (current.isBlank() || current.trim() == "You are a helpful assistant.") {
                defaultSystemPrompt
            } else {
                current
            }
        }

    val defaultSystemPrompt: String
        get() = try {
            AraWisperManager.appAssets.open("default_german_system_prompt.txt")
                .bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.e(TAG, "Not able to read default file", e)
            "You are a helpful assistant."
        }

    val fallbackToolInstruction: String
        get() = try {
            AraWisperManager.appAssets.open("fallback_tool_instruction.txt")
                .bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            ""
        }

    private val frameworkSettings = SettingsRepository(AraWisperManager.app, ToolRegistry())
    var enabledFrameworkTools = frameworkSettings.enabledTools

    var enabledTools: Set<String>
        get() {
            val savedTools = appPrefs.getStringSet(KEY_ENABLED_TOOLS, null)
            val knownTools = appPrefs.getStringSet(KEY_KNOWN_TOOLS, null)

            return if (savedTools == null) {
                appPrefs.edit { putStringSet(KEY_KNOWN_TOOLS, customTools) }
                customTools
            } else {
                val newTools = customTools.filter { it !in (knownTools ?: emptySet()) }
                if (newTools.isNotEmpty()) {
                    val mergedTools = HashSet(savedTools)
                    for (newTool in newTools) {
                        mergedTools.add(newTool)
                        Log.i("ProxySettingsRepository", "Auto-enabling new tool: $newTool")
                    }
                    appPrefs.edit { putStringSet(KEY_KNOWN_TOOLS, customTools) }
                    enabledTools = mergedTools
                    mergedTools
                } else {
                    savedTools
                }
            }
        }
        set(value) = appPrefs.edit { putStringSet(KEY_ENABLED_TOOLS, value) }

    val standardTools: Set<String>
        get() = ToolRegistry().getAllToolNames()

    val customTools: Set<String>
        get() = AraWisperToolRegistry().getAllToolNames()

    var currentTools: org.json.JSONArray? = null

    fun isStandardTool(toolName: String): Boolean {
        return toolName in standardTools
    }

    private fun getModelConfig(key: String): org.json.JSONObject {
        val raw = getPref(key, "{}")
        return try {
            org.json.JSONObject(raw)
        } catch (e: Exception) {
            org.json.JSONObject().put("id", raw)
        }
    }

    var activeUserId: String = "default_user"

    object Routes {
        const val WS = "/ws"
        const val CONSTANTS = "/constants"
        const val PROXY = "/proxy"
        const val PROXY_MODELS = "$PROXY/models"
        const val PROXY_PROBE = "$PROXY/probe"

        // Status Routes
        const val STATUS = "/status"
        const val STATUS_STT = "$STATUS/stt"
        const val STATUS_RAG = "$STATUS/rag"
        const val STATUS_DB = "$STATUS/db"

        // Frontend Routes
        const val RAG = "/rag"
        const val RAG_USERS = "$RAG/users"
        const val RAG_EXPERIMENTS = "$RAG/experiments"
        const val RAG_EXPERIMENT_USERS = "$RAG/experiments/{id}/users"
        const val RAG_EXPERIMENT_FILES = "$RAG/experiments/{id}/files"

        // Backend Database API Routes
        const val DB_API_USERS = "/api/users"
        const val DB_API_USER_HISTORY_TURNS = "/api/users/history/turns"
        const val DB_API_USERS_SUMMARY = "/api/users/summary"
        const val DB_API_EXPERIMENTS = "/api/experiments"
        const val DB_API_EXPERIMENTS_USERS = "/api/experiments/{id}/users"
        const val DB_API_INGEST = "/ingest"
        const val DB_API_SEARCH = "/search"

    }

    object Modes {
        object AUDIO {
            const val OPENAI = "OpenAI (24kHz)"
            const val GOOGLE = "Google (16kHz)"
            const val CUSTOM = "Custom Proxy"
            val OPTIONS = listOf(OPENAI, GOOGLE, CUSTOM)
        }
        object ROUTING {
            const val REALTIME = "REALTIME_STS"
            const val AZURE = "AZURE_STTTS"
            const val CUSTOM = "CUSTOM_STT"
            val OPTIONS = listOf(REALTIME, AZURE, CUSTOM)
        }
    }

    object Settings {
        const val SYSTEM_PROMPT = "SYSTEM_PROMPT"
        const val SYSTEM_AUDIO_MODE = "SYSTEM_AUDIO_MODE"
        const val SYSTEM_ROUTING_MODE = "SYSTEM_ROUTING_MODE"
        const val LLM_ENDPOINT = "CUSTOM_LLM_ENDPOINT"
        const val LLM_KEY = "CUSTOM_LLM_KEY"
        const val LLM_CONFIG = "CUSTOM_LLM_CONFIG"
        const val VLM_ENDPOINT = "CUSTOM_VLM_ENDPOINT"
        const val VLM_KEY = "CUSTOM_VLM_KEY"
        const val VLM_CONFIG = "CUSTOM_VLM_CONFIG"
        const val EMBEDDING_ENDPOINT = "CUSTOM_EMBEDDING_ENDPOINT"
        const val EMBEDDING_KEY = "CUSTOM_EMBEDDING_KEY"
        const val EMBEDDING_CONFIG = "CUSTOM_EMBEDDING_CONFIG"
        const val CUSTOM_VAD_MODEL = "CUSTOM_VAD_MODEL"
        const val STT_ENDPOINT = "CUSTOM_STT_ENDPOINT"
        const val STT_KEY = "CUSTOM_STT_KEY"
        const val PERSON_DB_API_ENDPOINT = "PERSON_DB_API_ENDPOINT"
        const val PERSON_DB_KEY = "PERSON_DB_KEY"
        const val FACT_DB_API_ENDPOINT = "FACT_DB_API_ENDPOINT"
        const val FACT_DB_KEY = "FACT_DB_KEY"

        const val OPENAI_KEY = "OPENAI_API_KEY"
        const val AZURE_ENDPOINT = "AZURE_OPENAI_ENDPOINT"
        const val AZURE_KEY = "AZURE_OPENAI_KEY"
        const val XAI_KEY = "XAI_API_KEY"
        const val GOOGLE_KEY = "GOOGLE_API_KEY"
        const val GROQ_KEY= "GROQ_API_KEY"
        const val TAVILY_KEY= "TAVILY_API_KEY"
        const val OPENWEATHER_KEY= "OPENWEATHER_API_KEY"
        const val YOUTUBE_KEY= "YOUTUBE_API_KEY"
        const val AZURE_SPEECH_KEY = "AZURE_SPEECH_KEY"
        const val AZURE_SPEECH_REGION = "AZURE_SPEECH_REGION"

        const val MIC_STATUS = "MIC_STATUS"
    }

    object Dashboard {
        object Events {
            const val LOAD_API_KEYS = "LOAD_API_KEYS"
            const val SAVE_API_KEYS = "SAVE_API_KEYS"
            const val STATUS_UPDATE = "STATUS_UPDATE"
            const val CHAT_UPDATE = "CHAT_UPDATE"
            const val CHAT_SYNC = "CHAT_SYNC"
            const val CHAT_COMMAND = "CHAT_COMMAND"
            const val MIC_ACTIVITY = "MIC_ACTIVITY"
            const val TOGGLE_MUTE = "TOGGLE_MUTE"
            const val RESTART = "RESTART"
            const val INTERRUPT = "INTERRUPT"
            const val SET_USER = "SET_USER"
            const val DELETE_USER = "DELETE_USER"
            const val UPDATE_USER_KNOWLEDGE = "UPDATE_USER_KNOWLEDGE"
            const val CLEAR_HISTORY = "CLEAR_HISTORY"
        }
    }

    object Logging {
        const val ERROR = "⚠️ ERROR"
        const val TYPE_PROXY_LOG = "PROXY_WS_LOG"

        object Direction {
            const val RTP = "Robot -> Proxy"
            const val PTR = "️Proxy -> Robot"
        }

        object Keys {
            const val TYPE = "type"
            const val DATA = "data"
            const val DIRECTION = "direction"
            const val PAYLOAD = "payload"
            const val ERROR = "error_details"
            const val SENDER = "sender"
            const val MSG = "message"
            const val UUID = "uuid"
        }
    }

    object Proxy {
        object VAD {
            const val WEBRTC = "WebRTC VAD"
            const val SILERO = "Silero VAD"
            const val YAMNET = "Yamnet VAD"
            // val OPTIONS = listOf(WEBRTC, SILERO, YAMNET)
            val OPTIONS = listOf(WEBRTC, YAMNET)
        }

        object Keys {
            const val TYPE = "type"
            const val AUDIO = "audio"
            const val RESPONSE = "response"
            const val RESPONSE_ID = "response_id"
            const val DELTA = "delta"
            const val ID = "id"
            const val ITEM = "item"
            const val CONTENT = "content"
            const val TEXT = "text"
            const val ITEM_ID = "item_id"
            const val TRANSCRIPT = "transcript"
        }

        object Events {
            const val SESSION_CREATED = "session.created"
            const val SESSION_UPDATE = "session.update"
            const val RESP_TRANSCRIPT_DELTA = "response.audio_transcript.delta"
            const val CONV_ITEM_ADDED = "conversation.item.added"
            const val CONV_ITEM_DONE = "conversation.item.done"
            const val RESP_AUDIO_DELTA = "response.audio.delta"
            const val RESP_CREATED = "response.created"
            const val RESP_AUDIO_DONE = "response.audio.done"
            const val RESP_OUTPUT_DONE = "response.output_audio.done"
            const val RESP_DONE = "response.done"
            const val RESP_OUTPUT_ADDED = "response.output_item.added"
            const val CONV_ITEM_CREATED = "conversation.item.created"
            const val CONV_ITEM_TRUNC = "conversation.item.truncate"
            const val RESP_PART_ADDED = "response.content_part.added"
            const val RESP_PART_DONE = "response.content_part.done"
            const val RESP_FN_DELTA = "response.function_call_arguments.delta"
            const val RESP_FN_DONE = "response.function_call_arguments.done"
            const val RESP_TRANSCRIPT_DONE = "response.audio_transcript.done"
            const val RESP_OUTPUT_DELTA = "response.output_audio.delta"
            const val RESP_OUTPUT_TRANSCRIPT_DELTA = "response.output_audio_transcript.delta"
            const val RESP_OUTPUT_TRANSCRIPT_DONE = "response.output_audio_transcript.done"
            const val RESP_OUTPUT_ITEM_DONE = "response.output_item.done"
            const val RATE_UPDATED = "rate_limits.updated"
            const val INPUT_BUFFER_START = "input_audio_buffer.speech_started"
            const val INPUT_BUFFER_STOP = "input_audio_buffer.speech_stopped"
            const val INPUT_BUFFER_COMMIT = "input_audio_buffer.committed"
            const val CONV_ITEM_TRANSCRIPT_COMPLETED = "conversation.item.input_audio_transcription.completed"
            const val CONV_ITEM_TRANSCRIPT_FAIL = "conversation.item.input_audio_transcription.failed"
            const val ERROR = "error"

            const val RESP_CANCEL = "response.cancel"
            const val INPUT_BUFFER_APPEND = "input_audio_buffer.append"
            const val RESP_CREATE = "response.create"
            const val CONV_ITEM_CREATE = "conversation.item.create"
        }

    }

}