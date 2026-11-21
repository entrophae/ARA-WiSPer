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

const SettingSegmentedButton = {
    props: ['label', 'options', 'modelValue'],
    emits: ['update:modelValue'],
    methods: {
        getVal(opt) {
            return (typeof opt === 'object' && opt !== null) ? (opt.value || opt.code || opt.id || opt.name || opt.modelName) : opt;
        },
        getLabel(opt) {
            console.log(opt)
            return (typeof opt === 'object' && opt !== null) ? (opt.label || opt.displayName || opt.name || opt.value) : opt;
        }
    },
    template: `
        <div class="input-wrapper">
            <label class="input-label">{{ label }}</label>
            <div class="segmented-control">
                <button
                    v-for="opt in options"
                    :key="getVal(opt)"
                    type="button"
                    :class="['segmented-btn', { active: modelValue === getVal(opt) }]"
                    @click="$emit('update:modelValue', getVal(opt))"
                >
                    {{ getLabel(opt) }}
                </button>
            </div>
        </div>
    `
};

const SettingSlider = {
    props: ['label', 'modelValue', 'min', 'max', 'step', 'displayFormatter'],
    emits: ['update:modelValue'],
    computed: {
        formattedValue() {
            if (this.displayFormatter) return this.displayFormatter(this.modelValue);
            return this.modelValue;
        }
    },
    template: `
        <div class="input-wrapper slider-wrapper">
            <div class="slider-header">
                <label class="input-label">{{ label }}</label>
                <span class="slider-value">{{ formattedValue }}</span>
            </div>
            <input
                type="range"
                :min="min || 0"
                :max="max || 100"
                :step="step || 1"
                :value="modelValue"
                @input="$emit('update:modelValue', Number($event.target.value))"
            />
        </div>
    `
};

const SettingSwitch = {
    props: ['label', 'description', 'modelValue'],
    emits: ['update:modelValue'],
    template: `
        <div class="switch-wrapper">
            <div class="switch-text">
                <label class="switch-label">{{ label }}</label>
                <p v-if="description" class="switch-description">{{ description }}</p>
            </div>
            <label class="switch-toggle">
                <input
                    type="checkbox"
                    :checked="modelValue"
                    @change="$emit('update:modelValue', $event.target.checked)"
                />
                <span class="slider-round"></span>
            </label>
        </div>
    `
};

const SettingVoiceDropdown = {
    props: ['label', 'voices', 'modelValue'],
    emits: ['update:modelValue'],
    template: `
        <div class="input-wrapper">
            <label class="input-label">{{ label }}</label>
            <select :value="modelValue" @change="$emit('update:modelValue', $event.target.value)">
                <option v-for="(desc, voiceKey) in voices" :key="voiceKey" :value="voiceKey">
                    {{ voiceKey }} — ({{ desc }})
                </option>
            </select>
        </div>
    `
};

const SettingToolsSection = {
    props: ['availableTools', 'enabledTools', 'googleSearch', 'xaiWebSearch', 'xaiXSearch', 'isGoogle', 'isXai'],
    emits: ['update:enabledTools', 'update:googleSearch', 'update:xaiWebSearch', 'update:xaiXSearch'],
    methods: {
        toggleTool(toolName) {
            const current = [...this.enabledTools];
            const idx = current.indexOf(toolName);
            if (idx > -1) {
                current.splice(idx, 1);
            } else {
                current.push(toolName);
            }
            this.$emit('update:enabledTools', current);
        }
    },
    template: `
        <div class="tools-container">
            <div v-if="isGoogle || isXai" class="server-tools">
                <h4>Server-Side Tools</h4>
                <div v-if="isGoogle" class="tool-card">
                    <label class="tool-checkbox">
                        <input type="checkbox" :checked="googleSearch" @change="$emit('update:googleSearch', $event.target.checked)" />
                        <strong>Google Search</strong>
                    </label>
                    <p class="tool-desc">Ground responses with real-time web search for improved accuracy</p>
                </div>
                <div v-if="isXai" class="tool-card">
                    <label class="tool-checkbox">
                        <input type="checkbox" :checked="xaiWebSearch" @change="$emit('update:xaiWebSearch', $event.target.checked)" />
                        <strong>Web Search</strong>
                    </label>
                    <p class="tool-desc">Search the web for real-time information</p>
                </div>
                <div v-if="isXai" class="tool-card">
                    <label class="tool-checkbox">
                        <input type="checkbox" :checked="xaiXSearch" @change="$emit('update:xaiXSearch', $event.target.checked)" />
                        <strong>X Search</strong>
                    </label>
                    <p class="tool-desc">Search X/Twitter posts for latest discussions and news</p>
                </div>
            </div>

            <h4>Client-Side Tools</h4>
            <div v-for="tool in availableTools" :key="tool.name" class="tool-card">
                <div class="tool-header">
                    <label class="tool-checkbox">
                        <input type="checkbox" :checked="enabledTools.includes(tool.name)" @change="toggleTool(tool.name)" />
                        <strong>{{ tool.name }}</strong>
                    </label>
                    <span v-if="tool.apiKeyRequired" :class="['api-badge', tool.isApiKeyAvailable ? 'badge-green' : 'badge-red']">
                        {{ tool.apiKeyRequired }}: {{ tool.isApiKeyAvailable ? 'Ready' : 'Missing/Incorrect Key' }}
                    </span>
                </div>
                <p class="tool-desc">{{ tool.description }}</p>
            </div>
        </div>
    `
};

const SettingModelDropdown = {
    props: ['fieldId', 'label', 'modelValue', 'placeholder'],
    emits: ['update:modelValue'],
    data() {
        return {
            availableModels: [],
            isLoading: false,
            isProbing: false
        };
    },
    computed: {
        selectedModelId: {
            get() {
                try {
                    const parsed = JSON.parse(this.modelValue);
                    return parsed.id || this.modelValue;
                } catch(e) {
                    return this.modelValue;
                }
            },
            set(value) {
                const selectedModel = this.availableModels.find(m => m.id === value);
                if (selectedModel) {
                    const bundledData = JSON.stringify({
                        id: selectedModel.id,
                        hasVision: selectedModel.hasVision,
                        hasTools: selectedModel.hasTools,
                        hasEmbedding: selectedModel.hasEmbedding
                    });
                    this.$emit('update:modelValue', bundledData);
                } else {
                    this.$emit('update:modelValue', JSON.stringify({ id: value }));
                }
            }
        },
        filteredModels() {
            return this.availableModels.filter(model => !this.isModelHidden(model));
        }
    },
    template: `
            <div class="input-wrapper">
                <label class="input-label">{{ label }}</label>
                <!-- v-model bound directly to the computed setter eliminates DOM sync lag -->
                <select v-model="selectedModelId">
                    <option value="" disabled>{{ isLoading ? 'Fetching models...' : placeholder }}</option>
                    <option v-for="model in filteredModels" :key="model.id" :value="model.id">
                        {{ model.id }} |
                            visual: {{ model.hasVision === true ? '✅' : (model.hasVision === false ? '❌' : '?') }} |
                            tools: {{ model.hasTools === true ? '✅' : (model.hasTools === false ? '❌' : '?') }} |
                            embedding: {{ model.hasEmbedding === true ? '✅' : (model.hasEmbedding === false ? '❌' : '?') }}
                        | [{{ model.provided_by || model.owned_by || '?' }}]
                    </option>
                    <option v-if="selectedModelId && !availableModels.find(m => m.id === selectedModelId)" :value="selectedModelId">
                        {{ selectedModelId }} | (Offline)
                    </option>
                </select>
                <button class="btn-icon" @click="fetchModels(true)" :disabled="isLoading || isProbing" title="Force Refresh & Clear Cache">🔄</button>
            </div>
        `,
    methods: {
        isModelHidden(model) {
            if (this.fieldId.includes('LLM_CONFIG')) {
                return model.hasEmbedding === true;
            }
            if (this.fieldId.includes('VLM_CONFIG')) {
                return model.hasVision === false;
            }
            if (this.fieldId.includes('EMBEDDING_CONFIG')) {
                return model.hasEmbedding === false;
            }
            return false;
        },
        async fetchModels(forceRefresh = false) {
            this.isLoading = true;
            try {
                const localHost = window.location.protocol === 'file:'
                    ? `http://${appStore.host}${appStore.port ? `:${appStore.port}` : ''}`
                    : window.location.protocol + '//' + window.location.host;

                const cacheKey = `ara_model_cache_${this.fieldId}_${appStore.apiData[this.fieldId] || 'default'}`;
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

                const [hasVision, hasTools, hasEmbedding] = await Promise.all([
                    ApiService.probeModelCapability(modelId, 'vision', this.fieldId),
                    ApiService.probeModelCapability(modelId, 'tools', this.fieldId),
                    ApiService.probeModelCapability(modelId, 'embed', this.fieldId)
                ]);

                this.availableModels[i].hasVision = hasVision;
                this.availableModels[i].hasTools = hasTools;
                this.availableModels[i].hasEmbedding = hasEmbedding;

                cachedData[modelId] = {
                    hasVision,
                    hasTools,
                    hasEmbedding
                };
                localStorage.setItem(cacheKey, JSON.stringify(cachedData));
            }

            this.isProbing = false;
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
        'setting-modal-textarea': SettingModalTextarea,
        'setting-segmented-button': SettingSegmentedButton,
        'setting-slider': SettingSlider,
        'setting-switch': SettingSwitch,
        'setting-voice-dropdown': SettingVoiceDropdown,
        'setting-tools-section': SettingToolsSection
    },
    props: ['initialKeys', 'schema'],
    data() {
        return {
            apiData: JSON.parse(JSON.stringify(this.initialKeys))
        };
    },
    computed: {
        appSchema() {
            return this.schema.appSettings || {};
        },
        proxySchema() {
            const filtered = {};
            for (const key in this.schema) {
                if (key !== 'appSettings') {
                    filtered[key] = this.schema[key];
                }
            }
            return filtered;
        },
        currentProvider() {
            return this.apiData.apiProvider || 'OPENAI';
        },
        isGoogle() {
            return this.currentProvider.includes('GOOGLE');
        },
        isXai() {
            return this.currentProvider.includes('XAI');
        },
        isRealtimeMode() {
            return this.apiData.audioInputMode === 'realtime_api';
        },
        isServerVad() {
            return this.apiData.turnDetectionType === 'server_vad';
        },
        currentModels() {
            const map = this.appSchema.modelsMap || {};
            return map[this.currentProvider] || map['OPENAI'] || [];
        },
        currentVoices() {
            const map = this.appSchema.voicesMap || {};
            return map[this.currentProvider] || map['OPENAI'] || {};
        }
    },
    watch: {
        currentProvider(newProvider) {
            if (!this.currentModels.includes(this.apiData.model)) {
                this.apiData.model = this.currentModels[0] || '';
            }
            const voiceKeys = Object.keys(this.currentVoices);
            if (!voiceKeys.includes(this.apiData.voice)) {
                this.apiData.voice = voiceKeys[0] || '';
            }
        }
    },
    template: `
        <div class="modal">
            <div class="modal-content panel wide-modal">
                <div class="modal-header">
                    <h2 class="modal-title">API Configuration</h2>
                    <span class="close-btn" @click="$emit('close')">&times;</span>
                </div>
                <div class="modal-body">
                    <div class="settings-grid">
                        <!-- 1. GENERAL APP SETTINGS -->
                        <settings-group title="App Controls">
                            <setting-slider
                                label="Volume"
                                v-model="apiData.volume"
                                :min="0" :max="100"
                                :displayFormatter="v => v + '%'"
                            />
                            <setting-modal-textarea
                                label="System Prompt"
                                placeholder="e.g. You are a helpful assistant."
                                v-model="apiData.systemPrompt"
                            />
                            <div class="input-wrapper">
                                <label class="input-label">Model</label>
                                <select v-model="apiData.model">
                                    <option v-for="m in currentModels" :key="m" :value="m">{{ m }}</option>
                                </select>
                            </div>
                            <setting-segmented-button
                                label="API Provider"
                                :options="appSchema.providers || []"
                                v-model="apiData.apiProvider"
                            />
                            <setting-voice-dropdown
                                label="Voice"
                                :voices="currentVoices"
                                v-model="apiData.voice"
                            />
                            <setting-slider
                                v-if="!isGoogle && !isXai"
                                label="Speech Speed"
                                v-model="apiData.speedProgress"
                                :min="25" :max="150"
                                :displayFormatter="v => (v / 100).toFixed(2) + 'x'"
                            />
                            <setting-slider
                                v-if="!isGoogle && !isXai"
                                label="Temperature"
                                v-model="apiData.temperatureProgress"
                                :min="0" :max="100"
                                :displayFormatter="v => (0.6 + (v / 100) * 0.6).toFixed(2)"
                            />
                            <setting-segmented-button
                                label="Audio Input"
                                :options="appSchema.audioInputModes || []"
                                v-model="apiData.audioInputMode"
                            />
                        </settings-group>

                        <!-- 2. PROVIDER SPECIFIC SETTINGS -->
                        <settings-group title="Voice & Realtime Settings">
                            <!-- Azure Settings -->
                            <template v-if="!isRealtimeMode">
                                <h3>Azure Speech Settings</h3>
                                <div class="input-wrapper">
                                    <label class="input-label">Speech Language</label>
                                    <select v-model="apiData.language">
                                        <option v-for="lang in appSchema.languages" :key="lang.code" :value="lang.code">
                                            {{ lang.name }}
                                        </option>
                                    </select>
                                </div>
                                <setting-slider
                                    label="Silence Timeout"
                                    v-model="apiData.silenceTimeout"
                                    :min="0" :max="2000" :step="50"
                                    :displayFormatter="v => v + 'ms'"
                                />
                                <setting-slider
                                    label="ASR Confidence Threshold"
                                    v-model="apiData.confidenceThreshold"
                                    :min="0" :max="1" :step="0.05"
                                    :displayFormatter="v => Math.round(v * 100) + '%'"
                                />
                            </template>

                            <!-- Google Live API Settings -->
                            <template v-else-if="isGoogle">
                                <h3>Google Live API Settings</h3>
                                <setting-segmented-button
                                    label="Start Sensitivity"
                                    :options="['LOW', 'HIGH']"
                                    v-model="apiData.googleStartSensitivity"
                                />
                                <setting-segmented-button
                                    label="End Sensitivity"
                                    :options="['LOW', 'HIGH']"
                                    v-model="apiData.googleEndSensitivity"
                                />
                                <setting-slider
                                    label="Prefix Padding"
                                    v-model="apiData.googlePrefixPaddingMs"
                                    :min="0" :max="500" :step="10"
                                    :displayFormatter="v => v + ' ms'"
                                />
                                <setting-slider
                                    label="Silence Duration"
                                    v-model="apiData.googleSilenceDurationMs"
                                    :min="100" :max="2000" :step="50"
                                    :displayFormatter="v => v + ' ms'"
                                />
                                <div class="input-wrapper">
                                    <label class="input-label">Thinking Level</label>
                                    <select v-model="apiData.googleThinkingLevel">
                                        <option v-for="lvl in ['minimal', 'low', 'medium', 'high']" :key="lvl" :value="lvl">{{ lvl }}</option>
                                    </select>
                                </div>
                                <setting-switch
                                    label="Show Thinking"
                                    description="Display AI thinking traces in chat"
                                    v-model="apiData.googleShowThinking"
                                />
                                <setting-switch
                                    label="Long Sessions"
                                    description="Enable context compression for unlimited session length"
                                    v-model="apiData.googleContextCompression"
                                />
                            </template>

                            <!-- xAI Voice Settings -->
                            <template v-else-if="isXai">
                                <h3>x.ai Voice Agent Settings</h3>
                                <setting-slider
                                    label="VAD Activation Threshold"
                                    v-model="apiData.vadThreshold"
                                    :min="0.1" :max="0.9" :step="0.05"
                                    :displayFormatter="v => Number(v).toFixed(2)"
                                />
                                <setting-slider
                                    label="Prefix Padding"
                                    v-model="apiData.prefixPadding"
                                    :min="0" :max="1000" :step="50"
                                    :displayFormatter="v => v + ' ms'"
                                />
                                <setting-slider
                                    label="Silence Duration"
                                    v-model="apiData.silenceDuration"
                                    :min="200" :max="2000" :step="50"
                                    :displayFormatter="v => v + ' ms'"
                                />
                            </template>

                            <!-- OpenAI Realtime Settings -->
                            <template v-else>
                                <h3>Voice API Settings</h3>
                                <div class="input-wrapper">
                                    <label class="input-label">Transcription Model</label>
                                    <select v-model="apiData.transcriptionModel">
                                        <option v-for="tm in appSchema.transcriptionModels" :key="tm" :value="tm">{{ tm }}</option>
                                    </select>
                                </div>
                                <setting-input label="Transcription Lang" v-model="apiData.transcriptionLanguage" placeholder="e.g. de, en" />
                                <setting-segmented-button
                                    label="Turn Detection"
                                    :options="appSchema.turnDetectionTypes || []"
                                    v-model="apiData.turnDetectionType"
                                />
                                <template v-if="isServerVad">
                                    <setting-slider label="VAD Threshold" v-model="apiData.vadThreshold" :min="0" :max="1" :step="0.05" :displayFormatter="v => Number(v).toFixed(2)" />
                                    <setting-slider label="Prefix Padding" v-model="apiData.prefixPadding" :min="0" :max="1000" :step="50" :displayFormatter="v => v + ' ms'" />
                                    <setting-slider label="Silence Duration" v-model="apiData.silenceDuration" :min="200" :max="2000" :step="50" :displayFormatter="v => v + ' ms'" />
                                </template>
                            </template>
                        </settings-group>

                        <!-- 3. FUNCTION CALLING & TOOLS -->
                        <settings-group title="Function Calls">
                            <setting-tools-section
                                :availableTools="appSchema.availableTools || []"
                                v-model:enabledTools="apiData.enabledTools"
                                v-model:googleSearch="apiData.googleSearchGrounding"
                                v-model:xaiWebSearch="apiData.xaiWebSearch"
                                v-model:xaiXSearch="apiData.xaiXSearch"
                                :isGoogle="isGoogle"
                                :isXai="isXai"
                            />
                        </settings-group>

                        <settings-group
                            v-for="(fields, categoryKey) in proxySchema"
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
                                />
                                <setting-segmented-button
                                    v-else-if="field.type === 'options'"
                                    :label="field.label"
                                    :options="field.options || []"
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
                </div>
                <div class="modal-footer">
                    <button @click="save" class="btn-save">Save Config</button>
                    <button @click="restart" class="btn-save">Restart Connection</button>
                    <button @click="saveAndRestart" class="btn-save">Save Config & Restart Connection</button>
                </div>
            </div>
        </div>
    `,
    methods: {
        async save() {
            await appStore.saveApiConfig(this.apiData);
            this.$emit('close');
        },
        restart() {
            appStore.restart();
            this.$emit('close');
        },
        async saveAndRestart() {
            await appStore.saveApiConfig(this.apiData);
            appStore.restart();
            this.$emit('close');
        },
        silentSave() {
            ApiService.sendSocketMessage(appStore.socket, {
                type: appStore.constants.MSG_SAVE_API_KEYS,
                data: this.apiData
            });
        },
        handleInputCommit(fieldId) {
            const autoSaveFields = [
                'CUSTOM_LLM_ENDPOINT', 'CUSTOM_LLM_KEY',
                'CUSTOM_VLM_ENDPOINT', 'CUSTOM_VLM_KEY',
                'CUSTOM_EMBEDDING_ENDPOINT', 'CUSTOM_EMBEDDING_KEY'
            ];

            if (autoSaveFields.includes(fieldId)) {
                this.silentSave();
                console.log(`Saved ${fieldId} via commit action.`);
            }
        },
        formatTitle(key) {
            return key.charAt(0).toUpperCase() + key.slice(1);
        }
    },
};