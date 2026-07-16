const BASE = window.location.pathname.replace(/\/$/, "");
const HREF = window.location.href.replace(/\/$/, "");
const WSS = HREF.replace(/^http/, "ws");

let mediaRecorder;
let audioChunks = [];
let fileInput, fileDisplay, fileSendBtn, start, stopBtn, apiUrlInput, copyApiBtn, connectionsNr, requestsNr, apiCardTitle, wssUrlInput, copyWssBtn, wconnectionsNr, wrequestsNr, wssState, wssCardTitle;

document.addEventListener("DOMContentLoaded", () => {
    fileInput = document.getElementById("audioFile");
    fileDisplay = document.getElementById("fileNameDisplay");
    fileSendBtn = document.getElementById("transcribe");
    startBtn = document.getElementById("start");
    stopBtn = document.getElementById("stop");
    apiUrlInput = document.getElementById("apiInput");
    copyApiBtn = document.getElementById("copyApiBtn");
    connectionsNr = document.getElementById("connCount");
    requestsNr = document.getElementById("reqCount");
    apiCardTitle = document.getElementById("apiCardTitle");
    wssUrlInput = document.getElementById("wssInput");
    copyWssBtn = document.getElementById("copyWssBtn");
    wconnectionsNr = document.getElementById("wconnCount");
    wrequestsNr = document.getElementById("wreqCount");
    wssState = document.getElementById("wssState");
    wssCardTitle = document.getElementById("wssCardTitle");

    if (apiUrlInput && copyApiBtn) {
        const fullApiUrl = `${HREF}/api`;
        apiUrlInput.value = fullApiUrl;
        
        copyApiBtn.addEventListener("click", async () => {
            try {
                await navigator.clipboard.writeText(fullApiUrl);
                const originalText = copyApiBtn.innerText;
                copyApiBtn.innerText = "Copied!";
                setTimeout(() => copyApiBtn.innerText = originalText, 2000);
            } catch (err) {
                console.error("Failed to copy!", err);
            }
        });
    }
    
    if (wssUrlInput && copyWssBtn) {
        const fullWssUrl = `${WSS}/ws/stream`;
        wssUrlInput.value = fullWssUrl;
    
        copyWssBtn.addEventListener("click", async () => {
            try {
                await navigator.clipboard.writeText(fullWssUrl);
                const originalText = copyWssBtn.innerText;
                copyWssBtn.innerText = "Copied!";
                setTimeout(() => copyWssBtn.innerText = originalText, 2000);
            } catch (err) {
                console.error("Failed to copy!", err);
            }
        });
    }

    fileSendBtn.disabled = true;
    setRecordingState(false);
    updateStats();
    loadHistory

    fileInput.addEventListener("change", () => {
        fileSendBtn.disabled = !fileInput.files.length;
        if (fileInput.files.length > 0) {
            const file = fileInput.files[0];
            const name = file.name;
            const size = formatBytes(file.size); 
            
            let typeStr = "Unknown Type";
            if (file.type) {
                typeStr = `${file.type.split("/")[0]}: ${file.type.split("/")[1]}`;
            }
            
            fileDisplay.innerText = `${name}\n${size} (${typeStr})`;
        } else {
            fileDisplay.innerText = "No file selected";
        }
    });
    
    fileSendBtn.addEventListener("click", uploadAudio);
    
    startBtn.onclick = async () => {
        const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
    
        mediaRecorder = new MediaRecorder(stream);
        audioChunks = [];
    
        mediaRecorder.ondataavailable = e => {
            audioChunks.push(e.data);
        };
    
        mediaRecorder.onstop = async () => {
            const audioBlob = new Blob(audioChunks, { type: "audio/webm" });
            setRecordingState(false);
            uploadFile(audioBlob, `Recording (${new Date().toLocaleTimeString()})`);
        };
    
        mediaRecorder.start();
        setRecordingState(true);
    };
    
    stopBtn.onclick = () => {
        if (mediaRecorder && mediaRecorder.state !== "inactive") {
            mediaRecorder.stop();
        }
        setRecordingState(false);
    };

    const ws = new WebSocket(`${WSS}/ws`);
    ws.onopen = () => {
        console.log("🟢 WebSocket Connected!");
        wssState.innerText = "🟢";
    }
    ws.onclose = () => {
        console.log("🔴 WebSocket Disconnected.")
        wssState.innerText = "🔴";
    }
    ws.onmessage = async (event) => {
        const payload = JSON.parse(event.data);
        console.log("📥 Received live event from server:", payload);
        
        if (payload.type === "new_task") {
            const safeId = payload.task_id || payload.id;
            const safeLabel = payload.label || "Audio Task";
            await createTaskUI(safeId, safeLabel);
        }
        else if (payload.type === "stats_update") {
            await updateStats(payload);
        }
    };
});

async function updateStats(data = null) {
    try {
        if (data === null) {
            const res = await fetch(`${BASE}/stats`);
            if (!res.ok) return;
            data = await res.json();
        }
        
        const connSize = Object.keys(data.connections || {}).length;
        connectionsNr.innerText = connSize;
        requestsNr.innerText = data.total_calls || 0;
        
        let apiTooltip = "Active API Connections:\n";
        for (const [ip, count] of Object.entries(data.connections || {})) {
            apiTooltip += `• ${ip} requested ${count}\n`;
        }
        apiCardTitle.title = connSize === 0 ? "No external API connections yet." : apiTooltip;

        const wconnSize = Object.keys(data.wconnections || {}).length;
        wconnectionsNr.innerText = wconnSize;
        wrequestsNr.innerText = data.total_wss_calls || 0;
        
        let wssTooltip = "Active WSS Connections:\n";
        for (const [ip, count] of Object.entries(data.wconnections || {})) {
            wssTooltip += `• ${ip} requested ${count}\n`;
        }
        wssCardTitle.title = wconnSize === 0 ? "No external WSS connections yet." : wssTooltip;
    } catch (err) {
        console.error("Could not fetch stats", err);
    }
}

function setRecordingState(isRecording) {
    if (isRecording) {
        startBtn.classList.add("inactive");
        stopBtn.classList.remove("inactive");
    } else {
        startBtn.classList.remove("inactive");
        stopBtn.classList.add("inactive");
    }
}

async function uploadFile(file, label = "Audio") {
    const formData = new FormData();
    formData.append("audio_file", file, label);

    try {
        const response = await fetch(`${BASE}/api`, {
            method: "POST",
            body: formData
        });

        if (!response.ok) throw new Error(`Server responded with ${response.status}`);

        const data = await response.json();
        const taskId = data.task_id || data.id;
        if (taskId) {
            createTaskUI(taskId, label);
        }
        
    } catch (err) {
        statusEl.innerText = "Upload failed!";
        console.error(err);
    }
}

async function createTaskUI(taskId, label){
    if (!taskId) return;
    if (document.getElementById(`task-${taskId}`)) {
        return; 
    }
    
    const container = document.getElementById("transcriptionsContainer");
    
    const taskDiv = document.createElement("div");
    taskDiv.className = "transcription-task";
    taskDiv.id = `task-${taskId}`;
    taskDiv.innerHTML = `
        <h3 class="title-el">${truncateMiddle(label, 30)}</h3>
        <div class="status-el">Processing...</div>
        <pre class="result-el"></pre>
    `;
    
    container.prepend(taskDiv);
    
    const titleEl = taskDiv.querySelector(".title-el");
    const statusEl = taskDiv.querySelector(".status-el");
    const resultEl = taskDiv.querySelector(".result-el");
    
    const pollInterval = setInterval(async () => {
        try {
            const statusRes = await fetch(`${BASE}/status/${taskId}`, { cache: "no-store" });
            if (!statusRes.ok) return;
            const task = await statusRes.json();
            
            if (task.status === "processing" && task.duration > 0) {
                const p = task.progress;
                statusEl.innerText = `⏳ Processing: ${p.percent}%`;
                resultEl.innerText = p.text; 
            } else if (task.status === "done") {
                clearInterval(pollInterval);
                statusEl.style.display = "none";
                titleEl.innerText = `✔ ${truncateMiddle(label, 30)} [${formatTime(task.duration)}]`;
                titleEl.style.color = "green";
                resultEl.innerText = task.final_text;
            } else if (task.status === "error") {
                clearInterval(pollInterval);
                statusEl.style.display = "none";
                titleEl.innerText = `❌ ${truncateMiddle(label, 30)} [Error]`;
                titleEl.style.color = "red"; 
                resultEl.innerText = task.error || "Unknown Error";
            }
        } catch (err) {
            console.error("Polling error:", err);
        }
    }, 500);
}

async function uploadAudio() {
    if (!fileInput.files.length) {
        alert("Select an audio file first");
        return;
    }

    const file = fileInput.files[0];
    uploadFile(file, file.name);
    
    fileInput.value = "";
    fileSendBtn.disabled = true;
    fileDisplay.innerText = "No file selected";
}

async function loadHistory() {
    try {
        const res = await fetch(`${BASE}/history`);
        if (!res.ok) return;
        const history = await res.json();
        
        const container = document.getElementById("transcriptionsContainer");
        
        // Loop through the history and build finished cards
        for (const item of history) {
            const taskDiv = document.createElement("div");
            taskDiv.className = "transcription-task";
            taskDiv.innerHTML = `
                <h3 class="title-el" style="color: green;">
                    ✔ ${truncateMiddle(item.label, 30)} [${formatTime(item.duration)}]
                </h3>
                <pre class="result-el">${item.final_text}</pre>
            `;
            container.prepend(taskDiv); 
        }
    } catch (err) {
        console.error("Could not fetch transcription history", err);
    }
}

function truncateMiddle(str, maxLength = 30) {
    if (!str) return "Unknown Task";
    if (str.length <= maxLength) return str;
    const charsToShow = maxLength - 3; 
    const frontChars = Math.ceil(charsToShow / 2);
    const backChars = Math.floor(charsToShow / 2);
    return str.substring(0, frontChars) + "..." + str.substring(str.length - backChars);
}

function formatTime(totalSeconds) {
    if (!totalSeconds) return "0s";
    const m = Math.floor(totalSeconds / 60);
    const s = Math.round(totalSeconds % 60);
    if (m > 0) return `${m}m ${s}s`;
    return `${s}s`;
}

function formatBytes(bytes, decimals = 2) {
    if (!+bytes) return '0 Bytes';
    const k = 1024;
    const dm = decimals < 0 ? 0 : decimals;
    const sizes = ['Bytes', 'KB', 'MB', 'GB', 'TB', 'PB', 'EB', 'ZB', 'YB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return `${parseFloat((bytes / Math.pow(k, i)).toFixed(dm))} ${sizes[i]}`;
}
