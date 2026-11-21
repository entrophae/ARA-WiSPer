const VideoStream = {
    template: `
        <div class="video-stream-container">
            <video
                ref="videoElement"
                autoplay
                playsinline
                muted
            ></video>

            <div v-if="!isConnected" class="video-overlay">
                <span class="status-dot"></span>
                {{ statusText }}
            </div>

            <div class="stream-buttons absolute-bottom">
                <button class="toggle-button" @click="forceStreamRefresh" title="Restart Stream">↻</button>
                <button class="toggle-button" ref="muteButton" @click="muteToggle" title="Toggle Mute">🔈</button>
            </div>
            <div class="stream-buttons absolute-top">
                <button class="toggle-button" ref="pipButton" @click="togglePIP" title="PictureInPicture">◲</button>
                <button class="toggle-button" @click="store.toggleVideoStream()" title="Toggle Cam">❌</button>
            </div>
        </div>
    `,

    data() {
        return {
            store: appStore,
            peerConnection: null,
            isConnected: false,
            statusText: 'Waiting for stream...',
            pendingIceCandidates: [],
            remoteDescriptionSet: false,
            streamRequested: false
        };
    },
    mounted() {
        if (this.store.isConnected && !this.streamRequested) {
            this.initWebRTC();
            this.requestStream();
        }
    },
    watch: {
        'store.isConnected'(connected) {
            if (connected && !this.streamRequested) {
                this.initWebRTC();
                this.requestStream();
            }
        }
    },

    beforeUnmount() {
        this.endStream();
    },

    methods: {
        requestStream() {
            const socket = this.store.socket;

            if (!socket || socket.readyState !== WebSocket.OPEN) {
                console.warn('[WebRTC] WebSocket is not ready');
                return;
            }

            console.log('[WebRTC] Requesting fresh video stream...');
            this.statusText = 'Requesting stream...';

            socket.send(JSON.stringify({
                type: 'webrtc-request-stream'
            }));

            this.streamRequested = true;
        },

        endStream() {
            const socket = this.store.socket;

            if (!socket || socket.readyState !== WebSocket.OPEN) {
                console.warn('[WebRTC] WebSocket is not ready');
                return;
            }

            console.log('[WebRTC] Ending video stream...');
            this.statusText = 'Ending stream...';

            socket.send(JSON.stringify({
                type: 'webrtc-end-stream'
            }));

            this.closeConnection();
        },

        forceStreamRefresh() {
            console.log('[WebRTC] Manually forcing stream re-init...');

            // Clean up old peer connection if stuck
            this.closeConnection();

            // Re-initialize and request a brand new offer from Android
            this.initWebRTC();
            this.requestStream();
        },

        muteToggle() {
            const video = this.$refs.videoElement;
            const muteButton = this.$refs.muteButton;
            muteButton.textContent = video.muted ? "🔊" : "🔈";
            video.muted = !video.muted;
        },

        togglePIP() {
            const pipButton = this.$refs.pipButton;
            if (document.pictureInPictureElement!=null) {
                document.exitPictureInPicture();
                pipButton.textContent = "◲";
            } else {
                const video = this.$refs.videoElement;
                video.requestPictureInPicture();
                pipButton.textContent = "◰";
            }
        },

        closeConnection() {
            window.removeEventListener('webrtc-receive', this.handleIncomingSignaling);
            if (this.peerConnection) {
                this.peerConnection.close();
                this.peerConnection = null;
            }
            this.isConnected = false;
            this.statusText = 'Closing stream...';
            this.pendingIceCandidates = [];
            this.remoteDescriptionSet = false;
            this.streamRequested = false;
        },

        initWebRTC() {
            window.addEventListener('webrtc-receive', this.handleIncomingSignaling)

            this.peerConnection = new RTCPeerConnection({
                iceServers: [{ urls: 'stun:stun.l.google.com:19302' }]
            });

            this.peerConnection.ontrack = (event) => {
                const video = this.$refs.videoElement;

                if (video) {
                    const newStream = event.streams[0] ?? new MediaStream([event.track]);
                    if (video.srcObject !== newStream) {
                        video.srcObject = newStream;
                        video.play().catch(error => {
                            // Ignore DOM aborts caused by rapid source switching
                            if (error.name !== 'AbortError') {
                                console.warn('[WebRTC] Video autoplay failed:', error);
                            }
                        });
                    }

                    this.isConnected = true;
                }
            };

            this.peerConnection.oniceconnectionstatechange = () => {
                const state = this.peerConnection.iceConnectionState;
                console.log('[WebRTC] ICE state:', state);

                if (state === 'disconnected' || state === 'failed' || state === 'closed') {
                    this.isConnected = false;
                    this.statusText = 'Stream disconnected';
                } else if (state === 'connected' || state === 'completed') {
                    this.isConnected = true;
                }
            };

            this.peerConnection.onicecandidate = (event) => {
                if (event.candidate && this.store.socket?.readyState === WebSocket.OPEN) {
                    this.store.socket.send(JSON.stringify({
                        type: 'webrtc-candidate',
                        candidate: event.candidate.toJSON()
                    }));
                }
            };
        },

        async handleIncomingSignaling(event) {
            const data = event.detail;

            try {
                if (data.type === 'webrtc-stream-refresh-needed') {
                    console.log('[WebRTC] Requested stream refresh.');
                    this.forceStreamRefresh();
                    return;
                }
                if (data.type === 'webrtc-offer') {
                    this.remoteDescriptionSet = false;

                    await this.peerConnection.setRemoteDescription(
                        new RTCSessionDescription({ type: 'offer', sdp: data.sdp })
                    );

                    this.remoteDescriptionSet = true;

                    for (const candidate of this.pendingIceCandidates) {
                        await this.peerConnection.addIceCandidate(new RTCIceCandidate(candidate));
                    }
                    this.pendingIceCandidates = [];

                    const answer = await this.peerConnection.createAnswer();
                    await this.peerConnection.setLocalDescription(answer);

                    if (this.store.socket?.readyState === WebSocket.OPEN) {
                        this.store.socket.send(JSON.stringify({
                            type: 'webrtc-answer',
                            sdp: this.peerConnection.localDescription.sdp
                        }));
                    }

                } else if (data.type === 'webrtc-candidate') {
                    if (!this.remoteDescriptionSet) {
                        this.pendingIceCandidates.push(data.candidate);
                        return;
                    }

                    await this.peerConnection.addIceCandidate(new RTCIceCandidate(data.candidate));
                }
            } catch (error) {
                console.error('[WebRTC] Signaling error:', error);
            }
        }
    }
};