const ApiService = {
    async getConstants() {
        const res = await fetch('/constants');
        if (!res.ok) throw new Error("Failed to fetch constants");
        return await res.json();
    },

    async getStatus(route, service) {
        const res = await fetch(route);
        if (!res.ok) throw new Error(`${service} is offline`);
        return true;
    },

    async getUsers(route) {
        const res = await fetch(route);
        if (!res.ok) throw new Error("Failed to fetch users");
        return await res.json();
    },

    async getUserHistory(route, userId) {
        const res = await fetch(`${route}?userId=${userId}`);
        if (!res.ok) throw new Error(`Failed to load history for ${userId}`);
        return await res.json();
    },

    async getExperiments(route) {
        const res = await fetch(route);
        if (!res.ok) throw new Error("Failed to fetch experiments");
        return await res.json();
    },

    async createExperiment(route, expId, name) {
        const res = await fetch(route, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ experimentId: expId, name: name })
        });
        if (!res.ok) throw new Error("Failed to create experiment");
        return true;
    },

    async deleteExperiment(route, expId) {
        const res = await fetch(`${route}/${expId}`, { method: 'DELETE' });
        if (!res.ok) throw new Error("Failed to delete experiment");
        return true;
    },

    async deleteExperimentFile(route, expId, fileName) {
        const encoded = encodeURIComponent(fileName).replace(/\+/g, '%20');
        const res = await fetch(`${route}/${expId}/files/${encoded}`, { method: 'DELETE' });
        if (!res.ok) throw new Error("Failed to delete file");
        return true;
    },

    async assignUserToExperiment(route, expId, userId) {
        const res = await fetch(`${route}/${expId}/users`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ userId: userId })
        });
        if (!res.ok) throw new Error("Failed to assign user");
        return true;
    },

    async removeUserFromExperiment(route, expId, userId) {
        const res = await fetch(`${route}/${expId}/users/${userId}`, { method: 'DELETE' });
        if (!res.ok) throw new Error("Failed to remove user");
        return true;
    },

    async probeModelCapability(modelId, probeType, fieldId) {
        try {
            const safeModelId = modelId ? encodeURIComponent(modelId) : '';

            const response = await fetch(`/proxy/probe?modelId=${safeModelId}&probeType=${probeType}&fieldId=${fieldId}`);

            if (response.ok) {
                const json = await response.json();
                return json.supported === true;
            }
            return false;
        } catch (e) {
            return false;
        }
    },

    sendSocketMessage(socket, payload) {
        try {
            if (socket && socket.readyState === WebSocket.OPEN) {
                socket.send(JSON.stringify(payload));
                return true;
            }
            return false;
        } catch (e) {
            console.error("Socket send error:", e);
            return false;
        }
    }
};