#!/bin/bash
source /opt/conda/etc/profile.d/conda.sh

set -a
source .env
set +a

conda activate $CONDA_ENV_NAME

echo "Starting RAG API on port $PORT..."
exec python -u -m gunicorn api:app \
  -k uvicorn.workers.UvicornWorker \
  --workers 1 \
  --bind 0.0.0.0:$PORT