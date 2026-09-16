const SettingInput = {
    props: ['label', 'modelValue', 'type', 'placeholder'],
    emits: ['update:modelValue', 'commit'],
    template: `
        <div class="input-wrapper">
            <label class="input-label">{{ label }}</label>
            <input
                :type="type || 'text'"
                :value="modelValue"
                @input="$emit('update:modelValue', $event.target.value)"
                @blur="$emit('commit')"
                @keyup.enter="$emit('commit')"
                :placeholder="placeholder"
                autocomplete="off"
            >
        </div>
    `
};

const SettingsGroup = {
    props: ['title'],
    template: `
        <div class="settings-group">
            <h3 v-if="title">{{ title }}</h3>
            <slot></slot>
        </div>
    `
};

const SettingRadioGroup = {
    props: ['label', 'modelValue', 'options'],
    emits: ['update:modelValue'],
    template: `
        <div class="input-wrapper">
            <label class="input-label">{{ label }}</label>

            <div class="radio-group">
                <label v-for="option in options" :key="option" class="radio-label">
                    <input
                        type="radio"
                        :value="option"
                        :checked="modelValue === option"
                        @change="$emit('update:modelValue', option)"
                    >
                    {{ option }}
                </label>
            </div>
        </div>
    `
};

const SettingModelDropdown = {
    props: ['fieldId', 'label', 'modelValue', 'placeholder'],
    emits: ['update:modelValue', 'capabilities'],
    data() {
        return {
            availableModels: [],
            isLoading: false,
            isProbing: false
        };
    },
    template: `
        <div class="input-wrapper">
            <label class="input-label">{{ label }}</label>

            <select :value="modelValue" @change="onModelSelected($event.target.value)">
                <option value="" disabled>{{ isLoading ? 'Fetching models...' : placeholder }}</option>

                <option v-for="model in filteredModels" :key="model.id" :value="model.id" :hidden="isModelHidden(model)">
                    {{ model.id }} |
                        visual: {{ model.hasVision === true ? '✅' : (model.hasVision === false ? '❌' : '?') }} |
                        tools: {{ model.hasTools === true ? '✅' : (model.hasTools === false ? '❌' : '?') }} |
                        embedding: {{ model.hasEmbedding === true ? '✅' : (model.hasEmbedding === false ? '❌' : '?') }}
                    | [{{ model.provided_by || model.owned_by || '?' }}]
                </option>

                <option v-if="modelValue && !availableModels.find(m => m.id === modelValue)" :value="modelValue">
                    {{ modelValue }} | (Offline)
                </option>
            </select>

            <button class="btn-icon" @click="fetchModels(true)" :disabled="isLoading || isProbing" title="Force Refresh & Clear Cache">↻</button>
        </div>
    `,
    methods: {
        onModelSelected(value) {
            this.$emit('update:modelValue', value);

            const selectedModel = this.availableModels.find(m => m.id === value);
            if (selectedModel) {
                this.$emit('capabilities', {
                    hasVision: selectedModel.hasVision,
                    hasTools: selectedModel.hasTools,
                    hasEmbedding: selectedModel.hasEmbedding
                });
            }
        },
        isModelHidden(model) {
            if (this.fieldId === 'CUSTOM_LLM_MODEL') {
                return model.hasEmbedding === true;
            }
            if (this.fieldId === 'CUSTOM_VLM_MODEL') {
                return model.hasVision === false;
            }
            if (this.fieldId === 'CUSTOM_EMBEDDING_MODEL') {
                return model.hasEmbedding === false;
            }
            return false;
        },
        async fetchModels(forceRefresh = false) {
            this.isLoading = true;
            try {
                const localHost = window.location.protocol === 'file:'
                    ? 'http://127.0.0.1:8080'
                    : window.location.protocol + '//' + window.location.host;

                const cacheKey = `ara_model_cache_${this.fieldId}_${this.$root.apiData[this.fieldId] || 'default'}`;
                if (forceRefresh) {
                    localStorage.removeItem(cacheKey);
                }

                const response = await fetch(`${localHost}/proxy/models?type=${this.fieldId}`);

                if (response.ok) {
                    const json = await response.json();
                    let models = json.data && Array.isArray(json.data) ? json.data : (Array.isArray(json) ? json : []);

                    let cachedData = {};
                    try {
                        cachedData = JSON.parse(localStorage.getItem(cacheKey) || '{}');
                    } catch (e) {
                        cachedData = {};
                    }

                    this.availableModels = models.map(model => {
                        const cached = cachedData[model.id];
                        return {
                            ...model,
                            hasVision: cached ? cached.hasVision : null,
                            hasTools: cached ? cached.hasTools : null,
                            hasEmbedding: cached ? cached.hasEmbedding : null
                        };
                    });

                    this.backgroundProbeQueue(cacheKey);
                }
            } catch (e) {
                console.error('Network error fetching models:', e);
            } finally {
                this.isLoading = false;
            }
        },
        async backgroundProbeQueue(cacheKey) {
            if (this.isProbing) return;
            this.isProbing = true;

            let cachedData = {};
            try {
                cachedData = JSON.parse(localStorage.getItem(cacheKey) || '{}');
            } catch (e) {
                cachedData = {};
            }

            for (let i = 0; i < this.availableModels.length; i++) {
                const modelId = this.availableModels[i].id;

                if (cachedData[modelId] !== undefined) {
                    this.availableModels[i].hasVision = cachedData[modelId].hasVision;
                    this.availableModels[i].hasTools = cachedData[modelId].hasTools;
                    this.availableModels[i].hasEmbedding = cachedData[modelId].hasEmbedding;
                    continue;
                }

                this.availableModels[i].hasVision = await this.probeModelCapability(modelId, 'vision');
                this.availableModels[i].hasTools = await this.probeModelCapability(modelId, 'tools');
                this.availableModels[i].hasEmbedding = await this.probeModelCapability(modelId, 'embed');

                cachedData[modelId] = {
                    hasVision: this.availableModels[i].hasVision,
                    hasTools: this.availableModels[i].hasTools,
                    hasEmbedding: this.availableModels[i].hasEmbedding
                };
                localStorage.setItem(cacheKey, JSON.stringify(cachedData));

                await new Promise(resolve => setTimeout(resolve, 200));
            }

            this.isProbing = false;
        },
        async probeModelCapability(modelId, probeType) {
            try {
                const localHost = window.location.protocol === 'file:' ? 'http://127.0.0.1:8080' : window.location.protocol + '//' + window.location.host;
                const response = await fetch(`${localHost}/proxy/probe?modelId=${encodeURIComponent(modelId)}&probeType=${probeType}&fieldId=${this.fieldId}`);

                if (response.ok) {
                    const json = await response.json();
                    return json.supported === true;
                }
                return false;
            } catch (e) {
                return false;
            }
        }
    },
    mounted() {
        this.fetchModels();
    }
};

const SettingModalTextarea = {
    props: ['label', 'modelValue', 'placeholder'],
    emits: ['update:modelValue'],
    data() {
        return {
            isOpen: false,
            tempText: ''
        };
    },
    methods: {
        openModal() {
            this.tempText = this.modelValue || '';
            this.isOpen = true;
        },
        saveAndClose() {
            this.$emit('update:modelValue', this.tempText);
            this.isOpen = false;
        },
        closeModal() {
            this.isOpen = false;
        }
    },
    template: `
        <div class="input-wrapper">
            <label class="input-label">{{ label }}</label>
            <button type="button" @click="openModal" class="btn-secondary">
                Edit Prompt
            </button>
            <teleport to="body">
                <div v-if="isOpen" class="modal modal-stacked">
                    <div class="modal-content panel">
                        <span class="close-btn" @click="closeModal">&times;</span>
                        <h2 class="modal-title">Edit {{ label }}</h2>

                        <textarea
                            v-model="tempText"
                            class="textarea-full"
                            :placeholder="placeholder"
                        ></textarea>

                        <div class="modal-actions">
                            <button type="button" @click="closeModal" class="btn-secondary">Cancel</button>
                            <button type="button" @click="saveAndClose">Apply Changes</button>
                        </div>
                    </div>
                </div>
            </teleport>
        </div>
    `
};
const SettingsModal = {
    components: {
        'settings-group': SettingsGroup,
        'setting-input': SettingInput,
        'setting-model-dropdown': SettingModelDropdown,
        'setting-radio-group': SettingRadioGroup,
        'setting-modal-textarea': SettingModalTextarea
    },
    props: ['initialKeys', 'schema'],
    data() {
        return {
            apiData: JSON.parse(JSON.stringify(this.initialKeys))
        };
    },
    template: `
        <div class="modal">
            <div class="modal-content panel">
                <span class="close-btn" @click="$emit('close')">&times;</span>
                <h2 class="modal-title">API Configuration</h2>
                <div class="settings-grid">
                    <settings-group
                        v-for="(fields, categoryKey) in schema"
                        :key="categoryKey"
                        :title="formatTitle(categoryKey)"
                    >
                        <template v-for="field in fields" :key="field.id">

                            <setting-model-dropdown
                                v-if="field.type === 'model_select'"
                                :field-id="field.id"
                                :label="field.label"
                                :placeholder="field.placeholder"
                                v-model="apiData[field.id]"
                                @capabilities="handleCapabilities(field.id, $event)"
                            />

                            <setting-radio-group
                                v-else-if="field.type === 'radio'"
                                :label="field.label"
                                :options="field.options"
                                v-model="apiData[field.id]"
                            />

                            <setting-modal-textarea
                                v-else-if="field.type === 'modal_textarea'"
                                :label="field.label"
                                :placeholder="field.placeholder"
                                v-model="apiData[field.id]"
                            />

                            <setting-input
                                v-else
                                :label="field.label"
                                :type="field.type"
                                :placeholder="field.placeholder"
                                v-model="apiData[field.id]"
                                @commit="handleInputCommit(field.id)"
                            />

                        </template>
                    </settings-group>
                </div>

                <button @click="$emit('save', apiData)" class="btn-save">Save Config & Restart Connection</button>
            </div>
        </div>
    `,
    methods: {
        silentSave() {
            if (this.$root.socket && this.$root.socket.readyState === WebSocket.OPEN) {
                this.$root.socket.send(JSON.stringify({
                    type: this.$root.constants.MSG_SAVE_API_KEYS,
                    data: this.apiData
                }));
            }
        },
        handleCapabilities(fieldId, caps) {
            const prefix = fieldId.replace('_MODEL', '');

            this.apiData[`${prefix}_HAS_VISUALS`] = caps.hasVision;
            this.apiData[`${prefix}_HAS_TOOLS`] = caps.hasTools;
            this.apiData[`${prefix}_HAS_EMBEDDING`] = caps.hasEmbedding;

            console.log(`Updated capabilities for ${prefix}:`, caps);
        },
        handleInputCommit(fieldId) {
            if (fieldId === 'PROXY_KEY' || fieldId === 'PROXY_ENDPOINT') {
                this.silentSave();
                console.log(`Saved ${fieldId} via commit action.`);
            }
        },
        formatTitle(key) {
            return key.charAt(0).toUpperCase() + key.slice(1);
        }
    },
};
const UserKnowledgeModal = {
    components: {
        'settings-group': SettingsGroup
    },
    props: ['userId', 'initialSummary', 'initialHistory'],
    data() {
        return {
            editUserSummary: this.initialSummary || '',
            // Deep copy the history array so we can edit it visually
            editHistory: JSON.parse(JSON.stringify(this.initialHistory || [])),
            deletedTurnIds: []
        };
    },
    watch: {
        initialHistory: {
            handler(newHistory) {
                this.editHistory = JSON.parse(JSON.stringify(newHistory || []));
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
                    <button class="btn-icon delete-btn" @click="clearHistory(userId)" title="Clear History">Clear History️</button>
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
                                <textarea v-model="turn.userQuery" class="textarea-full turn-textarea"></textarea>
                                <template v-if="turn.toolCalled">
                                    <label class="input-label">Buffer (Tool: {{ turn.toolCalled }})</label>
                                    <textarea v-model="turn.toolBufferResponse" class="textarea-full turn-textarea"></textarea>
                                </template>
                                <label class="input-label">Robot Response</label>
                                <textarea v-model="turn.llmResponse" class="textarea-full turn-textarea"></textarea>
                            </div>
                            <button class="btn-secondary" @click="addTurn">+ Add New Chat Turn</button>
                        </div>
                    </settings-group>
                </div>

                <div class="modal-actions" style="margin-top: 20px;">
                    <button class="btn-secondary" @click="$emit('close')">Cancel</button>
                    <button @click="saveChanges">Save Changes</button>
                </div>
            </div>
        </div>
    `,
    methods: {
        deleteUser() {
           if (confirm(`Are you sure you want to completely delete ${this.userId}? This cannot be undone.`)) {
               this.$emit('delete');
               this.$emit('close');
           }
        },
        clearHistory() {
           if (confirm(`Are you sure you want to wipe the chat history for ${this.userId}?`)) {
               this.$emit('clear');
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
                llmResponse: ''
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
                if (originalTurn.llmResponse !== currentTurn.llmResponse) return true;

                return false;
            });


            this.$emit('save', {
                summary: this.editUserSummary,
                history: this.editHistory,
                modifiedTurns: modifiedTurns,
                deletedTurnIds: this.deletedTurnIds
            });
        }
    }
};