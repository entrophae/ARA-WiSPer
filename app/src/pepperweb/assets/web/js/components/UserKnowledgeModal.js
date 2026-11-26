const UserKnowledgeModal = {
    components: {
        'settings-group': SettingsGroup
    },
    props: ['userId', 'initialSummary', 'initialHistory'],
    data() {
        return {
            editUserSummary: this.initialSummary || '',
            // Deep copy the history array so we can edit it visually
            editHistory: this.normalizeHistory(this.initialHistory),
            deletedTurnIds: []
        };
    },
    watch: {
        initialHistory: {
            handler(newHistory) {
                this.editHistory = this.normalizeHistory(newHistory);
                this.deletedTurnIds = [];
            },
            deep: true
        },
        initialSummary(newSummary) {
            this.editUserSummary = newSummary || '';
        }
    },
    template: `
        <div class="modal">
            <div class="modal-content panel">
                <span class="close-btn" @click="$emit('close')">&times;</span>
                <h2 class="modal-title">
                    Edit Knowledge: {{ userId }}
                    <button class="btn-icon delete-btn" @click="deleteUser(userId)" title="Delete User">Delete User</button>
                    <button class="btn-icon delete-btn" @click="clearHistory(userId)" title="Clear History">Clear History</button>
                </h2>


                <div class="settings-grid">
                    <settings-group title="Chat Summary">
                        <textarea v-model="editUserSummary" class="textarea-full"></textarea>
                    </settings-group>

                    <settings-group title="Chat History">
                        <div class="history-editor">
                            <div v-for="(turn, index) in editHistory" :key="index" class="history-turn-card">
                                <div class="turn-header">
                                    <strong>Turn {{ turn.turnId }}</strong>
                                    <button class="btn-icon delete-btn" @click="removeTurn(index)" title="Delete Turn">🗑️</button>
                                </div>
                                <label class="input-label">User Query</label>
                                <div v-if="turn.userImageUrl" class="query-image-wrapper">
                                    <img :src="turn.userImageUrl" class="turn-query-image" alt="Captured Vision" />
                                </div>
                                <textarea v-model="turn.userQuery" class="textarea-full turn-textarea"></textarea>
                                <template v-if="turn.toolsUsed && turn.toolsUsed.length > 0">
                                    <label class="input-label">Tools Used ({{ turn.toolsUsed.length }})</label>
                                    <div class="tools-used-section">
                                        <div v-for="(tool, tIndex) in turn.toolsUsed" :key="tIndex">
                                            <label class="input-label">{{ tool.name }} | id:<i>{{ tool.callId }}</i></label>
                                            <div class="tool-detail-wrapper">
                                                <div class="tool-detail-item">
                                                    <label class="tool-detail-item-label">Query</label>
                                                    <textarea v-model="tool.query" class="textarea-full turn-text-area tool-detail-item-text"></textarea>
                                                </div>
                                                <div class="tool-detail-item">
                                                    <label class="tool-detail-item-label">Answer</label>
                                                    <textarea v-model="tool.answer" class="textarea-full turn-text-area tool-detail-item-text"></textarea>
                                                </div>
                                            </div>
                                        </div>
                                    </div>
                                </template>
                                <template v-if="turn.toolBufferResponse">
                                    <label class="input-label">Thinking / Filler (Tool: {{ turn.toolCalled || 'Unknown' }})</label>
                                    <textarea v-model="turn.toolBufferResponse" class="textarea-full turn-textarea"></textarea>
                                </template>
                                <label class="input-label">Robot Response</label>
                                <textarea v-model="turn.llmResponse" class="textarea-full turn-textarea"></textarea>
                            </div>
                            <button class="btn-secondary" @click="addTurn">+ Add New Chat Turn</button>
                        </div>
                    </settings-group>
                </div>

                <div class="modal-actions">
                    <button class="btn-secondary" @click="$emit('close')">Cancel</button>
                    <button @click="saveChanges">Save Changes</button>
                </div>
            </div>
        </div>
    `,
    methods: {
        normalizeHistory(history) {
            if (!history) return [];
            return JSON.parse(JSON.stringify(history)).map(turn => {
                let rawTools = turn.toolsUsed || turn.tools_used;

                if (!rawTools) {
                    turn.toolsUsed = [];
                } else if (typeof rawTools === 'string') {
                    try {
                        turn.toolsUsed = JSON.parse(rawTools);
                    } catch (e) {
                        turn.toolsUsed = [];
                    }
                } else {
                    turn.toolsUsed = rawTools;
                }

                if (!Array.isArray(turn.toolsUsed)) {
                    turn.toolsUsed = [];
                }

                turn.userImageUrl = turn.userImageUrl || turn.user_image_url || null;

                return turn;
            });
        },
        deleteUser() {
           if (confirm(`Are you sure you want to completely delete ${this.userId}? This cannot be undone.`)) {
               appStore.deleteUser(this.userId);
               this.$emit('close');
           }
        },
        clearHistory() {
           if (confirm(`Are you sure you want to wipe the chat history for ${this.userId}?`)) {
               appStore.clearHistory(this.userId);
           }
        },
        removeTurn(index) {
            const turnId = this.editHistory[index].turnId;
            if (turnId && confirm(`Are you sure you want to completely delete this turn? This cannot be undone.`)) {
                this.deletedTurnIds.push(turnId);
                this.editHistory.splice(index, 1);
            }
        },
        addTurn() {
            this.editHistory.push({
                turnId: 'manual_' + Date.now(),
                userQuery: '',
                userImageUrl: null,
                llmResponse: '',
                toolBufferResponse: null,
                toolsUsed: []
            });

            setTimeout(() => {
                const container = this.$el.querySelector('.history-editor');
                if(container) container.scrollTop = container.scrollHeight;
            }, 50);
        },
        saveChanges() {
            // Filter out empty turns before saving
            const modifiedTurns = this.editHistory.filter(currentTurn => {
                // If it's a newly added turn, it definitely needs saving
                if (currentTurn.turnId.startsWith('manual_')) return true;

                // Find the original version of this turn
                const originalTurn = this.initialHistory.find(t => t.turnId === currentTurn.turnId);

                // If it changed, keep it. Otherwise, ignore it.
                if (!originalTurn) return true;
                if (originalTurn.userQuery !== currentTurn.userQuery) return true;
                if (originalTurn.userImageUrl !== currentTurn.userImageUrl) return true;
                if (originalTurn.llmResponse !== currentTurn.llmResponse) return true;
                if (originalTurn.toolBufferResponse !== currentTurn.toolBufferResponse) return true;

                // Deep check if any tools were edited
                if (JSON.stringify(originalTurn.toolsUsed) !== JSON.stringify(currentTurn.toolsUsed)) return true;

                return false;
            });

            appStore.userSummary = this.editUserSummary;
            appStore.userChatHistory = this.editHistory;
            appStore.saveUserKnowledge(this.userId, this.editUserSummary, modifiedTurns, this.deletedTurnIds);
            this.$emit('close');
        }
    }
};