package de.rub.hcd.entrophae.ara_wisper.core

import android.app.Activity
import android.content.SharedPreferences
import android.util.Log
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import ch.fhnw.pepper_realtime.manager.SettingsRepository
import ch.fhnw.pepper_realtime.network.RealtimeApiProvider
import ch.fhnw.pepper_realtime.ui.settings.SettingsViewModel
import de.rub.hcd.entrophae.ara_wisper.manager.AraWisperManager
import de.rub.hcd.entrophae.ara_wisper.manager.ProxySettingsRepository
import org.json.JSONArray
import org.json.JSONObject

object SettingsAdapter {

    fun readAllAsJson(prefs: SharedPreferences, adapter: ChatActivityAdapter? = null): JSONObject {
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
                put("systemPrompt", repo.systemPrompt)
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

                put("enabledTools", JSONArray(repo.enabledTools))
            } else {
                put("volume", 80)
                put("systemPrompt", "You are Pepper, a friendly robot assistant.")
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

    fun saveJsonToPrefs(prefs: SharedPreferences, data: JSONObject, adapter: ChatActivityAdapter? = null) {
        val activity: ViewModelStoreOwner = AraWisperManager.currentChatActivity as ViewModelStoreOwner
        val viewModel = ViewModelProvider(activity)[SettingsViewModel::class.java]
        // Save Proxy Server Settings
        ApiDataTemplate.allSettings.forEach { setting ->
            val value = data.optString(setting.id, setting.default)
            if (value.isNotBlank()) ProxySettingsRepository.setPref(setting.id, value)
        }
        // Save Native App Settings via SettingsRepository
        val repo = adapter?.settingsRepository
        if (repo != null) {
            if (data.has("volume")) {
                repo.volume = data.optInt("volume", repo.volume)
                viewModel.setVolume(repo.volume)
                viewModel.commitChanges()
            }
            if (data.has("systemPrompt")) repo.systemPrompt = data.optString("systemPrompt", repo.systemPrompt)

            // Update provider first so model and voice write to providerspecific keys
            if (data.has("apiProvider")) repo.apiProvider = data.optString("apiProvider", repo.apiProvider)
            if (data.has("model")) {
                repo.model = data.optString("model", repo.model)
                viewModel.setModel(repo.model)
            }
            if (data.has("voice")) repo.voice = data.optString("voice", repo.voice)

            if (data.has("speedProgress")) repo.speedProgress = data.optInt("speedProgress", repo.speedProgress)
            if (data.has("temperatureProgress")) repo.temperatureProgress = data.optInt("temperatureProgress", repo.temperatureProgress)
            if (data.has("audioInputMode")) repo.audioInputMode = data.optString("audioInputMode", repo.audioInputMode)

            if (data.has("language")) repo.language = data.optString("language", repo.language)
            if (data.has("silenceTimeout")) repo.silenceTimeout = data.optInt("silenceTimeout", repo.silenceTimeout)
            if (data.has("confidenceThreshold")) repo.confidenceThreshold = data.optDouble("confidenceThreshold", repo.confidenceThreshold.toDouble()).toFloat()

            if (data.has("googleStartSensitivity")) repo.googleStartSensitivity = data.optString("googleStartSensitivity", repo.googleStartSensitivity)
            if (data.has("googleEndSensitivity")) repo.googleEndSensitivity = data.optString("googleEndSensitivity", repo.googleEndSensitivity)
            if (data.has("googlePrefixPaddingMs")) repo.googlePrefixPaddingMs = data.optInt("googlePrefixPaddingMs", repo.googlePrefixPaddingMs)
            if (data.has("googleSilenceDurationMs")) repo.googleSilenceDurationMs = data.optInt("googleSilenceDurationMs", repo.googleSilenceDurationMs)
            if (data.has("googleThinkingLevel")) repo.googleThinkingLevel = data.optString("googleThinkingLevel", repo.googleThinkingLevel)
            if (data.has("googleThinkingBudget")) repo.googleThinkingBudget = data.optInt("googleThinkingBudget", repo.googleThinkingBudget)
            if (data.has("googleProactiveAudio")) repo.googleProactiveAudio = data.optBoolean("googleProactiveAudio", repo.googleProactiveAudio)
            if (data.has("googleShowThinking")) repo.googleShowThinking = data.optBoolean("googleShowThinking", repo.googleShowThinking)
            if (data.has("googleContextCompression")) repo.googleContextCompression = data.optBoolean("googleContextCompression", repo.googleContextCompression)
            if (data.has("googleSearchGrounding")) repo.googleSearchGrounding = data.optBoolean("googleSearchGrounding", repo.googleSearchGrounding)

            if (data.has("xaiWebSearch")) repo.xaiWebSearch = data.optBoolean("xaiWebSearch", repo.xaiWebSearch)
            if (data.has("xaiXSearch")) repo.xaiXSearch = data.optBoolean("xaiXSearch", repo.xaiXSearch)

            if (data.has("transcriptionModel")) repo.transcriptionModel = data.optString("transcriptionModel", repo.transcriptionModel)
            if (data.has("transcriptionLanguage")) repo.transcriptionLanguage = data.optString("transcriptionLanguage", repo.transcriptionLanguage)
            if (data.has("transcriptionPrompt")) repo.transcriptionPrompt = data.optString("transcriptionPrompt", repo.transcriptionPrompt)
            if (data.has("turnDetectionType")) repo.turnDetectionType = data.optString("turnDetectionType", repo.turnDetectionType)
            if (data.has("vadThreshold")) repo.vadThreshold = data.optDouble("vadThreshold", repo.vadThreshold.toDouble()).toFloat()
            if (data.has("prefixPadding")) repo.prefixPadding = data.optInt("prefixPadding", repo.prefixPadding)
            if (data.has("silenceDuration")) repo.silenceDuration = data.optInt("silenceDuration", repo.silenceDuration)
            if (data.has("idleTimeout")) repo.idleTimeout = data.optInt("idleTimeout", repo.idleTimeout ?: 0)
            if (data.has("eagerness")) repo.eagerness = data.optString("eagerness", repo.eagerness)
            if (data.has("noiseReduction")) repo.noiseReduction = data.optString("noiseReduction", repo.noiseReduction)

            if (data.has("enabledTools")) {
                val arr = data.optJSONArray("enabledTools")
                val set = mutableSetOf<String>()
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        set.add(arr.getString(i))
                    }
                }
                repo.enabledTools = set
            }


        }
        adapter?.updateRealtimeSession()
    }
}