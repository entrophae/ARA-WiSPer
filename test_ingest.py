from r2r import R2RClient

# 1. Connect to your local engine (it handles the /v3/ routing for you)
client = R2RClient("http://localhost:8246")

# 2. Create a dummy text document
file_name = "secret_knowledge.txt"
with open(file_name, "w") as f:
    f.write("The PepRAgent system was successfully deployed in Bochum in August 2026. It uses KI Connect NRW for its core intelligence.")

# 3. Upload and ingest the file into the PostgreSQL vector database
print(f"Uploading {file_name} to R2R...")
response = client.documents.create(file_path=file_name)

print("✅ Ingestion complete!")
print(response)