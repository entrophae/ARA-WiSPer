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
        val isAudioPlaying = adapter?.viewModel?.isAudioPlaying?.value == true
        val isSpeechBusy = AraWisperManager.speechManager.isSpeakingOrCoolingDown()

        // Enforce THINKING lock if main's ChatRealtimeHandler drifted to LISTENING while a turn/tool is active
        if (isTurnBusy && turnState == TurnManager.State.LISTENING && !isAudioPlaying) {
            turnManager.setState(TurnManager.State.THINKING)
        }

        // Set LISTENING if TurnManager remained stuck in THINKING after pipeline/speech finished
        if (!isTurnBusy && !isAudioPlaying && !isSpeechBusy && turnState == TurnManager.State.THINKING) {
            turnManager.setState(TurnManager.State.LISTENING)
        }

        val currentTurnState = turnManager?.state
        val isRobotBusyState = currentTurnState == TurnManager.State.THINKING || currentTurnState == TurnManager.State.SPEAKING

        val shouldDrop = adapter?.audioInputController?.isMuted == true ||
                isSpeechBusy ||
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
            if (adapter.turnManager?.state != TurnManager.State.SPEAKING) {
                adapter.turnManager?.setState(TurnManager.State.THINKING)
            }
        }
    }

    fun keepTurnActiveForExternalTool() {
        AraWisperManager.speechManager.isTurnActive = true
        AraWisperManager.chatActivityAdapter?.let { adapter ->
            if (!adapter.viewModel.isAudioPlaying.value && adapter.turnManager?.state == TurnManager.State.LISTENING) {
                adapter.turnManager?.setState(TurnManager.State.THINKING)
            }
        }
    }

    fun markListening(stopSpeech: Boolean = false, clearCooldown: Boolean = false, forceResetUi: Boolean = false) {
        AraWisperManager.speechManager.isTurnActive = false

        if (stopSpeech) {
            AraWisperManager.speechManager.stop(clearCooldown = clearCooldown)
        }

        AraWisperManager.chatActivityAdapter?.let { adapter ->
            if (forceResetUi) {
                adapter.viewModel.setResponseGenerating(false)
                adapter.viewModel.setAudioPlaying(false)
                adapter.turnManager?.setState(TurnManager.State.LISTENING)
                return
            }

            val isActivelyPlaying = adapter.viewModel.isAudioPlaying.value
            val isSpeakingState = adapter.turnManager?.state == TurnManager.State.SPEAKING
            val isSpeechBusy = AraWisperManager.speechManager.isSpeakingOrCoolingDown()
            if (!isActivelyPlaying && !isSpeakingState && !isSpeechBusy) {
                adapter.viewModel.setResponseGenerating(false)
                adapter.viewModel.setAudioPlaying(false)
                adapter.turnManager?.setState(TurnManager.State.LISTENING)
            }
        }
    }
}