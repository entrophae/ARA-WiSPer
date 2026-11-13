from pydantic import BaseModel
from typing import Optional, List

class UserCreate(BaseModel):
    userId: str

class ToolCallRecord(BaseModel):
    callId: str
    name: str
    query: str
    answer: Optional[str] = ""
    
class ChatTurn(BaseModel):
    userId: str
    turnId: str
    userQuery: str
    userImageUrl: Optional[str] = None
    llmResponse: str
    toolCalled: Optional[str] = None
    toolBufferResponse: Optional[str] = None
    toolsUsed: Optional[List[ToolCallRecord]] = []
    
class UserKnowledge(BaseModel):
    userId: str
    chatSummary: str
    history: List[ChatTurn]
    
class HistoryUpdate(BaseModel):
    userId: str
    history: List[ChatTurn]
    
class SummaryUpdate(BaseModel):
    userId: str
    summary: str