const ExperimentManagerModal = {
    props: ['experiments', 'allUsers'],
    data() {
        return {
            newExpName: '',
            selectedUsers: {},
            viewingContent: null,
            viewingFileUrl: null,
            viewingFilename: '',
            uploadingFiles: {},
            editingSequenceExpId: null
        };
    },
    computed: {
        fileExtension() {
            if (!this.viewingFilename) return '';
            return this.viewingFilename.split('.').pop().toLowerCase();
        },
        isImage() {
            return ['png', 'jpg', 'jpeg', 'gif', 'webp', 'svg'].includes(this.fileExtension);
        },
        isPdf() {
            return this.fileExtension === 'pdf';
        }
    },
    template: `
        <div class="modal">
            <div class="modal-content panel wide-modal">
                <div class="modal-header">
                    <h2 class="modal-title">Experiments</h2>
                    <span class="close-btn" @click="$emit('close')">&times;</span>
                </div>
                <div class="modal-body">
                    <!-- Create New Experiment -->
                    <div class="settings-group">
                        <h3>Create New</h3>
                        <div class="input-wrapper">
                            <input type="text" v-model="newExpName" placeholder="New Experiment Name" @keyup.enter="handleCreate">
                            <button @click="handleCreate">Create Experiment</button>
                        </div>
                    </div>

                    <!-- Existing Experiments -->
                    <div class="settings-group bordered-card" v-for="exp in experiments" :key="exp.id">
                        <div class="turn-header">
                            <h3 class="exp-card-title">
                                {{ exp.name }} <span class="text-muted">({{ exp.id }})</span>
                            </h3>
                            <button @click="deleteExperiment(exp.id)" class="btn-icon delete-btn" title="Delete Experiment">🗑️</button>
                        </div>

                        <!-- 1. Assigned Users -->
                        <div class="input-wrapper stack">
                            <label class="input-label">Assigned Users</label>
                            <div class="gapped-flexrow">
                                <span v-if="!exp.users || exp.users.length === 0" class="text-muted">No users assigned</span>
                                <span v-for="u in exp.users" :key="u" class="pipeline-badge blue">
                                    {{ u }} <span @click="handleUnassign(exp.id, u)" class="badge-remove">&times;</span>
                                </span>
                            </div>
                            <div class="input-wrapper">
                                <select v-model="selectedUsers[exp.id]">
                                    <option disabled value="">Assign a user...</option>
                                    <option v-for="user in allUsers" :key="user" :value="user" :disabled="exp.users && exp.users.includes(user)">{{ user }}</option>
                                </select>
                                <button class="btn-secondary" @click="handleAssign(exp.id)">Assign</button>
                            </div>
                        </div>

                        <!-- 2. Sequence Configuration (Experiment Builder) -->
                        <div class="input-wrapper">
                            <label class="input-label">Sequence Configuration</label>

                            <button type="button" disabled class="btn-secondary btn-block btn-split">
                                <span>⚙️ Sequence Builder</span>
                                <span class="pipeline-badge yellow">Not yet implemented</span>
                            </button>

                             <!-- Toggle Button (Commented out: not yet implemented) -->
                            <button
                                v-if="false && editingSequenceExpId !== exp.id"
                                @click="editingSequenceExpId = exp.id"
                                class="btn-secondary btn-block btn-split"
                            >
                                ⚙️ Edit Sequence Data ({{ exp.config && exp.config.sequence ? Object.keys(exp.config.sequence).length : 0 }} Steps)
                            </button>

                            <!-- Inline Builder Component (Deactivated) -->
                            <experiment-builder
                                v-if="false && editingSequenceExpId === exp.id"
                                :initial-config="exp.config"
                                @save="handleSaveConfig(exp.id, $event)"
                                @close="editingSequenceExpId = null"
                            ></experiment-builder>
                        </div>

                        <!-- 3. Ingested Documents -->
                        <div class="input-wrapper stack">
                            <label class="input-label">Ingested Documents</label>

                            <ul v-if="exp.files && exp.files.length > 0" class="file-list">
                                <li v-for="file in exp.files" :key="file.id" class="file-list-item">
                                    <span>{{ file.name }}</span>
                                    <div class="gapped-flexrow">
                                        <button @click="viewFileContent(exp.id, file.name)" class="btn-secondary btn-sm" title="View Text Content">Txt</button>
                                        <button @click="viewRawFile(exp.id, file.name)" class="btn-secondary btn-sm" title="Preview Original File">🔍</button>
                                        <button @click="deleteFile(exp.id, file.name)" class="btn-danger btn-sm">X</button>
                                    </div>
                                </li>
                            </ul>
                            <span v-else class="text-muted">No files ingested yet.</span>

                            <ul v-if="hasUploadingFiles(exp.id)" class="file-list">
                                <template v-for="(statusMsg, uploadKey) in uploadingFiles" :key="uploadKey">
                                    <li v-if="uploadKey.startsWith(exp.id + '_')" class="file-list-item">
                                        <span>{{ uploadKey.substring(uploadKey.indexOf('_') + 1) }}</span>
                                        <span class="pipeline-badge yellow">
                                            <span class="spinner">⏳</span> {{ statusMsg }}
                                        </span>
                                    </li>
                                </template>
                            </ul>

                            <div class="input-wrapper">
                                <input type="file" :ref="'file_' + exp.id">
                                <button class="btn-secondary" @click="handleUpload(exp.id)">Upload & Embed</button>
                            </div>
                        </div>
                    </div>
                </div>
                <div class="modal-footer">
                    <button @click="$emit('close')" class="btn-secondary">Close</button>
                </div>
            </div>

            <!-- File Preview Modal -->
            <div v-if="viewingContent !== null || viewingFileUrl !== null" class="modal modal-stacked">
                <div class="modal-content panel preview-modal">
                    <h3 class="modal-title">{{ viewingContent !== null ? 'Content' : 'File Preview' }}: {{ viewingFilename }}</h3>

                    <div class="preview-body">
                        <pre v-if="viewingContent !== null" class="preview-text">{{ viewingContent }}</pre>

                        <template v-else>
                            <img v-if="isImage" :src="viewingFileUrl" :alt="viewingFilename" class="preview-image">
                            <iframe v-else-if="isPdf" :src="viewingFileUrl" class="preview-frame"></iframe>
                            <div v-else class="preview-fallback">
                                <p>Direct preview not available for <strong>.{{ fileExtension }}</strong> files.</p>
                                <a :href="viewingFileUrl" download class="btn-link btn-secondary">Download File</a>
                            </div>
                        </template>
                    </div>

                    <div class="modal-actions">
                        <a v-if="viewingFileUrl" :href="viewingFileUrl" target="_blank" class="btn-link btn-secondary">Open in New Tab</a>
                        <button @click="closeModal" class="btn-secondary">Close Preview</button>
                    </div>
                </div>
            </div>
        </div>
    `,
    methods: {
        async handleSaveConfig(expId, configJson) {
            // Closes the builder panel
            this.editingSequenceExpId = null;
            // Commits the JSON to the Database
            await appStore.saveExperimentConfig(expId, configJson);
        },
        hasUploadingFiles(expId) {
            return Object.keys(this.uploadingFiles).some(key => key.startsWith(expId + '_'));
        },
        handleCreate() {
            if (this.newExpName.trim()) {
                appStore.createExperiment(this.newExpName.trim());
                this.newExpName = '';
            }
        },
        handleAssign(expId) {
            const userId = this.selectedUsers[expId];
            if (userId) {
                appStore.assignUserToExperiment({ expId, userId });
                this.selectedUsers[expId] = '';
            }
        },
        handleUnassign(expId,userId) {
            console.log("removing user", userId);
            appStore.removeUserFromExperiment({ expId, userId });
        },
        async handleUpload(expId) {
            const fileInput = this.$refs['file_' + expId][0];
            if (!fileInput || fileInput.files.length === 0) return;

            const file = fileInput.files[0];
            fileInput.value = ''; // clear input

            const progressKey = `${expId}_${file.name}`;

            // Guard against accidental duplicate
            if (this.uploadingFiles[progressKey]) return;

            // Force Vue to react by re-assigning the whole object
            this.uploadingFiles = { ...this.uploadingFiles, [progressKey]: "Uploading & initializing..." };

            //this.$emit('upload', { expId, file: file });

            const formData = new FormData();
            formData.append('file', file);

            const pollInterval = setInterval(async () => {
                try {
                    const encodedName = encodeURIComponent(file.name).replace(/\+/g, '%20');
                    const res = await fetch(`/rag/experiments/${expId}/progress?fileName=${encodedName}`);

                    if (res.ok) {
                        const data = await res.json();

                        this.uploadingFiles = { ...this.uploadingFiles, [progressKey]: data.message || data.status };

                        if (data.status === 'completed' || data.status === 'error') {
                            clearInterval(pollInterval);
                            setTimeout(async () => {
                                const updated = { ...this.uploadingFiles };
                                delete updated[progressKey];
                                this.uploadingFiles = updated;
                                if (data.status === 'completed') {
                                    await appStore.fetchExperiments();
                                }
                            }, 1000);
                        }
                    }
                } catch (e) {
                    console.error("Polling error", e);
                }
            }, 1000);

            try {
                const res = await fetch(`/rag/experiments/${expId}/files`, {
                    method: 'POST',
                    body: formData
                });

                if (!res.ok) {
                    this.uploadingFiles = { ...this.uploadingFiles, [progressKey]: "Upload failed." };
                    clearInterval(pollInterval);
                }

            } catch (e) {
                this.uploadingFiles = { ...this.uploadingFiles, [progressKey]: "Network error." };
                clearInterval(pollInterval);
            }
        },
        async viewFileContent(expId, fileName) {
            try {
                const encoded = encodeURIComponent(fileName).replace(/\+/g, '%20');
                const res = await fetch(`/rag/experiments/${expId}/files/${encoded}/content`);
                if (res.ok) {
                    const data = await res.json();
                    this.viewingContent = data.content;
                    this.viewingFilename = fileName;
                }
            } catch (e) {
                console.error(`Failed to load content for ${fileName}:`, e);
            }
        },
        viewRawFile(expId, fileName) {
            const encoded = encodeURIComponent(fileName).replace(/\+/g, '%20');
            this.viewingFileUrl = `/rag/experiments/${expId}/files/${encoded}/file`;
            this.viewingFilename = fileName;
        },
        closeModal() {
            this.viewingContent = null;
            this.viewingFileUrl = null;
        },
        async deleteFile(expId, fileName) {
            if (!confirm(`Are you sure you want to delete ${fileName}?`)) return;
            await appStore.deleteExperimentFile(expId, fileName);
        },
        async deleteExperiment(expId) {
            if (!confirm('Are you sure you want to delete this entire experiment? This will permanently wipe all ingested files, vectors, and knowledge.')) {
                return;
            }
            await appStore.deleteExperiment(expId);
        }
    }
};