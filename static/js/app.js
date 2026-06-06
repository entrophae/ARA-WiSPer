const BASE = window.location.pathname.replace(/\/$/, "");
const HREF = window.location.href.replace(/\/$/, "");

let mediaRecorder;
let audioChunks = [];

const fileInput = document.getElementById("audioFile");
const fileDisplay = document.getElementById("fileNameDisplay");
const fileSendBtn = document.getElementById("transcribe");
const startBtn = document.getElementById("start");
const stopBtn = document.getElementById("stop");
const apiUrlInput = document.getElementById("apiUrlInput");
const copyApiBtn = document.getElementById("copyApiBtn");
const connectionsNr = document.getElementById("connCount");
const requestsNr = document.getElementById("reqCount");
const apiCardTitle = document.getElementById("apiCardTitle");


if (apiUrlInput && copyApiBtn) {
    const fullApiUrl = `${HREF}/api`;
    apiUrlInput.value = fullApiUrl;
    apiUrlInput.style.width = (fullApiUrl.length) + "ch";

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

fileSendBtn.disabled = true;
setRecordingState(false);
updateStats();
loadHistory();

const evtSource = new EventSource(`${BASE}/stream`);
evtSource.onmessage = (event) => {
    const payload = JSON.parse(event.data);
    console.log("📥 Received live event from server:", payload);
    if (payload.type === "new_task") {
        createTaskUI(payload.task_id, payload.label);
    }
    else if (payload.type === "stats_update") {
        const connSize = Object.keys(payload.connections).length;
        connectionsNr.innerText = connSize;
        requestsNr.innerText = payload.total_calls;
        
        let tooltipText = "Active Connections:\n";
        for (const [ip, count] of Object.entries(payload.connections)) {
            tooltipText += `• ${ip} requested ${count}\n`;
        }
        
        if (connSize === 0) {
            tooltipText = "No external connections yet.";
        }
        apiCardTitle.title = tooltipText;
    }
};

async function updateStats() {
    try {
        const res = await fetch(`${BASE}/stats`);
        if (!res.ok) return;
        const data = await res.json();
        
        connectionsNr.innerText = Object.keys(data.connections).length;
        requestsNr.innerText = data.total_calls;
        
        let tooltipText = "Active Connections:\n";
        for (const [ip, count] of Object.entries(data.connections)) {
            tooltipText += `• ${ip} requested ${count}\n`;
        }
        if (Object.keys(data.connections).length === 0) { 
            tooltipText = "No external connections yet.";
        }
        apiCardTitle.title = tooltipText
        
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
        
    } catch (err) {
        statusEl.innerText = "Upload failed!";
        console.error(err);
    }
}

async function createTaskUI(taskId, label){
    const container = document.getElementById("transcriptionsContainer");
    
    const taskDiv = document.createElement("div");
    taskDiv.className = "transcription-task";
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