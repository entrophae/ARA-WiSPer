package de.rub.hcd.entrophae.ara_wisper.core

import android.util.Log
import ch.fhnw.pepper_realtime.manager.SettingsRepository
import ch.fhnw.pepper_realtime.network.RealtimeApiProvider
import de.rub.hcd.entrophae.ara_wisper.manager.AraWisperManager
import de.rub.hcd.entrophae.ara_wisper.manager.ProxySettingsRepository
import org.json.JSONArray
import org.json.JSONObject

/**
 * Bidirectional persistence adapter between Android `SharedPreferences` and dashboard JSON.
 *
 * Serializes all active proxy and host settings for initial WebSocket sync and writes
 * incoming dashboard configuration updates back to local storage.
 */
object SettingsAdapter {

    fun readAllAsJson(): JSONObject {
        val adapter = AraWisperManager.chatActivityAdapter
        val repo = adapter?.settingsRepository

        Log.i("SettingsAdapter", "adapter arrived: ${adapter!=null}")

        return JSONObject().apply {
            // Proxy Server Keys
            ApiDataTemplate.allSettings.forEach { setting ->
                put(setting.id, ProxySettingsRepository.getPref(setting.id, setting.default))
            }

            // Native App Settings
            if (repo != null) {
                put("volume", repo.volume)
                put("systemPrompt", ProxySettingsRepository.activeSystemPrompt)
                put("apiProvider", repo.apiProvider) // Set provider first for scoped keys
                put("model", repo.model)
                put("voice", repo.voice)
                put("speedProgress", repo.speedProgress)
                put("temperatureProgress", repo.temperatureProgress)
                put("audioInputMode", repo.audioInputMode)

                put("language", repo.language)
                put("silenceTimeout", repo.silenceTimeout)
                put("confidenceThreshold", repo.confidenceThreshold)

                put("googleStartSensitivity", repo.googleStartSensitivity)
                put("googleEndSensitivity", repo.googleEndSensitivity)
                put("googlePrefixPaddingMs", repo.googlePrefixPaddingMs)
                put("googleSilenceDurationMs", repo.googleSilenceDurationMs)
                put("googleThinkingLevel", repo.googleThinkingLevel)
                put("googleThinkingBudget", repo.googleThinkingBudget)
                put("googleProactiveAudio", repo.googleProactiveAudio)
                put("googleShowThinking", repo.googleShowThinking)
                put("googleContextCompression", repo.googleContextCompression)
                put("googleSearchGrounding", repo.googleSearchGrounding)

                put("xaiWebSearch", repo.xaiWebSearch)
                put("xaiXSearch", repo.xaiXSearch)

                put("transcriptionModel", repo.transcriptionModel)
                put("transcriptionLanguage", repo.transcriptionLanguage)
                put("transcriptionPrompt", repo.transcriptionPrompt)
                put("turnDetectionType", repo.turnDetectionType)
                put("vadThreshold", repo.vadThreshold)
                put("prefixPadding", repo.prefixPadding)
                put("silenceDuration", repo.silenceDuration)
                put("idleTimeout", repo.idleTimeout ?: -1)
                put("eagerness", repo.eagerness)
                put("noiseReduction", repo.noiseReduction)

                put("enabledTools", JSONArray(ProxySettingsRepository.enabledTools))
            } else {
                put("volume", 80)
                put("systemPrompt", ProxySettingsRepository.activeSystemPrompt)
                put("apiProvider", RealtimeApiProvider.OPENAI_DIRECT.name)
                put("model", "gpt-realtime-1.5")
                put("voice", "ash")
                put("speedProgress", 100)
                put("temperatureProgress", 33)
                put("audioInputMode", SettingsRepository.MODE_REALTIME_API)
                put("language", "en-US")
                put("silenceTimeout", 500)
                put("confidenceThreshold", 0.7f)
                put("turnDetectionType", "server_vad")
                put("enabledTools", JSONArray())
            }
        }
    }

    fun saveJsonToPrefs(data: JSONObject) {
        val adapter = AraWisperManager.chatActivityAdapter
        val settingsViewModel = adapter?.settingsViewModel
        settingsViewModel?.beginEditing()
        // Save Proxy Server Settings
        ApiDataTemplate.allSettings.forEach { setting ->
            val value = data.optString(setting.id, setting.default)
            val isChanged = ProxySettingsRepository.getPref(setting.id) != value
            if (isChanged) ProxySettingsRepository.setPref(setting.id, value)
        }
        // Save Native App Settings via SettingsRepository
        val repo = adapter?.settingsRepository
        if (repo != null) {
            if (data.has("systemPrompt")) {
                val newPrompt = data.optString("systemPrompt")
                settingsViewModel?.setSystemPrompt(newPrompt)
                ProxySettingsRepository.customSystemPrompt = newPrompt
            }

            // Update provider first so model and voice write to providerspecific keys
            if (data.has("apiProvider")) {
                settingsViewModel?.setApiProvider(data.optString("apiProvider"))
            }
            if (data.has("model")) {
                settingsViewModel?.setModel(data.optString("model"))
            }
            if (data.has("voice")) {
                settingsViewModel?.setVoice(data.optString("voice"))
            }
            if (data.has("speedProgress")) {
                settingsViewModel?.setSpeedProgress(data.optInt("speedProgress"))
            }
            if (data.has("language")) {
                settingsViewModel?.setLanguage(data.optString("language"))
            }
            if (data.has("audioInputMode")) {
                settingsViewModel?.setAudioInputMode(data.optString("audioInputMode"))
            }
            if (data.has("temperatureProgress")) {
                settingsViewModel?.setTemperatureProgress(data.optInt("temperatureProgress"))
            }
            if (data.has("volume")) {
                settingsViewModel?.setVolume(data.optInt("volume"))
            }
            if (data.has("silenceTimeout")) {
                settingsViewModel?.setSilenceTimeout(data.optInt("silenceTimeout"))
            }
            if (data.has("confidenceThreshold")) {
                settingsViewModel?.setConfidenceThreshold(data.optDouble("confidenceThreshold").toFloat())
            }
            if (data.has("enabledTools")) {
                val arr = data.optJSONArray("enabledTools")
                val set = mutableSetOf<String>()
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        set.add(arr.getString(i))
                    }
                }
                settingsViewModel?.setEnabledTools(set)
                ProxySettingsRepository.enabledTools = set
            }
            if (data.has("transcriptionModel")) {
                settingsViewModel?.setTranscriptionModel(data.optString("transcriptionModel", repo.transcriptionModel))
            }
            if (data.has("transcriptionLanguage")) {
                settingsViewModel?.setTranscriptionLanguage(data.optString("transcriptionLanguage"))
            }
            if (data.has("transcriptionPrompt")) {
                settingsViewModel?.setTranscriptionPrompt(data.optString("transcriptionPrompt"))
            }
            if (data.has("turnDetectionType")) {
                settingsViewModel?.setTurnDetectionType(data.optString("turnDetectionType"))
            }
            if (data.has("vadThreshold")) {
                settingsViewModel?.setVadThreshold(data.optDouble("vadThreshold").toFloat())
            }
            if (data.has("prefixPadding")) {
                settingsViewModel?.setPrefixPadding(data.optInt("prefixPadding"))
            }
            if (data.has("silenceDuration")) {
                settingsViewModel?.setSilenceDuration(data.optInt("silenceDuration"))
            }
            if (data.has("idleTimeout")) {
                settingsViewModel?.setIdleTimeout(data.optInt("idleTimeout"))
            }
            if (data.has("eagerness")) {
                settingsViewModel?.setEagerness(data.optString("eagerness"))
            }
            if (data.has("noiseReduction")) {
                settingsViewModel?.setNoiseReduction(data.optString("noiseReduction"))
            }
            if (data.has("googleStartSensitivity")) {
                settingsViewModel?.setGoogleStartSensitivity(data.optString("googleStartSensitivity"))
            }
            if (data.has("googleEndSensitivity")) {
                settingsViewModel?.setGoogleEndSensitivity(data.optString("googleEndSensitivity"))
            }
            if (data.has("googlePrefixPaddingMs")) {
                settingsViewModel?.setGooglePrefixPaddingMs(data.optInt("googlePrefixPaddingMs"))
            }
            if (data.has("googleSilenceDurationMs")) {
                settingsViewModel?.setGoogleSilenceDurationMs(data.optInt("googleSilenceDurationMs"))
            }
            if (data.has("googleThinkingBudget")) {
                settingsViewModel?.setGoogleThinkingBudget(data.optInt("googleThinkingBudget"))
            }
            if (data.has("googleThinkingLevel")) {
                settingsViewModel?.setGoogleThinkingLevel(data.optString("googleThinkingLevel"))
            }
            if (data.has("googleProactiveAudio")) {
                settingsViewModel?.setGoogleProactiveAudio(data.optBoolean("googleProactiveAudio"))
            }
            if (data.has("googleShowThinking")) {
                settingsViewModel?.setGoogleShowThinking(data.optBoolean("googleShowThinking"))
            }
            if (data.has("googleSearchGrounding")) {
                settingsViewModel?.setGoogleSearchGrounding(data.optBoolean("googleSearchGrounding"))
            }
            if (data.has("googleContextCompression")) {
                settingsViewModel?.setGoogleContextCompression(data.optBoolean("googleContextCompression"))
            }
            if (data.has("xaiWebSearch")) {
                settingsViewModel?.setXaiWebSearch(data.optBoolean("xaiWebSearch"))
            }
            if (data.has("xaiXSearch")) {
                settingsViewModel?.setXaiXSearch(data.optBoolean("xaiXSearch"))
            }
        }
        settingsViewModel?.commitChanges()
    }

}