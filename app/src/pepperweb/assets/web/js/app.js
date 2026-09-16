const { createApp } = Vue;

const app = createApp({
    data() {
        return {
            constants: {},
            host: document.domain,
            isConnected: false,
            isMicActive: false,
            micTimeout: null,
            showSettings: false,
            showUserEdit: false,
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
                CUSTOM_LLM_MODEL: '', 
                CUSTOM_STT_ENDPOINT: '', CUSTOM_STT_KEY: '',
                CUSTOM_VAD_MODEL: '',
                SYSTEM_AUDIO_MODE: '', SYSTEM_ROUTING_MODE: '',
                PERSON_DB_API_ENDPOINT: '', PERSON_DB_KEY: ''
            },
            activeUserId: localStorage.getItem('ara_active_user') || 'default_user',
            users: [],
            selectedUser: null,
            userChatHistory: [],
            userSummary: ''
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
                        if (!this.apiData.SYSTEM_AUDIO_MODE) {
                            this.apiData.SYSTEM_AUDIO_MODE = this.constants.AUDIO_CUSTOM;
                        }
                        if (!this.apiData.CUSTOM_VAD_MODEL) {
                            this.apiData.CUSTOM_VAD_MODEL = this.constants.VAD_WEBRTC;
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
                    else if (payload.type === this.constants.MSG_MIC_ACTIVITY) {
                        this.isMicActive = true;

                        clearTimeout(this.micTimeout);

                        this.micTimeout = setTimeout(() => {
                            this.isMicActive = false;
                        }, 300);

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
                    else if (payload.type ===  "sync_local_storage") {

                        if (payload.userId === this.activeUserId) {
                            this.userChatHistory = payload.history;
                            // Rebuild the visual terminal logs from the fresh history
                            this.terminalLogs = this.terminalLogs.filter(log => log.type !== 'chat-bubble');
                            this.userChatHistory.forEach(turn => {
                                if (turn.userQuery) {
                                    this.terminalLogs.push({ id: turn.turnId + '_u', text: turn.userQuery, type: 'chat-bubble', sender: 'USER' });
                                }
                                if (turn.toolBufferResponse) {
                                    this.terminalLogs.push({ id: turn.turnId + '_b', text: turn.toolBufferResponse, type: 'chat-bubble', sender: 'ROBOT' });
                                }
                                if (turn.llmResponse) {
                                    this.terminalLogs.push({ id: turn.turnId + '_r', text: turn.llmResponse, type: 'chat-bubble', sender: 'ROBOT' });
                                }
                            });

                            console.log(`Synced ${payload.history.length} turns for ${payload.userId}`);
                        }
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
        },
        setUserId() {
            if (!this.activeUserId) return;
            if (!this.isConnected || !this.socket) {
                this.logMessage('Cannot set user, disconnected.', 'log-error');
                return;
            }

            this.socket.send(JSON.stringify({
                type: this.constants.MSG_SET_USER,
                userId: this.activeUserId
            }));

            this.logMessage('Active user set to: ' + this.activeUserId, 'log-system');
        },
        promptNewUser() {
            const newUser = prompt("Enter the ID for the new user:");
            if (newUser && newUser.trim() !== "") {
                const cleanId = newUser.trim();

                // Add to list if it doesn't exist
                if (!this.users.includes(cleanId)) {
                    this.users.push(cleanId);
                }

                // Automatically select the new user
                this.selectUser(cleanId);
            }
        },
        async fetchUsers() {
            try {
                const response = await fetch(this.constants.ROUTE_RAG_USERS);
                const fetchedUsers = await response.json();

                if (!fetchedUsers.includes('default_user')) {
                    fetchedUsers.push('default_user');
                }

                this.users = fetchedUsers;
            } catch (error) {
                this.logMessage('Failed to scan database for users.', 'log-error');
                this.users = ['default_user'];
            }
        },
        async loadHistory(userId) {
            try {
                const response = await fetch(`${this.constants.ROUTE_RAG_USERS}?userId=${userId}`);
                const data = await response.json();

                this.userChatHistory = data.history || [];
                this.userSummary = data.chatSummary || '';

                if (this.userChatHistory.length > 0) {
                    console.log(`Loaded ${this.userChatHistory.length} turns from database for ${userId}`);
                } else {
                    console.log(`No history found for ${userId}, starting fresh.`);
                }
            } catch (error) {
                this.logMessage(`Failed to load history for ${userId} from database.`, 'log-error');
            }
        },
        async selectUser(userId) {
            if (!userId || userId.trim() === "") return;
            this.selectedUser = userId;
            this.activeUserId = userId;
            localStorage.setItem('ara_active_user', userId);

            if (!this.users.includes(userId)) {
                this.users.push(userId);
            }

            this.terminalLogs = [];
            this.proxyLogs = [];

            await this.loadHistory(userId);
            this.setUserId()
        },
        async saveUserEdit(payload) {
            this.userSummary = payload.summary;
            this.userChatHistory = payload.history;

            const requestPayload = {
                type: this.constants.MSG_UPDATE_USER_KNOWLEDGE,
                userId: this.selectedUser,
                summary: this.userSummary,
                updatedTurns: payload.modifiedTurns,
                deletedTurnIds: payload.deletedTurnIds
            };

            this.socket.send(JSON.stringify(requestPayload));

            this.logMessage(`Saved knowledge for ${this.selectedUser}.`, 'log-system');

            this.showUserEdit = false;
        },
        async deleteUser() {
            if (!this.selectedUser || this.selectedUser === 'default_user') {
                this.logMessage('Cannot delete the default user.', 'log-error');
                return;
            }
            this.socket.send(JSON.stringify({
                type: this.constants.MSG_DELETE_USER,
                userId: this.selectedUser
            }));

            this.users = this.users.filter(u => u !== this.selectedUser);
            this.selectUser('default_user');
            this.logMessage('User deleted.', 'log-system');
        },
        async clearHistory() {
            this.socket.send(JSON.stringify({
                type: this.constants.MSG_CLEAR_HISTORY,
                userId: this.selectedUser
            }));

            // clear local ui instantly
            this.terminalLogs = this.terminalLogs.filter(log => log.type !== 'chat-bubble');
            this.userChatHistory = [];
            this.logMessage('History cleared.', 'log-system');
        },
    },
    async mounted() {
        await this.fetchConstants();
        await this.fetchUsers();
        await this.selectUser(this.activeUserId);

        this.connectWebSocket();
    }
});

app.component('settings-modal', SettingsModal);
app.component('user-knowledge-modal', UserKnowledgeModal);

app.mount('#app');
