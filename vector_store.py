import os
import psycopg2
from pgvector.psycopg2 import register_vector
from openai import OpenAI

class VectorStore:
    def __init__(self, emb_config, experiment_id, user_id, dimension=4096):
        self.experiment_id = experiment_id
        self.user_id = user_id
        self.dimension = dimension

        self.db_params = {
            "dbname": os.getenv("POSTGRES_DBNAME"),
            "user": os.getenv("POSTGRES_USER"),
            "password": os.getenv("POSTGRES_PASSWORD"),
            "host": os.getenv("POSTGRES_HOST", "localhost"),
            "port": os.getenv("POSTGRES_PORT", "5432")
        }
        
        self.embedding_client = OpenAI(
            base_url=emb_config.get("endpoint", ""),
            api_key=emb_config.get("key", "")
        )
        self.embedding_model = emb_config.get("model", "")

        self.conn = psycopg2.connect(**self.db_params)
        self.conn.autocommit = True
        self.cursor = self.conn.cursor()
        register_vector(self.conn)
        
        self._setup_config()

    def _setup_config(self):
        self.cursor.execute('''
            SELECT config_id FROM embedding_configs
            WHERE experiment_id = %s
              AND active = TRUE
              AND model_id = %s
        ''', (self.experiment_id, self.embedding_model))
        
        active_config = self.cursor.fetchone()
        if active_config:
            self.config_id = active_config[0]
        else:
            self.cursor.execute('''
                UPDATE embedding_configs
                SET active = FALSE 
                WHERE experiment_id = %s
                  AND active = TRUE
            ''', (self.experiment_id,))
            
            self.cursor.execute('''
                INSERT INTO embedding_configs (experiment_id, model_id, dimensions, active)
                VALUES (%s, %s, %s, TRUE)
                RETURNING config_id
            ''', (self.experiment_id, self.embedding_model, self.dimension))
            self.config_id = self.cursor.fetchone()[0]
            
    def get_embedding(self, text: str, is_query=False):
        # E5 model requires prefixes
        if "e5" in self.embedding_model.lower():
            prefixed_text = f"query: {text}" if is_query else f"passage: {text}"
        else:
            prefixed_text = text

        response = self.embedding_client.embeddings.create(
            input=prefixed_text,
            model=self.embedding_model
        )
        return response.data[0].embedding

    def insert_chunk(self, chunk: str, file_name: str, langugae: str = 'unknown'):
        vector = self.get_embedding(chunk, is_query=False)
        self.cursor.execute('''
            INSERT INTO documents (experiment_id, path, language, content)
            VALUES (%s, %s, %s, %s)
            RETURNING document_id
        ''', (self.experiment_id, file_name, language, chunk))
        
        document_id = self.cursor.fetchone()[0]
        
        self.cursor.execute(f"""
            INSERT INTO document_embeddings_{self.dimension} (document_id, config_id, embedding)
            VALUES (%s, %s, %s)
        """, (document_id, self.config_id, vector))

    def get_vault_languages(self) -> list:
        self.cursor.execute("""
            SELECT DISTINCT language FROM documents 
            WHERE experiment_id = %s AND language != 'unknown'
        """, (self.experiment_id,))
        return [row[0] for row in self.cursor.fetchall()]
        
    def search_vectors(self, query: str, limit: int = 10, max_distance: float = 0.75, language: str = None):
        query_vector = self.get_embedding(query, is_query=True)
        table_name = f"document_embeddings_{self.dimension}"

        base_query = f"""
            SELECT d.content FROM {table_name} e
            JOIN documents d ON d.document_id = e.document_id
            JOIN experiment_users eu ON eu.experiment_id = d.experiment_id
            WHERE eu.user_id = %s
              AND e.config_id = %s
              AND e.embedding <=> %s::vector < %s
        """
        
        if language:
            full_query = base_query + " AND d.language = %s ORDER BY e.embedding <=> %s::vector LIMIT %s"
            params = (self.user_id, self.config_id, query_vector, max_distance, language, query_vector, limit)
        else:
            full_query = base_query + " ORDER BY e.embedding <=> %s::vector LIMIT %s"
            params = (self.user_id, self.config_id, query_vector, max_distance, query_vector, limit)

        self.cursor.execute(full_query, params)
        return [row[0] for row in self.cursor.fetchall()]