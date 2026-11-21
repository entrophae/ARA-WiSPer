const ExperimentBuilder = {
    props: ['initialConfig'],
    emits: ['save', 'close'],
    template: `
        <div class="experiment-builder" style="border: 1px solid #444; padding: 15px; border-radius: 8px; margin-top: 10px">
            <div style="display: flex; justify-content: space-between; margin-bottom: 15px;">
                <h4 style="margin: 0;">Sequence Builder</h4>
                <div>
                    <button class="btn-secondary" @click="$emit('close')" style="margin-right: 10px;">Cancel</button>
                    <button @click="generateJSON">Save Configuration</button>
                </div>
            </div>

            <!-- Global Settings -->
            <div class="settings-grid" style="margin-bottom: 20px;">
                <div class="input-wrapper">
                    <label class="input-label">Speech Speed</label>
                    <input type="number" v-model="defaults.speech_speed" min="0" max="200">
                </div>
                <div class="input-wrapper">
                    <label class="input-label">Speech Pitch</label>
                    <input type="number" v-model="defaults.speech_pitch" min="0" max="200">
                </div>
                <div class="switch-wrapper" style="align-items: center;">
                    <label class="switch-text">Enable LLM Usage</label>
                    <label class="switch-toggle">
                        <input type="checkbox" v-model="defaults.llm_usage" />
                        <span class="slider-round"></span>
                    </label>
                </div>
            </div>

            <!-- Sequence Builder -->
            <div class="builder-section">
                <h4 style="margin-bottom: 10px;">Steps</h4>

                <div v-for="(step, index) in steps" :key="step.id" class="panel" style="margin-bottom: 15px; padding: 10px;">
                    <div style="display: flex; gap: 10px; align-items: center; margin-bottom: 10px;">
                        <span style="font-weight: bold; color: #aaa;">#{{ index }}</span>
                        <input type="text" v-model="step.name" placeholder="Step Name" style="flex: 1;">
                        <select v-model="step.type" @change="resetStepValue(step)">
                            <option v-for="type in availableTypes" :key="type" :value="type">{{ type }}</option>
                        </select>
                        <button class="btn-danger btn-icon" @click="removeStep(index)" title="Remove Step">🗑️</button>
                    </div>

                    <div class="step-body" style="padding-left: 25px;">
                        <!-- Dynamic Inputs -->
                        <div v-if="step.type === 'look_at' || step.type === 'move_to' || step.type === 'point_at'" style="display: flex; gap: 10px;">
                            <label>X: <input type="number" step="0.1" v-model="step.value.x" style="width: 60px;"></label>
                            <label>Y: <input type="number" step="0.1" v-model="step.value.y" style="width: 60px;"></label>
                            <label>Z: <input type="number" step="0.1" v-model="step.value.z" style="width: 60px;"></label>
                        </div>
                        <div v-else-if="step.type === 'wait' || step.type === 'time'" class="input-wrapper">
                            <label>Duration (ms):</label>
                            <input type="number" v-model="step.value">
                        </div>
                        <div v-else-if="['say', 'random_say', 'display', 'animation', 'sound'].includes(step.type)" class="input-wrapper">
                            <label>Value / Content:</label>
                            <input type="text" v-model="step.value" style="width: 100%;">
                        </div>
                        <div v-else-if="step.type === 'llm'" class="switch-wrapper">
                            <label>LLM Active</label>
                            <input type="checkbox" v-model="step.value">
                        </div>

                        <!-- Step -->
                        <div style="display: flex; gap: 20px; margin-top: 10px; font-size: 0.9em; color: #aaa;">
                            <label><input type="checkbox" v-model="step.stopping"> Blocking (Wait for completion)</label>
                            <label><input type="checkbox" v-model="step.requires_user_interaction"> Requires Interaction</label>
                        </div>

                        <!-- Interactions -->
                        <div v-if="step.requires_user_interaction" style="margin-top: 15px; padding: 10px; border-left: 2px solid #555;">
                            <h5>Decisions / Interactions</h5>
                            <div v-for="(interaction, iIndex) in step.interactions" :key="interaction.id" style="display: flex; gap: 10px; margin-bottom: 5px; align-items: center;">
                                <input type="text" v-model="interaction.name" placeholder="Interaction Name" style="width: 150px;">
                                <select v-model="interaction.type" style="width: 100px;">
                                    <option value="say">Say</option>
                                    <option value="empty">Empty (Jump)</option>
                                </select>
                                <input v-if="interaction.type === 'say'" type="text" v-model="interaction.value" placeholder="Say value..." style="flex: 1;">

                                <div style="display: flex; gap: 5px; align-items: center;">
                                    <label title="Enable Jump"><input type="checkbox" v-model="interaction.jump.enabled"> ↪️</label>
                                    <select v-if="interaction.jump.enabled" v-model="interaction.jump.target_id" style="width: 120px;">
                                        <option v-for="target in availableJumpTargets(step.id)" :key="target.id" :value="target.id">
                                            {{ target.name || target.id }}
                                        </option>
                                    </select>
                                </div>
                                <button class="btn-danger btn-sm" @click="removeInteraction(step, iIndex)">X</button>
                            </div>
                            <button class="btn-secondary btn-sm" @click="addInteraction(step)" style="margin-top: 5px;">+ Add Option</button>
                        </div>
                    </div>
                </div>

                <button class="btn-secondary" @click="addStep" style="width: 100%; border: 1px dashed #666;">+ Add New Step</button>
            </div>
        </div>
    `,
    data() {
        return {
            availableTypes: [
                'look_at', 'move_to', 'animation', 'wait', 'time', 'say',
                'sound', 'reset_look', 'display', 'reset_display',
                'point_at', 'random_say', 'empty', 'llm'
            ],
            defaults: {
                speech_speed: 75,
                speech_pitch: 80,
                llm_usage: false
            },
            steps: []
        };
    },
    mounted() {
        if (this.initialConfig) {
            // Parse existing configuration JSON into Vue Reactive state
            this.defaults = this.initialConfig.default || this.defaults;

            if (this.initialConfig.sequence) {
                // Convert sequence dictionary to sorted array
                this.steps = Object.values(this.initialConfig.sequence).sort((a,b) => a.order - b.order);

                this.steps.forEach(step => {
                    if (step.interactions) {
                        // Convert interactions dictionary to sorted array
                        step.interactions = Object.values(step.interactions).sort((a,b) => a.order - b.order);
                    } else {
                        step.interactions = [];
                    }
                    if (!step.jump) step.jump = { enabled: false, target_id: "", execute: false };
                });
            }
        }
    },
    methods: {
        generateId(prefix = 'id_') {
            return prefix + Math.random().toString(36).substr(2, 9);
        },
        availableJumpTargets(currentStepId) {
            return this.steps.filter(s => s.id !== currentStepId);
        },
        addStep() {
            this.steps.push({
                id: this.generateId('step_'),
                type: 'say',
                name: '',
                value: '',
                requires_user_interaction: false,
                stopping: true,
                jump: { enabled: false, target_id: "", execute: false },
                interactions: []
            });
        },
        removeStep(index) {
            this.steps.splice(index, 1);
        },
        addInteraction(step) {
            if (!step.interactions) step.interactions = [];
            step.interactions.push({
                id: this.generateId('int_'),
                type: 'say',
                name: '',
                value: '',
                stopping: true,
                jump: { enabled: false, target_id: "", execute: false }
            });
        },
        removeInteraction(step, index) {
            step.interactions.splice(index, 1);
        },
        resetStepValue(step) {
            if (['look_at', 'move_to', 'point_at'].includes(step.type)) {
                step.value = { x: 0.0, y: 0.0, z: 0.0 };
            } else if (['wait', 'time'].includes(step.type)) {
                step.value = 1000;
            } else if (step.type === 'llm') {
                step.value = true;
            } else {
                step.value = '';
            }
        },
        generateJSON() {
            // Convert Array format back to Target Dictionary format
            const sequenceDict = {};

            this.steps.forEach((step, index) => {
                const stepKey = `step_${index}`;
                const stepData = {
                    id: step.id,
                    type: step.type,
                    name: step.name,
                    value: step.value,
                    requires_user_interaction: step.requires_user_interaction,
                    stopping: step.stopping,
                    order: index,
                    jump: { ...step.jump }
                };

                if (step.requires_user_interaction && step.interactions.length > 0) {
                    stepData.interactions = {};
                    step.interactions.forEach((inter, iIndex) => {
                        stepData.interactions[`interaction${iIndex}`] = {
                            type: inter.type,
                            name: inter.name,
                            value: inter.value,
                            stopping: inter.stopping,
                            order: iIndex,
                            jump: { ...inter.jump }
                        };
                    });
                }

                sequenceDict[stepKey] = stepData;
            });

            const finalJSON = {
                default: this.defaults,
                sequence: sequenceDict
            };

            this.$emit('save', finalJSON);
            return finalJSON;
        }
    }
};