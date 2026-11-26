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
                        <div class="input-wrapper" style="flex-direction: row; gap: 10px;">
                            <input type="text" v-model="newExpName" placeholder="New Experiment Name" @keyup.enter="handleCreate" style="flex: 1;">
                            <button @click="handleCreate" style="width: auto;">Create Experiment</button>
                        </div>
                    </div>

                    <!-- Existing Experiments -->
                    <div class="settings-group" v-for="exp in experiments" :key="exp.id" style="border: 1px solid #333; padding: 15px; border-radius: 8px; margin-bottom: 20px;">
                        <h3 style="display: flex; justify-content: space-between; align-items: center; margin-top: 0;">
                            <span>{{ exp.name }} <span style="font-size: 0.8em; color: #888;">({{ exp.id }})</span></span>
                            <button @click="deleteExperiment(exp.id)" class="btn-icon delete-btn" title="Delete Experiment">🗑️</button>
                        </h3>

                        <!-- 1. Assigned Users -->
                        <div class="input-wrapper" style="margin-bottom: 15px;">
                            <label class="input-label">Assigned Users</label>
                            <div style="margin-bottom: 10px;">
                                <span v-if="!exp.users || exp.users.length === 0" style="color: #666; font-size: 0.9em;">No users assigned</span>
                                <span v-for="u in exp.users" :key="u" class="pipeline-badge blue" style="margin-right: 5px;">
                                    {{ u }} <span @click="handleUnassign(exp.id, u)" style="cursor:pointer; color:#ff6b6b; margin-left: 4px;">&times;</span>
                                </span>
                            </div>
                            <div style="display: flex; gap: 10px;">
                                <select v-model="selectedUsers[exp.id]" style="flex: 1;">
                                    <option disabled value="">Assign a user...</option>
                                    <option v-for="user in allUsers" :key="user" :value="user" :disabled="exp.users && exp.users.includes(user)">{{ user }}</option>
                                </select>
                                <button class="btn-secondary" @click="handleAssign(exp.id)" style="width: auto;">Assign</button>
                            </div>
                        </div>

                        <!-- 2. Sequence Configuration (Experiment Builder) -->
                        <div class="input-wrapper" style="margin-bottom: 15px;">
                            <label class="input-label">Sequence Configuration</label>

                            <!-- Toggle Button -->
                            <button
                                v-if="editingSequenceExpId !== exp.id"
                                @click="editingSequenceExpId = exp.id"
                                class="btn-secondary"
                                style="width: 100%; text-align: left;"
                            >
                                ⚙️ Edit Sequence Data ({{ exp.config && exp.config.sequence ? Object.keys(exp.config.sequence).length : 0 }} Steps)
                            </button>

                            <!-- Inline Builder Component -->
                            <experiment-builder
                                v-if="editingSequenceExpId === exp.id"
                                :initial-config="exp.config"
                                @save="handleSaveConfig(exp.id, $event)"
                                @close="editingSequenceExpId = null"
                            ></experiment-builder>
                        </div>

                        <!-- 3. Ingested Documents -->
                        <div class="input-wrapper">
                            <label class="input-label">Ingested Documents</label>

                            <ul v-if="exp.files && exp.files.length > 0" style="list-style: none; padding: 0; margin-bottom: 10px;">
                                <li v-for="file in exp.files" :key="file.id" style="display: flex; justify-content: space-between; align-items: center; padding: 8px 10px; margin-bottom: 5px; border-radius: 4px;">
                                    <span>{{ file.name }}</span>
                                    <div style="display: flex; gap: 5px;">
                                        <button @click="viewFileContent(exp.id, file.name)" class="btn-secondary btn-sm" title="View Text Content">Txt</button>
                                        <button @click="viewRawFile(exp.id, file.name)" class="btn-secondary btn-sm" title="Preview Original File">🔍</button>
                                        <button @click="deleteFile(exp.id, file.name)" class="btn-danger btn-sm">X</button>
                                    </div>
                                </li>
                            </ul>
                            <span v-else style="color: #666; font-size: 0.9em; display: block; margin-bottom: 10px;">No files ingested yet.</span>

                            <ul v-if="hasUploadingFiles(exp.id)" style="list-style: none; padding: 0; margin-bottom: 10px;">
                                <template v-for="(statusMsg, uploadKey) in uploadingFiles" :key="uploadKey">
                                    <li v-if="uploadKey.startsWith(exp.id + '_')" style="padding: 8px 10px; margin-bottom: 5px; border-radius: 4px;">
                                        <span>{{ uploadKey.substring(uploadKey.indexOf('_') + 1) }}</span>
                                        <span class="pipeline-badge yellow" style="font-size: 0.8em; float: right;">
                                            <span class="spinner">⏳</span> {{ statusMsg }}
                                        </span>
                                    </li>
                                </template>
                            </ul>

                            <div style="display: flex; gap: 10px;">
                                <input type="file" :ref="'file_' + exp.id" style="flex: 1;">
                                <button class="btn-secondary" @click="handleUpload(exp.id)" style="width: auto;">Upload & Embed</button>
                            </div>
                        </div>
                    </div>
                </div>
                <div class="modal-footer">
                    <button @click="$emit('close')" class="btn-secondary">Close</button>
                </div>
            </div>
            <!-- File Preview Backdrop overlay -->
            <div v-if="viewingContent !== null || viewingFileUrl !== null" class="modal-backdrop" style="position: fixed; top: 0; left: 0; width: 100%; height: 100%; display: flex; align-items: center; justify-content: center; z-index: 1050;">
                <div class="modal-content panel" style="padding: 20px; width: 80vw; max-width: 900px; max-height: 85vh; display: flex; flex-direction: column; align-items: center;">
                    <h3 style="color: #111;">{{ viewingContent !== null ? 'Content' : 'File Preview' }}: {{ viewingFilename }}</h3>

                    <div style="margin: 15px 0; width: 100%; flex: 1; display: flex; justify-content: center; overflow-y: auto; min-height: 0;">
                        <!-- Text Content -->
                        <pre v-if="viewingContent !== null" style="white-space: pre-wrap; word-break: break-word; color: #333; padding: 15px; border-radius: 4px; width: 100%;">{{ viewingContent }}</pre>

                        <!-- Media/File Preview -->
                        <template v-else>
                            <img v-if="isImage" :src="viewingFileUrl" :alt="viewingFilename" style="max-width: 100%; max-height: 60vh; object-fit: contain; border: 1px solid #ddd; border-radius: 4px;">
                            <iframe v-else-if="isPdf" :src="viewingFileUrl" style="width: 100%; height: 60vh; border: 1px solid #ddd; border-radius: 4px;"></iframe>
                            <div v-else style="padding: 40px; text-align: center; color: #333; width: 100%; border-radius: 4px;">
                                <p>Direct preview not available for <strong>.{{ fileExtension }}</strong> files.</p>
                                <a :href="viewingFileUrl" download class="btn-secondary" style="text-decoration: none; display: inline-block; padding: 8px 16px; margin-top: 10px; color: #333;">Download File</a>
                            </div>
                        </template>
                    </div>

                    <div style="display: flex; gap: 10px; margin-top: 10px;">
                        <a v-if="viewingFileUrl" :href="viewingFileUrl" target="_blank"><button class="btn-secondary" style="color: #333; border-color: #ccc;">Open in New Tab</button></a>
                        <button @click="closeModal" class="btn-secondary" style="color: #333; border-color: #ccc;">Close Preview</button>
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