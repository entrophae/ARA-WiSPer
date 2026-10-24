package de.rub.hcd.entrophae.ara_wisper.core

import android.util.Log
import ch.fhnw.pepper_realtime.manager.SettingsRepository
import ch.fhnw.pepper_realtime.manager.TurnManager
import ch.fhnw.pepper_realtime.ui.ChatActivity
import ch.fhnw.pepper_realtime.ui.ChatViewModel

class ChatActivityAdapter(private val activity: ChatActivity) {

    private val TAG = "ARA WISPER CHATACTIVITY ADAPTER"

    // Pepper Hardware & QiSDK Context
    val qiContext = activity.getQiContext()
    val robotController = activity.getRobotController()
    val perceptionService = activity.perceptionService
    val visionService = activity.visionService

    // Microphone & Audio
    val audioInputController = activity.audioInputController
    val audioPlayer = activity.audioPlayer

    // Realtime Session & Turn Lifecycle
    val viewModel = activity.viewModel
    val turnManager = activity.turnManager                    // TurnManager (State.LISTENING, SPEAKING, etc.)
    val sessionManager = activity.sessionManager              // RealtimeSessionManager
    val sessionController = activity.sessionController        // ChatSessionController

    // Tools & App Settings
    val keyManager = activity.keyManager
    val toolRegistry = activity._toolRegistry
    val toolContext = activity.toolContext
    val settingsRepository: SettingsRepository = activity._settingsRepository

    fun restartSession() {
        activity.runOnUiThread {
            activity.viewModel.startNewSession()
        }
    }

    fun updateRealtimeSession() {
        activity.runOnUiThread {
            activity.sessionManager.updateSession()
        }
    }

    // Adapted version of "onStatusCapsuleClick" for website
    fun interruptSpeech() {
        try {
            val currentState = activity.chatDeps.turnManager.state

            // Only interrupt during SPEAKING
            if (currentState == TurnManager.State.SPEAKING) {
                if (activity.viewModel.isResponseGenerating.value || activity.viewModel.isAudioPlaying.value || activity.chatDeps.audioPlayer.isPlaying()
                ) {
                    activity.chatDeps.interruptController.interruptSpeech()
                    Log.i(TAG, "Speech interrupted via status capsule")
                }
            }
            // No action in other states. capsule just shows status
        } catch (e: Exception) {
            Log.e(TAG, "Status capsule click failed", e)
        }
    }

    // Adapted version of "onMicrophoneToggle" for website
    fun toggleMicrophone() {
        try {
            val newIntent = !activity.viewModel.userWantsMicOn.value
            activity.viewModel.setUserWantsMicOn(newIntent)
            Log.i(TAG, "Mic intent toggled: userWantsMicOn=$newIntent")

            val currentState = activity.chatDeps.turnManager.state

            // If currently LISTENING, apply immediately
            if (currentState == TurnManager.State.LISTENING) {
                if (newIntent) {
                    activity.unmuteMicrophone()
                } else {
                    activity.muteMicrophone()
                }
            }
            // Otherwise, intent is stored and applied on state transition to LISTENING
        } catch (e: Exception) {
            Log.e(TAG, "Microphone toggle failed", e)
        }
    }
    fun muteMicrophone() = activity.muteMicrophone()
    fun unmuteMicrophone() = activity.unmuteMicrophone()
}