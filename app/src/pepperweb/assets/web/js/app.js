const { createApp } = Vue;

const app = createApp({
    data() {
        return {
            showSettings: false,
            showUserEdit: false,
            showExperimentManager: false,
            commandInput: '',
            store: appStore
        };
    },
    methods: {
        sendCommand() {
            const text = this.commandInput.trim();
            if (!text || !this.store.isConnected) return;
            const success = ApiService.sendSocketMessage(this.store.socket, {
                type: this.store.constants.MSG_CHAT_COMMAND,
                apiMode: this.store.apiMode,
                text: text
            });
            if (success) {
                this.commandInput = '';
                this.logMessage('Manual command sent', 'log-system');
            } else {
                this.logMessage('ERROR: Cannot save settings. Robot is disconnected!', 'log-error');
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
        await this.store.checkStatus();
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
