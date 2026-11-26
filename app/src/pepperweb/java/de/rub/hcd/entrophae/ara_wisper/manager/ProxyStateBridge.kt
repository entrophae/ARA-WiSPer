package de.rub.hcd.entrophae.ara_wisper.manager

import ch.fhnw.pepper_realtime.manager.TurnManager
import de.rub.hcd.entrophae.ara_wisper.core.ChatActivityAdapter
import de.rub.hcd.entrophae.ara_wisper.server.service.AudioProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

/**
 * Single outbound controller synchronizing the proxy pipeline with native host state
 * (`TurnManager`, `ChatViewModel`, `AudioInputController`) and the active WebSocket transport.
 *
 * Acts as the sole authority for mutating UI/turn states (`LISTENING`, `THINKING`, `SPEAKING`),
 * gates incoming microphone frames while the robot is busy or cooling down after speech,
 * and creates turn-scoped [TurnBridge] instances for outbound protocol events.
 */
object ProxyStateBridge {

    private val bridgeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var playbackObserverJob: Job? = null
    private var observedAdapterRef: WeakReference<ChatActivityAdapter>? = null

    @Volatile
    private var sessionScope: CoroutineScope? = null

    @Volatile
    private var packetSender: (suspend (String) -> Unit)? = null

    @Volatile
    private var activeTurnBridge: TurnBridge? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    private val audioSendDispatcher = Dispatchers.IO.limitedParallelism(1)

    fun attachSession(scope: CoroutineScope, sendToApp: suspend (String) -> Unit) {
        ensureWired()
        sessionScope = scope
        packetSender = sendToApp
    }

    fun detachSession() {
        activeTurnBridge?.cancelPendingAudio()
        activeTurnBridge = null
        packetSender = null
        sessionScope = null
    }

    fun startTurn(responseId: String, turnId: String): TurnBridge {
        ensureWired()
        activeTurnBridge?.cancelPendingAudio()
        AraWisperManager.speechManager.clearBuffer()
        return TurnBridge(
            responseId = responseId,
            turnId = turnId,
            sendPacket = ::sendPacket,
            launchAudioSend = ::launchOrderedAudio
        ).also {
            activeTurnBridge = it
        }
    }

    fun shouldDropIncomingAudio(isTurnBusy: Boolean, audioProcessor: AudioProcessor): Boolean {
        ensureWired()
        val adapter = AraWisperManager.chatActivityAdapter
        val turnManager = adapter?.turnManager
        val turnState = turnManager?.state
        val isAudioPlaying = adapter?.viewModel?.isAudioPlaying?.value == true
        val isSpeechBusy = AraWisperManager.speechManager.isSpeakingOrCoolingDown(isAudioPlaying)

        // Enforce THINKING lock if main's ChatRealtimeHandler drifted to LISTENING while a turn/tool is active
        if (isTurnBusy && turnState == TurnManager.State.LISTENING && !isAudioPlaying) {
            turnManager.setState(TurnManager.State.THINKING)
        }

        // Set LISTENING if TurnManager remained stuck in THINKING after pipeline/speech finished
        if (!isTurnBusy && !isAudioPlaying && !isSpeechBusy && turnState == TurnManager.State.THINKING) {
            turnManager.setState(TurnManager.State.LISTENING)
        }

        val currentTurnState = turnManager?.state
        val isRobotBusyState = currentTurnState == TurnManager.State.THINKING ||
                currentTurnState == TurnManager.State.SPEAKING

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
        ensureWired()
        AraWisperManager.speechManager.isTurnActive = true
        AraWisperManager.chatActivityAdapter?.let { adapter ->
            if (adapter.turnManager?.state != TurnManager.State.SPEAKING) {
                adapter.turnManager?.setState(TurnManager.State.THINKING)
            }
        }
    }

    fun keepTurnActiveForExternalTool() {
        ensureWired()
        AraWisperManager.speechManager.isTurnActive = true
        AraWisperManager.chatActivityAdapter?.let { adapter ->
            if (!adapter.viewModel.isAudioPlaying.value && adapter.turnManager?.state == TurnManager.State.LISTENING) {
                adapter.turnManager?.setState(TurnManager.State.THINKING)
            }
        }
    }

    fun markListening(stopSpeech: Boolean = false, clearCooldown: Boolean = false, forceResetUi: Boolean = false) {
        ensureWired()
        val speechManager = AraWisperManager.speechManager
        speechManager.isTurnActive = false

        if (stopSpeech) {
            activeTurnBridge?.cancelPendingAudio()
            speechManager.stop(clearCooldown = clearCooldown)
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
            val isSpeechBusy = speechManager.isSpeakingOrCoolingDown(isActivelyPlaying)
            if (!isActivelyPlaying && !isSpeakingState && !isSpeechBusy) {
                adapter.viewModel.setResponseGenerating(false)
                adapter.viewModel.setAudioPlaying(false)
                adapter.turnManager?.setState(TurnManager.State.LISTENING)
            }
        }
    }

    private fun ensureWired() {
        val speechManager = AraWisperManager.speechManager
        if (speechManager.onDirectSpeakingChanged == null) {
            speechManager.onDirectSpeakingChanged = { speaking ->
                handleDirectSpeechChanged(speaking)
            }
        }

        val adapter = AraWisperManager.chatActivityAdapter ?: return
        if (observedAdapterRef?.get() === adapter && playbackObserverJob?.isActive == true) return

        observedAdapterRef = WeakReference(adapter)
        playbackObserverJob?.cancel()
        playbackObserverJob = bridgeScope.launch {
            var wasPlaying = false
            adapter.viewModel.isAudioPlaying.collect { playing ->
                if (playing) {
                    wasPlaying = true
                } else if (wasPlaying) {
                    wasPlaying = false
                    speechManager.notifyClientPlaybackEnded()
                    if (speechManager.isTurnActive &&
                        adapter.turnManager?.state == TurnManager.State.LISTENING
                    ) {
                        adapter.turnManager?.setState(TurnManager.State.THINKING)
                    }
                }
            }
        }
    }

    private fun handleDirectSpeechChanged(speaking: Boolean) {
        val adapter = AraWisperManager.chatActivityAdapter ?: return
        adapter.viewModel.setAudioPlaying(speaking)

        val turnManager = adapter.turnManager ?: return
        if (speaking) {
            turnManager.setState(TurnManager.State.SPEAKING)
        } else if (turnManager.state == TurnManager.State.SPEAKING) {
            val nextState =
                if (AraWisperManager.speechManager.isTurnActive) TurnManager.State.THINKING else TurnManager.State.LISTENING
            turnManager.setState(nextState)
        }
    }

    private suspend fun sendPacket(packet: String) {
        packetSender?.invoke(packet)
    }

    private fun launchOrderedAudio(block: suspend () -> Unit): Job? {
        return sessionScope?.launch(audioSendDispatcher) {
            block()
        }
    }
}