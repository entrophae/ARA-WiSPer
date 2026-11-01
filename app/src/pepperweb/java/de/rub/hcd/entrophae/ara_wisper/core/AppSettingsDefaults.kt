package de.rub.hcd.entrophae.ara_wisper.core

import ch.fhnw.pepper_realtime.manager.SettingsRepository
import ch.fhnw.pepper_realtime.network.RealtimeApiProvider
import ch.fhnw.pepper_realtime.network.XAI_FAST_MODEL
import ch.fhnw.pepper_realtime.network.XAI_THINK_FAST_MODEL

object AppSettingsDefaults {

    data class SettingOption(val label: String, val value: String)

    val providers: List<RealtimeApiProvider>
        get() = RealtimeApiProvider.entries

    val openAiModels = listOf(
        "gpt-realtime-1.5",
        "gpt-realtime",
        "gpt-realtime-mini",
        "gpt-4o-realtime-preview",
        "gpt-4o-mini-realtime-preview"
    )

    val xaiModels = listOf(
        XAI_THINK_FAST_MODEL,
        XAI_FAST_MODEL
    )

    val googleModels = listOf(
        "models/gemini-3.1-flash-live-preview",
        "models/gemini-2.5-flash-native-audio-preview-12-2025",
        "models/gemini-2.0-flash-live-001"
    )

    val openAiVoices = linkedMapOf(
        "alloy" to "Neutral, balanced",
        "ash" to "Clear, precise",
        "ballad" to "Melodic, smooth",
        "cedar" to "Natural, clear",
        "coral" to "Warm, friendly",
        "echo" to "Resonant, deep",
        "marin" to "Natural, clear",
        "sage" to "Calm, thoughtful",
        "shimmer" to "Bright, energetic",
        "verse" to "Versatile, expressive"
    )

    val xaiVoices = linkedMapOf(
        "eve" to "Female",
        "ara" to "Female",
        "rex" to "Male",
        "sal" to "Male",
        "leo" to "Male"
    )

    val googleVoices = linkedMapOf(
        "Zephyr" to "Bright",
        "Puck" to "Upbeat",
        "Charon" to "Informative",
        "Kore" to "Firm",
        "Fenrir" to "Excitable",
        "Leda" to "Youthful",
        "Orus" to "Firm",
        "Aoede" to "Breezy",
        "Callirrhoe" to "Easy-going",
        "Autonoe" to "Bright",
        "Enceladus" to "Breathy",
        "Iapetus" to "Clear",
        "Umbriel" to "Easy-going",
        "Algieba" to "Smooth",
        "Despina" to "Smooth",
        "Erinome" to "Clear",
        "Algenib" to "Gravelly",
        "Rasalgethi" to "Informative",
        "Laomedeia" to "Upbeat",
        "Achernar" to "Soft",
        "Alnilam" to "Firm",
        "Schedar" to "Even",
        "Gacrux" to "Mature",
        "Pulcherrima" to "Forward",
        "Achird" to "Friendly",
        "Zubenelgenubi" to "Casual",
        "Vindemiatrix" to "Gentle",
        "Sadachbia" to "Lively",
        "Sadaltager" to "Knowledgeable",
        "Sulafat" to "Warm"
    )

    val audioInputModes = listOf(
        SettingOption("Direct Audio", SettingsRepository.MODE_REALTIME_API),
        SettingOption("Azure Speech", SettingsRepository.MODE_AZURE_SPEECH)
    )

    val transcriptionModels = listOf(
        "whisper-1",
        "gpt-4o-mini-transcribe",
        "gpt-4o-transcribe",
        "gpt-4o-transcribe-diarize"
    )

    val turnDetectionTypes = listOf(
        SettingOption("Server VAD", "server_vad"),
        SettingOption("Semantic VAD", "semantic_vad")
    )
    val eagernessLevels = listOf(
        SettingOption("Auto (Medium)", "auto"),
        SettingOption("Low (Max 8s)", "low"),
        SettingOption("Medium (Max 4s)", "medium"),
        SettingOption("High (Max 2s)", "high")
    )
    val noiseReductionTypes = listOf(
        SettingOption("Off", "off"),
        SettingOption("Near Field", "near_field"),
        SettingOption("Far Field", "far_field")
    )

    val thinkingLevels = listOf("minimal", "low", "medium", "high")

    object Strings {
        val resetToDefaults = "Reset all settings to their default values? API keys will not be affected. The session will restart."
        val proactiveAudio = "Allow Gemini to decide not to respond when content is not relevant"
        val showThinking = "Display AI thinking traces in chat"
        val longSession = "Enable context compression for unlimited session length (default: 15min audio, 2min video)"
        val idleTimeoutHint = "Milliseconds - leave empty to disable"
        val googleSearch = "Ground responses with real-time web search for improved accuracy"
        val xaiWebSearch = "Search the web for real-time information"
        val xaiXSearch = "Search X/Twitter posts for latest discussions and news"
    }


}