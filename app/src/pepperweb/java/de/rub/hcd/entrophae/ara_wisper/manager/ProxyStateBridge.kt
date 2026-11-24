package de.rub.hcd.entrophae.ara_wisper.manager

import ch.fhnw.pepper_realtime.manager.TurnManager
import de.rub.hcd.entrophae.ara_wisper.server.service.AudioProcessor

/**
 * Bridges proxy pipeline lifecycle transitions with the
 * Android UI, TurnManager, SpeechManager, and incoming audio gating.
 */
object ProxyStateBridge {

    fun shouldDropIncomingAudio(isTurnBusy: Boolean, audioProcessor: AudioProcessor): Boolean {
        val adapter = AraWisperManager.chatActivityAdapter
        val turnManager = adapter?.turnManager
        val turnState = turnManager?.state
        val isRobotBusyState = turnState == TurnManager.State.THINKING || turnState == TurnManager.State.SPEAKING

        // Enforce THINKING lock if main's ChatRealtimeHandler drifted to LISTENING while a turn/tool is active
        if (isTurnBusy && turnState == TurnManager.State.LISTENING && !adapter.viewModel.isAudioPlaying.value) {
            turnManager.setState(TurnManager.State.THINKING)
        }

        val shouldDrop = adapter?.audioInputController?.isMuted == true ||
                AraWisperManager.speechManager.isSpeakingOrCoolingDown() ||
                isTurnBusy ||
                isRobotBusyState

        if (shouldDrop) {
            audioProcessor.reset()
        }
        return shouldDrop
    }

    fun markThinking() {
        AraWisperManager.speechManager.isTurnActive = true
        AraWisperManager.chatActivityAdapter?.let { adapter ->
            adapter.viewModel.setResponseGenerating(true)
            if (adapter.turnManager?.state != TurnManager.State.SPEAKING) {
                adapter.turnManager?.setState(TurnManager.State.THINKING)
            }
        }
    }

    fun keepTurnActiveForExternalTool() {
        AraWisperManager.speechManager.isTurnActive = true
    }

    fun markListening(stopSpeech: Boolean = false, clearCooldown: Boolean = false) {
        AraWisperManager.speechManager.isTurnActive = false

        if (stopSpeech) {
            AraWisperManager.speechManager.stop(clearCooldown = clearCooldown)
        }

        AraWisperManager.chatActivityAdapter?.let { adapter ->
            adapter.viewModel.setResponseGenerating(false)
            if (!AraWisperManager.speechManager.isSpeakingOrCoolingDown()) {
                adapter.viewModel.setAudioPlaying(false)
                adapter.turnManager?.setState(TurnManager.State.LISTENING)
            }
        }
    }
}