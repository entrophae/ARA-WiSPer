import os
import urllib.parse
import shutil
import uvicorn
from dotenv import load_dotenv
from fastapi import FastAPI, Request, Header, UploadFile, File, BackgroundTasks, Depends
from fastapi.staticfiles import StaticFiles
from schemas import IngestResponse, ProgressResponse, AgenticSearchResponse, ProgressHeaders, IngestHeaders, SearchHeaders
from rag_agent import RagAgent

load_dotenv()

app = FastAPI()

raw_storage = os.getenv("STORAGE_DIR", "storage/experiments")
BASE_STORAGE_DIR = os.path.abspath(os.path.expanduser(raw_storage))
os.makedirs(BASE_STORAGE_DIR, exist_ok=True)
app.mount("/files", StaticFiles(directory=BASE_STORAGE_DIR), name="files")

INGESTION_PROGRESS = {}
        
@app.get("/progress", response_model=ProgressResponse)
def get_progress_api(headers: ProgressHeaders = Depends()):
    key = f"{headers.experiment_id}:{headers.file_name}"
    return INGESTION_PROGRESS.get(key, {"status": "pending", "message": "Waiting to start..."})

@app.post("/ingest", response_model=IngestResponse)
def ingest_document_api(
    request: Request,
    file: UploadFile = File(...),
    background_tasks: BackgroundTasks = BackgroundTasks(),
    headers: IngestHeaders = Depends()
):
    target_dir = os.path.join(BASE_STORAGE_DIR, headers.experiment_id)
    os.makedirs(target_dir, exist_ok=True)
    file_path = os.path.join(target_dir, file.filename)
    
    with open(file_path, "wb") as buffer:
        shutil.copyfileobj(file.file, buffer)

    def update_progress(exp_id, file_name, status, message):
        key = f"{exp_id}:{file_name}"
        INGESTION_PROGRESS[key] = {"status": status, "message": message}

    agent = RagAgent(
        llm_config=headers.llm_config.model_dump(),
        vlm_config=headers.vlm_config.model_dump(),
        emb_config=headers.embedding_config.model_dump(),
        stt_config=headers.stt_config.model_dump(),
        experiment_id=headers.experiment_id,
        user_id=headers.user_id,
        progress_callback=update_progress
    )

    # Offload processing to background task
    background_tasks.add_task(agent.ingest_file, file_path, strategy="markdown")
    
    return IngestResponse(
        status="processing",
        message=f"{file.filename} is being processed."
    )

@app.get("/search", response_model=AgenticSearchResponse)
def search_documents_api(headers: SearchHeaders = Depends()):
    decoded_keywords = urllib.parse.unquote(headers.keywords)
    
    agent = RagAgent(
        llm_config=headers.llm_config.model_dump(),
        vlm_config=headers.vlm_config.model_dump(),
        emb_config=headers.embedding_config.model_dump(),
        stt_config={}, 
        experiment_id=headers.experiment_id,
        user_id=headers.user_id
    )
    
    result = agent.agentic_search(decoded_keywords, limit=headers.limit, max_distance=0.75)
    return AgenticSearchResponse(**result)

if __name__ == "__main__":
    port = int(os.getenv("PORT", 8246))
    uvicorn.run(app, host="0.0.0.0", port=port)