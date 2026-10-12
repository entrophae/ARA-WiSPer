import uuid
import os
import threading
from faster_whisper import WhisperModel
import state
import base64
import numpy as np
import asyncio
import traceback
import ctranslate2

isGpu = ctranslate2.get_cuda_device_count() > 0;

model = "large-v2" if isGpu else "base";
device = "cuda" if isGpu else "cpu";
compute_type = "float16" if isGpu else "int8";

model = WhisperModel(model, device=device, compute_type=compute_type)

async def start_base64_task(audio_b64: str, label="🤖 Robot API"):
    import base64
    import numpy as np
    
    task_id = uuid.uuid4().hex
    
    raw_audio = base64.b64decode(audio_b64)
    audio_np = np.frombuffer(raw_audio, dtype=np.int16).astype(np.float32) / 32768.0
    
    state.tasks[task_id] = {"label": label, "status": "processing", "duration": 0, "progress": {"text": "", "percent": 0}}
    
    state.event_queue.append({"type": "new_task", "task_id": task_id, "label": label})
    
    threading.Thread(target=run_transcription, args=(task_id, audio_np, False)).start()
    
    return task_id
        
def run_transcription(task_id, audio_np, is_temp_file=False):
    try:
        segments, info = model.transcribe(
            audio_np, 
            beam_size=5,
            vad_filter=True,
            vad_parameters=dict(min_silence_duration_ms=500)
        )
        state.tasks[task_id].update({"duration": info.duration, "status": "processing"})
        
        text = []
        for segment in segments:
            text.append(segment.text)
            state.tasks[task_id]["progress"] = {
                "text": "".join(text),
                "end": segment.end,
                "percent": round(min(100.0, (segment.end / info.duration) * 100), 1)
            }
            
        final_text = "".join(text)
        state.tasks[task_id].update({"status": "done", "final_text": "".join(text)})
        state.transcription_history.append({
            "task_id": task_id,
            "label": state.tasks[task_id].get("label", "Unknown Audio"),
            "duration": info.duration,
            "final_text": final_text
        })
    except Exception as e:
        state.tasks[task_id].update({"status": "error", "error": str(e)})
        print(f"\n❌ FATAL ERROR IN TRANSCRIPTION THREAD (Task: {task_id}) ❌")
        traceback.print_exc() 
        print("-" * 50 + "\n")
    finally:
        if is_temp_file and isinstance(audio_np, str) and os.path.exists(audio_np): 
            os.remove(audio_np)

async def start_transcription_task(file_bytes, label="Upload"):
    task_id = uuid.uuid4().hex
    temp_path = f"/tmp/stt_{task_id}.wav"
    with open(temp_path, "wb") as f: f.write(file_bytes)
    
    state.tasks[task_id] = {"label": label, "status": "processing", "duration": 0, "progress": {"text": "", "percent": 0}}
    
    state.event_queue.append({"type": "new_task", "task_id": task_id, "label": label})
    
    threading.Thread(target=run_transcription, args=(task_id, temp_path, True)).start()
    return task_id

async def get_task_result_text(task_id):
    while state.tasks[task_id]["status"] == "processing":
        await asyncio.sleep(0.5)
    return state.tasks[task_id].get("final_text", "")
