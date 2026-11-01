package de.rub.hcd.entrophae.ara_wisper.core

import android.util.Log
import androidx.activity.viewModels
import androidx.core.content.ContextCompat.getString
import androidx.lifecycle.ViewModelProvider
import ch.fhnw.pepper_realtime.R
import ch.fhnw.pepper_realtime.manager.SettingsRepository
import ch.fhnw.pepper_realtime.manager.TurnManager
import ch.fhnw.pepper_realtime.ui.ChatActivity
import ch.fhnw.pepper_realtime.ui.settings.SettingsViewModel

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
    val settingsViewModel: SettingsViewModel by activity.viewModels()

    // Tools & App Settings
    val keyManager = activity.keyManager
    val toolRegistry = activity._toolRegistry
    val toolContext = activity.toolContext
    val settingsRepository: SettingsRepository = activity._settingsRepository

    fun restartSession() {
        activity.runOnUiThread {
            viewModel.startNewSession()
            settingsViewModel.consumeRestartSessionEvent()
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
    fun toggleMicrophone(userWantsMicOn: Boolean) {
        try {

            if (activity.viewModel.userWantsMicOn.value != userWantsMicOn) {
                activity.viewModel.setUserWantsMicOn(userWantsMicOn)
            }

            val currentState = activity.chatDeps.turnManager.state

            // If currently LISTENING, apply immediately
            if (currentState == TurnManager.State.LISTENING) {
                if (userWantsMicOn) {
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