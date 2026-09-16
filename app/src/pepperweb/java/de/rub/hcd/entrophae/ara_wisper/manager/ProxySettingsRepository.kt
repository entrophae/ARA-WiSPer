package de.rub.hcd.entrophae.ara_wisper.manager

import de.rub.hcd.entrophae.ara_wisper.manager.AraWisperManager.appPrefs

object ProxySettingsRepository {
    const val PREFS_NAME = "PepperDialogPrefs"
    const val TAG = "AraWisper Proxy"

    fun getPref(key: String, default: String = ""): String {
        return appPrefs.getString(key, default) ?: default
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

    val customLlmModel: String
        get() = getPref(Settings.LLM_MODEL, "")

    val customSttEndpoint: String
        get() = getPref(Settings.STT_ENDPOINT, "")

    val customSttKey: String
        get() = getPref(Settings.STT_KEY, "")
    
    val databasePersonApiEndpoint: String
        get() = getPref(Settings.PERSON_DB_API_ENDPOINT, "").removeSuffix("/")

    val databasePersonKey: String
        get() = getPref(Settings.PERSON_DB_KEY, "")
        
    val customSystemPrompt: String
        get() = getPref(Settings.SYSTEM_PROMPT, "")

    val defaultSystemPrompt: String
        get() = try {
            AraWisperManager.appAssets.open("default_german_system_prompt.txt")
                .bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            "You are a helpful assistant."
        }

    val fallbackToolInstruction: String
        get() = try {
            AraWisperManager.appAssets.open("fallback_tool_instruction.txt")
                .bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            ""
        }

    var activeUserId: String = "default_user"
    
    object Routes {
        const val WS = "/ws"
        const val CONSTANTS = "/constants"
        const val PROXY = "/proxy"
        const val PROXY_MODELS = "$PROXY/models"
        const val RAG = "/rag"
        const val RAG_USERS = "$RAG/users"
        const val RAG_USER_HISTORY = "$RAG_USERS/history"
        const val RAG_USER_SUMMARY = "$RAG_USERS/summary"
        const val DB_API_USERS = "/api/users"
        const val DB_API_USERS_KNOWLEDGE = "/api/knowledge/users"
        const val DB_API_USERS_HISTORY = "/api/knowledge/history"
        const val DB_API_USERS_TURN = "/api/knowledge/history/turn"
        const val DB_API_USERS_SUMMARY = "/api/knowledge/summary"
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
        const val LLM_MODEL = "CUSTOM_LLM_MODEL"
        const val CUSTOM_VAD_MODEL = "CUSTOM_VAD_MODEL"
        const val STT_ENDPOINT = "CUSTOM_STT_ENDPOINT"
        const val STT_KEY = "CUSTOM_STT_KEY"
        const val PERSON_DB_API_ENDPOINT = "PERSON_DB_API_ENDPOINT"
        const val PERSON_DB_KEY = "PERSON_DB_KEY"

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
    }

    object Dashboard {
        object Events {
            const val LOAD_API_KEYS = "LOAD_API_KEYS"
            const val SAVE_API_KEYS = "SAVE_API_KEYS"
            const val STATUS_UPDATE = "STATUS_UPDATE"
            const val CHAT_UPDATE = "CHAT_UPDATE"
            const val CHAT_COMMAND = "CHAT_COMMAND"
            const val MIC_ACTIVITY = "MIC_ACTIVITY"
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
            const val RTP = "🤖 Robot -> 🎛️️ Proxy"
            const val PTR = "🎛️️ Proxy -> 🤖 Robot"
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