import uuid
import os
import threading
import time
from faster_whisper import WhisperModel
import state
import base64
import numpy as np
import asyncio
import traceback
import ctranslate2

model = None
model_status = "loading"  # "loading" | "online" | "error"
model_name = None
processor = None
model_error = None
_load_lock = threading.Lock()


def load_model():
    global model, model_status, model_name, processor, model_error

    with _load_lock:
        if model is not None:
            return

        start = time.perf_counter()
        try:
            is_gpu = ctranslate2.get_cuda_device_count() > 0
            model_name = "large-v2" if is_gpu else "base"
            device = "cuda" if is_gpu else "cpu"
            compute_type = "float16" if is_gpu else "int8"
            processor = f"{device.upper()} ({compute_type})"

            state.event_queue.append({
                "type": "model_status",
                "status": model_status,
                "model": model_name,
                "processor": processor,
            })

            print(f"⏳ Loading Whisper '{model_name}' on {processor}...")
            loaded = WhisperModel(model_name, device=device, compute_type=compute_type)

            model = loaded
            model_status = "online"
            state.event_queue.append({
                "type": "model_status",
                "status": model_status,
                "model": model_name,
                "processor": processor,
            })
            elapsed = time.perf_counter() - start
            print(f"🟢 Whisper '{model_name}' ready in {elapsed:.2f}s")
        except Exception as e:
            model_status = "error"
            model_error = str(e)
            state.event_queue.append({
                "type": "model_status",
                "status": model_status,
                "model": model_name,
                "processor": processor,
                "error": model_error,
            })
            print(f"🔴 Failed to load Whisper model: {e}")
            traceback.print_exc()


def is_ready() -> bool:
    return model is not None and model_status == "online"


async def start_base64_task(audio_b64: str, label="🤖 Robot API"):
    if not is_ready():
        raise RuntimeError(f"STT engine is not ready (status: {model_status})")

    task_id = uuid.uuid4().hex
    
    raw_audio = base64.b64decode(audio_b64)
    audio_np = np.frombuffer(raw_audio, dtype=np.int16).astype(np.float32) / 32768.0
    
    state.tasks[task_id] = {
        "label": label,
        "status": "processing",
        "duration": 0,
        "progress": {"text": "", "percent": 0},
    }

    state.event_queue.append({"type": "new_task", "task_id": task_id, "label": label})

    threading.Thread(target=run_transcription, args=(task_id, audio_np, False), daemon=True).start()
    return task_id
        
def run_transcription(task_id, audio_np, is_temp_file=False):
    try:
        segments, info = model.transcribe(
            audio_np, 
            beam_size=5,
            vad_filter=True,
            vad_parameters=dict(min_silence_duration_ms=500),
        )
        state.tasks[task_id].update({"duration": info.duration, "status": "processing"})
        
        print(f"task_{task_id} : transcribing")
        text = []
        for segment in segments:
            text.append(segment.text)
            state.tasks[task_id]["progress"] = {
                "text": "".join(text),
                "end": segment.end,
                "percent": round(min(100.0, (segment.end / info.duration) * 100), 1),
            }
            
        final_text = "".join(text)
        print(f"task_{task_id} : final text {final_text}")
        state.tasks[task_id].update({"status": "done", "final_text": final_text})
        state.transcription_history.append({
            "task_id": task_id,
            "label": state.tasks[task_id].get("label", "Unknown Audio"),
            "duration": info.duration,
            "final_text": final_text,
        })
        state.event_queue.append({
            "type": "task_done",
            "task_id": task_id,
            "status": "done",
            "final_text": final_text,
        })
    except Exception as e:
        state.tasks[task_id].update({"status": "error", "error": str(e)})
        state.event_queue.append({
            "type": "task_error",
            "task_id": task_id,
            "status": "error",
            "error": str(e),
        })
        print(f"\n❌ FATAL ERROR IN TRANSCRIPTION THREAD (Task: {task_id}) ❌")
        traceback.print_exc() 
        print("-" * 50 + "\n")
    finally:
        if is_temp_file and isinstance(audio_np, str) and os.path.exists(audio_np):
            os.remove(audio_np)

async def start_transcription_task(file_bytes, label="Upload"):
    if not is_ready():
        raise RuntimeError(f"STT engine is not ready (status: {model_status})")

    task_id = uuid.uuid4().hex
    temp_path = f"/tmp/stt_{task_id}.wav"
    with open(temp_path, "wb") as f: f.write(file_bytes)

    state.tasks[task_id] = {
        "label": label,
        "status": "processing",
        "duration": 0,
        "progress": {"text": "", "percent": 0},
    }
    state.event_queue.append({"type": "new_task", "task_id": task_id, "label": label})

    threading.Thread(target=run_transcription, args=(task_id, temp_path, True), daemon=True).start()
    return task_id

async def get_task_result_text(task_id):
    while state.tasks[task_id]["status"] == "processing":
        print(f"task_{task_id} : in process")
        await asyncio.sleep(0.5)
    return state.tasks[task_id].get("final_text", "")
