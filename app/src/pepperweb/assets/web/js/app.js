const { createApp } = Vue;

const app = createApp({
    data() {
        return {
            constants: {},
            host: document.domain,
            isConnected: false,
            showSettings: false,
            commandInput: '',
            apiMode: '',
            apiSchema: {},
            proxyLogs: [],
            terminalLogs: [],
            toasts: [],

            socket: null,
            apiData: {
                OPENAI_API_KEY: '', AZURE_OPENAI_KEY: '',
                AZURE_OPENAI_ENDPOINT: '',
                XAI_API_KEY: '', GOOGLE_API_KEY: '', AZURE_SPEECH_KEY: '',
                AZURE_SPEECH_REGION: '', GROQ_API_KEY: '', TAVILY_API_KEY: '',
                OPENWEATHER_API_KEY: '', YOUTUBE_API_KEY: '',
                CUSTOM_LLM_ENDPOINT: '', CUSTOM_LLM_KEY: '',
                CUSTOM_LLM_MODEL: '', SYSTEM_ROUTING_MODE: ''
            },
        };
    },
    methods: {
        logMessage(text, type = 'log-system', id = null) {
            const toastId = id || ('toast-' + Date.now() + Math.random());

            const lastToast = this.toasts[this.toasts.length - 1];
            if (lastToast && lastToast.text === text) {
                return;
            }

            this.toasts.push({
                id: toastId,
                text: text,
                type: type
            });

            setTimeout(() => {
                this.toasts = this.toasts.filter(t => t.id !== toastId);
            }, 3500);
        },

        async fetchConstants() {
            try {
                const constantsResponse = await fetch('/constants');
                if (constantsResponse.ok) {
                    this.constants = await constantsResponse.json();
                    console.log("Constants loaded successfully from Kotlin backend");
                    this.apiMode = this.constants.MODE_CUSTOM;
                } else {
                    this.logMessage('Failed to load constants from server.', 'log-error');
                }
            } catch (error) {
                console.error("Network error loading constants:", error);
                this.logMessage('Network error loading constants.', 'log-error');
            }
        },

        connectWebSocket() {
            let wsUrl;
            if (window.location.protocol === 'file:') {
                wsUrl = 'ws://127.0.0.1:8080' + this.constants.ROUTE_WS;
            } else {
                const wsProtocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
                wsUrl = wsProtocol + '//' + window.location.host + this.constants.ROUTE_WS;
            }
            this.socket = new WebSocket(wsUrl);

            this.socket.onopen = () => {
                this.isConnected = true;
                this.logMessage('Connected to AraWisper Server on ' + (window.location.host || '127.0.0.1:8080'), 'log-system');
                this.setUserId();
            };

            this.socket.onclose = () => {
                this.isConnected = false;
                this.logMessage('Connection lost. Reconnecting in 3s...', 'log-error');
                setTimeout(() => this.connectWebSocket(), 3000);
            };

            this.socket.onmessage = (event) => {
                try {
                    const payload = JSON.parse(event.data);
                    let logMessage = "";
                    let logType = "";
                    let logId = null;

                    if (payload.type === this.constants.MSG_LOAD_API_KEYS) {
                        this.apiData = { ...this.apiData, ...payload.data };
                        this.apiSchema = payload.schema;
                        if (!this.apiData.SYSTEM_ROUTING_MODE) {
                            this.apiData.SYSTEM_ROUTING_MODE = this.constants.MODE_CUSTOM;
                        }
                        logMessage = 'Loaded configuration from server.';
                        logType = 'log-system';

                        this.logMessage(logMessage, logType, logId);
                    }
                    else if (payload.type === this.constants.MSG_CHAT_UPDATE) {
                        payload.data.forEach(msg => {
                            if (!msg.message || msg.message.trim() === "") return;

                            const existingLog = this.terminalLogs.find(log => log.id === msg.uuid);
                            const prefix = msg.sender === "ROBOT" ? "🤖 Robot: " : "👤 You: ";
                            const logType = "chat-bubble";

                            if (existingLog) {
                                existingLog.text = msg.message;
                                existingLog.sender = msg.sender;
                            } else {
                                this.terminalLogs.push({
                                    id: msg.uuid,
                                    text: msg.message,
                                    type: logType,
                                    sender: msg.sender
                                });
                            }
                        });
                        return;
                    }
                    else if (payload.type === this.constants.MSG_PROXY_WS_LOG) {
                        const direction = payload.direction;
                        const wsData = payload.payload;
                        const wsPayloadType = wsData.type;
                        let color = "yellow";
                        let label = wsPayloadType;

                        if (["session.created", "SAVE_API_KEYS", "response.audio_transcript.delta", "response.created", "response.done"].includes(wsPayloadType)) {
                            color = "green";
                        }
                        else if (["CHAT_COMMAND", "conversation.item.create", "response.create"].includes(wsPayloadType)) {
                            color = "yellow";
                        }
                        else if (direction === "⚠️ ERROR" || wsPayloadType === "error") {
                            color = "red";
                        }
                        this.proxyLogs.push({
                            id: Date.now() + Math.random(),
                            color: color,
                            label: wsPayloadType,
                            rawJson: direction + "\n" + JSON.stringify(wsData, null, 2)
                        });

                        if (this.proxyLogs.length > 10) {
                            this.proxyLogs.shift();
                        }
                        return
                    }
                } catch (e) {
                    console.error("Failed to parse message", e);
                }
            };
        },

        sendCommand() {
            const text = this.commandInput.trim();
            if (!text) {
                this.logMessage('Cannot send empty command.', 'log-error');
                return;
            }
            if (!this.isConnected || !this.socket) {
                this.logMessage('Cannot send, robot is disconnected.', 'log-error');
                return;
            }

            const payload = {
                type: this.constants.MSG_CHAT_COMMAND,
                apiMode: this.apiMode,
                text: text
            };

            this.socket.send(JSON.stringify(payload));
            this.commandInput = '';
        },
        saveConfig(updatedApiData) {
            this.apiData = { ...updatedApiData };

            if (this.socket && this.isConnected) {
                this.socket.send(JSON.stringify({
                    type: this.constants.MSG_SAVE_API_KEYS,
                    data: this.apiData
                }));
                this.logMessage('Saved new API configuration.', 'log-system');
                this.showSettings = false;
            } else {
                this.logMessage('ERROR: Cannot save settings. Robot is disconnected!', 'log-error');
            }
        }
    },
    async mounted() {
        await this.fetchConstants();
        this.connectWebSocket();
    }
});

app.component('settings-modal', SettingsModal);

app.mount('#app');
