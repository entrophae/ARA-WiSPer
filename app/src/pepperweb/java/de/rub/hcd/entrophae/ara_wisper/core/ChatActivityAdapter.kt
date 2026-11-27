package de.rub.hcd.entrophae.ara_wisper.core

import android.util.Log
import androidx.activity.viewModels
import ch.fhnw.pepper_realtime.manager.TurnManager
import ch.fhnw.pepper_realtime.ui.ChatActivity
import ch.fhnw.pepper_realtime.ui.settings.SettingsViewModel
import de.rub.hcd.entrophae.ara_wisper.manager.AraWisperManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Lifecycle-safe facade wrapping the host `ChatActivity` instance.
 *
 * Exposes `ChatViewModel`, `TurnManager`, `ToolContext`, and `AudioInputController` to
 * the `ara_wisper` module while providing clean helpers for microphone toggling,
 * speech interruption, and session restarts.
 */
class ChatActivityAdapter(activity: ChatActivity) {

    private val TAG = "ARA WISPER CHATACTIVITY ADAPTER"
    private val activityRef = java.lang.ref.WeakReference(activity)

    fun isFor(target: ChatActivity): Boolean = activityRef.get() === target

    private val activity: ChatActivity?
        get() = activityRef.get()


    // Feature Dependencies - group related dependencies
    val chatDeps = activity.chatDeps
    val robotDeps = activity.robotDeps
    val navigationDeps = activity.navigationDeps

    // Cross-cutting dependencies that remain directly injected
    val keyManager = activity.keyManager
    val permissionManager = activity.permissionManager
    val sessionImageManager = activity.getSessionImageManager()
    val settingsRepository = activity._settingsRepository
    val toolRegistry = activity._toolRegistry
    val toolContextFactory = activity.toolContextFactory
    val lifecycleController = activity.lifecycleController
    val videoInputController = activity.videoInputController
    val ioDispatcher = activity.ioDispatcher
    val applicationScope = activity.applicationScope

    // Controllers & Managers (Initialized in onCreate)
    val toolContext get() = activity?.toolContext
    val volumeController get() = activity?.volumeController

    // ViewModels
    val viewModel = activity.viewModel
    val settingsViewModel: SettingsViewModel by activity.viewModels()

    // Pepper Hardware & QiSDK Context & AppContext
    val robotController get() = activity?.getRobotController()
    val qiContext get() = activity?.getQiContext()
    val appContext get() = activity?.getAppContext()

    // Public accessors via modules
    val locationProvider get() = activity?.locationProvider

    // Settings accessors
    val volume get() = activity?.getVolume()
    val isUsingRealtimeAudioInput get() = activity?.isUsingRealtimeAudioInput()
    val model get() = activity?.getModel()
    val sessionManager get() = activity?.sessionManager              // RealtimeSessionManager
    val gestureController get() = activity?.gestureController
    val audioPlayer get() = activity?.audioPlayer
    val perceptionService get() = activity?.perceptionService
    val visionService get() = activity?.visionService
    val touchSensorManager get() = activity?.touchSensorManager
    val navigationServiceManager get() = activity?.navigationServiceManager
    val sessionController get() = activity?.sessionController        // ChatSessionController
    val audioInputController get() = activity?.audioInputController
    val turnManager get() = activity?.turnManager                    // TurnManager (State.LISTENING, SPEAKING, etc.)
    val robotFocusManager get() = activity?.robotFocusManager

    fun restartSession() {
        activity?.runOnUiThread {
            applicationScope.launch(ioDispatcher) {
                val muteState = audioInputController?.isMuted ?: false
                toggleMicrophone(false)
                viewModel.startNewSession()
                delay(900)
                toggleMicrophone(!muteState)
            }
        }
    }

    // Adapted version of "onStatusCapsuleClick/onInterrupt" for website
    fun interruptSpeech() {
        try {
            val currentState = chatDeps.turnManager.state
            AraWisperManager.speechManager.stop()
            chatDeps.interruptController.interruptSpeech()
            chatDeps.turnManager.setState(TurnManager.State.LISTENING)
            Log.i(TAG, "Speech interrupted in state $currentState")
        } catch (e: Exception) {
            Log.e(TAG, "Status capsule click failed", e)
        }
    }

    // Adapted version of "onVideoToggle" for website
    fun toggleVideoStream() {
        try {
            val newState = videoInputController.toggleStreaming()
            viewModel.setVideoStreamActive(newState)
            Log.i(TAG, "Video streaming toggled: active=$newState")
        } catch (e: Exception) {
            Log.e(TAG, "Video toggle failed", e)
        }
    }

    // Adapted version of "onMicrophoneToggle" for website
    fun toggleMicrophone(userWantsMicOn: Boolean) {
        try {

            if (viewModel.userWantsMicOn.value != userWantsMicOn) {
                viewModel.setUserWantsMicOn(userWantsMicOn)
            }

            val currentState = chatDeps.turnManager.state

            // If currently LISTENING, apply immediately
            if (currentState == TurnManager.State.LISTENING) {
                if (userWantsMicOn) {
                    activity?.unmuteMicrophone()
                } else {
                    activity?.muteMicrophone()
                }
            }
            // Otherwise, intent is stored and applied on state transition to LISTENING
        } catch (e: Exception) {
            Log.e(TAG, "Microphone toggle failed", e)
        }
    }

    // Adapted version of "shutdown" for website
    fun shutDown() {
        chatDeps.audioInputController.shutdown()
        videoInputController.shutdown()

        chatDeps.audioPlayer.setListener(null)
        chatDeps.sessionManager.listener = null
        toolContext?.updateQiContext(null)

        robotDeps.perceptionService.shutdown()
        viewModel.resetDashboard()
        robotDeps.touchSensorManager.shutdown()
        navigationDeps.navigationServiceManager.shutdown()

        viewModel.disconnectWebSocket()
        chatDeps.audioPlayer.release()
        try {
            robotDeps.gestureController.shutdown()
        } catch (_: Exception) {
        }

        robotDeps.robotFocusManager.unregister()

        viewModel.setSessionController(null)
    }

    fun updateNavigationStatus(mapStatus: String, localizationStatus: String) {
        // Update localization status, then refresh all map data
        viewModel.setLocalizationStatus(localizationStatus)
        activity?.updateMapPreview()
    }
    fun muteMicrophone() = activity?.muteMicrophone()
    fun unmuteMicrophone() = activity?.unmuteMicrophone()

    fun addImageMessage(imagePath: String) {
        viewModel.addImageMessage(imagePath)
    }

}