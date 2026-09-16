package de.rub.hcd.entrophae.ara_wisper.server.service

import android.util.Log
import com.konovalov.vad.silero.VadSilero
import com.konovalov.vad.silero.config.Mode as sileroMode
import com.konovalov.vad.silero.config.FrameSize as sileroFrameSize
import com.konovalov.vad.silero.config.SampleRate as sileroSampleRate
import com.konovalov.vad.webrtc.VadWebRTC
import com.konovalov.vad.webrtc.config.Mode as webrtcMode
import com.konovalov.vad.webrtc.config.FrameSize as webrtcFrameSize
import com.konovalov.vad.webrtc.config.SampleRate as webrtcSampleRate
import com.konovalov.vad.yamnet.VadYamnet
import com.konovalov.vad.yamnet.config.Mode as yamnetMode
import com.konovalov.vad.yamnet.config.FrameSize as yamnetFrameSize
import com.konovalov.vad.yamnet.config.SampleRate as yamnetSampleRate
import de.rub.hcd.entrophae.ara_wisper.manager.AraWisperManager.appContext
import de.rub.hcd.entrophae.ara_wisper.manager.ProxySettingsRepository

class VadProvider (
    val isSpeech: (ByteArray) -> Boolean,
    val close: () -> Unit
) {
    companion object {
        fun create(modelType: String): VadProvider {
            Log.i(ProxySettingsRepository.TAG, "AudioProcessor Initializing VAD Model: $modelType")
            return when (modelType) {
                ProxySettingsRepository.Proxy.VAD.WEBRTC -> {
                    val webrtc = VadWebRTC(
                        sampleRate = webrtcSampleRate.SAMPLE_RATE_16K,
                        frameSize = webrtcFrameSize.FRAME_SIZE_320,
                        mode = webrtcMode.VERY_AGGRESSIVE,
                        silenceDurationMs = 300,
                        speechDurationMs = 50
                    )

                    VadProvider(
                        isSpeech = webrtc::isSpeech,
                        close = webrtc::close
                    )
                }

                // Not functioning.
                ProxySettingsRepository.Proxy.VAD.SILERO -> {
                    val silero = VadSilero(
                        appContext,
                        sampleRate = sileroSampleRate.SAMPLE_RATE_16K,
                        frameSize = sileroFrameSize.FRAME_SIZE_512,
                        mode = sileroMode.NORMAL,
                        silenceDurationMs = 300,
                        speechDurationMs = 50
                    )
                    VadProvider(
                        isSpeech = silero::isSpeech,
                        close = silero::close
                    )
                }

                ProxySettingsRepository.Proxy.VAD.YAMNET -> {
                    val yamnet = VadYamnet(
                        appContext,
                        sampleRate = yamnetSampleRate.SAMPLE_RATE_16K,
                        frameSize = yamnetFrameSize.FRAME_SIZE_243,
                        mode = yamnetMode.NORMAL,
                        silenceDurationMs = 30,
                        speechDurationMs = 30
                    )
                    VadProvider(
                        isSpeech = { bytes ->
                            val speech = yamnet.classifyAudio("Speech", bytes)
                            when (speech.label) {
                                "Speech" -> Log.i(
                                    ProxySettingsRepository.TAG,
                                    "Speech Detected: " + speech.score
                                )

                                else -> Log.i(
                                    ProxySettingsRepository.TAG,
                                    "Noise Detected as ${speech.label}: " + speech.score
                                )
                            }
                            speech.label == "Speech"
                        },
                        close = yamnet::close
                    )
                }

                else -> {
                    create(ProxySettingsRepository.Proxy.VAD.YAMNET)
                }
            }
        }
    }
}