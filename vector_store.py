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
            INSERT INTO embedding_configs (experiment_id, model_id, dimensions, active)
            VALUES (%s, %s, %s, TRUE)
            ON CONFLICT (experiment_id) WHERE active = TRUE
            DO UPDATE SET 
                model_id = EXCLUDED.model_id,
                dimensions = EXCLUDED.dimensions
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

    def delete_document(self, file_name: str):
        self.cursor.execute('''
            DELETE FROM documents 
            WHERE experiment_id = %s AND path = %s
        ''', (self.experiment_id, file_name))

        print(f"Deleted {file_name}")

    def insert_chunk(self, chunk_index: int, chunk: str, file_name: str, language: list = None):

        print(f"Inserting chunks for {file_name}")
        if language is None:
            language = []

        vector = self.get_embedding(chunk, is_query=False)
        table_name = f"document_embeddings_{self.dimension}"

        # Rollback protection
        original_autocommit = self.conn.autocommit
        self.conn.autocommit = False

        try:
            self.cursor.execute("""
                INSERT INTO documents (experiment_id, path, language)
                VALUES (%s, %s, %s)
                ON CONFLICT (experiment_id, path) DO UPDATE 
                    SET language = (
                        SELECT ARRAY(
                            SELECT DISTINCT elem FROM unnest(documents.language || EXCLUDED.language) AS elem
                        )
                    )
                RETURNING document_id;
            """, (self.experiment_id, file_name, language))
            document_id = self.cursor.fetchone()[0]

            self.cursor.execute("""
                INSERT INTO document_chunks (document_id, chunk_index, content, language)
                VALUES (%s, %s, %s, %s)
                RETURNING chunk_id;
            """, (document_id, chunk_index, chunk, language))
            chunk_id = self.cursor.fetchone()[0]

            self.cursor.execute(f"""
                INSERT INTO {table_name} (chunk_id, config_id, embedding)
                VALUES (%s, %s, %s)
                ON CONFLICT (chunk_id, config_id) DO UPDATE 
                    SET embedding = EXCLUDED.embedding;
            """, (chunk_id, self.config_id, vector))

            self.conn.commit()
        except Exception as e:
            print(f"Error during chunk insertion for {file_name}")
            self.conn.rollback()
            raise e
        finally:
            print(f"Chunks inserted for {file_name}")
            self.conn.autocommit = original_autocommit

    def get_vault_languages(self) -> list:
        self.cursor.execute("""
            SELECT DISTINCT unnest(language) FROM documents 
            WHERE experiment_id = %s
        """, (self.experiment_id,))
        return [row[0] for row in self.cursor.fetchall() if row[0]]

    def search_vectors(self, query: str, limit: int = 10, max_distance: float = 0.75, language: str = None):
        query_vector = self.get_embedding(query, is_query=True)
        table_name = f"document_embeddings_{self.dimension}"

        print(f"Searching vectors for query: {query}")

        base_query = f"""
            SELECT d.path, c.content FROM {table_name} e
            JOIN document_chunks c ON c.chunk_id = e.chunk_id
            JOIN documents d ON d.document_id = c.document_id
            JOIN experiment_users eu ON eu.experiment_id = d.experiment_id
            WHERE eu.user_id = %s
              AND e.config_id = %s
              AND e.embedding <=> %s::vector < %s
        """

        if language:
            full_query = base_query + " AND (%s = ANY(d.language) OR d.language = '{}'::text[]) ORDER BY e.embedding <=> %s::vector LIMIT %s"
            params = (self.user_id, self.config_id, query_vector, max_distance, language, query_vector, limit)
        else:
            full_query = base_query + " ORDER BY e.embedding <=> %s::vector LIMIT %s"
            params = (self.user_id, self.config_id, query_vector, max_distance, query_vector, limit)

        self.cursor.execute(full_query, params)

        results = []
        for row in self.cursor.fetchall():
            path, content = row[0], row[1]
            file_title = path.rsplit('/', 1)[-1] if path else "Unknown File"
            results.append(f"[File: {file_title}]\n{content}")

        print(f"Results from query: {len(results)}")
        return results