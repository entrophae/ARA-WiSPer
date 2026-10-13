from fastapi import FastAPI, Request
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles
from fastapi.templating import Jinja2Templates
from fastapi.openapi.docs import get_swagger_ui_html
import asyncpg
from schemas import UserCreate, ChatTurn, UserKnowledge, SummaryUpdate, HistoryUpdate, ToolCallRecord
from typing import List
import getpass
import os
import json
from dotenv import load_dotenv
load_dotenv(dotenv_path=".env", override=True)

# Dynamically fetch the current system username and jupyterhub username
username = os.environ.get("JUPYTERHUB_USER")
port = os.environ.get("API_PORT")
PROXY_PATH = f"/user/{username}/proxy/{port}"

hostname = os.environ.get("POSTGRES_USER") or getpass.getuser()
db_name = os.environ.get("POSTGRES_DBNAME")
db_pass = os.environ.get("POSTGRES_PASSWORD", "")
auth_part = f"{hostname}:{db_pass}" if db_pass else f"{hostname}"
db_connection = f"postgresql://{auth_part}@localhost/{db_name}"

raw_storage = os.getenv("STORAGE_DIR", "storage/experiments")
BASE_STORAGE_DIR = os.path.abspath(os.path.expanduser(raw_storage))
os.makedirs(BASE_STORAGE_DIR, exist_ok=True)

app = FastAPI(docs_url=None)
templates = Jinja2Templates("templates")
app.mount("/static", StaticFiles(directory="static"), name="static")
app.mount("/storage", StaticFiles(directory=BASE_STORAGE_DIR), name="storage")

db_pool = None

@app.get("/docs", include_in_schema=False)
async def custom_swagger_ui_html():
    return get_swagger_ui_html(
        openapi_url=f"{PROXY_PATH}/openapi.json",
        title="Database API Docs"
    )
@app.get("/")
async def root(request: Request):
    return templates.TemplateResponse(
        request=request, 
        name="index.html",
        context={"base_path": PROXY_PATH}
    )
    
@app.on_event("startup")
async def startup_event():
    global db_pool
    try:

        # The username was set as the system username during the jupytersetup 
        db_pool = await asyncpg.create_pool(db_connection)
        print(f"🟢 Connected to PostgreSQL")
        
        async with db_pool.acquire() as conn:
            await conn.execute('''
                CREATE EXTENSION IF NOT EXISTS vector;
                
                CREATE TABLE IF NOT EXISTS users (
                    user_id TEXT PRIMARY KEY,
                    chat_summary TEXT NOT NULL DEFAULT ''
                );

                CREATE TABLE IF NOT EXISTS experiments (
                    experiment_id TEXT PRIMARY KEY,
                    name TEXT NOT NULL
                );

                CREATE TABLE IF NOT EXISTS experiment_users (
                    experiment_id TEXT NOT NULL
                        REFERENCES experiments(experiment_id)
                        ON DELETE CASCADE,
                    user_id TEXT NOT NULL
                        REFERENCES users(user_id)
                        ON DELETE CASCADE,
                    PRIMARY KEY (experiment_id, user_id)
                );
            
                CREATE TABLE IF NOT EXISTS chat_turns (
                    turn_id TEXT PRIMARY KEY,
                    user_id TEXT NOT NULL
                        REFERENCES users(user_id)
                        ON DELETE CASCADE,
                    user_query TEXT,
                    llm_response TEXT,
                    tool_called TEXT,
                    tool_buffer_response TEXT,
                    tools_used JSONB DEFAULT '[]'::jsonb,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                );

                CREATE TABLE IF NOT EXISTS documents (
                    document_id SERIAL PRIMARY KEY,
                    experiment_id TEXT NOT NULL
                        REFERENCES experiments(experiment_id)
                        ON DELETE CASCADE,
                    content TEXT NOT NULL,
                    path TEXT
                );

                CREATE TABLE IF NOT EXISTS embedding_configs (
                    config_id SERIAL PRIMARY KEY,
                    experiment_id TEXT NOT NULL
                        REFERENCES experiments(experiment_id)
                        ON DELETE CASCADE,
                    model_id TEXT NOT NULL,
                    dimensions INTEGER NOT NULL,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    active BOOLEAN NOT NULL DEFAULT FALSE
                );

                CREATE UNIQUE INDEX IF NOT EXISTS active_embedding_config
                    ON embedding_configs (experiment_id)
                    WHERE active = TRUE;

                CREATE TABLE IF NOT EXISTS document_embeddings_4096 (
                    embedding_id SERIAL PRIMARY KEY,
                    document_id INTEGER NOT NULL
                        REFERENCES documents(document_id)
                        ON DELETE CASCADE,
                    config_id INTEGER NOT NULL
                        REFERENCES embedding_configs(config_id)
                        ON DELETE CASCADE,
                    embedding vector(4096) NOT NULL,
                    UNIQUE(document_id, config_id)
                );
                
                CREATE TABLE IF NOT EXISTS document_embeddings_1536 (
                    embedding_id SERIAL PRIMARY KEY,
                    document_id INTEGER NOT NULL
                        REFERENCES documents(document_id)
                        ON DELETE CASCADE,
                    config_id INTEGER NOT NULL
                        REFERENCES embedding_configs(config_id)
                        ON DELETE CASCADE,
                    embedding vector(1536) NOT NULL,
                    UNIQUE(document_id, config_id)
                );
            ''')
    except Exception as e:
        print(f"🔴 Database connection failed: {e}")

@app.get("/api/users", response_model=List[str])
async def get_all_users():
    """Kotlin calls this to populate the dashboard dropdown menu."""
    if db_pool:
        async with db_pool.acquire() as conn:
            # Fetch all user IDs from the knowledge table
            rows = await conn.fetch('SELECT user_id FROM users')
            
            # Convert the SQL rows into a simple list of strings
            return [row["user_id"] for row in rows]
    return []

@app.post("/api/users")
async def create_user(data: UserCreate):
    if db_pool:
        async with db_pool.acquire() as conn:
            await conn.execute(
                'INSERT INTO users (user_id) VALUES ($1) ON CONFLICT DO NOTHING',
                data.userId
            )
    return {"status": "success"}

@app.delete("/api/users/{user_id}")
async def delete_user(user_id: str):
    if db_pool:
        async with db_pool.acquire() as conn:
            await conn.execute(
                'DELETE FROM users WHERE user_id = $1',
                user_id
            )
    return {"status": "success"}


@app.get("/api/users/{user_id}", response_model=UserKnowledge)
async def get_users(user_id: str):
    if db_pool:
        async with db_pool.acquire() as conn:
            
            summary_row = await conn.fetchrow(
                'SELECT chat_summary FROM users WHERE user_id = $1', user_id
            )
            chat_summary = summary_row["chat_summary"] if summary_row else ""
            
            history_rows = await conn.fetch(
                'SELECT * FROM chat_turns WHERE user_id = $1 ORDER BY created_at ASC', user_id
            )
            
            history_list = []
            for row in history_rows:
                raw_tools = row.get("tools_used")
                tools_used_list = []

                if raw_tools:
                    if isinstance(raw_tools, str):
                        raw_tools = json.loads(raw_tools)
                    for tr in raw_tools:
                        tools_used_list.append(ToolCallRecord(
                            toolName=tr.get("toolName", ""),
                            query=tr.get("query", ""),
                            answer=tr.get("answer", "")
                        ))

                history_list.append(ChatTurn(
                    userId=row["user_id"],
                    turnId=row["turn_id"],
                    userQuery=row["user_query"],
                    llmResponse=row["llm_response"],
                    toolCalled=row["tool_called"],
                    toolBufferResponse=row["tool_buffer_response"],
                    toolsUsed=tools_used_list
                ))
            
            return UserKnowledge(
                userId=user_id,
                chatSummary=chat_summary,
                history=history_list
            )
    return UserKnowledge(userId=user_id, chatSummary="", history=[])

@app.post("/api/users/{user_id}/history/turns")
async def add_chat_turn(user_id: str, turn: ChatTurn):
    if db_pool:
        async with db_pool.acquire() as conn:
            # Make sure the user exists in the table first
            await conn.execute(
                'INSERT INTO users (user_id) VALUES ($1) ON CONFLICT DO NOTHING',
                user_id
            )
            
            # Serialize the list of ToolCallRecord models to a JSON string for JSONB
            tools_json = json.dumps([t.model_dump() for t in turn.toolsUsed]) if turn.toolsUsed else "[]"
            
            # Insert the turn using the path parameter
            await conn.execute('''
                INSERT INTO chat_turns (
                    turn_id, user_id, user_query, llm_response,
                    tool_called, tool_buffer_response, tools_used
                ) 
                VALUES ($1, $2, $3, $4, $5, $6, $7::jsonb)
                ON CONFLICT (turn_id) DO UPDATE 
                SET user_query = EXCLUDED.user_query,
                    llm_response = EXCLUDED.llm_response,
                    tool_called = EXCLUDED.tool_called,
                    tool_buffer_response = EXCLUDED.tool_buffer_response,
                    tools_used = EXCLUDED.tools_used
            ''', turn.turnId, user_id, turn.userQuery, turn.llmResponse, turn.toolCalled, turn.toolBufferResponse, tools_json)
            
    return {"status": "success"}
    
@app.delete("/api/users/{user_id}/history/turns/{turn_id}")
async def delete_chat_turn(user_id: str, turn_id: str):
    if db_pool:
        async with db_pool.acquire() as conn:
            await conn.execute(
                'DELETE FROM chat_turns WHERE turn_id = $1 AND user_id = $2',
                turn_id, user_id
            )
    return {"status": "success"}
    
@app.delete("/api/users/{user_id}/history")
async def delete_history(user_id: str):
    if db_pool:
        async with db_pool.acquire() as conn:
            await conn.execute(
                'DELETE FROM chat_turns WHERE user_id = $1',
                user_id
            )
    return {"status": "success"}
    
@app.put("/api/users/{user_id}/summary")
async def update_summary(user_id: str, data: SummaryUpdate):
     if db_pool:
        async with db_pool.acquire() as conn:
            await conn.execute(
                'INSERT INTO users (user_id) VALUES ($1) ON CONFLICT DO NOTHING',
                user_id
            )
            await conn.execute(
                'UPDATE users SET chat_summary = $1 WHERE user_id = $2',
                data.summary, user_id
            )
     return {"status": "success"}

@app.delete("/api/users/{user_id}/summary")
async def delete_summary(user_id: str):
     if db_pool:
        async with db_pool.acquire() as conn:
            await conn.execute(
                'UPDATE users SET chat_summary = \'\' WHERE user_id = $1',
                user_id
            )
     return {"status": "success"}

@app.get("/api/status")
async def get_status():
    try:
        async with db_pool.acquire() as connection:
            records = await connection.fetch(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public';"
            )
            table_names = [record['table_name'] for record in records]
            
            api_routes = []
            for route in app.routes:
                if hasattr(route, "methods") and route.path.startswith("/api/"):
                    methods = [m for m in route.methods if m != "OPTIONS"]
                    api_routes.append({"path": route.path, "methods": methods})
            
            return {
                "status": "online", 
                "tables": table_names,
                "routes": api_routes
            }
            
    except Exception as e:
        return {"status": "offline", "error": str(e)}

@app.get("/api/experiments")
async def get_experiments():
    if not db_pool: return []
    async with db_pool.acquire() as conn:
        exps = await conn.fetch('SELECT * FROM experiments')
        results = []
        for exp in exps:
            exp_id = exp['experiment_id']
            users = await conn.fetch('SELECT user_id FROM experiment_users WHERE experiment_id = $1', exp_id)
            files = await conn.fetch('''
                SELECT MAX(document_id) as doc_id, path 
                FROM documents 
                WHERE experiment_id = $1 AND path IS NOT NULL 
                GROUP BY path
            ''', exp_id)
            results.append({
                "id": exp_id,
                "name": exp["name"],
                "users": [u["user_id"] for u in users],
                "files": [{"id": str(f["doc_id"]), "name": f["path"]} for f in files]
            })
        return results

@app.post("/api/experiments")
async def create_experiment(data: dict):
    if db_pool:
        async with db_pool.acquire() as conn:
            await conn.execute(
                'INSERT INTO experiments (experiment_id, name) VALUES ($1, $2) ON CONFLICT DO NOTHING',
                data.get("experimentId"), data.get("name")
            )
    return {"status": "success"}

@app.get("/api/users/{user_id}/experiments", response_model=List[str])
async def get_user_experiments(user_id: str):
    if db_pool:
        async with db_pool.acquire() as conn:
            rows = await conn.fetch(
                'SELECT experiment_id FROM experiment_users WHERE user_id = $1', 
                user_id
            )
            return [row["experiment_id"] for row in rows]
    return []

@app.post("/api/experiments/{exp_id}/users")
async def assign_user_to_experiment(exp_id: str, data: dict):
    if db_pool:
        async with db_pool.acquire() as conn:
            await conn.execute('INSERT INTO users (user_id) VALUES ($1) ON CONFLICT DO NOTHING', data.get("userId"))
            await conn.execute(
                'INSERT INTO experiment_users (experiment_id, user_id) VALUES ($1, $2) ON CONFLICT DO NOTHING',
                exp_id, data.get("userId")
            )
    return {"status": "success"}

@app.delete("/api/experiments/{exp_id}/users/{user_id}")
async def remove_user_from_experiment(exp_id: str, user_id: str):
    if db_pool:
        async with db_pool.acquire() as conn:
            await conn.execute(
                'DELETE FROM experiment_users WHERE experiment_id = $1 AND user_id = $2',
                exp_id, user_id
            )
    return {"status": "success"}
    
@app.get("/experiments/{experiment_id}/documents")
async def list_experiment_documents(experiment_id: str):
    if not db_pool:
        return {"files": []}
    async with db_pool.acquire() as conn:
        rows = await conn.fetch(
            "SELECT DISTINCT path FROM documents WHERE experiment_id = $1 AND path IS NOT NULL", 
            experiment_id
        )
        paths = [row["path"] for row in rows]
        return {"files": paths}

@app.get("/experiments/{experiment_id}/files/{file_name:path}/content")
async def get_document_content(experiment_id: str, file_name: str):
    if db_pool:
        async with db_pool.acquire() as conn:
            rows = await conn.fetch(
                "SELECT content FROM documents WHERE experiment_id = $1 AND path = $2",
                experiment_id, file_name
            )
            if rows:
                full_content = "\n\n".join([row["content"] for row in rows])
                return {"content": full_content, "filename": file_name}
    return {"content": f"No extracted text found for {file_name}.", "filename": file_name}

@app.delete("/experiments/{experiment_id}/files/{file_name:path}")
async def delete_experiment_file(experiment_id: str, file_name: str):
    file_path = os.path.join(BASE_STORAGE_DIR, experiment_id, file_name)
    print(f"DEBUG: Attempting to delete file from disk: {file_path}")
    
    if os.path.exists(file_path):
        os.remove(file_path)
    else:
        print(f"DEBUG: File not found on disk for deletion: {file_path}")
    
    if db_pool:
        async with db_pool.acquire() as conn:
            print(f"DEBUG: Deleting from DB where experiment_id = {experiment_id} and path = {file_name}")
            await conn.execute(
                "DELETE FROM documents WHERE experiment_id = $1 AND path = $2", 
                experiment_id, 
                file_name
            )
            
    return {"status": "success"}