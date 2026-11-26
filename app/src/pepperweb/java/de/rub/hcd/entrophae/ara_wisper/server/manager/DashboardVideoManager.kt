package de.rub.hcd.entrophae.ara_wisper.server.manager

import android.util.Log
import de.rub.hcd.entrophae.ara_wisper.manager.AraWisperManager
import io.getstream.webrtc.android.ktx.createSessionDescription
import io.getstream.webrtc.android.ktx.suspendSdpObserver
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.webrtc.*

/**
 * WebRTC camera capture and peer-connection manager for live dashboard video streaming.
 *
 * Captures frames from the device/robot camera, handles SDP offer/answer and ICE
 * candidate signaling over the dashboard WebSocket, and restarts streams after vision snapshots.
 * Currently only device camera!
 */
class DashboardVideoManager(
    private val sendWebSocketMessage: suspend (String) -> Unit
) {

    companion object {
        private const val TAG = "WebRTCManager"
        private const val VIDEO_TRACK_ID = "100"
        private const val AUDIO_TRACK_ID = "101"
        private const val MEDIA_STREAM_ID = "mediaStream"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null

    private var eglBase: EglBase? = null
    private var videoCapturer: VideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null

    private val iceMutex = Mutex()
    private val pendingIceCandidates = mutableListOf<IceCandidate>()
    private var remoteDescriptionSet = false

    init {
        initializePeerConnectionFactory()
        createPeerConnection()
    }

    private fun initializePeerConnectionFactory() {

        val initOptions = PeerConnectionFactory.InitializationOptions
            .builder(AraWisperManager.appContext)
            .createInitializationOptions()

        PeerConnectionFactory.initialize(initOptions)

        eglBase = EglBase.create()
        surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase!!.eglBaseContext)

        val options = PeerConnectionFactory.Options()
        val defaultVideoEncoderFactory = DefaultVideoEncoderFactory(eglBase?.eglBaseContext,true, true)
        val defaultVideoDecoderFactory = DefaultVideoDecoderFactory(eglBase?.eglBaseContext)
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(options)
            .setVideoEncoderFactory(defaultVideoEncoderFactory)
            .setVideoDecoderFactory(defaultVideoDecoderFactory)
            .createPeerConnectionFactory()
    }

    private fun createPeerConnection() {

        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )

        peerConnection = peerConnectionFactory?.createPeerConnection(
            iceServers,
            object : SimplePeerConnectionObserver() {

                override fun onIceCandidate(candidate: IceCandidate?) {
                    candidate ?: return

                    val candidateJson = JSONObject().apply {
                        put("type", "webrtc-candidate")
                        put("candidate", JSONObject().apply {
                            put("sdpMid", candidate.sdpMid)
                            put("sdpMLineIndex", candidate.sdpMLineIndex)
                            put("candidate", candidate.sdp)
                        })
                    }

                    scope.launch {
                        try {
                            sendWebSocketMessage(candidateJson.toString())
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to send ICE candidate", e)
                        }
                    }
                }
            }
        )
    }

    fun startCameraAndStream() {
        scope.launch {
            try {
                val cameraDevices = Camera2Enumerator(AraWisperManager.appContext)
                val deviceNames = cameraDevices.deviceNames
                if (deviceNames.isEmpty()) {
                    Log.e(TAG, "No cameras found on device!")
                    return@launch
                }

                // Prefer front camera or fallback to back camera
                val cameraName = deviceNames.firstOrNull { cameraDevices.isFrontFacing(it) } ?: deviceNames.first()
                videoCapturer = cameraDevices.createCapturer(cameraName, null)
                if (videoCapturer == null) {
                    Log.e(TAG, "Failed to create camera capturer. Check camera permissions!")
                    return@launch
                }
                videoSource = peerConnectionFactory?.createVideoSource(videoCapturer!!.isScreencast)
                videoCapturer?.initialize(surfaceTextureHelper, AraWisperManager.appContext, videoSource?.capturerObserver)
                videoCapturer?.startCapture(640, 480, 30)
                localVideoTrack = peerConnectionFactory?.createVideoTrack(VIDEO_TRACK_ID, videoSource)
                if (localVideoTrack != null && peerConnection != null) {
                    peerConnection?.addTrack(localVideoTrack!!, listOf(MEDIA_STREAM_ID))
                }

                val audioSource = peerConnectionFactory?.createAudioSource(MediaConstraints())
                val localAudioTrack = peerConnectionFactory?.createAudioTrack(AUDIO_TRACK_ID, audioSource)
                if (localAudioTrack != null && peerConnection != null) {
                    peerConnection?.addTrack(localAudioTrack, listOf(MEDIA_STREAM_ID))
                }

                createAndSendOffer()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start camera and stream", e)
            }
        }
    }

    fun restartCameraStream() {
        scope.launch {
            try {
                val refreshJson = JSONObject().apply {
                    put("type", "webrtc-stream-refresh-needed")
                }
                sendWebSocketMessage(refreshJson.toString())
            } catch (e: Exception) {
                Log.e("WebRTCManager", "Failed to restart camera stream", e)
            }
        }
    }

    private fun createAndSendOffer() {
        scope.launch {
            try {
                val connection = peerConnection ?: return@launch

                val offer = createSessionDescription { observer ->
                    connection.createOffer(observer, MediaConstraints())
                }.getOrThrow()

                suspendSdpObserver { observer ->
                    connection.setLocalDescription(observer, offer)
                }.getOrThrow()

                val offerJson = JSONObject().apply {
                    put("type", "webrtc-offer")
                    put("sdp", offer.description)
                }

                sendWebSocketMessage(offerJson.toString())

            } catch (e: Exception) {
                Log.e(TAG, "Failed to create or send SDP offer", e)
            }
        }
    }

    fun handleIncomingSignaling(json: JSONObject) {
        when (json.optString("type")) {
            "webrtc-answer" -> {
                scope.launch {
                    try {
                        val connection = peerConnection ?: return@launch
                        val answer = SessionDescription(
                            SessionDescription.Type.ANSWER,
                            json.getString("sdp")
                        )

                        suspendSdpObserver { observer ->
                            connection.setRemoteDescription(observer, answer)
                        }.getOrThrow()

                        iceMutex.withLock {
                            remoteDescriptionSet = true
                            pendingIceCandidates.forEach { candidate ->
                                connection.addIceCandidate(candidate)
                            }
                            pendingIceCandidates.clear()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to apply remote SDP answer", e)
                    }
                }
            }
            "webrtc-candidate" -> {
                scope.launch {
                    try {
                        val candidateObject = json.getJSONObject("candidate")
                        val candidate = IceCandidate(
                            candidateObject.getString("sdpMid"),
                            candidateObject.getInt("sdpMLineIndex"),
                            candidateObject.getString("candidate")
                        )

                        iceMutex.withLock {
                            if (remoteDescriptionSet) {
                                peerConnection?.addIceCandidate(candidate)
                            } else {
                                pendingIceCandidates.add(candidate)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to process ICE candidate", e)
                    }
                }
            }
        }
    }

    fun stop() {
        scope.launch {
            try { videoCapturer?.stopCapture() } catch (_: Exception) {}
            videoCapturer?.dispose()
            localVideoTrack?.dispose()
            videoSource?.dispose()
            surfaceTextureHelper?.dispose()
            eglBase?.release()
            peerConnection?.close()
            peerConnectionFactory?.dispose()
            scope.cancel()
        }
    }
}

open class SimplePeerConnectionObserver : PeerConnection.Observer {
    override fun onSignalingChange(newState: PeerConnection.SignalingState?) {}
    override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {}
    override fun onIceConnectionReceivingChange(receiving: Boolean) {}
    override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) {}
    override fun onIceCandidate(candidate: IceCandidate?) {}
    override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
    override fun onAddStream(stream: MediaStream?) {}
    override fun onRemoveStream(stream: MediaStream?) {}
    override fun onDataChannel(dataChannel: DataChannel?) {}
    override fun onRenegotiationNeeded() {}
    override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {}
    override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {}
}