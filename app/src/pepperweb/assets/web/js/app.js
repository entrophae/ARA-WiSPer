const { createApp } = Vue;

const app = createApp({
    data() {
        return {
            showSettings: false,
            showUserEdit: false,
            showExperimentManager: false,
            speakInput: '',
            promptInput: '',
            store: appStore
        };
    },
    methods: {
        sendSpeak() {
            const text = this.speakInput.trim();
            if (!text || !this.store.isConnected) return;
            const success = ApiService.sendSocketMessage(this.store.socket, {
                type: this.store.constants.MSG_SPEAK_COMMAND,
                apiMode: this.store.apiMode,
                llmMode: this.store.isLLMMode,
                text: text
            });
            if (success) {
                this.speakInput = '';
                this.store.logMessage('Manual speak sent', 'log-system');
            } else {
                this.store.logMessage('ERROR: Cannot send command. Robot is disconnected!', 'log-error');
            }
        },
        sendPrompt() {
            const text = this.promptInput.trim();
            if (!text || !this.store.isConnected && !this.store.isLLMConfigured) return;
            const success = ApiService.sendSocketMessage(this.store.socket, {
                type: this.store.constants.MSG_CHAT_COMMAND,
                apiMode: this.store.apiMode,
                text: text
            });
            if (success) {
                this.promptInput = '';
                this.store.logMessage('Manual prompt sent', 'log-system');
            } else {
                this.store.logMessage('ERROR: Cannot send command. Robot is disconnected!', 'log-error');
            }
        },
        promptNewUser() {
            const newUser = prompt("Enter the ID for the new user:");
            if (newUser && newUser.trim() !== "") {
                this.store.selectUser(newUser.trim());
            }
        }
    },
    async mounted() {
        await this.store.initializeStore();
        this.store.connectWebSocket();
        await this.store.checkDBStatus();
        // DB is needed but the rest can be silently called
        this.store.checkRAGStatus();
        this.store.checkSTTStatus();
        this.store.checkConfigStatus();
        if (this.store.isDBConnected == true) {
            await this.store.fetchUsers();
            await this.store.fetchExperiments();
        }
        await this.store.selectUser(this.store.activeUserId);
    }
});

app.component('settings-modal', SettingsModal);
app.component('user-knowledge-modal', UserKnowledgeModal);
app.component('experiment-manager-modal', ExperimentManagerModal);

app.mount('#app');
