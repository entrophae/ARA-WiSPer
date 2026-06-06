from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse, FileResponse, StreamingResponse
from fastapi.staticfiles import StaticFiles
import asyncio
import json
import stt_engine as engine
import state

app = FastAPI()
app.mount("/static", StaticFiles(directory="static"), name="static")

@app.get("/stream")
async def message_stream():
    async def event_generator():
        idle_loops = 0
        while True:
            if state.event_queue:
                task = state.event_queue.pop(0)
                yield f"data: {json.dumps(task)}\n\n"
                idle_loops = 0
            else:
                idle_loops += 1
                if idle_loops >= 50:
                    yield ": keepalive\n\n" 
                    idle_loops = 0
            await asyncio.sleep(0.2)
    return StreamingResponse(
        event_generator(), 
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "Connection": "keep-alive",
            "X-Accel-Buffering": "no"
        }
    )
        
@app.post("/api")
async def unified_transcribe(request: Request):
    content_type = request.headers.get("content-type", "")

    host = request.headers.get("host", "")
    origin = request.headers.get("origin", "")
    referer = request.headers.get("referer", "")

    is_internal = (origin and host in origin) or (referer and host in referer)
    
    if not is_internal:
        state.total_api_calls += 1
        forwarded_for = request.headers.get("x-forwarded-for", "")
        
        if forwarded_for:
            client_ip = forwarded_for.split(",")[0].strip()
        else:
            client_ip = request.client.host if request.client else "unknown"
            
        print(f"Request from client_ip: {client_ip}")
        
        state.connections[client_ip] = state.connections.get(client_ip, 0) + 1
        
        state.event_queue.append({
            "type": "stats_update",
            "total_calls": state.total_api_calls,
            "connections": state.connections
        })
    
    if "application/json" in content_type:
        data = await request.json()
        audio_b64 = data.get("audio_b64")
        
        if not audio_b64:
            return JSONResponse(status_code=400, content={"error": "Missing audio_b64"})
            
        task_id = await engine.start_base64_task(audio_b64, label=f"🤖 JSON API ({client_ip})")
        
        text = await engine.get_task_result_text(task_id)
        return {"text": text}

    elif "multipart/form-data" in content_type:
        form = await request.form()
        audio_file = form.get("audio_file") 
        if not audio_file:
            return JSONResponse(status_code=400, content={"error": "No file provided"})
        
        task_id = await engine.start_transcription_task(await audio_file.read(), audio_file.filename)

        if not is_internal:
            text = await engine.get_task_result_text(task_id)
            return {"text": text}
        return {"task_id": task_id}

    else:
        return JSONResponse(status_code=415, content={"error": "Unsupported Media Type. Send JSON or Form-Data."})
        
@app.get("/status/{task_id}")
async def get_status(task_id: str):
    if task_id not in state.tasks:
        return JSONResponse(status_code=404, content={"error": "Task not found"})
    return state.tasks[task_id]

@app.get("/stats")
async def get_stats():
    return {
        "total_calls": state.total_api_calls,
        "connections": state.connections
    }
@app.get("/history")
async def get_history():
    return state.transcription_history
    
@app.get("/")
async def root():
    return FileResponse("templates/index.html")