from fastapi import Header
from pydantic import BaseModel
from typing import List, Optional
import json
        
class ConfigSchema(BaseModel):
    endpoint: Optional[str] = ""
    key: Optional[str] = ""
    model: Optional[str] = ""
    
def parse_header_config(config_str: str) -> ConfigSchema:
    try:
        data = json.loads(config_str) if config_str else {}
        return ConfigSchema(**data)
    except Exception:
        return ConfigSchema()

class IngestResponse(BaseModel):
    status: str
    message: str

class ProgressResponse(BaseModel):
    status: str
    message: str

class AgenticSearchResponse(BaseModel):
    keywords: str
    results: List[str]
    status: str

class ProgressHeaders:
    def __init__(
        self,
        experiment_id: str = Header(""),
        file_name: str = Header("")
    ):
        self.experiment_id = experiment_id
        self.file_name = file_name

class IngestHeaders:
    def __init__(
        self,
        llm_config: str = Header("{}"),
        vlm_config: str = Header("{}"),
        embedding_config: str = Header("{}"),
        stt_config: str = Header("{}"),
        dimension: int = Header(4096),
        user_id: str = Header(None),
        experiment_id: str = Header("")
    ):
        self.llm_config = parse_header_config(llm_config)
        self.vlm_config = parse_header_config(vlm_config)
        self.embedding_config = parse_header_config(embedding_config)
        self.stt_config = parse_header_config(stt_config)
        self.dimension = dimension
        self.user_id = user_id
        self.experiment_id = experiment_id

class SearchHeaders:
    def __init__(
        self,
        keywords: str = Header(""),
        limit: int = Header(5),
        llm_config: str = Header("{}"),
        vlm_config: str = Header("{}"),
        embedding_config: str = Header("{}"),
        dimension: int = Header(4096),
        experiment_id: str = Header(""),
        user_id: str = Header("")
    ):
        self.keywords = keywords
        self.limit = limit
        self.llm_config = parse_header_config(llm_config)
        self.vlm_config = parse_header_config(vlm_config)
        self.embedding_config = parse_header_config(embedding_config)
        self.dimension = dimension
        self.experiment_id = experiment_id
        self.user_id = user_id