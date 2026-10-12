import os
import re
from openai import OpenAI
from document_pipeline import DocumentPipeline
from vector_store import VectorStore

class RagAgent:
    def __init__(self, llm_config, vlm_config, emb_config, stt_config, experiment_id, user_id, progress_callback=None):
        self.experiment_id = experiment_id
        self.progress_callback = progress_callback

        # Initialize LLM Client (For Text & Grading) with fallback to VLM/Embedding if empty
        llm_base = llm_config.get("endpoint", "") or vlm_config.get("endpoint", "") or emb_config.get("endpoint", "")
        llm_key = llm_config.get("key", "") or vlm_config.get("key", "") or emb_config.get("key", "") 
        self.llm_model = llm_config.get("model", "") or vlm_config.get("model", "")
        self.llm_client = OpenAI(base_url=llm_base, api_key=llm_key)
        
        # Initialize VLM Client (For Images) - with fallback to LLM if empty
        vlm_base = vlm_config.get("endpoint", "") or llm_base
        vlm_key = vlm_config.get("key", "") or llm_key
        self.vlm_model = vlm_config.get("model", "") or self.llm_model
        self.vlm_client = OpenAI(base_url=vlm_base, api_key=vlm_key)

        # Initialize STT Client
        stt_base = stt_config.get("endpoint", "")
        stt_key = stt_config.get("key", "")
        self.stt_model = stt_config.get("model", "")
        self.stt_client = OpenAI(base_url=stt_base, api_key=stt_key) if stt_base else None

        # Instantiate pipeline and storage
        self.pipeline = DocumentPipeline(self.vlm_client, self.vlm_model, self.stt_client, self.stt_model)
        self.vector_store = VectorStore(emb_config, experiment_id, user_id)

    def _update_status(self, file_name, status, message):
        if self.progress_callback:
            try:
                self.progress_callback(self.experiment_id, file_name, status, message)
            except Exception as e:
                print(f"Error updating progress state: {e}")

    def _detect_language(self, text: str) -> str:
        try:
            response = self.llm_client.chat.completions.create(
                model=self.llm_model,
                messages=[
                    {"role": "system", "content": "Detect the primary language of the text. Reply with ONLY the English name of the language (e.g., 'English', 'German', 'French')."},
                    {"role": "user", "content": text[:1000]}
                ],
                max_tokens=10,
                temperature=0.0
            )
            return response.choices[0].message.content.strip()
        except:
            return "unknown"
            
    def ingest_file(self, file_path: str, strategy="markdown"):
        file_name = os.path.basename(file_path)
        self._update_status(file_name, "started", f"Parsing {file_name}...")
        
        text = self.pipeline.extract_text(file_path)
        if not text:
            self._update_status(file_name, "completed", "No extractable text found.")
            return

        # Detect language once for the whole file
        doc_language = self._detect_language(text)
        self._update_status(file_name, "progress", f"Detected language: {doc_language}")
        
        chunks = self.pipeline.chunk_text(text, strategy=strategy)
        total_chunks = len(chunks)

        for index, chunk in enumerate(chunks):
            self.vector_store.insert_chunk(chunk, file_name, language=doc_language)
            if index > 0 and index % 5 == 0:
                self._update_status(file_name, "progress", f"Embedded {index}/{total_chunks} chunks.")
                
        self._update_status(file_name, "completed", f"Successfully processed {total_chunks} chunks.")

    def agentic_search(self, search_keywords: str, limit=10, max_distance=0.75):
        print(f"Agentic Search initialized for keywords: [{search_keywords}]")

        # Check what languages we actually have in the database
        vault_languages = self.vector_store.get_vault_languages()
        search_tasks = {}

        if vault_languages:
            try:
                lang_list = ", ".join(vault_languages)
                trans_response = self.llm_client.chat.completions.create(
                    model=self.llm_model,
                    messages=[
                        {"role": "system", "content": f"Translate these keywords into the following languages: {lang_list}. Return your answer as a list where each line is formatted exactly as 'Language: Translation'."},
                        {"role": "user", "content": search_keywords}
                    ],
                    max_tokens=50,
                    temperature=0.0
                )
                # Parse the "Language: Translation" output
                for line in trans_response.choices[0].message.content.split('\n'):
                    if ':' in line:
                        lang, trans = line.split(':', 1)
                        lang_clean = lang.strip()
                        if lang_clean in vault_languages:
                            search_tasks[lang_clean] = trans.strip()

            except Exception as e:
                print(f"Translation failed: {e}")

        search_tasks["unknown"] = search_keywords

        # If translation completely failed, search everything without language filters
        if not any(l in vault_languages for l in search_tasks.keys()):
            search_tasks[None] = search_keywords
        
        # Search the vector database for every language variation
        raw_chunks = []
        for lang, query in search_tasks.items():
            print(f"Querying DB for language [{lang}] with keywords: [{query}]")
            chunks = self.vector_store.search_vectors(query, limit=limit, max_distance=max_distance, language=lang)
            raw_chunks.extend(chunks)
            
        # Remove duplicate chunks
        raw_chunks = list(set(raw_chunks))
        
        if not raw_chunks:
            return {"keywords": search_keywords, "results": [], "status": "no_matches"}

        print(f"DEBUG - Retrieved Chunks:\n{raw_chunks}")
        # Combine all chunks into a single numbered text block to minimize latency
        formatted_chunks = "\n\n".join(
            [f"--- Chunk {i} ---\n{chunk}" for i, chunk in enumerate(raw_chunks)]
        )
        
        # Relevance Grading
        grading_prompt = [
            {
                "role": "system", 
                "content": (
                    "You are a strict relevance grader. Evaluate the following numbered context chunks against the search keywords."
                    "If a chunk contains information about the keywords, describes the entity, or is highly related to the topic, mark it as relevant."
                    "Output ONLY a comma-separated list of the relevant chunk numbers (e.g., '0, 2, 3')."
                    "If absolutely none are relevant, output 'NONE'."
                )
            },
            {
                "role": "user", 
                "content": f"Keywords: {search_keywords}\n\nContexts:\n{formatted_chunks}"
            }
        ]

        grade_response = self.llm_client.chat.completions.create(
            model=self.llm_model,
            messages=grading_prompt,
            max_tokens=15,
            temperature=0.0
        )
        
        response_text = grade_response.choices[0].message.content.strip().upper()
        
        valid_chunks = []
        if "NONE" not in response_text:
            import re
            relevant_indices = [int(idx) for idx in re.findall(r'\d+', response_text)]
            
            for idx in relevant_indices:
                if 0 <= idx < len(raw_chunks):
                    valid_chunks.append(raw_chunks[idx])

        print(f"Agentic Grading: Retained {len(valid_chunks)}/{len(raw_chunks)} chunks. (Response: {response_text})")

        return {
            "keywords": search_keywords,
            "results": valid_chunks,
            "status": "success" if valid_chunks else "filtered_all"
        }