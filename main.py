from fastapi import FastAPI, Request, WebSocket, WebSocketDisconnect
from fastapi.responses import JSONResponse, FileResponse
from fastapi.staticfiles import StaticFiles
import asyncio
from datetime import datetime
import json
import stt_engine as engine
import state

app = FastAPI()
app.mount("/static", StaticFiles(directory="static"), name="static")

@app.on_event("startup")
async def startup_event():
    asyncio.create_task(broadcast_worker())

async def broadcast_worker():
    while True:
        try:
            if state.event_queue:
                event = state.event_queue.pop(0)
                print(f"📡 Broadcasting to UI: {event.get('type')}")
                dead_sockets = []
                for ws in state.active_websockets:
                    try:
                        await ws.send_json(event)
                    except Exception:
                        dead_sockets.append(ws)

                for ws in dead_sockets:
                    if ws in state.active_websockets:
                        state.active_websockets.remove(ws)
            else:
                await asyncio.sleep(0.1)
        except Exception as e:
            print(f"Worker Error: {e}")
            await asyncio.sleep(1)

@app.websocket("/ws")
async def websocket_endpoint(websocket: WebSocket):
    await websocket.accept()
    state.active_websockets.append(websocket)
    try:
        while True:
            await websocket.receive_text()
    except WebSocketDisconnect:
        if websocket in state.active_websockets:
            state.active_websockets.remove(websocket)
            
@app.websocket("/ws/stream")
async def audio_intake_stream(websocket: WebSocket):
    await websocket.accept()
    state.total_wss_calls += 1
    
    client_ip = websocket.client.host if websocket.client else "unknown"
    state.wconnections[client_ip] = state.wconnections.get(client_ip, 0) + 1
    
    current_time = datetime.now().strftime("%d/%m;%Y %H:%M:%S")
    print(f"{current_time}: Audio client connected via WebSocket from {client_ip}")

    try:
        while True:
            data = await websocket.receive_json()
            audio_b64 = data.get("audio_b64")
            
            if audio_b64:
                state.event_queue.append({
                    "type": "stats_update",
                    "total_calls": state.total_api_calls,
                    "connections": state.connections,
                    "total_wss_calls": state.total_wss_calls,
                    "wconnections": state.wconnections
                })
                
                current_time = datetime.now().strftime("%d/%m;%Y %H:%M:%S")
                label = f"🤖 WS Audio: {current_time}: ({client_ip})"
                task_id = await engine.start_base64_task(audio_b64, label=label)
                
                state.event_queue.append({
                    "type": "new_task",
                    "task_id": task_id,
                    "label": label
                })
                await asyncio.sleep(0.05)
                
                text = await engine.get_task_result_text(task_id)
                
                await websocket.send_json({"text": text})
                print(f"{current_time}: WS Response sent")

    except WebSocketDisconnect:
        print(f"Audio client at {client_ip} disconnected the stream.")
    except Exception as e:
        print(f"WebSocket Error: {e}")
        
@app.post("/api")
async def unified_transcribe(request: Request):
    content_type = request.headers.get("content-type", "")

    host = request.headers.get("host", "")
    origin = request.headers.get("origin", "")
    referer = request.headers.get("referer", "")

    is_internal = (origin and host in origin) or (referer and host in referer)

    forwarded_for = request.headers.get("x-forwarded-for", "")
    if forwarded_for:
        client_ip = forwarded_for.split(",")[0].strip()
    else:
        client_ip = request.client.host if request.client else "unknown"

    current_time = datetime.now().strftime("%d/%m;%Y %H:%M:%S")
    
    if not is_internal:
        state.total_api_calls += 1
        print(f"{current_time}: POST Request from {client_ip}")
        
        state.connections[client_ip] = state.connections.get(client_ip, 0) + 1
        
        state.event_queue.append({
            "type": "stats_update",
            "total_calls": state.total_api_calls,
            "connections": state.connections,
            "total_wss_calls": state.total_wss_calls,
            "wconnections": state.wconnections
        })
        await asyncio.sleep(0.05)
    
    if "application/json" in content_type:
        data = await request.json()
        audio_b64 = data.get("audio_b64")
        
        if not audio_b64:
            return JSONResponse(status_code=400, content={"error": "Missing audio_b64"})

        label = f"🤖 API: {current_time}: ({client_ip})"
        task_id = await engine.start_base64_task(audio_b64, label=label)

        state.event_queue.append({
            "type": "new_task",
            "task_id": task_id,
            "label": label
        })
        await asyncio.sleep(0.05)
        
        text = await engine.get_task_result_text(task_id)

        print(f"{current_time}: POST Response sent")
        return {"text": text}

    elif "multipart/form-data" in content_type:
        form = await request.form()
        audio_file = form.get("audio_file") 
        if not audio_file:
            return JSONResponse(status_code=400, content={"error": "No file provided"})
        
        task_id = await engine.start_transcription_task(await audio_file.read(), audio_file.filename)

        state.event_queue.append({
            "type": "new_task",
            "task_id": task_id,
            "label": audio_file.filename
        })
        await asyncio.sleep(0.05)
        
        if not is_internal:
            text = await engine.get_task_result_text(task_id)
            print(f"{current_time}: POST Response sent")
            return {"text": text}
        return {"task_id": task_id}

    else:
        print(f"Error: POST Unsupported Type")
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
        "connections": state.connections,
        "total_wss_calls": state.total_wss_calls,
        "wconnections": state.wconnections
    }
@app.get("/history")
async def get_history():
    return state.transcription_history
    
@app.get("/")
async def root():
    return FileResponse("templates/index.html")
