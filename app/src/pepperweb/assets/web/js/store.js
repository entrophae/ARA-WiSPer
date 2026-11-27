const { reactive } = Vue;

const appStore = reactive({
    constants: {},
    host: window.location.hostname,
    port: window.location.port || null,
    isConnected: false,
    isLLMMode: false,
    isSTTConnected: false,
    isDBConnected: false,
    isRAGConnected: false,
    isLLMConfigured: false,
    isELMConfigured: false,
    isVLMConfigure: false,
    isVideoStream: false,
    isMicActive: false,
    isMuted: false,
    micTimeout: null,
    apiMode: '',
    apiSchema: {},
    proxyLogs: [],
    terminalLogs: [],
    toasts: [],
    socket: null,
    activeUserId: localStorage.getItem('ara_active_user') || 'default_user',
    users: [],
    experiments: [],
    selectedUser: null,
    userChatHistory: [],
    userSummary: '',

    // Default API keys structure
    apiData: {
        OPENAI_API_KEY: '', AZURE_OPENAI_KEY: '', AZURE_OPENAI_ENDPOINT: '',
        XAI_API_KEY: '', GOOGLE_API_KEY: '', AZURE_SPEECH_KEY: '',
        AZURE_SPEECH_REGION: '', GROQ_API_KEY: '', TAVILY_API_KEY: '',
        OPENWEATHER_API_KEY: '', YOUTUBE_API_KEY: '', CUSTOM_LLM_ENDPOINT: '',
        CUSTOM_LLM_KEY: '', CUSTOM_LLM_CONFIG: '{}', CUSTOM_VLM_ENDPOINT: '',
        CUSTOM_VLM_KEY: '', CUSTOM_VLM_CONFIG: '{}', CUSTOM_EMBEDDING_ENDPOINT: '',
        CUSTOM_EMBEDDING_KEY: '', CUSTOM_EMBEDDING_CONFIG: '{}', CUSTOM_STT_ENDPOINT: '',
        CUSTOM_STT_KEY: '', CUSTOM_VAD_MODEL: '', SYSTEM_AUDIO_MODE: '',
        SYSTEM_ROUTING_MODE: '', PERSON_DB_API_ENDPOINT: '', PERSON_DB_KEY: '',
        FACT_DB_API_ENDPOINT: '', FACT_DB_KEY: '', AUTO_SUMMARY_INTERVAL: '25'
    },

    // --- ACTIONS: UI UTILS ---
    async saveApiConfig(updatedApiData) {
        this.apiData = { ...updatedApiData };
        const success = ApiService.sendSocketMessage(this.socket, {
            type: this.constants.MSG_SAVE_API_KEYS,
            data: this.apiData
        });
        if (success) {
            this.logMessage('Saved new API configuration.', 'log-system');
        } else {
            this.logMessage('ERROR: Cannot save settings. Robot is disconnected!', 'log-error');
        }
    },
    toggleLLMMode() {
        const success = ApiService.sendSocketMessage(this.socket, {
            type: this.constants.MSG_TOGGLE_LLM_MODE,
            data: true
        });
        if (success) {
            this.isLLMMode = !this.isLLMMode
        }
    },
    toggleVideoStream() {
        this.isVideoStream = !this.isVideoStream;
    },
    restart() {
        const success = ApiService.sendSocketMessage(this.socket, {
            type: this.constants.MSG_RESTART,
            data: true
        });
        if (success) {
            this.logMessage('Restarting connection.', 'log-system');
        } else {
            this.logMessage('ERROR: Cannot restart connection. Robot is disconnected!', 'log-error');
        }
    },
    interrupt() {
        const success = ApiService.sendSocketMessage(this.socket, {
            type: this.constants.MSG_INTERRUPT,
            data: true
        });
        if (success) {
            this.logMessage('Interrupting speech.', 'log-system');
        } else {
            this.logMessage('ERROR: Cannot interrupt speech. Robot is disconnected!', 'log-error');
        }
    },

    logMessage(text, type = 'log-system', id = null) {
        const toastId = id || ('toast-' + Date.now() + Math.random());
        const lastToast = this.toasts[this.toasts.length - 1];
        if (lastToast && lastToast.text === text) return;

        this.toasts.push({ id: toastId, text: text, type: type });
        setTimeout(() => {
            this.toasts = this.toasts.filter(t => t.id !== toastId);
        }, 3500);
    },

    async initializeStore() {
        try {
            this.constants = await ApiService.getConstants();
            console.log("Constants loaded successfully");
            this.apiMode = this.constants.MODE_CUSTOM;
        } catch (error) {
            this.logMessage('Failed to load constants.', 'log-error');
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
            this.logMessage('Connected to AraWisper Server', 'log-system');
            this.setUserIdOverSocket();
        };

        this.socket.onclose = () => {
            this.isConnected = false;
            this.logMessage('Connection lost. Reconnecting in 3s...', 'log-error');
            setTimeout(() => this.connectWebSocket(), 3000);
        };

        this.socket.onmessage = (event) => {
            try {
                const payload = JSON.parse(event.data);

                if (payload.type === this.constants.MSG_LOAD_API_KEYS) {
                    this.apiData = { ...this.apiData, ...payload.data };
                    this.apiSchema = payload.schema;
                    this.logMessage('Loaded configuration from server.', 'log-system');
                }
                else if (payload.type === this.constants.MSG_CHAT_UPDATE) {
                    payload.data.forEach(msg => {
                        const imageUrl = msg.userImageUrl || null;
                        if ((!msg.message || msg.message.trim() === "") && !imageUrl) return;

                        const existingLog = this.terminalLogs.find(log =>
                            log.id === msg.uuid || (log.type === 'chat-bubble' && log.sender === msg.sender && log.text === msg.message)
                        );
                        if (existingLog) {
                            if (msg.message) {
                                existingLog.text = msg.message;
                            }
                            if (imageUrl) {
                                existingLog.userImageUrl = imageUrl;
                            }
                        } else {
                            this.terminalLogs.push({
                                id: msg.uuid,
                                text: msg.message,
                                type: 'chat-bubble',
                                sender: msg.sender,
                                userImageUrl: imageUrl
                            });
                        }
                    });
                }
                else if (payload.type === this.constants.MSG_MIC_ACTIVITY) {
                    this.isMicActive = true;
                    clearTimeout(this.micTimeout);
                    this.micTimeout = setTimeout(() => this.isMicActive = false, 300);
                }
                else if (payload.type === this.constants.MSG_PROXY_WS_LOG) {
                    this.proxyLogs.push({
                        id: Date.now() + Math.random(),
                        color: "yellow",
                        label: payload.payload.type,
                        rawJson: payload.direction + "\n" + JSON.stringify(payload.payload, null, 2)
                    });
                    if (this.proxyLogs.length > 10) this.proxyLogs.shift();
                }
                else if (payload.type === this.constants.MSG_CHAT_SYNC) {
                    if (payload.userId === this.activeUserId) {
                        this.userChatHistory = payload.history || [];
                        if (typeof payload.chatSummary === 'string') {
                            this.userSummary = payload.chatSummary;
                        }
                        this.rebuildTerminalLogs();
                    }
                }
                else if (payload.type === 'webrtc-offer' || payload.type === 'webrtc-answer' || payload.type === 'webrtc-candidate' || payload.type === 'webrtc-stream-refresh-needed') {
                    window.dispatchEvent(new CustomEvent('webrtc-receive', { detail: payload }));
                }
            } catch (e) {
                console.error("Failed to parse message", e);
            }
        };
    },
    async checkDBStatus() {
        try {
            await ApiService.getStatus(this.constants.ROUTE_STATUS_DB, "Database service");
            this.isDBConnected = true;
        } catch (error) {
            this.isDBConnected = false;
            this.logMessage('Database service is offline.', 'log-error');
        }
    },
    async checkRAGStatus() {
        try {
            await ApiService.getStatus(this.constants.ROUTE_STATUS_RAG, "RAG agent");
            this.isRAGConnected = true;
        } catch (error) {
            this.isRAGConnected = false;
            this.logMessage('RAG agent is offline.', 'log-error');
        }
    },
    async checkSTTStatus() {
        try {
            await ApiService.getStatus(this.constants.ROUTE_STATUS_STT, "STT service");
            this.isSTTConnected = true;
        } catch (error) {
            this.isSTTConnected = false;
            this.logMessage('STT service is offline.', 'log-error');
        }
    },
    async checkConfigStatus() {
        const getModelId = (configStr) => {
            try { return JSON.parse(configStr || '{}').id || ''; }
            catch (e) { return configStr || ''; }
        };

        // Check LLM Configuration
        if (this.apiData.CUSTOM_LLM_ENDPOINT && this.apiData.CUSTOM_LLM_KEY) {
            const modelId = getModelId(this.apiData.CUSTOM_LLM_CONFIG);
            this.isLLMConfigured = await ApiService.probeModelCapability(modelId, 'text', 'CUSTOM_LLM_CONFIG');
        } else {
            this.isLLMConfigured = false;
        }

        // Check VLM (Vision) Configuration
        if (this.apiData.CUSTOM_VLM_ENDPOINT && this.apiData.CUSTOM_VLM_KEY) {
            const modelId = getModelId(this.apiData.CUSTOM_VLM_CONFIG);
            this.isVLMConfigured = await ApiService.probeModelCapability(modelId, 'vision', 'CUSTOM_VLM_CONFIG');
        } else {
            this.isVLMConfigured = false;
        }

        // Check ELM (Embedding) Configuration
        if (this.apiData.CUSTOM_EMBEDDING_ENDPOINT && this.apiData.CUSTOM_EMBEDDING_KEY) {
            const modelId = getModelId(this.apiData.CUSTOM_EMBEDDING_CONFIG);
            this.isELMConfigured = await ApiService.probeModelCapability(modelId, 'embed', 'CUSTOM_EMBEDDING_CONFIG');
        } else {
            this.isELMConfigured = false;
        }
    },

    toggleMute() {
        const success = ApiService.sendSocketMessage(this.socket, {
            type: this.constants.MSG_TOGGLE_MUTE,
            data: this.isMuted
        });
        if (success) {
            this.isMuted = !this.isMuted;
        } else {
            this.logMessage('ERROR: Cannot toggle Mute. Robot is disconnected!', 'log-error');
        }
    },

    async fetchUsers() {
        try {
            const fetchedUsers = await ApiService.getUsers(this.constants.ROUTE_RAG_USERS);
            if (!fetchedUsers.includes('default_user')) {
                fetchedUsers.push('default_user');
            }
            this.users = fetchedUsers;
        } catch (error) {
            this.logMessage('Failed to scan database for users.', 'log-error');
            this.users = ['default_user'];
        }
    },

    async fetchExperiments() {
        try {
            this.experiments = await ApiService.getExperiments(this.constants.ROUTE_RAG_EXPERIMENTS);
        } catch (error) {
            this.logMessage('Failed to load experiments.', 'log-error');
        }
    },

    rebuildTerminalLogs() {
        const systemLogs = this.terminalLogs.filter(log => log.type !== 'chat-bubble');
        const chatLogs = [];
        this.userChatHistory.forEach(turn => {
            if (turn.userQuery || turn.userImageUrl) {
                chatLogs.push({ id: turn.turnId + '_u', text: turn.userQuery, type: 'chat-bubble', sender: 'USER', userImageUrl: turn.userImageUrl || null });
            }
            if (turn.toolBufferResponse) {
                chatLogs.push({ id: turn.turnId + '_b', text: turn.toolBufferResponse, type: 'chat-bubble', sender: 'ROBOT' });
            }
            if (turn.llmResponse) {
                chatLogs.push({ id: turn.turnId + '_r', text: turn.llmResponse, type: 'chat-bubble', sender: 'ROBOT' });
            }
        });
        this.terminalLogs = [...systemLogs, ...chatLogs];
    },

    async loadHistory(userId) {
        try {
            const data = await ApiService.getUserHistory(this.constants.ROUTE_RAG_USERS, userId);
            this.userChatHistory = data.history || [];
            this.userSummary = data.chatSummary || '';
            this.rebuildTerminalLogs();
        } catch (error) {
            this.logMessage(`Failed to load history for ${userId}.`, 'log-error');
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
        this.setUserIdOverSocket();
    },

    async createExperiment(name) {
        const expId = 'exp_' + Date.now();
        try {
            await ApiService.createExperiment(this.constants.ROUTE_RAG_EXPERIMENTS, expId, name);
            this.logMessage(`Experiment '${name}' created.`, 'log-system');
            await this.fetchExperiments();
        } catch(e) {
            this.logMessage('Error creating experiment', 'log-error');
        }
    },

    async deleteExperiment(expId) {
        try {
            await ApiService.deleteExperiment(this.constants.ROUTE_RAG_EXPERIMENTS, expId);
            this.experiments = this.experiments.filter(e => e.id !== expId); // Mutate locally
        } catch (e) {
            alert('Network error while deleting experiment.');
        }
    },

    async deleteExperimentFile(expId, fileName) {
        try {
            await ApiService.deleteExperimentFile(this.constants.ROUTE_RAG_EXPERIMENTS, expId, fileName);
            this.logMessage(`File '${fileName}' deleted.`, 'log-system');
            await this.fetchExperiments();
        } catch (e) {
            this.logMessage('Error deleting file.', 'log-error');
        }
    },

    async assignUserToExperiment(payload) {
        try {
            await ApiService.assignUserToExperiment(this.constants.ROUTE_RAG_EXPERIMENTS, payload.expId, payload.userId);
            this.logMessage(`User ${payload.userId} assigned.`, 'log-system');
            await this.fetchExperiments();
        } catch(e) {
            this.logMessage('Error assigning user', 'log-error');
        }
    },

    async removeUserFromExperiment(payload) {
         try {
            await ApiService.removeUserFromExperiment(this.constants.ROUTE_RAG_EXPERIMENTS, payload.expId, payload.userId);
            this.logMessage(`User ${payload.userId} removed.`, 'log-system');
            await this.fetchExperiments();
        } catch(e) {
            this.logMessage('Error removing user', 'log-error');
        }
    },
    async saveExperimentConfig(expId, configJson) {
        try {
            await ApiService.saveExperimentConfig(this.constants.ROUTE_RAG_EXPERIMENTS, expId, configJson);
            this.logMessage(`Experiment configuration saved successfully.`, 'log-system');

            await this.fetchExperiments();
        } catch (e) {
            console.error(e);
            this.logMessage('Error saving experiment configuration to database.', 'log-error');
        }
    },

    setUserIdOverSocket() {
        if (!this.activeUserId || !this.isConnected) return;
        const success = ApiService.sendSocketMessage(this.socket, {
            type: this.constants.MSG_SET_USER,
            userId: this.activeUserId
        });
        if (success) {
            this.logMessage('Active user set to: ' + this.activeUserId, 'log-system');
        } else {
            this.logMessage('ERROR: Cannot set active user. Robot is disconnected!', 'log-error');
        }
    },

    deleteUser(userId) {
        if (userId === 'default_user') {
            this.logMessage('Cannot delete the default user.', 'log-error');
            return;
        }

       const success = ApiService.sendSocketMessage(this.socket, {
           type: this.constants.MSG_DELETE_USER,
           userId: userId
       });

        if (success) {
            this.users = this.users.filter(u => u !== userId);
            this.selectUser('default_user');
            this.logMessage('User deleted.', 'log-system');
        } else {
            this.logMessage('ERROR: Cannot delete user. Robot is disconnected!', 'log-error');
        }
    },

    clearHistory(userId) {
        const success = ApiService.sendSocketMessage(this.socket, {
            type: this.constants.MSG_CLEAR_HISTORY,
            userId: userId
        });

        if (success) {
            this.terminalLogs = this.terminalLogs.filter(log => log.type !== 'chat-bubble');
            this.userChatHistory = [];
            this.logMessage('History cleared.', 'log-system');
        } else {
            this.logMessage('ERROR: Cannot clear history. Robot is disconnected!', 'log-error');
        }
    },

    saveUserKnowledge(userId, summary, updatedTurns, deletedTurnIds) {
        const success = ApiService.sendSocketMessage(this.socket, {
            type: this.constants.MSG_UPDATE_USER_KNOWLEDGE,
            userId: userId,
            summary: summary,
            updatedTurns: updatedTurns,
            deletedTurnIds: deletedTurnIds
        });

        if (success) {
            this.logMessage(`Saved knowledge for ${userId}.`, 'log-system');
        } else {
            this.logMessage('ERROR: Cannot save knowledge. Robot is disconnected!', 'log-error');
        }
    },

    generateUserSummary(userId) {
        const success = ApiService.sendSocketMessage(this.socket, {
            type: this.constants.MSG_GENERATE_USER_SUMMARY,
            userId
        });

        if (success) {
            this.logMessage(`Generating chat summary for ${userId}.`, 'log-system');
        } else {
            this.logMessage('ERROR: Cannot generate chat summary. Robot is disconnected!', 'log-error');
        }
    }
});